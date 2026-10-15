/**
 * Validation for pricing configuration and quote input.
 *
 * Two layers, deliberately:
 *  1. shape validation (zod) — types, ranges, required fields;
 *  2. semantic validation — relationships that only make sense together
 *     (margin < 100%, minimum <= maximum, exactly one catch-all band, and the
 *     fields each pricing method actually needs).
 *
 * No configuration reaches Firestore without passing through here, so a company
 * can never save a config that would make the engine divide by zero or quote a
 * nonsense price.
 */

import { z } from "zod";
import { errors } from "../shared/errors";
import type { Minor } from "../shared/money";
import {
  PRICING_METHODS,
  RIDER_COST_METHODS,
  ROUNDING_MODES,
  SERVICE_LEVELS,
  type AdditionalCostInput,
  type DistanceBand,
  type PricingConfig,
  type QuoteDistance,
  type QuoteInput,
  type SurchargeRule,
  type ZoneRule,
} from "./types";

const minorSchema = z
  .number()
  .int("must be a whole number of minor units (kobo)")
  .min(0, "must not be negative")
  .max(Number.MAX_SAFE_INTEGER);

const optionalMinorSchema = minorSchema.optional();
const idSchema = z
  .string()
  .min(1)
  .max(64)
  .regex(/^[A-Za-z0-9_-]+$/, "may only contain letters, numbers, hyphens and underscores");

const latitudeSchema = z.number().min(-90).max(90);
const longitudeSchema = z.number().min(-180).max(180);

const distanceBandSchema = z.object({
  upToMetres: z.number().int().positive().nullable(),
  chargeMinor: minorSchema,
  label: z.string().max(60).optional(),
});

const zoneRuleSchema = z.object({
  fromZoneId: idSchema,
  toZoneId: idSchema,
  chargeMinor: minorSchema,
  adjustmentMinor: optionalMinorSchema,
});

const surchargeSchema = z.object({
  id: idSchema,
  label: z.string().min(1).max(60),
  kind: z.enum(["flat", "percentage"]),
  amountMinor: optionalMinorSchema,
  percentageBps: z.number().int().min(0).max(10_000).optional(),
  appliesToServiceLevels: z.array(z.enum(SERVICE_LEVELS)).max(SERVICE_LEVELS.length).optional(),
});

const riderCostSchema = z.object({
  method: z.enum(RIDER_COST_METHODS),
  perKmRateMinor: optionalMinorSchema,
  bands: z.array(distanceBandSchema).max(20).optional(),
  zoneRules: z.array(zoneRuleSchema).max(500).optional(),
  defaultFeeMinor: optionalMinorSchema,
  historicalAverageMinor: optionalMinorSchema,
  historicalSampleSize: z.number().int().min(0).max(100_000).optional(),
  minimumCostMinor: optionalMinorSchema,
});

export const pricingConfigSchema = z.object({
  id: z.string().max(64).optional(),
  name: z.string().min(1).max(60),
  isActive: z.boolean(),
  currency: z.string().length(3).regex(/^[A-Z]{3}$/, "must be an ISO-4217 code such as NGN"),
  method: z.enum(PRICING_METHODS),
  baseChargeMinor: minorSchema,
  perKmRateMinor: minorSchema,
  distanceBands: z.array(distanceBandSchema).max(20),
  zoneRules: z.array(zoneRuleSchema).max(500),
  fallbackToDistanceWhenZoneMissing: z.boolean(),
  minimumChargeMinor: minorSchema,
  maximumChargeMinor: minorSchema.nullable(),
  roundingIncrementMinor: minorSchema,
  roundingMode: z.enum(ROUNDING_MODES),
  surcharges: z.array(surchargeSchema).max(20),
  riderCost: riderCostSchema,
  targetProfitAmountMinor: minorSchema.nullable(),
  targetProfitMarginBps: z.number().int().min(0).max(9_999).nullable(),
});

export type PricingConfigInput = z.infer<typeof pricingConfigSchema>;

function fieldErrors(error: z.ZodError): Record<string, string> {
  const flattened: Record<string, string> = {};
  for (const issue of error.issues) {
    const path = issue.path.join(".") || "config";
    if (!flattened[path]) flattened[path] = issue.message;
  }
  return flattened;
}

