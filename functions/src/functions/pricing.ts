/**
 * Pricing configuration and the delivery quote.
 *
 * `calculateDeliveryQuote` is the only way a price is ever produced. It resolves
 * the road distance, runs the pricing engine and returns a full breakdown. The
 * mobile client renders that breakdown and never re-implements the formulas
 * (ADR-005) — which also means a change to company pricing rules takes effect
 * immediately on every device without an app release.
 */

import { z } from "zod";
import type { DataStore } from "../data/types";
import { calculateQuote } from "../pricing/engine";
import type { AdditionalCostInput, PricingConfig, QuoteInput } from "../pricing/types";
import { parsePricingConfig, parseQuoteInput } from "../pricing/validate";
import { resolveDistance } from "../routing/resolve";
import { writeAuditLog } from "../shared/audit";
import { assertPermission, requireAuth, requireMembership } from "../shared/auth";
import { errors } from "../shared/errors";
import { requireIdempotencyKey, withIdempotency } from "../shared/idempotency";
import { ok, type Handler, type HandlerDeps } from "./deps";

const locationSchema = z.object({
  address: z.string().trim().min(3).max(240),
  zoneId: z.string().trim().max(40).nullable().optional(),
  area: z.string().trim().max(60).nullable().optional(),
  latitude: z.number().min(-90).max(90).nullable().optional(),
  longitude: z.number().min(-180).max(180).nullable().optional(),
  contactName: z.string().trim().max(80).optional(),
  contactPhone: z.string().trim().max(24).optional(),
  notes: z.string().trim().max(500).optional(),
  manualDistanceMetres: z.number().int().min(1).max(2_000_000).nullable().optional(),
});

const additionalCostSchema = z.object({
  id: z.string().trim().min(1).max(64),
  label: z.string().trim().min(1).max(60),
  amountMinor: z.number().int().min(0),
  kind: z.enum(["delivery", "operating"]),
});

export const quotePricingInputSchema = z.object({
  pickup: locationSchema,
  destination: locationSchema,
  deliveryType: z.string().trim().max(40).nullable().optional(),
  serviceLevel: z.enum(["standard", "express", "scheduled"]),
  additionalCosts: z.array(additionalCostSchema).max(30).optional(),
  riderCostOverride: z
    .object({ amountMinor: z.number().int().min(0), reason: z.string().trim().min(3).max(240), note: z.string().trim().max(240).optional() })
    .nullable()
    .optional(),
  customerPriceOverride: z
    .object({
      amountMinor: z.number().int().min(0),
      reason: z.string().trim().min(3).max(240),
      allowBelowMinimum: z.boolean().optional(),
    })
    .nullable()
    .optional(),
  targetProfitOverride: z
    .object({ amountMinor: z.number().int().min(0).optional(), marginBps: z.number().int().min(0).max(9_999).optional() })
    .nullable()
    .optional(),
});

export type QuotePricingInput = z.infer<typeof quotePricingInputSchema>;

export const calculateDeliveryQuoteSchema = quotePricingInputSchema.extend({
  tenantId: z.string().min(1).max(64),
  /** Save the calculation to the quote history for later comparison. */
  saveQuote: z.boolean().optional(),
  /** Optional link so a quote can be traced back to an order. */
  orderId: z.string().max(64).nullable().optional(),
});

export type CalculateDeliveryQuoteInput = z.infer<typeof calculateDeliveryQuoteSchema>;

