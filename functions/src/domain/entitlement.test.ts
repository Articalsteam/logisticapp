/**
 * Subscription entitlement tests (spec §17).
 *
 * Entitlement decides whether a company workspace may write. It is deliberately
 * derived — recomputed from the subscription record and persisted — rather than
 * stored as a flag someone could set, and these tests pin the precedence order
 * (suspension → bypass → paid period → trial → grace → limited) that the whole
 * billing model hangs on.
 */

import { describe, expect, it } from "vitest";
import {
  bypassDurationDays,
  computeEntitlement,
  daysUntilExpiry,
  DEFAULT_ENTITLEMENT_POLICY,
  entitlementFields,
  isRevenueBearingEntitlement,
  writesAllowedIn,
  type SubscriptionRecord,
} from "./entitlement";

const NOW = new Date("2026-10-14T09:00:00.000Z");

function daysFromNow(days: number): string {
  return new Date(NOW.getTime() + days * 24 * 60 * 60 * 1000).toISOString();
}

function subscription(overrides: Partial<SubscriptionRecord> = {}): SubscriptionRecord {
  return {
    tenantId: "mx-logistics",
    planId: "pilot",
    status: "trialing",
    billingMode: "trial",
    trialEndsAt: daysFromNow(20),
    currentPeriodStart: null,
    currentPeriodEnd: null,
    lastOfflinePayment: null,
    bypass: null,
    ...overrides,
  };
}

describe("precedence", () => {
  it("puts an administrative suspension above everything, including a live bypass", () => {
    const entitlement = computeEntitlement(
      subscription({
        status: "suspended",
        bypass: { active: true, reason: "complimentary_pilot", until: daysFromNow(60), grantedBy: "admin", grantedAt: daysFromNow(-1) },
      }),
      NOW,
    );
    expect(entitlement.state).toBe("suspended");
    expect(entitlement.writesAllowed).toBe(false);
    expect(entitlement.reason).toBe("administratively_suspended");
  });

  it("honours an active bypass over an expired paid period", () => {
    const entitlement = computeEntitlement(
      subscription({
        status: "expired",
        billingMode: "offline",
        trialEndsAt: daysFromNow(-90),
        currentPeriodEnd: daysFromNow(-40),
        bypass: { active: true, reason: "partner_access", until: daysFromNow(10), grantedBy: "admin", grantedAt: daysFromNow(-5) },
      }),
      NOW,
    );
    expect(entitlement.state).toBe("active_bypass");
    expect(entitlement.writesAllowed).toBe(true);
    expect(entitlement.effectiveUntil).toBe(daysFromNow(10));
  });

  it("ignores an expired bypass", () => {
    const entitlement = computeEntitlement(
      subscription({
        status: "expired",
        billingMode: "bypass",
        trialEndsAt: daysFromNow(-90),
        bypass: { active: true, reason: "complimentary_pilot", until: daysFromNow(-1), grantedBy: "admin", grantedAt: daysFromNow(-30) },
      }),
      NOW,
    );
    expect(entitlement.state).not.toBe("active_bypass");
  });

  it("ignores a bypass flag that is not active", () => {
    const entitlement = computeEntitlement(
      subscription({
        status: "expired",
        trialEndsAt: daysFromNow(-2),
        bypass: { active: false, reason: "support_goodwill", until: daysFromNow(30), grantedBy: "admin", grantedAt: daysFromNow(-1) },
      }),
      NOW,
    );
    expect(entitlement.state).toBe("grace");
  });
});

describe("paid states", () => {
  it("reports an offline payment as active_offline, never as automated revenue", () => {
    const entitlement = computeEntitlement(
      subscription({
        status: "active",
        billingMode: "offline",
        trialEndsAt: daysFromNow(-10),
        currentPeriodStart: daysFromNow(-5),
        currentPeriodEnd: daysFromNow(25),
      }),
      NOW,
    );
    expect(entitlement.state).toBe("active_offline");
    expect(entitlement.reason).toBe("offline_payment_recorded");
    expect(isRevenueBearingEntitlement(entitlement.state)).toBe(true);
  });

  it("reports a verified automated payment as active_paid", () => {
    const entitlement = computeEntitlement(
      subscription({
        status: "active",
        billingMode: "automated",
        currentPeriodStart: daysFromNow(-5),
        currentPeriodEnd: daysFromNow(25),
      }),
      NOW,
    );
    expect(entitlement.state).toBe("active_paid");
    expect(entitlement.reason).toBe("verified_payment");
  });

  it("falls back to the trial when a paid period is recorded but already over", () => {
    const entitlement = computeEntitlement(
      subscription({
        status: "active",
        billingMode: "offline",
        trialEndsAt: daysFromNow(10),
        currentPeriodEnd: daysFromNow(-2),
      }),
      NOW,
    );
    expect(entitlement.state).toBe("trialing");
  });
});