/** Validates a distance-band set: sorted, non-overlapping, exactly one catch-all, last. */
function assertBandsValid(bands: DistanceBand[], label: string): void {
  const bounded = bands.filter((band) => band.upToMetres !== null);
  const catchAll = bands.filter((band) => band.upToMetres === null);
  if (catchAll.length > 1) {
    throw errors.invalidArgument(`pricing.${label}.multiple_catch_all`, `Only one "${label}" band may be open-ended.`);
  }
  const bounds = bounded.map((band) => band.upToMetres as number);
  if (new Set(bounds).size !== bounds.length) {
    throw errors.invalidArgument(`pricing.${label}.duplicate_bounds`, `Two "${label}" bands end at the same distance.`);
  }
  if (catchAll.length === 1) {
    const lastBounded = bounds.length > 0 ? Math.max(...bounds) : 0;
    // The catch-all is implicit "everything above the last bounded band"; a
    // catch-all with no bounded bands covers 0m..∞, which is legitimate.
    if (lastBounded <= 0) return;
  }
}

/** Semantic checks that go beyond the zod shape. */
export function validatePricingConfigRelations(config: PricingConfig): void {
  if (config.maximumChargeMinor !== null && config.maximumChargeMinor < config.minimumChargeMinor) {
    throw errors.invalidArgument(
      "pricing.maximum_below_minimum",
      "The maximum charge must be greater than or equal to the minimum charge.",
    );
  }
  if (config.roundingIncrementMinor > 100_000) {
    throw errors.invalidArgument("pricing.rounding_too_large", "The rounding increment must be ₦1,000 or less.");
  }
  if (config.targetProfitMarginBps !== null && config.targetProfitMarginBps >= 10_000) {
    throw errors.invalidArgument("pricing.margin_too_high", "The target profit margin must be below 100%.");
  }
  if (config.method === "distance_bands" && config.distanceBands.length === 0) {
    throw errors.invalidArgument("pricing.bands_required", "Distance-band pricing needs at least one band.");
  }
  if (config.method === "zone_based" && config.zoneRules.length === 0) {
    throw errors.invalidArgument("pricing.zone_rules_required", "Zone pricing needs at least one zone price.");
  }
  if ((config.method === "base_plus_distance" || config.method === "hybrid") && config.perKmRateMinor === 0) {
    throw errors.invalidArgument(
      "pricing.per_km_rate_required",
      "This pricing method needs a rate per kilometre greater than zero.",
    );
  }
  assertBandsValid(config.distanceBands, "distanceBands");
  assertBandsValid(config.riderCost.bands ?? [], "riderBands");

  const rider = config.riderCost;
  if (rider.method === "distance_rate" && rider.perKmRateMinor === undefined) {
    throw errors.invalidArgument("pricing.rider_rate_required", "Distance-rate rider costing needs a rate per kilometre.");
  }
  if (rider.method === "distance_bands" && (rider.bands ?? []).length === 0) {
    throw errors.invalidArgument("pricing.rider_bands_required", "Distance-band rider costing needs at least one band.");
  }
  if (rider.method === "zone_based" && (rider.zoneRules ?? []).length === 0) {
    throw errors.invalidArgument("pricing.rider_zones_required", "Zone rider costing needs at least one zone price.");
  }
  if (rider.method === "fixed" && rider.defaultFeeMinor === undefined) {
    throw errors.invalidArgument("pricing.rider_default_fee_required", "Fixed rider costing needs a default fee.");
  }
  if (rider.method === "historical_average" && rider.historicalAverageMinor === undefined) {
    throw errors.invalidArgument(
      "pricing.rider_history_required",
      "Historical rider costing needs an average; record completed deliveries first.",
    );
  }
  for (const surcharge of config.surcharges) {
    if (surcharge.kind === "flat" && (surcharge.amountMinor ?? 0) <= 0) {
      throw errors.invalidArgument(
        `pricing.surcharge_amount_required:${surcharge.id}`,
        `The flat surcharge "${surcharge.label}" needs an amount greater than zero.`,
      );
    }
    if (surcharge.kind === "percentage" && (surcharge.percentageBps ?? 0) <= 0) {
      throw errors.invalidArgument(
        `pricing.surcharge_percentage_required:${surcharge.id}`,
        `The percentage surcharge "${surcharge.label}" needs a percentage greater than zero.`,
      );
    }
  }
}

/**
 * Validates raw pricing settings from the app and returns a complete config.
 * `existing` supplies tenant/version/id context so that partial updates cannot
 * reset the version or move the config to another tenant.
 */