/** Loads the company's active pricing configuration, validated. */
export async function loadActivePricingConfig(store: DataStore, tenantId: string): Promise<PricingConfig> {
  const company = await store.get(`companies/${tenantId}`);
  if (!company) throw errors.notFound("company_missing", "That company workspace was not found.");
  const configId = company.data["activePricingConfigId"];
  if (typeof configId !== "string" || configId.length === 0) {
    throw errors.failedPrecondition(
      "pricing_config_missing",
      "This company has no pricing configuration yet. Set one up in pricing settings.",
    );
  }
  const doc = await store.get(`pricingConfigs/${configId}`);
  if (!doc) {
    throw errors.failedPrecondition(
      "pricing_config_missing",
      "This company's pricing configuration could not be found. Set it up again in pricing settings.",
    );
  }
  const data = doc.data;
  return {
    id: doc.id,
    tenantId,
    name: typeof data["name"] === "string" ? (data["name"] as string) : "Pricing",
    isActive: data["isActive"] !== false,
    version: typeof data["version"] === "number" ? (data["version"] as number) : 1,
    currency: typeof data["currency"] === "string" ? (data["currency"] as string) : "NGN",
    method: data["method"] as PricingConfig["method"],
    baseChargeMinor: numberField(data, "baseChargeMinor"),
    perKmRateMinor: numberField(data, "perKmRateMinor"),
    distanceBands: (data["distanceBands"] ?? []) as PricingConfig["distanceBands"],
    zoneRules: (data["zoneRules"] ?? []) as PricingConfig["zoneRules"],
    fallbackToDistanceWhenZoneMissing: data["fallbackToDistanceWhenZoneMissing"] !== false,
    minimumChargeMinor: numberField(data, "minimumChargeMinor"),
    maximumChargeMinor: data["maximumChargeMinor"] === null || data["maximumChargeMinor"] === undefined ? null : numberField(data, "maximumChargeMinor"),
    roundingIncrementMinor: numberField(data, "roundingIncrementMinor"),
    roundingMode: (data["roundingMode"] as PricingConfig["roundingMode"]) ?? "nearest",
    surcharges: (data["surcharges"] ?? []) as PricingConfig["surcharges"],
    riderCost: (data["riderCost"] ?? { method: "manual" }) as PricingConfig["riderCost"],
    targetProfitAmountMinor:
      data["targetProfitAmountMinor"] === null || data["targetProfitAmountMinor"] === undefined
        ? null
        : numberField(data, "targetProfitAmountMinor"),
    targetProfitMarginBps:
      data["targetProfitMarginBps"] === null || data["targetProfitMarginBps"] === undefined
        ? null
        : numberField(data, "targetProfitMarginBps"),
  };
}

function numberField(data: Record<string, unknown>, field: string): number {
  const value = data[field];
  return typeof value === "number" && Number.isFinite(value) ? value : 0;
}

export interface QuoteComputation {
  quoteInput: QuoteInput;
  distanceFallbackReason?: string;
  result: ReturnType<typeof calculateQuote>;
}

/**
 * Shared quote pipeline used by both the calculator screen and order creation, so
 * a price can never be produced two different ways.
 */
export async function computeQuote(
  deps: HandlerDeps,
  tenantId: string,
  input: QuotePricingInput,
): Promise<QuoteComputation> {
  const config = await loadActivePricingConfig(deps.store, tenantId);
  const regionCode = deps.config.routingRegionCode;

  const resolution = await resolveDistance(
    deps.routing,
    {
      pickup: {
        address: input.pickup.address,
        latitude: input.pickup.latitude ?? null,
        longitude: input.pickup.longitude ?? null,
        manualDistanceMetres: input.pickup.manualDistanceMetres ?? null,
      },
      destination: {
        address: input.destination.address,
        latitude: input.destination.latitude ?? null,
        longitude: input.destination.longitude ?? null,
      },
      allowManualFallback: true,
    },
    deps.store.now(),
    regionCode,
  );

  const quoteInput: QuoteInput = {
    pickup: {
      address: input.pickup.address,
      zoneId: input.pickup.zoneId ?? null,
      latitude: input.pickup.latitude ?? null,
      longitude: input.pickup.longitude ?? null,
    },
    destination: {
      address: input.destination.address,
      zoneId: input.destination.zoneId ?? null,
      latitude: input.destination.latitude ?? null,
      longitude: input.destination.longitude ?? null,
    },
    distance: resolution.distance,
    deliveryType: input.deliveryType ?? null,
    serviceLevel: input.serviceLevel,
    additionalCosts: (input.additionalCosts ?? []) as AdditionalCostInput[],
    riderCostOverride: input.riderCostOverride ?? null,
    customerPriceOverride: input.customerPriceOverride ?? null,
    targetProfitOverride: input.targetProfitOverride ?? null,
  };

  const result = calculateQuote(config, quoteInput, { now: deps.store.now() });
  const computation: QuoteComputation = { quoteInput, result };
  if (resolution.fallbackReason) computation.distanceFallbackReason = resolution.fallbackReason;
  return computation;
}

