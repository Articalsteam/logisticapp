/**
 * Subscriptions, offline payments, billing bypass and entitlement enforcement.
 *
 * Rules that this module exists to guarantee (spec §19):
 *  - entitlement is always *computed* on the backend; the app never supplies it;
 *  - only platform administrators (a Firebase custom claim) may record an offline
 *    payment or grant/extend/revoke a bypass — a company owner cannot, through any
 *    path;
 *  - every such action requires a reason, is written to an append-only ledger and to
 *    the audit log, and the internal reason, notes and evidence stay admin-only;
 *  - a bypass changes entitlement only: it never widens data access;
 *  - offline records are labelled honestly in every screen: "Recorded offline by
 *    Articals Studio", never as a provider-verified payment;
 *  - complimentary or bypassed time is never counted as revenue.
 */

import { z } from "zod";
import {
  BYPASS_REASONS,
  DEFAULT_ENTITLEMENT_POLICY,
  bypassDurationDays,
  computeEntitlement,
  daysUntilExpiry,
  entitlementFields,
  isRevenueBearingEntitlement,
  type BypassReason,
  type EntitlementPolicy,
  type SubscriptionRecord,
} from "../domain/entitlement";
import { DEFAULT_PLANS } from "../config/platformDefaults";
import type { DataStore } from "../data/types";
import { writeAuditLog } from "../shared/audit";
import { requireAuth, requireMembership, requirePlatformAdmin } from "../shared/auth";
import { errors } from "../shared/errors";
import { requireIdempotencyKey, withIdempotency } from "../shared/idempotency";
import { addDaysToInstant, toIso } from "../shared/time";
import { currencySchema, dateKeySchema, isoInstantSchema, minorSchema, parseWith, notesSchema } from "../shared/validation";
import { ok, type Handler } from "./deps";
import { queueNotification } from "./orders";

const offlinePaymentMethodSchema = z.enum(["bank_transfer", "cash", "pos", "other"]);

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

export async function loadSubscription(store: DataStore, tenantId: string): Promise<SubscriptionRecord | null> {
  const doc = await store.get(`subscriptions/${tenantId}`);
  if (!doc) return null;
  const data = doc.data;
  return {
    tenantId,
    planId: typeof data["planId"] === "string" ? (data["planId"] as string) : "none",
    status:
      data["status"] === "suspended" ? "suspended" : data["status"] === "trialing" ? "trialing" : data["status"] === "expired" ? "expired" : "active",
    billingMode:
      data["billingMode"] === "automated" || data["billingMode"] === "offline" || data["billingMode"] === "bypass" ? data["billingMode"] : "trial",
    trialEndsAt: typeof data["trialEndsAt"] === "string" ? (data["trialEndsAt"] as string) : null,
    currentPeriodStart: typeof data["currentPeriodStart"] === "string" ? (data["currentPeriodStart"] as string) : null,
    currentPeriodEnd: typeof data["currentPeriodEnd"] === "string" ? (data["currentPeriodEnd"] as string) : null,
    bypass: (data["bypass"] ?? null) as SubscriptionRecord["bypass"],
    lastOfflinePayment: (data["lastOfflinePayment"] ?? null) as SubscriptionRecord["lastOfflinePayment"],
  };
}

export interface LedgerEntry {
  type:
    | "payment_recorded_offline"
    | "bypass_granted"
    | "bypass_extended"
    | "bypass_revoked"
    | "subscription_activated"
    | "subscription_suspended"
    | "subscription_reinstated"
    | "period_extended"
    | "plan_changed"
    | "entitlement_changed";
  actorUserId: string;
  reason?: string | null;
  effectiveFrom?: string | null;
  effectiveUntil?: string | null;
  amountMinor?: number | null;
  currency?: string | null;
  reference?: string | null;
  /** Admin-only notes; kept out of the company-visible subscription document. */
  internalNotes?: string | null;
  metadata?: Record<string, unknown>;
}