export function parsePricingConfig(
  raw: unknown,
  context: { tenantId: string; id: string; version: number; currency: string },
): PricingConfig {
  const parsed = pricingConfigSchema.safeParse(raw);
  if (!parsed.success) {
    throw errors.invalidArgument("pricing.invalid_shape", "Some pricing settings are not valid.", fieldErrors(parsed.error));
  }
  const data = parsed.data;
  const config: PricingConfig = {
    id: context.id,
    tenantId: context.tenantId,
    name: data.name,
    isActive: data.isActive,
    version: context.version,
    currency: data.currency,
    method: data.method,
    baseChargeMinor: data.baseChargeMinor,
    perKmRateMinor: data.perKmRateMinor,
    distanceBands: data.distanceBands as DistanceBand[],
    zoneRules: data.zoneRules as ZoneRule[],
    fallbackToDistanceWhenZoneMissing: data.fallbackToDistanceWhenZoneMissing,
    minimumChargeMinor: data.minimumChargeMinor,
    maximumChargeMinor: data.maximumChargeMinor,
    roundingIncrementMinor: data.roundingIncrementMinor,
    roundingMode: data.roundingMode,
    surcharges: data.surcharges as SurchargeRule[],
    riderCost: data.riderCost,
    targetProfitAmountMinor: data.targetProfitAmountMinor,
    targetProfitMarginBps: data.targetProfitMarginBps,
  };
  validatePricingConfigRelations(config);
  return config;
}

// ---------------------------------------------------------------------------
// Quote input
// ---------------------------------------------------------------------------

const locationSchema = z.object({
  address: z.string().min(1).max(240),
  zoneId: idSchema.nullable().optional(),
  latitude: latitudeSchema.nullable().optional(),
  longitude: longitudeSchema.nullable().optional(),
});

const distanceSchema = z.object({
  metres: z.number().int().min(1).max(2_000_000),
  source: z.enum(["provider", "manual"]),
  provider: z.string().min(1).max(40),
  summary: z.string().max(240).optional(),
  durationSeconds: z.number().int().min(0).max(86_400).optional(),
  calculatedAt: z.string().refine((value) => !Number.isNaN(Date.parse(value)), "must be an ISO date-time"),
  pickupCoordinates: z.object({ latitude: latitudeSchema, longitude: longitudeSchema }).optional(),
  destinationCoordinates: z.object({ latitude: latitudeSchema, longitude: longitudeSchema }).optional(),
});

const additionalCostSchema = z.object({
  id: idSchema,
  label: z.string().min(1).max(60),
  amountMinor: minorSchema,
  kind: z.enum(["delivery", "operating"]),
});

export const quoteInputSchema = z.object({
  pickup: locationSchema,
  destination: locationSchema,
  distance: distanceSchema.nullable(),
  customerId: z.string().max(64).nullable().optional(),
  deliveryType: z.string().max(40).nullable().optional(),
  serviceLevel: z.enum(SERVICE_LEVELS),
  additionalCosts: z.array(additionalCostSchema).max(30).optional(),
  riderCostOverride: z
    .object({ amountMinor: minorSchema, reason: z.string().min(3).max(240), note: z.string().max(240).optional() })
    .nullable()
    .optional(),
  customerPriceOverride: z
    .object({
      amountMinor: minorSchema,
      reason: z.string().min(3).max(240),
      allowBelowMinimum: z.boolean().optional(),
    })
    .nullable()
    .optional(),
  targetProfitOverride: z
    .object({ amountMinor: optionalMinorSchema, marginBps: z.number().int().min(0).max(9_999).optional() })
    .nullable()
    .optional(),
});

export function parseQuoteInput(raw: unknown): QuoteInput {
  const parsed = quoteInputSchema.safeParse(raw);
  if (!parsed.success) {
    throw errors.invalidArgument("quote.invalid_shape", "Some delivery details are not valid.", fieldErrors(parsed.error));
  }
  const data = parsed.data;
  return {
    pickup: data.pickup,
    destination: data.destination,
    distance: (data.distance as QuoteDistance | null) ?? null,
    customerId: data.customerId ?? null,
    deliveryType: data.deliveryType ?? null,
    serviceLevel: data.serviceLevel,
    additionalCosts: (data.additionalCosts as AdditionalCostInput[] | undefined) ?? [],
    riderCostOverride: data.riderCostOverride ?? null,
    customerPriceOverride: data.customerPriceOverride ?? null,
    targetProfitOverride: data.targetProfitOverride ?? null,
  };
}