export const calculateDeliveryQuote: Handler<
  CalculateDeliveryQuoteInput,
  ReturnType<typeof ok<Record<string, unknown>>>
> = async (input, context, deps) => {
  const parsed = parseQuoteInputRequest(input, calculateDeliveryQuoteSchema);
  await requireMembership(deps.store, context, parsed.tenantId, "quotes.create");
  if (parsed.customerPriceOverride) {
    const membership = await requireMembership(deps.store, context, parsed.tenantId);
    assertPermission(membership, "quotes.override_price");
  }

  const computation = await computeQuote(deps, parsed.tenantId, parsed);
  const { result } = computation;

  let quoteId: string | null = null;
  if (parsed.saveQuote && result.status === "ok" && result.quote) {
    const quote = result.quote;
    quoteId = deps.store.newId("quotes");
    await deps.store.set(`quotes/${quoteId}`, {
      tenantId: parsed.tenantId,
      orderId: parsed.orderId ?? null,
      createdBy: context.uid,
      createdAt: deps.store.now().toISOString(),
      pickup: parsed.pickup,
      destination: parsed.destination,
      serviceLevel: parsed.serviceLevel,
      distance: quote.distance,
      pricingSnapshot: quote.pricingSnapshot,
      recommendedCustomerChargeMinor: quote.recommendedCustomerChargeMinor,
      estimatedRiderCost: quote.estimatedRiderCost,
      estimatedAdditionalDeliveryCostsMinor: quote.estimatedAdditionalDeliveryCostsMinor,
      estimatedProfitMinor: quote.estimatedProfitMinor,
      estimatedMarginBps: quote.estimatedMarginBps,
      warnings: quote.warnings,
    });
    await writeAuditLog(deps.store, {
      tenantId: parsed.tenantId,
      actorUserId: context.uid ?? "unknown",
      action: "quote.saved",
      resourceType: "quote",
      resourceId: quoteId,
      metadata: {
        recommendedCustomerChargeMinor: quote.recommendedCustomerChargeMinor,
        pricingConfigVersion: quote.pricingSnapshot.pricingConfigVersion,
        distanceSource: quote.distance?.source ?? null,
      },
    });
  }

  return ok({
    status: result.status,
    quote: result.quote ?? null,
    warnings: result.warnings,
    quoteId,
    distanceFallbackReason: computation.distanceFallbackReason ?? null,
    routingProvider: deps.routing.id,
  });
};

/**
 * Parses and validates quote input, reusing the stricter pricing schema for the
 * numeric parts while keeping the richer location fields the app sends.
 */
export function parseQuoteInputRequest<T extends z.ZodTypeAny>(input: unknown, schema: T): z.infer<T> {
  const parsed = schema.safeParse(input);
  if (!parsed.success) {
    const fields: Record<string, string> = {};
    for (const issue of parsed.error.issues) {
      const path = issue.path.join(".") || "value";
      if (!fields[path]) fields[path] = issue.message;
    }
    throw errors.invalidArgument("quote.invalid_input", "Some delivery details are not valid.", fields);
  }
  return parsed.data;
}