/** Append-only ledger. Never an array field on the subscription document. */
export async function writeLedgerEntry(store: DataStore, tenantId: string, entry: LedgerEntry): Promise<string> {
  const id = store.newId("ledger");
  await store.create(`subscriptions/${tenantId}/ledger/${id}`, {
    tenantId,
    type: entry.type,
    actorUserId: entry.actorUserId,
    reason: entry.reason ?? null,
    effectiveFrom: entry.effectiveFrom ?? null,
    effectiveUntil: entry.effectiveUntil ?? null,
    amountMinor: entry.amountMinor ?? null,
    currency: entry.currency ?? null,
    reference: entry.reference ?? null,
    internalNotes: entry.internalNotes ?? null,
    metadata: entry.metadata ?? {},
    createdAt: store.now().toISOString(),
  });
  return id;
}

/**
 * Recomputes and persists entitlement. Called after every subscription change and by
 * the scheduled sweep, so the stored value is always derived, never assumed.
 */
export async function recomputeEntitlement(
  store: DataStore,
  tenantId: string,
  policy: EntitlementPolicy = DEFAULT_ENTITLEMENT_POLICY,
): Promise<ReturnType<typeof computeEntitlement>> {
  const record = await loadSubscription(store, tenantId);
  const now = store.now();
  if (!record) {
    return computeEntitlement(
      {
        tenantId,
        planId: "none",
        status: "expired",
        billingMode: "trial",
        trialEndsAt: null,
        currentPeriodStart: null,
        currentPeriodEnd: null,
        bypass: null,
        lastOfflinePayment: null,
      },
      now,
      policy,
    );
  }
  const entitlement = computeEntitlement(record, now, policy);
  await store.set(`subscriptions/${tenantId}`, { ...entitlementFields(entitlement, now), updatedAt: now.toISOString() }, { merge: true });
  return entitlement;
}

async function ownerUserIds(store: DataStore, tenantId: string): Promise<string[]> {
  const owners = await store.query("memberships", {
    where: [
      { field: "tenantId", op: "==", value: tenantId },
      { field: "role", op: "==", value: "owner" },
      { field: "status", op: "==", value: "active" },
    ],
    limit: 5,
  });
  return owners.map((doc) => String(doc.data["userId"]));
}

async function notifyOwners(store: DataStore, tenantId: string, title: string, body: string, type: "subscription_expiring" | "subscription_changed"): Promise<void> {
  const recipients = await ownerUserIds(store, tenantId);
  if (recipients.length === 0) return;
  await queueNotification(store, {
    tenantId,
    type,
    recipientUserIds: recipients,
    title,
    // No business details, no amounts: a lock screen may be visible to anyone.
    body,
    data: { tenantId, type },
  });
}

// ---------------------------------------------------------------------------
// Company-facing view
// ---------------------------------------------------------------------------

export const getMySubscriptionSchema = z.object({ tenantId: z.string().min(1).max(64) });

export type GetMySubscriptionInput = z.infer<typeof getMySubscriptionSchema>;

export interface MySubscriptionResult {
  planId: string;
  planName: string;
  planPriceMinor: number;
  planCurrency: string;
  status: string;
  billingMode: string;
  entitlement: string;
  entitlementEffectiveUntil: string | null;
  entitlementLabel: string;
  trialEndsAt: string | null;
  currentPeriodStart: string | null;
  currentPeriodEnd: string | null;
  bypass: { active: boolean; until: string | null; reason: string | null } | null;
  lastOfflinePayment: {
    amountMinor: number;
    currency: string;
    method: string;
    receivedAt: string;
    periodStart: string;
    periodEnd: string;
  } | null;
  daysRemaining: number | null;
  writesAllowed: boolean;
  /** Honest labelling for the UI (spec §19). */
  billingNotice: string | null;
}

/**
 * What a company owner may see: status and dates only. Internal reasons, notes,
 * references and evidence remain visible to platform administrators alone.
 */
