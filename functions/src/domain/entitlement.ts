/**
 * Subscription entitlement.
 *
 * The company's access level is *derived*, never supplied by the app: given the
 * subscription record and the current time, `computeEntitlement` returns the
 * state and the date it is effective until. Cloud Functions enforce this on every
 * write operation; the client only displays what the backend computed.
 *
 * States (see docs/ARCHITECTURE_DECISIONS.md ADR-009):
 *   trialing       — inside the configured free trial
 *   active_paid    — paid through a verified automated payment (later phase)
 *   active_offline — paid offline, recorded by a platform administrator
 *   active_bypass  — time-limited billing bypass granted by an administrator
 *   grace          — expired, still inside the grace period (writes allowed, warned)
 *   limited        — expired past grace: read + export only, no new records
 *   suspended      — explicitly suspended by Articals Studio; always wins
 */

export const ENTITLEMENT_STATES = [
  "trialing",
  "active_paid",
  "active_offline",
  "active_bypass",
  "grace",
  "limited",
  "suspended",
] as const;

export type EntitlementState = (typeof ENTITLEMENT_STATES)[number];

export type BillingMode = "trial" | "automated" | "offline" | "bypass";

export interface BypassRecord {
  active: boolean;
  reason: BypassReason;
  /** ISO date-time the bypass stops being effective. */
  until: string;
  grantedBy: string;
  grantedAt: string;
  /** Admin-only. Must never be returned to company users. */
  internalNotes?: string;
}

export const BYPASS_REASONS = [
  "offline_payment_pending",
  "complimentary_pilot",
  "partner_access",
  "custom_commercial_terms",
  "support_goodwill",
] as const;

export type BypassReason = (typeof BYPASS_REASONS)[number];

export interface OfflinePaymentSummary {
  amountMinor: number;
  currency: string;
  method: "bank_transfer" | "cash" | "pos" | "other";
  receivedAt: string;
  periodStart: string;
  periodEnd: string;
  reference?: string;
}

export interface SubscriptionRecord {
  tenantId: string;
  /** Denormalised for rules and UI; the authoritative value is `status`. */
  planId: string;
  /** Account status. `suspended` is administrative and overrides everything. */
  status: "trialing" | "active" | "expired" | "suspended";
  billingMode: BillingMode;
  trialEndsAt: string | null;
  currentPeriodStart: string | null;
  currentPeriodEnd: string | null;
  /** Backend-written summary of the last offline payment (company-visible dates only). */
  lastOfflinePayment?: OfflinePaymentSummary | null;
  /** Backend-written bypass. `internalNotes` is stripped for company users. */
  bypass?: BypassRecord | null;
}

export interface EntitlementPolicy {
  trialDays: number;
  graceDays: number;
  /** Maximum bypass duration an administrator can grant without elevation. */
  bypassMaxDays: number;
  /** Length of a standard paid period, used when granting a period manually. */
  standardPeriodDays: number;
}

export const DEFAULT_ENTITLEMENT_POLICY: EntitlementPolicy = {
  trialDays: 30,
  graceDays: 7,
  bypassMaxDays: 90,
  standardPeriodDays: 30,
};

export interface Entitlement {
  state: EntitlementState;
  /** UTC ISO instant the current state is valid until; null means open-ended. */
  effectiveUntil: string | null;
  /** Machine-readable explanation, safe to display and to log. */
  reason: string;
  /** True when the workspace may create or modify operational records. */
  writesAllowed: boolean;
}

const WRITE_ALLOWED_STATES: readonly EntitlementState[] = [
  "trialing",
  "active_paid",
  "active_offline",
  "active_bypass",
  "grace",
];

export function writesAllowedIn(state: EntitlementState): boolean {
  return WRITE_ALLOWED_STATES.includes(state);
}

function parseInstant(value: string | null | undefined): number | null {
  if (!value) return null;
  const parsed = Date.parse(value);
  return Number.isNaN(parsed) ? null : parsed;
}

function addDays(instantMs: number, days: number): string {
  return new Date(instantMs + days * 24 * 60 * 60 * 1000).toISOString();
}

/**
 * Computes entitlement from the subscription record.
 *
 * `now` is injected so that the expiry sweep and the tests can move time.
 */