// ---------------------------------------------------------------------------
// Pricing settings
// ---------------------------------------------------------------------------

export const updateCompanyPricingSchema = z.object({
  tenantId: z.string().min(1).max(64),
  /** Omitted when the company is saving its first configuration. */
  configId: z.string().max(64).nullable().optional(),
  settings: z.record(z.string(), z.unknown()),
});

export type UpdateCompanyPricingInput = z.infer<typeof updateCompanyPricingSchema>;

export interface UpdateCompanyPricingResult {
  configId: string;
  version: number;
  method: string;
}

/**
 * Saves pricing settings and increments the configuration version.
 *
 * History is preserved: previous versions are archived under
 * `pricingConfigs/{id}/versions/{version}` so that a quote or order can always be
 * explained by the rules that produced it (spec §8).
 */
export const updateCompanyPricing: Handler<
  UpdateCompanyPricingInput,
  ReturnType<typeof ok<UpdateCompanyPricingResult>>
> = async (input, context, deps) => {
  const uid = requireAuth(context);
  const parsed = parseWith2(updateCompanyPricingSchema, input);
  const membership = await requireMembership(deps.store, context, parsed.tenantId, "pricing.manage");
  const now = deps.store.now();

  const company = await deps.store.get(`companies/${parsed.tenantId}`);
  if (!company) throw errors.notFound("company_missing", "That company workspace was not found.");
  const existingId =
    typeof parsed.configId === "string" && parsed.configId.length > 0
      ? parsed.configId
      : typeof company.data["activePricingConfigId"] === "string"
        ? (company.data["activePricingConfigId"] as string)
        : deps.store.newId("pricingConfigs");

  const existing = await deps.store.get(`pricingConfigs/${existingId}`);
  const previousVersion = existing && typeof existing.data["version"] === "number" ? (existing.data["version"] as number) : 0;
  const currency = typeof company.data["currency"] === "string" ? (company.data["currency"] as string) : "NGN";

  const config = parsePricingConfig(parsed.settings, {
    tenantId: parsed.tenantId,
    id: existingId,
    version: previousVersion + 1,
    currency,
  });

  await deps.store.runTransaction(async (tx) => {
    if (existing) {
      tx.set(`pricingConfigs/${existingId}/versions/${String(previousVersion).padStart(6, "0")}`, {
        ...existing.data,
        archivedAt: now.toISOString(),
      });
    }
    tx.set(
      `pricingConfigs/${existingId}`,
      { ...config, createdAt: existing ? existing.data["createdAt"] ?? now.toISOString() : now.toISOString(), updatedAt: now.toISOString(), updatedBy: uid },
      { merge: false },
    );
    tx.set(`companies/${parsed.tenantId}`, { activePricingConfigId: existingId, updatedAt: now.toISOString() }, { merge: true });
  });

  await writeAuditLog(deps.store, {
    tenantId: parsed.tenantId,
    actorUserId: uid,
    action: "pricing.config_updated",
    resourceType: "pricingConfig",
    resourceId: existingId,
    metadata: {
      version: config.version,
      previousVersion,
      method: config.method,
      byRole: membership.role,
      minimumChargeMinor: config.minimumChargeMinor,
      targetProfitMarginBps: config.targetProfitMarginBps,
    },
  });

  return ok({ configId: existingId, version: config.version, method: config.method });
};

function parseWith2<T extends z.ZodTypeAny>(schema: T, input: unknown): z.infer<T> {
  const parsed = schema.safeParse(input);
  if (!parsed.success) {
    const fields: Record<string, string> = {};
    for (const issue of parsed.error.issues) {
      const path = issue.path.join(".") || "value";
      if (!fields[path]) fields[path] = issue.message;
    }
    throw errors.invalidArgument("pricing.invalid_update_input", "Some pricing settings are not valid.", fields);
  }
  return parsed.data;
}

export { requireIdempotencyKey, withIdempotency };