export const getMySubscription: Handler<GetMySubscriptionInput, ReturnType<typeof ok<MySubscriptionResult>>> = async (
  input,
  context,
  deps,
) => {
  const parsed = parseWith(getMySubscriptionSchema, input, {
    detail: "subscription.invalid_input",
    userMessage: "That request is not valid.",
  });
  const membership = await requireMembership(deps.store, context, parsed.tenantId, "subscription.view_status");

  const record = await loadSubscription(deps.store, parsed.tenantId);
  const now = deps.store.now();
  const entitlement = await recomputeEntitlement(deps.store, parsed.tenantId);
  const plan = DEFAULT_PLANS.find((entry) => entry.id === (record?.planId ?? "")) ?? null;

  let billingNotice: string | null = null;
  if (entitlement.state === "active_offline") billingNotice = "Recorded offline by Articals Studio";
  else if (entitlement.state === "active_bypass") billingNotice = "Billing bypass granted by Articals Studio";
  else if (entitlement.state === "trialing") billingNotice = "Free trial";
  else if (entitlement.state === "grace") billingNotice = "Payment due — inside the grace period";
  else if (entitlement.state === "limited") billingNotice = "Account limited until billing is settled. Your data is safe and exportable.";

  return ok({
    planId: record?.planId ?? "none",
    planName: plan?.name ?? "No plan",
    planPriceMinor: plan?.priceMinor ?? 0,
    planCurrency: plan?.currency ?? "NGN",
    status: record?.status ?? "expired",
    billingMode: record?.billingMode ?? "trial",
    entitlement: entitlement.state,
    entitlementEffectiveUntil: entitlement.effectiveUntil,
    entitlementLabel: entitlementLabel(entitlement.state),
    trialEndsAt: record?.trialEndsAt ?? null,
    currentPeriodStart: record?.currentPeriodStart ?? null,
    currentPeriodEnd: record?.currentPeriodEnd ?? null,
    bypass: record?.bypass
      ? { active: record.bypass.active, until: record.bypass.until, reason: record.bypass.reason }
      : null,
    lastOfflinePayment: record?.lastOfflinePayment
      ? {
          amountMinor: record.lastOfflinePayment.amountMinor,
          currency: record.lastOfflinePayment.currency,
          method: record.lastOfflinePayment.method,
          receivedAt: record.lastOfflinePayment.receivedAt,
          periodStart: record.lastOfflinePayment.periodStart,
          periodEnd: record.lastOfflinePayment.periodEnd,
        }
      : null,
    daysRemaining: daysUntilExpiry(entitlement, now),
    writesAllowed: entitlement.writesAllowed,
    billingNotice,
  });
};

function entitlementLabel(state: string): string {
  switch (state) {
    case "trialing":
      return "Free trial";
    case "active_paid":
      return "Active — paid";
    case "active_offline":
      return "Active — paid offline (recorded by Articals Studio)";
    case "active_bypass":
      return "Active — billing bypass";
    case "grace":
      return "Grace period";
    case "limited":
      return "Limited access";
    case "suspended":
      return "Suspended";
    default:
      return state;
  }
}

// ---------------------------------------------------------------------------
// Offline payments (platform admin only)
// ---------------------------------------------------------------------------

export const recordOfflinePaymentSchema = z.object({
  tenantId: z.string().min(1).max(64),
  idempotencyKey: z.string(),
  amountMinor: minorSchema,
  currency: currencySchema,
  method: offlinePaymentMethodSchema,
  /** Bank transfer reference, receipt number, cheque number, etc. */
  externalReference: z.string().trim().min(2).max(120),
  receivedAt: isoInstantSchema,
  /** The period this payment covers. */
  periodStart: isoInstantSchema,
  periodEnd: isoInstantSchema,
  /** Optional protected Storage path to evidence (never logged, never returned). */
  evidencePath: z.string().max(400).nullable().optional(),
  internalNotes: notesSchema.optional(),
  /** Required: why this payment is being recorded. */
  reason: z.string().trim().min(3).max(240),
  planId: z.string().max(40).optional(),
});

export type RecordOfflinePaymentInput = z.infer<typeof recordOfflinePaymentSchema>;

export interface RecordOfflinePaymentResult {
  tenantId: string;
  ledgerEntryId: string;
  entitlement: string;
  entitlementEffectiveUntil: string | null;
  periodEnd: string;
}

export const recordOfflinePayment: Handler<
  RecordOfflinePaymentInput,
  ReturnType<typeof ok<RecordOfflinePaymentResult>>
