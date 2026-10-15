/**
 * Articals Studio platform administration.
 *
 * Platform authority comes from a Firebase custom claim (`platformAdmin`) that only
 * this module can set. Supplying `role: "admin"` in a request body does nothing.
 *
 * A company's private delivery records are never exposed here: platform staff see
 * account, subscription and onboarding metadata, and sensitive support access would
 * require an explicit, audited, time-limited grant (future work, deliberately not
 * implemented as an open door).
 */

import { z } from "zod";
import type { AdminAuthPort } from "../data/adminAuth";
import { DEFAULT_PLANS } from "../config/platformDefaults";
import { DEFAULT_ENTITLEMENT_POLICY, daysUntilExpiry, isRevenueBearingEntitlement } from "../domain/entitlement";
import { writeAuditLog } from "../shared/audit";
import { requireAuth, requirePlatformAdmin } from "../shared/auth";
import { errors } from "../shared/errors";
import { parseWith } from "../shared/validation";
import { ok, type Handler } from "./deps";

export const listCompaniesSchema = z.object({
  search: z.string().trim().max(80).optional(),
  status: z.enum(["trialing", "active", "expired", "suspended"]).optional(),
  limit: z.number().int().min(1).max(100).optional(),
  cursor: z.array(z.unknown()).max(2).nullable().optional(),
});

export type ListCompaniesInput = z.infer<typeof listCompaniesSchema>;

export interface CompanySummary {
  tenantId: string;
  name: string;
  slug: string | null;
  status: string;
  planId: string;
  entitlement: string;
  entitlementEffectiveUntil: string | null;
  billingMode: string;
  trialEndsAt: string | null;
  currentPeriodEnd: string | null;
  bypassActive: boolean;
  bypassUntil: string | null;
  onboardingComplete: boolean;
  createdAt: string | null;
  createdBy: string | null;
}

export const listCompanies: Handler<ListCompaniesInput, ReturnType<typeof ok<{ companies: CompanySummary[] }>>> = async (
  input,
  context,
  deps,
) => {
  requirePlatformAdmin(context);
  const parsed = parseWith(listCompaniesSchema, input, {
    detail: "admin.invalid_list_input",
    userMessage: "That filter is not valid.",
  });

  const where = parsed.status
    ? [
        { field: "status", op: "==" as const, value: parsed.status },
      ]
    : [];
  const nextCursor = parsed.cursor as unknown[] | null | undefined;

  const docs = await deps.store.query("companies", {
    ...(where.length > 0 ? { where } : {}),
    orderBy: [{ field: "createdAt", direction: "desc" }],
    limit: parsed.limit ?? 25,
    cursor: nextCursor && nextCursor.length > 0 ? nextCursor : null,
  });

  const companies: CompanySummary[] = [];
  for (const doc of docs) {
    const data = doc.data;
    if (parsed.search && !String(data["name"] ?? "").toLowerCase().includes(parsed.search.toLowerCase())) continue;
    const subscription = await deps.store.get(`subscriptions/${doc.id}`);
    const subscriptionData = subscription?.data ?? {};
    const onboarding = (data["onboarding"] ?? {}) as Record<string, unknown>;
    companies.push({
      tenantId: doc.id,
      name: String(data["name"] ?? "Unnamed company"),
      slug: typeof data["slug"] === "string" ? (data["slug"] as string) : null,
      status: String(subscriptionData["status"] ?? data["status"] ?? "unknown"),
      planId: String(subscriptionData["planId"] ?? data["planId"] ?? "none"),
      entitlement: String(subscriptionData["entitlement"] ?? "unknown"),
      entitlementEffectiveUntil:
        typeof subscriptionData["entitlementEffectiveUntil"] === "string"
          ? (subscriptionData["entitlementEffectiveUntil"] as string)
          : null,
      billingMode: String(subscriptionData["billingMode"] ?? "trial"),
      trialEndsAt: typeof subscriptionData["trialEndsAt"] === "string" ? (subscriptionData["trialEndsAt"] as string) : null,
      currentPeriodEnd: typeof subscriptionData["currentPeriodEnd"] === "string" ? (subscriptionData["currentPeriodEnd"] as string) : null,
      bypassActive: (subscriptionData["bypass"] as Record<string, unknown> | undefined)?.["active"] === true,
      bypassUntil:
        typeof (subscriptionData["bypass"] as Record<string, unknown> | undefined)?.["until"] === "string"
          ? ((subscriptionData["bypass"] as Record<string, unknown>)["until"] as string)
          : null,
      onboardingComplete: onboarding["isComplete"] === true,
      createdAt: typeof data["createdAt"] === "string" ? (data["createdAt"] as string) : null,
      createdBy: typeof data["createdBy"] === "string" ? (data["createdBy"] as string) : null,
    });
  }

  return ok({ companies });
};

export const getPlatformOverviewSchema = z.object({});

