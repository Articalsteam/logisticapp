/**
 * Company (tenant) lifecycle.
 *
 * `createCompany` is the trusted onboarding operation (spec §6): the app can only
 * ask for a workspace to be created — it cannot write company, membership or
 * subscription documents itself. Everything happens in one transaction, so a
 * dropped connection can never leave a workspace without an owner.
 */

import { z } from "zod";
import { DEFAULT_CURRENCY, DEFAULT_PLANS, DEFAULT_TIMEZONE, starterPricingConfig } from "../config/platformDefaults";
import { DEFAULT_ENTITLEMENT_POLICY, entitlementFields, computeEntitlement, type SubscriptionRecord } from "../domain/entitlement";
import { writeAuditLog } from "../shared/audit";
import { requireAuth, requireCompany, requireMembership } from "../shared/auth";
import { errors } from "../shared/errors";
import { requireIdempotencyKey, withIdempotency } from "../shared/idempotency";
import { sanitizePrefix, slugify } from "../shared/ids";
import { ok, type Handler } from "./deps";
import {
  addressSchema,
  currencySchema,
  emailSchema,
  minorSchema,
  nameSchema,
  parseWith,
  phoneSchema,
  timezoneSchema,
  zoneIdSchema,
} from "../shared/validation";

const hexColourSchema = z
  .string()
  .regex(/^#[0-9A-Fa-f]{6}$/, "Use a six-digit hex colour such as #14532D");

export const createCompanySchema = z.object({
  idempotencyKey: z.string(),
  companyName: nameSchema,
  contactPhone: phoneSchema,
  contactEmail: emailSchema,
  businessAddress: addressSchema.optional(),
  serviceAreas: z.array(z.string().trim().min(2).max(60)).max(30).optional(),
  currency: currencySchema.optional(),
  timezone: timezoneSchema.optional(),
  orderNumberPrefix: z.string().max(4).optional(),
  brandColor: hexColourSchema.optional(),
  /** Optional starter pricing values captured during onboarding. */
  pricing: z
    .object({
      baseChargeMinor: minorSchema.optional(),
      perKmRateMinor: minorSchema.optional(),
      minimumChargeMinor: minorSchema.optional(),
      defaultRiderCostMinor: minorSchema.optional(),
      targetProfitMarginBps: z.number().int().min(0).max(9_999).optional(),
      pricingMethod: z.enum(["base_plus_distance", "distance_bands", "zone_based", "hybrid"]).optional(),
    })
    .optional(),
});

export type CreateCompanyInput = z.infer<typeof createCompanySchema>;

export interface CreateCompanyResult {
  tenantId: string;
  companyName: string;
  pricingConfigId: string;
  subscriptionPlanId: string;
  trialEndsAt: string | null;
  entitlement: string;
}

export const createCompany: Handler<CreateCompanyInput, ReturnType<typeof ok<CreateCompanyResult>>> = async (
  input,
  context,
  deps,
) => {
  const uid = requireAuth(context);
  const parsed = parseWith(createCompanySchema, input, {
    detail: "company.invalid_create_input",
    userMessage: "Some company details are not valid.",
  });
  const idempotencyKey = requireIdempotencyKey(parsed.idempotencyKey);

  const outcome = await withIdempotency<CreateCompanyResult & Record<string, unknown>>(
    deps.store,
    { scope: "createCompany", tenantId: "platform", actorUserId: uid, key: idempotencyKey },
    async () => {
      const now = deps.store.now();
      const tenantId = deps.store.newId("companies");
      const pricingConfigId = deps.store.newId("pricingConfigs");
      const currency = parsed.currency ?? DEFAULT_CURRENCY;
      const timezone = parsed.timezone ?? DEFAULT_TIMEZONE;
      const plan = DEFAULT_PLANS.find((entry) => entry.id === "pilot") ?? DEFAULT_PLANS[0];
      if (!plan) throw errors.internal("no_default_plan");
      const trialEndsAt = new Date(now.getTime() + plan.trialDays * 24 * 60 * 60 * 1000).toISOString();
      const prefix = sanitizePrefix(parsed.orderNumberPrefix ?? parsed.companyName.slice(0, 3), "ORD");
      const subscription: SubscriptionRecord = {
        tenantId,
        planId: plan.id,
        status: "trialing",
        billingMode: "trial",
        trialEndsAt,
        currentPeriodStart: now.toISOString(),
        currentPeriodEnd: null,
        bypass: null,
        lastOfflinePayment: null,
      };
      const entitlement = computeEntitlement(subscription, now, DEFAULT_ENTITLEMENT_POLICY);

      await deps.store.runTransaction(async (tx) => {
        tx.create(`companies/${tenantId}`, {
          name: parsed.companyName,
          slug: slugify(parsed.companyName) || tenantId,
          logoUrl: null,
          brandColor: parsed.brandColor ?? null,
          email: parsed.contactEmail,
          phone: parsed.contactPhone,
          address: parsed.businessAddress ?? null,
          currency,
          timezone,
          serviceAreas: parsed.serviceAreas ?? [],
          orderNumberPrefix: prefix,
          status: "active",
          planId: plan.id,
          activePricingConfigId: pricingConfigId,
          onboarding: {
            isComplete: false,
            completedSteps: ["company_details"],
            startedAt: now.toISOString(),
            updatedAt: now.toISOString(),
          },
          createdAt: now.toISOString(),
          updatedAt: now.toISOString(),
          createdBy: uid,
        });

        // The owner membership is created server-side only. There is no path in
        // which a client writes its own role (see firestore.rules).
        tx.create(`memberships/${tenantId}_${uid}`, {
          tenantId,
          userId: uid,
          role: "owner",
          status: "active",
          displayName: typeof context.claims["name"] === "string" ? (context.claims["name"] as string) : null,
          email: parsed.contactEmail,
          grantedPermissions: [],
          revokedPermissions: [],
          createdAt: now.toISOString(),
          updatedAt: now.toISOString(),
          createdBy: uid,
        });

        const pricing = starterPricingConfig({
          tenantId,
          id: pricingConfigId,
          currency,
          ...(parsed.pricing?.baseChargeMinor !== undefined ? { baseChargeMinor: parsed.pricing.baseChargeMinor } : {}),
          ...(parsed.pricing?.perKmRateMinor !== undefined ? { perKmRateMinor: parsed.pricing.perKmRateMinor } : {}),
          ...(parsed.pricing?.minimumChargeMinor !== undefined ? { minimumChargeMinor: parsed.pricing.minimumChargeMinor } : {}),
          ...(parsed.pricing?.defaultRiderCostMinor !== undefined
            ? { defaultRiderCostMinor: parsed.pricing.defaultRiderCostMinor }
            : {}),
          ...(parsed.pricing?.targetProfitMarginBps !== undefined
            ? { targetProfitMarginBps: parsed.pricing.targetProfitMarginBps }
            : {}),
          ...(parsed.pricing?.pricingMethod !== undefined ? {} : {}),
        });
        if (parsed.pricing?.pricingMethod) {
          pricing.method = parsed.pricing.pricingMethod;
        }
        tx.create(`pricingConfigs/${pricingConfigId}`, { ...pricing, createdAt: now.toISOString(), updatedAt: now.toISOString() });

        tx.create(`subscriptions/${tenantId}`, {
          ...subscription,
          ...entitlementFields(entitlement, now),
          createdAt: now.toISOString(),
          updatedAt: now.toISOString(),
        });
      });

      await writeAuditLog(deps.store, {
        tenantId,
        actorUserId: uid,
        action: "company.created",
        resourceType: "company",
        resourceId: tenantId,
        metadata: { planId: plan.id, currency, timezone, trialEndsAt },
      });

      const result: CreateCompanyResult = {
        tenantId,
        companyName: parsed.companyName,
        pricingConfigId,
        subscriptionPlanId: plan.id,
        trialEndsAt,
        entitlement: entitlement.state,
      };
      return result as CreateCompanyResult & Record<string, unknown>;
    },
  );

  deps.log("company.created", { tenantId: outcome.result.tenantId, replayed: outcome.replayed });
  return ok(outcome.result, outcome.replayed);
};

// ---------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------

export const updateCompanySettingsSchema = z.object({
  tenantId: z.string(),
  companyName: nameSchema.optional(),
  contactPhone: phoneSchema.optional(),
  contactEmail: emailSchema.optional(),
  businessAddress: addressSchema.nullable().optional(),
  serviceAreas: z.array(zoneIdSchema.or(z.string().trim().min(2).max(60))).max(30).nullable().optional(),
  currency: currencySchema.optional(),
  timezone: timezoneSchema.optional(),
  logoUrl: z.string().url().max(500).nullable().optional(),
  brandColor: hexColourSchema.nullable().optional(),
  orderNumberPrefix: z.string().max(4).optional(),
  onboardingSteps: z.array(z.string().trim().min(1).max(40)).max(20).optional(),
  onboardingComplete: z.boolean().optional(),
  /**
   * Public tracking exposure: what a customer may see on the tracking page, and
   * whether they must confirm an extra detail (spec §12).
   */
  publicTracking: z
    .object({
      shareAreas: z.boolean().optional(),
      requireVerification: z.boolean().optional(),
      supportPhone: phoneSchema.nullable().optional(),
    })
    .optional(),
});

export type UpdateCompanySettingsInput = z.infer<typeof updateCompanySettingsSchema>;

/**
 * Updates company profile, branding and onboarding progress.
 *
 * Onboarding progress is stored on the company document so that an owner who is
 * interrupted on one device (or reinstalls the app) can continue where they left
 * off on another.
 */
export const updateCompanySettings: Handler<UpdateCompanySettingsInput, ReturnType<typeof ok<Record<string, unknown>>>> = async (
  input,
  context,
  deps,
) => {
  const parsed = parseWith(updateCompanySettingsSchema, input, {
    detail: "company.invalid_settings_input",
    userMessage: "Some company settings are not valid.",
  });
  const membership = await requireMembership(deps.store, context, parsed.tenantId, "company.settings.manage");
  const company = await requireCompany(deps.store, parsed.tenantId);
  const now = deps.store.now();

  const patch: Record<string, unknown> = { updatedAt: now.toISOString() };
  if (parsed.companyName !== undefined) patch["name"] = parsed.companyName;
  if (parsed.contactPhone !== undefined) patch["phone"] = parsed.contactPhone;
  if (parsed.contactEmail !== undefined) patch["email"] = parsed.contactEmail;
  if (parsed.businessAddress !== undefined) patch["address"] = parsed.businessAddress;
  if (parsed.serviceAreas !== undefined) patch["serviceAreas"] = parsed.serviceAreas ?? [];
  if (parsed.currency !== undefined && parsed.currency !== company["currency"]) {
    // Changing currency would reinterpret every stored amount, so it is blocked
    // once the company has any financial history. Nothing is silently converted.
    const orderCount = await deps.store.count("orders", { where: [{ field: "tenantId", op: "==", value: parsed.tenantId }], limit: 1 });
    if (orderCount > 0) {
      throw errors.failedPrecondition(
        "company.currency_locked",
        "The currency cannot be changed after deliveries have been recorded, because existing amounts are stored in it.",
      );
    }
    patch["currency"] = parsed.currency;
  }
  if (parsed.timezone !== undefined) patch["timezone"] = parsed.timezone;
  if (parsed.logoUrl !== undefined) patch["logoUrl"] = parsed.logoUrl;
  if (parsed.brandColor !== undefined) patch["brandColor"] = parsed.brandColor;
  if (parsed.orderNumberPrefix !== undefined) patch["orderNumberPrefix"] = sanitizePrefix(parsed.orderNumberPrefix, "ORD");
  if (parsed.publicTracking !== undefined) {
    const existingTracking = (company["publicTracking"] ?? {}) as Record<string, unknown>;
    patch["publicTracking"] = {
      shareAreas: parsed.publicTracking.shareAreas ?? existingTracking["shareAreas"] ?? true,
      requireVerification: parsed.publicTracking.requireVerification ?? existingTracking["requireVerification"] ?? false,
      supportPhone: parsed.publicTracking.supportPhone ?? existingTracking["supportPhone"] ?? null,
    };
  }

  if (parsed.onboardingSteps !== undefined || parsed.onboardingComplete !== undefined) {
    const onboarding = (company["onboarding"] ?? {}) as Record<string, unknown>;
    const existingSteps = Array.isArray(onboarding["completedSteps"]) ? (onboarding["completedSteps"] as string[]) : [];
    const merged = [...new Set([...existingSteps, ...(parsed.onboardingSteps ?? [])])];
    const isComplete = parsed.onboardingComplete ?? (typeof onboarding["isComplete"] === "boolean" ? onboarding["isComplete"] : false);
    patch["onboarding"] = {
      ...onboarding,
      completedSteps: merged,
      isComplete,
      updatedAt: now.toISOString(),
      ...(isComplete ? { completedAt: now.toISOString() } : {}),
    };
  }

  await deps.store.set(`companies/${parsed.tenantId}`, patch, { merge: true });
  await writeAuditLog(deps.store, {
    tenantId: parsed.tenantId,
    actorUserId: membership.userId,
    action: "company.settings_updated",
    resourceType: "company",
    resourceId: parsed.tenantId,
    metadata: { fields: Object.keys(patch).filter((key) => key !== "updatedAt") },
  });

  const updated = await deps.store.get(`companies/${parsed.tenantId}`);
  return ok(updated?.data ?? {});
};