> = async (input, context, deps) => {
  const adminUserId = requirePlatformAdmin(context);
  const parsed = parseWith(recordOfflinePaymentSchema, input, {
    detail: "subscription.invalid_offline_payment",
    userMessage: "Some payment details are not valid.",
  });
  const idempotencyKey = requireIdempotencyKey(parsed.idempotencyKey);

  if (Date.parse(parsed.periodEnd) <= Date.parse(parsed.periodStart)) {
    throw errors.invalidArgument("subscription.period_invalid", "The covered period must end after it starts.");
  }
  const company = await deps.store.get(`companies/${parsed.tenantId}`);
  if (!company) throw errors.notFound("company_missing", "That company workspace was not found.");

  const outcome = await withIdempotency<RecordOfflinePaymentResult & Record<string, unknown>>(
    deps.store,
    { scope: "recordOfflinePayment", tenantId: parsed.tenantId, actorUserId: adminUserId, key: idempotencyKey },
    async () => {
      const now = deps.store.now();
      const existing = await loadSubscription(deps.store, parsed.tenantId);
      const billingMode = "offline" as const;

      const next: SubscriptionRecord = {
        tenantId: parsed.tenantId,
        planId: parsed.planId ?? existing?.planId ?? "starter",
        status: "active",
        billingMode,
        trialEndsAt: existing?.trialEndsAt ?? null,
        currentPeriodStart: parsed.periodStart,
        currentPeriodEnd: parsed.periodEnd,
        bypass: existing?.bypass ?? null,
        lastOfflinePayment: {
          amountMinor: parsed.amountMinor,
          currency: parsed.currency,
          method: parsed.method,
          receivedAt: parsed.receivedAt,
          periodStart: parsed.periodStart,
          periodEnd: parsed.periodEnd,
          reference: parsed.externalReference,
        },
      };
      const entitlement = computeEntitlement(next, now, DEFAULT_ENTITLEMENT_POLICY);

      const ledgerEntryId = await deps.store.runTransaction(async () => {
        return writeLedgerEntry(deps.store, parsed.tenantId, {
          type: "payment_recorded_offline",
          actorUserId: adminUserId,
          reason: parsed.reason,
          effectiveFrom: parsed.periodStart,
          effectiveUntil: parsed.periodEnd,
          amountMinor: parsed.amountMinor,
          currency: parsed.currency,
          reference: parsed.externalReference,
          internalNotes: parsed.internalNotes ?? null,
          metadata: { method: parsed.method, planId: next.planId },
        });
      });

      await deps.store.set(
        `subscriptions/${parsed.tenantId}`,
        {
          planId: next.planId,
          status: next.status,
          billingMode,
          trialEndsAt: next.trialEndsAt,
          currentPeriodStart: next.currentPeriodStart,
          currentPeriodEnd: next.currentPeriodEnd,
          // Company-visible summary: amount, dates and method only. The reference and
          // notes live in the admin-only document below.
          lastOfflinePayment: {
            amountMinor: parsed.amountMinor,
            currency: parsed.currency,
            method: parsed.method,
            receivedAt: parsed.receivedAt,
            periodStart: parsed.periodStart,
            periodEnd: parsed.periodEnd,
          },
          ...entitlementFields(entitlement, now),
          updatedAt: now.toISOString(),
        },
        { merge: true },
      );

      await deps.store.set(
        `subscriptions/${parsed.tenantId}/private/billing`,
        {
          tenantId: parsed.tenantId,
          lastPaymentReference: parsed.externalReference,
          lastPaymentInternalNotes: parsed.internalNotes ?? null,
          evidencePath: parsed.evidencePath ?? null,
          recordedBy: adminUserId,
          recordedAt: now.toISOString(),
        },
        { merge: true },
      );

      await writeAuditLog(deps.store, {
        tenantId: parsed.tenantId,
        actorUserId: adminUserId,
        action: "subscription.offline_payment_recorded",
        resourceType: "subscription",
        resourceId: parsed.tenantId,
        metadata: {
          amountMinor: parsed.amountMinor,
          currency: parsed.currency,
          method: parsed.method,
          reference: parsed.externalReference,
          periodStart: parsed.periodStart,
          periodEnd: parsed.periodEnd,
          reason: parsed.reason,
          ledgerEntryId,
          entitlement: entitlement.state,
        },
      });

      await notifyOwners(
        deps.store,
        parsed.tenantId,
        "Subscription updated",
        "Articals Studio recorded an offline payment for your company account.",
        "subscription_changed",
      );

      return {
        tenantId: parsed.tenantId,
        ledgerEntryId,
        entitlement: entitlement.state,
        entitlementEffectiveUntil: entitlement.effectiveUntil,
        periodEnd: parsed.periodEnd,
      } as RecordOfflinePaymentResult & Record<string, unknown>;
    },
  );

  return ok(outcome.result, outcome.replayed);
};