export function computeEntitlement(
  record: SubscriptionRecord,
  now: Date,
  policy: EntitlementPolicy = DEFAULT_ENTITLEMENT_POLICY,
): Entitlement {
  const nowMs = now.getTime();

  // 1. Suspension always wins — even over an active bypass.
  if (record.status === "suspended") {
    return {
      state: "suspended",
      effectiveUntil: null,
      reason: "administratively_suspended",
      writesAllowed: false,
    };
  }

  // 2. Active bypass (time-limited, granted by a platform administrator).
  const bypassUntil = record.bypass?.active ? parseInstant(record.bypass.until) : null;
  if (bypassUntil !== null && bypassUntil > nowMs) {
    return {
      state: "active_bypass",
      effectiveUntil: new Date(bypassUntil).toISOString(),
      reason: `bypass_${record.bypass?.reason ?? "granted"}`,
      writesAllowed: true,
    };
  }

  // 3. Recorded offline payment covering the current moment.
  const paidUntil = parseInstant(record.currentPeriodEnd);
  if (paidUntil !== null && paidUntil > nowMs) {
    if (record.billingMode === "offline") {
      return {
        state: "active_offline",
        effectiveUntil: new Date(paidUntil).toISOString(),
        reason: "offline_payment_recorded",
        writesAllowed: true,
      };
    }
    if (record.billingMode === "automated") {
      return {
        state: "active_paid",
        effectiveUntil: new Date(paidUntil).toISOString(),
        reason: "verified_payment",
        writesAllowed: true,
      };
    }
  }

  // 4. Free trial.
  const trialEnds = parseInstant(record.trialEndsAt);
  if (trialEnds !== null && trialEnds > nowMs) {
    return {
      state: "trialing",
      effectiveUntil: new Date(trialEnds).toISOString(),
      reason: "within_trial",
      writesAllowed: true,
    };
  }

  // 5. Grace period after the latest period/trial end.
  const candidates = [paidUntil, trialEnds].filter((value): value is number => value !== null);
  const expiredAt = candidates.length > 0 ? Math.max(...candidates) : null;
  if (expiredAt !== null) {
    const graceEnds = Date.parse(addDays(expiredAt, policy.graceDays));
    if (nowMs <= graceEnds) {
      return {
        state: "grace",
        effectiveUntil: new Date(graceEnds).toISOString(),
        reason: "within_grace_period",
        writesAllowed: true,
      };
    }
    return {
      state: "limited",
      effectiveUntil: null,
      reason: "expired_past_grace",
      writesAllowed: false,
    };
  }

  // No trial date and no paid period recorded at all: treat as limited rather
  // than granting free access. New companies always get a trialEndsAt at creation.
  return {
    state: "limited",
    effectiveUntil: null,
    reason: "no_active_period",
    writesAllowed: false,
  };
}

/**
 * Days until the current entitlement ends — used by the expiry sweep to warn the
 * administrator and the company owner before access changes.
 */
export function daysUntilExpiry(entitlement: Entitlement, now: Date): number | null {
  if (!entitlement.effectiveUntil) return null;
  const until = Date.parse(entitlement.effectiveUntil);
  if (Number.isNaN(until)) return null;
  return Math.ceil((until - now.getTime()) / (24 * 60 * 60 * 1000));
}

/** True when the state represents money that Articals Studio has actually received. */
export function isRevenueBearingEntitlement(state: EntitlementState): boolean {
  return state === "active_paid" || state === "active_offline";
}

export function bypassDurationDays(until: string, from: Date): number {
  const untilMs = Date.parse(until);
  if (Number.isNaN(untilMs)) return 0;
  // Clamped at zero: a date in the past is not a negative duration, and the daily
  // sweeper must never store a negative day count.
  return Math.max(0, Math.ceil((untilMs - from.getTime()) / (24 * 60 * 60 * 1000)));
}

/** Serialises the entitlement onto a Firestore document (backend-written only). */
export function entitlementFields(entitlement: Entitlement, now: Date) {
  return {
    entitlement: entitlement.state,
    entitlementEffectiveUntil: entitlement.effectiveUntil,
    entitlementReason: entitlement.reason,
    entitlementComputedAt: now.toISOString(),
  };
}