export interface PlatformOverview {
  companies: {
    total: number;
    trialing: number;
    activePaid: number;
    activeOffline: number;
    activeBypass: number;
    grace: number;
    limited: number;
    suspended: number;
    onboardingIncomplete: number;
  };
  upcomingExpiries: Array<{ tenantId: string; name: string; entitlement: string; daysRemaining: number | null }>;
  /** Revenue categories, kept separate on purpose (spec §19). */
  revenue: {
    automated: number;
    offline: number;
    /** Trials, bypasses and complimentary access: never counted as revenue. */
    nonRevenue: number;
  };
  activeBypasses: Array<{ tenantId: string; until: string | null; reason: string | null }>;
  generatedAt: string;
}

export const getPlatformOverview: Handler<
  Record<string, never>,
  ReturnType<typeof ok<PlatformOverview>>
> = async (_input, context, deps) => {
  requirePlatformAdmin(context);
  const docs = await deps.store.query("companies", { orderBy: [{ field: "createdAt", direction: "desc" }], limit: 200 });
  const now = deps.store.now();
  const overview: PlatformOverview = {
    companies: {
      total: docs.length,
      trialing: 0,
      activePaid: 0,
      activeOffline: 0,
      activeBypass: 0,
      grace: 0,
      limited: 0,
      suspended: 0,
      onboardingIncomplete: 0,
    },
    upcomingExpiries: [],
    revenue: { automated: 0, offline: 0, nonRevenue: 0 },
    activeBypasses: [],
    generatedAt: now.toISOString(),
  };

  for (const doc of docs) {
    const subscription = await deps.store.get(`subscriptions/${doc.id}`);
    const data = subscription?.data ?? {};
    const entitlement = String(data["entitlement"] ?? "limited");
    const onboarding = (doc.data["onboarding"] ?? {}) as Record<string, unknown>;
    if (onboarding["isComplete"] !== true) overview.companies.onboardingIncomplete += 1;

    switch (entitlement) {
      case "trialing":
        overview.companies.trialing += 1;
        break;
      case "active_paid":
        overview.companies.activePaid += 1;
        break;
      case "active_offline":
        overview.companies.activeOffline += 1;
        break;
      case "active_bypass":
        overview.companies.activeBypass += 1;
        break;
      case "grace":
        overview.companies.grace += 1;
        break;
      case "suspended":
        overview.companies.suspended += 1;
        break;
      default:
        overview.companies.limited += 1;
    }

    if (entitlement === "active_paid") overview.revenue.automated += 1;
    else if (entitlement === "active_offline") overview.revenue.offline += 1;
    else overview.revenue.nonRevenue += 1;

    const bypass = data["bypass"] as Record<string, unknown> | undefined;
    if (bypass && bypass["active"] === true) {
      overview.activeBypasses.push({
        tenantId: doc.id,
        until: typeof bypass["until"] === "string" ? (bypass["until"] as string) : null,
        reason: typeof bypass["reason"] === "string" ? (bypass["reason"] as string) : null,
      });
    }

    const effectiveUntil =
      typeof data["entitlementEffectiveUntil"] === "string" ? (data["entitlementEffectiveUntil"] as string) : null;
    if (effectiveUntil) {
      const days = daysUntilExpiry({ state: entitlement as never, effectiveUntil, reason: "", writesAllowed: true }, now);
      if (days !== null && days <= 14) {
        overview.upcomingExpiries.push({
          tenantId: doc.id,
          name: String(doc.data["name"] ?? "Unnamed company"),
          entitlement,
          daysRemaining: days,
        });
      }
    }
  }

  overview.upcomingExpiries.sort((left, right) => (left.daysRemaining ?? 0) - (right.daysRemaining ?? 0));
  overview.upcomingExpiries = overview.upcomingExpiries.slice(0, 20);

  return ok(overview);
};

// ---------------------------------------------------------------------------
// Platform administrator claims
// ---------------------------------------------------------------------------

export const setPlatformAdminSchema = z.object({
  userId: z.string().min(1).max(128),
  grant: z.boolean(),
  /** Elevation allows long bypasses; granted only alongside the admin claim. */
  elevated: z.boolean().optional(),
  reason: z.string().trim().min(3).max(240),
});

export type SetPlatformAdminInput = z.infer<typeof setPlatformAdminSchema>;

export function createSetPlatformAdmin(auth: AdminAuthPort, bootstrapEmails: string[]): Handler<
  SetPlatformAdminInput,
  ReturnType<typeof ok<{ userId: string; platformAdmin: boolean }>>