// ---------------------------------------------------------------------------
// Billing bypass (platform admin only)
// ---------------------------------------------------------------------------

export const setBillingBypassSchema = z.object({
  tenantId: z.string().min(1).max(64),
  idempotencyKey: z.string(),
  reason: z.enum(BYPASS_REASONS),
  /** ISO instant the bypass stops being effective. Required. */
  until: isoInstantSchema,
  internalNotes: notesSchema.optional(),
  /** Optional replacement of an existing bypass (an "extension"). */
  replacesExisting: z.boolean().optional(),
});

export type SetBillingBypassInput = z.infer<typeof setBillingBypassSchema>;

export interface SetBillingBypassResult {
  tenantId: string;
  ledgerEntryId: string;
  entitlement: string;
  activeUntil: string;
  durationDays: number;
}

export const setBillingBypass: Handler<SetBillingBypassInput, ReturnType<typeof ok<SetBillingBypassResult>>> = async (
  input,
  context,
  deps,
) => {
  const adminUserId = requirePlatformAdmin(context);
  const parsed = parseWith(setBillingBypassSchema, input, {
    detail: "subscription.invalid_bypass_input",
    userMessage: "Some bypass details are not valid.",
  });
  const idempotencyKey = requireIdempotencyKey(parsed.idempotencyKey);

  const company = await deps.store.get(`companies/${parsed.tenantId}`);
  if (!company) throw errors.notFound("company_missing", "That company workspace was not found.");

  const now = deps.store.now();
  const untilDate = new Date(parsed.until);
  if (untilDate.getTime() <= now.getTime()) {
    throw errors.invalidArgument("subscription.bypass_in_past", "A bypass must end in the future.");
  }
  const durationDays = bypassDurationDays(parsed.until, now);
  const elevated = context.claims["platformAdminElevated"] === true;
  if (durationDays > DEFAULT_ENTITLEMENT_POLICY.bypassMaxDays && !elevated) {
    throw errors.permissionDenied(
      "subscription.bypass_too_long",
      `A bypass longer than ${DEFAULT_ENTITLEMENT_POLICY.bypassMaxDays} days needs an elevated platform permission.`,
      { requestedDays: durationDays, maximumDays: DEFAULT_ENTITLEMENT_POLICY.bypassMaxDays },
    );
  }

  const outcome = await withIdempotency<SetBillingBypassResult & Record<string, unknown>>(
    deps.store,
    { scope: "setBillingBypass", tenantId: parsed.tenantId, actorUserId: adminUserId, key: idempotencyKey },
    async () => {
      const existing = await loadSubscription(deps.store, parsed.tenantId);
      const isExtension = existing?.bypass?.active === true;

      const next: SubscriptionRecord = {
        tenantId: parsed.tenantId,
        planId: existing?.planId ?? "pilot",
        status: "active",
        billingMode: "bypass",
        trialEndsAt: existing?.trialEndsAt ?? null,
        currentPeriodStart: existing?.currentPeriodStart ?? null,
        // The bypass window is what keeps the account active, so mirror it onto the
        // period end: one place answers "how long is this account good for".
        currentPeriodEnd: parsed.until,
        lastOfflinePayment: existing?.lastOfflinePayment ?? null,
        bypass: {
          active: true,
          reason: parsed.reason as BypassReason,
          until: parsed.until,
          grantedBy: adminUserId,
          grantedAt: now.toISOString(),
          internalNotes: parsed.internalNotes ?? undefined,
        },
      };
      const entitlement = computeEntitlement(next, now, DEFAULT_ENTITLEMENT_POLICY);

      const ledgerEntryId = await writeLedgerEntry(deps.store, parsed.tenantId, {
        type: isExtension ? "bypass_extended" : "bypass_granted",
        actorUserId: adminUserId,
        reason: parsed.reason,
        effectiveFrom: now.toISOString(),
        effectiveUntil: parsed.until,
        internalNotes: parsed.internalNotes ?? null,
        metadata: { durationDays, elevated, previousUntil: existing?.bypass?.until ?? null },
      });

      await deps.store.set(
        `subscriptions/${parsed.tenantId}`,
        {
          billingMode: "bypass",
          status: "active",
          currentPeriodEnd: parsed.until,
          // Only safe fields: the reason is an enum and the notes stay in the ledger.
          bypass: {
            active: true,
            reason: parsed.reason,
            until: parsed.until,
            grantedBy: adminUserId,
            grantedAt: now.toISOString(),
          },
          ...entitlementFields(entitlement, now),
          updatedAt: now.toISOString(),
        },
        { merge: true },
      );

      await writeAuditLog(deps.store, {
        tenantId: parsed.tenantId,
        actorUserId: adminUserId,
        action: isExtension ? "subscription.bypass_extended" : "subscription.bypass_granted",
        resourceType: "subscription",
        resourceId: parsed.tenantId,
        metadata: { reason: parsed.reason, until: parsed.until, durationDays, ledgerEntryId, entitlement: entitlement.state },
      });

      await notifyOwners(
        deps.store,
        parsed.tenantId,
        "Account access extended",
        "Articals Studio granted a temporary billing bypass for your company account.",
        "subscription_changed",
      );

      return {
        tenantId: parsed.tenantId,
        ledgerEntryId,
        entitlement: entitlement.state,
        activeUntil: parsed.until,
        durationDays,
      } as SetBillingBypassResult & Record<string, unknown>;
    },
  );

  return ok(outcome.result, outcome.replayed);
};