describe("trial, grace and limited", () => {
  it("grants full access during the trial", () => {
    const entitlement = computeEntitlement(subscription(), NOW);
    expect(entitlement.state).toBe("trialing");
    expect(entitlement.writesAllowed).toBe(true);
    expect(daysUntilExpiry(entitlement, NOW)).toBe(20);
  });

  it("enters grace for a week after the trial ends, then falls to limited", () => {
    const expired = computeEntitlement(subscription({ trialEndsAt: daysFromNow(-1) }), NOW);
    expect(expired.state).toBe("grace");
    expect(expired.writesAllowed).toBe(true);
    expect(daysUntilExpiry(expired, NOW)).toBe(6); // 7-day grace period, 1 day used

    const past = computeEntitlement(subscription({ trialEndsAt: daysFromNow(-30) }), NOW);
    expect(past.state).toBe("limited");
    expect(past.writesAllowed).toBe(false);
    expect(past.reason).toBe("expired_past_grace");
  });

  it("treats a subscription with no dates as limited rather than granting free access", () => {
    const entitlement = computeEntitlement(
      subscription({ trialEndsAt: null, currentPeriodEnd: null, status: "expired" }),
      NOW,
    );
    expect(entitlement.state).toBe("limited");
    expect(entitlement.writesAllowed).toBe(false);
    expect(entitlement.reason).toBe("no_active_period");
  });

  it("tolerates a malformed date instead of crashing the sweep", () => {
    const entitlement = computeEntitlement(subscription({ trialEndsAt: "not-a-date" }), NOW);
    expect(entitlement.state).toBe("limited");
  });

  it("uses the grace window from policy, so plans can differ", () => {
    const entitlement = computeEntitlement(
      subscription({ trialEndsAt: daysFromNow(-10) }),
      NOW,
      { ...DEFAULT_ENTITLEMENT_POLICY, graceDays: 14 },
    );
    expect(entitlement.state).toBe("grace");
  });
});

describe("writes", () => {
  it("allows writes only in the states that represent an active relationship", () => {
    expect(writesAllowedIn("trialing")).toBe(true);
    expect(writesAllowedIn("active_paid")).toBe(true);
    expect(writesAllowedIn("active_offline")).toBe(true);
    expect(writesAllowedIn("active_bypass")).toBe(true);
    expect(writesAllowedIn("grace")).toBe(true);
    // Limited access keeps read and export, never writes.
    expect(writesAllowedIn("limited")).toBe(false);
    expect(writesAllowedIn("suspended")).toBe(false);
  });

  it("keeps read access after expiry — limited is not blocked", () => {
    // The app distinguishes "cannot write" from "cannot use": reporting and exports
    // stay available so a company can always retrieve its own data.
    const limited = computeEntitlement(subscription({ trialEndsAt: daysFromNow(-60) }), NOW);
    expect(limited.state).toBe("limited");
    expect(limited.reason).toBe("expired_past_grace");
  });
});

describe("revenue classification", () => {
  it("counts only real paid periods as revenue", () => {
    expect(isRevenueBearingEntitlement("active_paid")).toBe(true);
    expect(isRevenueBearingEntitlement("active_offline")).toBe(true);
  });

  it("never counts a trial, a grace period, a bypass or a suspension as revenue", () => {
    expect(isRevenueBearingEntitlement("trialing")).toBe(false);
    expect(isRevenueBearingEntitlement("grace")).toBe(false);
    expect(isRevenueBearingEntitlement("active_bypass")).toBe(false);
    expect(isRevenueBearingEntitlement("limited")).toBe(false);
    expect(isRevenueBearingEntitlement("suspended")).toBe(false);
  });
});

describe("bypass duration", () => {
  it("counts whole days upward so a partial day still counts as a day", () => {
    const from = new Date("2026-10-14T09:00:00.000Z");
    expect(bypassDurationDays("2026-10-15T08:00:00.000Z", from)).toBe(1);
    expect(bypassDurationDays("2026-11-13T09:00:00.000Z", from)).toBe(30);
    expect(bypassDurationDays("2026-10-14T08:00:00.000Z", from)).toBe(0);
    expect(bypassDurationDays("nonsense", from)).toBe(0);
  });

  it("defaults to a 90-day ceiling for unelevated administrators and a 30-day period", () => {
    expect(DEFAULT_ENTITLEMENT_POLICY.bypassMaxDays).toBe(90);
    expect(DEFAULT_ENTITLEMENT_POLICY.standardPeriodDays).toBe(30);
    expect(DEFAULT_ENTITLEMENT_POLICY.trialDays).toBe(30);
  });
});

describe("persisted fields", () => {
  it("writes a denormalised view for rules, lists and the dashboard", () => {
    const entitlement = computeEntitlement(subscription(), NOW);
    expect(entitlementFields(entitlement, NOW)).toEqual({
      entitlement: "trialing",
      entitlementEffectiveUntil: daysFromNow(20),
      entitlementReason: "within_trial",
      entitlementComputedAt: NOW.toISOString(),
    });
  });

  it("reports no expiry for an open-ended state", () => {
    const entitlement = computeEntitlement(subscription({ trialEndsAt: daysFromNow(-60) }), NOW);
    expect(daysUntilExpiry(entitlement, NOW)).toBeNull();
  });
});