> {
  return async (input, context, deps) => {
    const callerUid = requireAuth(context);
    const parsed = parseWith(setPlatformAdminSchema, input, {
      detail: "admin.invalid_claim_input",
      userMessage: "That request is not valid.",
    });

    // Bootstrap path: the very first administrator cannot be created by an existing
    // administrator, so a deployment can name bootstrap emails in the environment.
    // Everything else requires the claim we are granting.
    const callerEmail = typeof context.claims["email"] === "string" ? (context.claims["email"] as string).toLowerCase() : null;
    const callerIsAdmin = context.claims["platformAdmin"] === true;
    const isBootstrap = !callerIsAdmin && callerEmail !== null && bootstrapEmails.includes(callerEmail);
    if (!callerIsAdmin && !isBootstrap) {
      throw errors.permissionDenied("platform_admin_required");
    }
    if (isBootstrap && parsed.grant !== true) {
      throw errors.permissionDenied("admin.bootstrap_grant_only", "Bootstrap access can only grant administrator rights.");
    }
    if (parsed.userId === callerUid && parsed.grant === false) {
      throw errors.failedPrecondition("admin.cannot_revoke_self", "Ask another administrator to remove your access.");
    }

    const target = await auth.getUser(parsed.userId);
    if (!target) throw errors.notFound("admin.user_missing", "That user account was not found.");

    const nextClaims: Record<string, unknown> = { ...target.customClaims };
    if (parsed.grant) {
      nextClaims["platformAdmin"] = true;
      if (parsed.elevated) nextClaims["platformAdminElevated"] = true;
    } else {
      delete nextClaims["platformAdmin"];
      delete nextClaims["platformAdminElevated"];
    }
    await auth.setCustomUserClaims(parsed.userId, nextClaims);
    // Force a token refresh so the change takes effect promptly and revocation is real.
    await auth.revokeRefreshTokens(parsed.userId);

    await writeAuditLog(deps.store, {
      tenantId: null,
      actorUserId: callerUid,
      action: parsed.grant ? "admin.platform_admin_granted" : "admin.platform_admin_revoked",
      resourceType: "user",
      resourceId: parsed.userId,
      metadata: {
        reason: parsed.reason,
        elevated: parsed.elevated === true,
        viaBootstrap: isBootstrap,
        targetEmail: target.email,
      },
    });

    return ok({ userId: parsed.userId, platformAdmin: parsed.grant });
  };
}

// ---------------------------------------------------------------------------
// Platform settings
// ---------------------------------------------------------------------------

export const updatePlatformSettingsSchema = z.object({
  plans: z
    .array(
      z.object({
        id: z.string().min(1).max(40),
        name: z.string().min(1).max(60),
        description: z.string().max(240),
        priceMinor: z.number().int().min(0),
        currency: z.string().length(3),
        periodDays: z.number().int().min(1).max(730),
        trialDays: z.number().int().min(0).max(365),
        features: z.array(z.string().max(120)).max(20),
        isActive: z.boolean(),
      }),
    )
    .max(10)
    .optional(),
  entitlementPolicy: z
    .object({
      trialDays: z.number().int().min(0).max(365),
      graceDays: z.number().int().min(0).max(90),
      bypassMaxDays: z.number().int().min(1).max(365),
      standardPeriodDays: z.number().int().min(1).max(730),
    })
    .optional(),
});

export type UpdatePlatformSettingsInput = z.infer<typeof updatePlatformSettingsSchema>;

/** Plans and the entitlement policy are stored centrally so no screen hardcodes them. */
export const updatePlatformSettings: Handler<
  UpdatePlatformSettingsInput,
  ReturnType<typeof ok<{ updated: string[] }>>
> = async (input, context, deps) => {
  const adminUserId = requirePlatformAdmin(context);
  const parsed = parseWith(updatePlatformSettingsSchema, input, {
    detail: "admin.invalid_settings_input",
    userMessage: "Some platform settings are not valid.",
  });
  const updated: string[] = [];
  const now = deps.store.now();

  if (parsed.plans) {
    await deps.store.set(`platformConfig/plans`, { plans: parsed.plans, updatedAt: now.toISOString(), updatedBy: adminUserId }, { merge: true });
    updated.push("plans");
  }
  if (parsed.entitlementPolicy) {
    await deps.store.set(
      `platformConfig/settings`,
      { entitlementPolicy: parsed.entitlementPolicy, updatedAt: now.toISOString(), updatedBy: adminUserId },
      { merge: true },
    );
    updated.push("entitlementPolicy");
  }

  await writeAuditLog(deps.store, {
    tenantId: null,
    actorUserId: adminUserId,
    action: "admin.platform_settings_updated",
    resourceType: "platformConfig",
    resourceId: "settings",
    metadata: { updated },
  });

  return ok({ updated });
};

/** Seeds the default plan catalogue on first deployment. Idempotent. */
export async function ensurePlatformConfig(store: Parameters<Handler<unknown, unknown>>[2]["store"]): Promise<void> {
  const existing = await store.get("platformConfig/plans");
  if (existing) return;
  await store.set(
    "platformConfig/plans",
    { plans: DEFAULT_PLANS, updatedAt: store.now().toISOString(), seededBy: "ensurePlatformConfig" },
    { merge: false },
  );
  const settings = await store.get("platformConfig/settings");
  if (!settings) {
    await store.set(
      "platformConfig/settings",
      { entitlementPolicy: DEFAULT_ENTITLEMENT_POLICY, updatedAt: store.now().toISOString() },
      { merge: false },
    );
  }
}

export { isRevenueBearingEntitlement };