export const revokeBillingBypassSchema = z.object({
  tenantId: z.string().min(1).max(64),
  reason: z.string().trim().min(3).max(240),
  /** Also suspend the account outright, or just drop the bypass. */
  suspendAccount: z.boolean().optional(),
});

export type RevokeBillingBypassInput = z.infer<typeof revokeBillingBypassSchema>;

export const revokeBillingBypass: Handler<RevokeBillingBypassInput, ReturnType<typeof ok<{ tenantId: string; entitlement: string }>>> =
  async (input, context, deps) => {
    const adminUserId = requirePlatformAdmin(context);
    const parsed = parseWith(revokeBillingBypassSchema, input, {
      detail: "subscription.invalid_revoke_input",
      userMessage: "That request is not valid.",
    });
    const existing = await loadSubscription(deps.store, parsed.tenantId);
    if (!existing) throw errors.notFound("subscription_missing", "That company has no subscription record.");
    if (!existing.bypass?.active) {
      throw errors.failedPrecondition("subscription.no_active_bypass", "There is no active bypass to revoke.");
    }

    const now = deps.store.now();
    await deps.store.set(
      `subscriptions/${parsed.tenantId}`,
      {
        bypass: { ...existing.bypass, active: false, revokedBy: adminUserId, revokedAt: now.toISOString(), revokedReason: parsed.reason },
        ...(parsed.suspendAccount ? { status: "suspended" } : {}),
        updatedAt: now.toISOString(),
      },
      { merge: true },
    );

    const ledgerEntryId = await writeLedgerEntry(deps.store, parsed.tenantId, {
      type: "bypass_revoked",
      actorUserId: adminUserId,
      reason: parsed.reason,
      effectiveFrom: now.toISOString(),
      effectiveUntil: now.toISOString(),
      metadata: { suspendedAccount: parsed.suspendAccount === true, previousUntil: existing.bypass.until },
    });

    const entitlement = await recomputeEntitlement(deps.store, parsed.tenantId);
    await writeAuditLog(deps.store, {
      tenantId: parsed.tenantId,
      actorUserId: adminUserId,
      action: "subscription.bypass_revoked",
      resourceType: "subscription",
      resourceId: parsed.tenantId,
      metadata: { reason: parsed.reason, ledgerEntryId, entitlement: entitlement.state, suspended: parsed.suspendAccount === true },
    });

    return ok({ tenantId: parsed.tenantId, entitlement: entitlement.state });
  };

// ---------------------------------------------------------------------------
// Administrative subscription management
// ---------------------------------------------------------------------------

export const manageSubscriptionSchema = z.object({
  tenantId: z.string().min(1).max(64),
  action: z.enum(["activate", "suspend", "reinstate", "extend_period", "change_plan", "set_trial_end"]),
  planId: z.string().max(40).optional(),
  extendDays: z.number().int().min(1).max(730).optional(),
  trialEndsAt: isoInstantSchema.nullable().optional(),
  reason: z.string().trim().min(3).max(240),
});

export type ManageSubscriptionInput = z.infer<typeof manageSubscriptionSchema>;

export interface ManageSubscriptionResult {
  tenantId: string;
  status: string;
  entitlement: string;
  entitlementEffectiveUntil: string | null;
}

export const manageSubscription: Handler<
  ManageSubscriptionInput,
  ReturnType<typeof ok<ManageSubscriptionResult>>
> = async (input, context, deps) => {
  const adminUserId = requirePlatformAdmin(context);
  const parsed = parseWith(manageSubscriptionSchema, input, {
    detail: "subscription.invalid_manage_input",
    userMessage: "That subscription change is not valid.",
  });
  const company = await deps.store.get(`companies/${parsed.tenantId}`);
  if (!company) throw errors.notFound("company_missing", "That company workspace was not found.");

  const now = deps.store.now();
  const existing = await loadSubscription(deps.store, parsed.tenantId);
  const patch: Record<string, unknown> = {};
  let ledgerType: LedgerEntry["type"] = "subscription_activated";
  let ledgerReason = parsed.reason;

  switch (parsed.action) {
    case "activate": {
      const planId = parsed.planId ?? existing?.planId ?? "starter";
      const periodStart = now;
      const periodEnd = addDaysToInstant(now, DEFAULT_ENTITLEMENT_POLICY.standardPeriodDays);
      patch["status"] = "active";
      patch["planId"] = planId;
      patch["billingMode"] = "automated";
      patch["currentPeriodStart"] = periodStart.toISOString();
      patch["currentPeriodEnd"] = periodEnd.toISOString();
      ledgerType = "subscription_activated";
      break;
    }
    case "suspend": {
      patch["status"] = "suspended";
      ledgerType = "subscription_suspended";
      break;
    }
    case "reinstate": {
      patch["status"] = "active";
      ledgerType = "subscription_reinstated";
      break;
    }
    case "extend_period": {
      if (!parsed.extendDays) {
        throw errors.invalidArgument("subscription.extend_days_required", "How many days should the period be extended by?");
      }
      const base = existing?.currentPeriodEnd ? new Date(existing.currentPeriodEnd) : now;
      const from = base.getTime() > now.getTime() ? base : now;
      patch["currentPeriodEnd"] = addDaysToInstant(from, parsed.extendDays).toISOString();
      patch["currentPeriodStart"] = existing?.currentPeriodStart ?? now.toISOString();
      patch["status"] = "active";
      ledgerType = "period_extended";
      break;
    }
    case "change_plan": {
      if (!parsed.planId) throw errors.invalidArgument("subscription.plan_required", "Choose a plan.");
      patch["planId"] = parsed.planId;
      ledgerType = "plan_changed";
      break;
    }
    case "set_trial_end": {
      patch["trialEndsAt"] = parsed.trialEndsAt ?? null;
      patch["status"] = "trialing";
      patch["billingMode"] = "trial";
      break;
    }
    default: {
      throw errors.unsupported("subscription.unknown_action", "That subscription action is not supported.");
    }
  }

  await deps.store.set(`subscriptions/${parsed.tenantId}`, { ...patch, updatedAt: now.toISOString() }, { merge: true });
  const entitlement = await recomputeEntitlement(deps.store, parsed.tenantId);

  const ledgerEntryId = await writeLedgerEntry(deps.store, parsed.tenantId, {
    type: ledgerType,
    actorUserId: adminUserId,
    reason: ledgerReason,
    effectiveFrom: now.toISOString(),
    effectiveUntil: entitlement.effectiveUntil,
    metadata: { action: parsed.action, ...patch },
  });

  await writeAuditLog(deps.store, {
    tenantId: parsed.tenantId,
    actorUserId: adminUserId,
    action: `subscription.${parsed.action}`,
    resourceType: "subscription",
    resourceId: parsed.tenantId,
    metadata: { reason: ledgerReason, ledgerEntryId, entitlement: entitlement.state, patch },
  });

  await notifyOwners(
    deps.store,
    parsed.tenantId,
    "Subscription updated",
    "Articals Studio updated your company subscription. Open the app for the current status.",
    "subscription_changed",
  );

  const status = typeof patch["status"] === "string" ? (patch["status"] as string) : (existing?.status ?? "active");
  return ok({
    tenantId: parsed.tenantId,
    status,
    entitlement: entitlement.state,
    entitlementEffectiveUntil: entitlement.effectiveUntil,
  });
};

// ---------------------------------------------------------------------------
// Scheduled expiry sweep
// ---------------------------------------------------------------------------

export interface SweepSummary {
  scanned: number;
  changed: number;
  limited: number;
  suspended: number;
  warningsQueued: number;
}

/**
 * Runs daily (Cloud Scheduler). Recomputes entitlement for every company, notifies
 * owners before an expiry and platform administrators when an account actually
 * crosses into grace or limited.
 */
export async function runEntitlementSweep(
  deps: Parameters<Handler<unknown, unknown>>[2],
  adminRecipients: string[],
): Promise<SweepSummary> {
  const subscriptions = await deps.store.query("subscriptions", { limit: 500 });
  const summary: SweepSummary = { scanned: 0, changed: 0, limited: 0, suspended: 0, warningsQueued: 0 };
  const now = deps.store.now();

  for (const doc of subscriptions) {
    summary.scanned += 1;
    const tenantId = String(doc.data["tenantId"] ?? doc.id);
    const before = typeof doc.data["entitlement"] === "string" ? (doc.data["entitlement"] as string) : null;
    const record = await loadSubscription(deps.store, tenantId);
    if (!record) continue;
    const entitlement = computeEntitlement(record, now, DEFAULT_ENTITLEMENT_POLICY);
    if (entitlement.state !== before) summary.changed += 1;
    if (entitlement.state === "limited") summary.limited += 1;
    if (entitlement.state === "suspended") summary.suspended += 1;

    await deps.store.set(`subscriptions/${tenantId}`, { ...entitlementFields(entitlement, now), updatedAt: now.toISOString() }, { merge: true });

    const days = daysUntilExpiry(entitlement, now);
    const shouldWarnOwner = days !== null && days >= 0 && days <= 7 && entitlement.writesAllowed;
    const lastWarned = typeof doc.data["lastOwnerWarningAt"] === "string" ? (doc.data["lastOwnerWarningAt"] as string) : null;
    const warnedToday = lastWarned !== null && lastWarned.slice(0, 10) === now.toISOString().slice(0, 10);

    if (shouldWarnOwner && !warnedToday) {
      await notifyOwners(
        deps.store,
        tenantId,
        "Subscription expiring soon",
        `Your company account access ends in ${days} day${days === 1 ? "" : "s"}. Contact Articals Studio to continue.`,
        "subscription_expiring",
      );
      await deps.store.set(`subscriptions/${tenantId}`, { lastOwnerWarningAt: now.toISOString() }, { merge: true });
      summary.warningsQueued += 1;
    }

    if (entitlement.state !== before && (entitlement.state === "limited" || entitlement.state === "grace")) {
      if (adminRecipients.length > 0) {
        await queueNotification(deps.store, {
          tenantId,
          type: "subscription_expiring",
          recipientUserIds: adminRecipients,
          title: entitlement.state === "limited" ? "Company account limited" : "Company account in grace",
          body: `A company account moved to ${entitlement.state}.`,
          data: { tenantId, type: "subscription_state_changed" },
        });
      }
      await writeAuditLog(deps.store, {
        tenantId,
        actorUserId: "scheduled-sweep",
        action: "subscription.entitlement_changed",
        resourceType: "subscription",
        resourceId: tenantId,
        metadata: { from: before, to: entitlement.state, reason: entitlement.reason },
      });
      await writeLedgerEntry(deps.store, tenantId, {
        type: "entitlement_changed",
        actorUserId: "scheduled-sweep",
        reason: entitlement.reason,
        effectiveFrom: now.toISOString(),
        effectiveUntil: entitlement.effectiveUntil,
        metadata: { from: before, to: entitlement.state },
      });
    }
  }

  return summary;
}

/** Platform revenue categories — bypassed and complimentary time is never revenue. */
export function classifyEntitlementForRevenue(state: string): "automated" | "offline" | "non_revenue" {
  if (state === "active_paid") return "automated";
  if (state === "active_offline") return "offline";
  return "non_revenue";
}

export { isRevenueBearingEntitlement, dateKeySchema };
