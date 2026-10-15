/**
 * Pricing engine tests (spec §8 verification list).
 *
 * Every case below is expressed in kobo — integer minor units — and asserts the
 * exact expected number, because a pricing regression is a money bug. The engine is
 * the single source of truth for prices on both platforms (ADR-005), so this file is
 * the specification of record for how a price is produced.
 */

import { describe, expect, it } from "vitest";
import {
  calculateQuote,
  deliveryContributionMinor,
  estimateRiderCost,
  requiredChargeForTarget,
  REVIEW_REQUIRED_WARNING_CODES,
} from "./engine";
import { PRICING_ENGINE_VERSION, type PricingConfig, type QuoteInput } from "./types";

const NOW = new Date("2026-10-14T09:00:00.000Z");

function config(overrides: Partial<PricingConfig> = {}): PricingConfig {
  return {
    id: "config-1",
    tenantId: "mx-logistics",
    name: "Standard pricing",
    isActive: true,
    version: 3,
    currency: "NGN",
    method: "base_plus_distance",
    baseChargeMinor: 70_000, // ₦700
    perKmRateMinor: 15_000, // ₦150 / km
    distanceBands: [],
    zoneRules: [],
    fallbackToDistanceWhenZoneMissing: false,
    minimumChargeMinor: 80_000, // ₦800
    maximumChargeMinor: null,
    roundingIncrementMinor: 0,
    roundingMode: "nearest",
    surcharges: [],
    riderCost: { method: "fixed", defaultFeeMinor: 50_000 }, // ₦500
    targetProfitAmountMinor: null,
    targetProfitMarginBps: null,
    ...overrides,
  };
}

function input(overrides: Partial<QuoteInput> = {}): QuoteInput {
  return {
    pickup: { address: "12 Allen Avenue, Ikeja", zoneId: "ikeja", latitude: 6.6, longitude: 3.35 },
    destination: { address: "5 Herbert Macaulay Way, Yaba", zoneId: "yaba", latitude: 6.5, longitude: 3.37 },
    distance: {
      metres: 8_400,
      source: "provider",
      provider: "google_routes",
      summary: "8.4 km via Ikorodu Road",
      durationSeconds: 1_500,
      calculatedAt: NOW.toISOString(),
    },
    serviceLevel: "standard",
    additionalCosts: [],
    ...overrides,
  };
}

function quote(configOverrides: Partial<PricingConfig> = {}, inputOverrides: Partial<QuoteInput> = {}) {
  const result = calculateQuote(config(configOverrides), input(inputOverrides), { now: NOW });
  if (result.status !== "ok" || !result.quote) throw new Error(`expected an ok quote, got ${result.status}`);
  return result;
}

function warningsOf(result: ReturnType<typeof calculateQuote>): string[] {
  return result.warnings.map((warning) => warning.code);
}

describe("method A — base charge plus distance", () => {
  it("charges base + per-km and reports every line", () => {
    const { quote: q } = quote();
    expect(q.lines.map((line) => line.code)).toEqual(["base_charge", "distance_charge"]);
    expect(q.lines[1]?.amountMinor).toBe(126_000); // 8.4 km × ₦150
    expect(q.recommendedCustomerChargeMinor).toBe(196_000); // ₦1,960
    expect(q.computedCustomerChargeMinor).toBe(196_000);
    expect(q.estimatedRiderCost.amountMinor).toBe(50_000);
    expect(q.estimatedTotalDeliveryCostMinor).toBe(50_000);
    expect(q.estimatedProfitMinor).toBe(146_000);
    expect(q.estimatedMarginBps).toBe(7_449);
    expect(q.estimatedMarginIsDefined).toBe(true);
  });

  it("scales the distance charge to whole kobo at sub-kilometre distances", () => {
    const { quote: q } = quote({}, { distance: { ...input().distance!, metres: 1_250 } });
    // 1.25 km × ₦150 = ₦187.50 => 18,750 kobo exactly
    expect(q.lines[1]?.amountMinor).toBe(18_750);
    // base 70,000 + 18,750 = 88,750 → above the ₦800 minimum
    expect(q.recommendedCustomerChargeMinor).toBe(88_750);
  });

  it("never produces a fractional kobo for awkward distances", () => {
    for (const metres of [1, 3, 7, 99, 333, 1_001, 12_345, 87_654]) {
      const { quote: q } = quote({ baseChargeMinor: 0, minimumChargeMinor: 0 }, { distance: { ...input().distance!, metres } });
      expect(Number.isInteger(q.recommendedCustomerChargeMinor)).toBe(true);
      expect(Number.isInteger(q.estimatedProfitMinor)).toBe(true);
    }
  });

  it("refuses to run with an inactive configuration", () => {
    expect(() => calculateQuote(config({ isActive: false }), input(), { now: NOW })).toThrow("pricing_config_inactive");
  });
});

describe("method B — distance bands", () => {
  const bands = [
    { upToMetres: 3_000, chargeMinor: 100_000, label: "Within 3 km" },
    { upToMetres: 7_000, chargeMinor: 160_000, label: "3–7 km" },
    { upToMetres: 15_000, chargeMinor: 240_000, label: "7–15 km" },
    { upToMetres: null, chargeMinor: 300_000, label: "Beyond 15 km" },
  ];

  it("picks the band that contains the distance, boundaries included", () => {
    const charge = (metres: number) =>
      quote({ method: "distance_bands", distanceBands: bands, minimumChargeMinor: 0 }, { distance: { ...input().distance!, metres } }).quote
        .recommendedCustomerChargeMinor;
    expect(charge(2_999)).toBe(100_000);
    expect(charge(3_000)).toBe(100_000); // inclusive upper bound
    expect(charge(3_001)).toBe(160_000);
    expect(charge(7_000)).toBe(160_000);
    expect(charge(20_000)).toBe(300_000); // catch-all
  });

  it("asks for input when a distance band configuration has no matching band", () => {
    const noCatchAll = bands.filter((band) => band.upToMetres !== null);
    const result = calculateQuote(
      config({ method: "distance_bands", distanceBands: noCatchAll, minimumChargeMinor: 0 }),
      input({ distance: { ...input().distance!, metres: 20_000 } }),
      { now: NOW },
    );
    expect(result.status).toBe("needs_input");
    expect(warningsOf(result)).toContain("no_matching_band");
  });
});

describe("method C — zone to zone", () => {
  const zoneRules = [
    { fromZoneId: "ikeja", toZoneId: "yaba", chargeMinor: 200_000 },
    { fromZoneId: "ikeja", toZoneId: "lekki", chargeMinor: 450_000 },
  ];

  it("charges the configured zone price and ignores distance / per-km rates", () => {
    const { quote: q } = quote({ method: "zone_based", zoneRules });
    expect(q.lines).toHaveLength(1);
    expect(q.lines[0]?.amountMinor).toBe(200_000);
    expect(q.recommendedCustomerChargeMinor).toBe(200_000);
  });

  it("uses the distance-based fallback when the zone pair is unconfigured and fallback is on", () => {
    const { quote: q } = quote(
      { method: "zone_based", zoneRules, fallbackToDistanceWhenZoneMissing: true },
      { destination: { ...input().destination, zoneId: "ajah" } },
    );
    expect(q.lines.map((line) => line.code)).toEqual(["base_charge", "distance_charge"]);
    expect(q.recommendedCustomerChargeMinor).toBe(196_000);
    expect(q.warnings.map((warning) => warning.code)).toContain("zone_rule_missing_fallback");
  });

  it("asks the dispatcher for a price when the zone pair is missing and fallback is off", () => {
    const result = calculateQuote(
      config({ method: "zone_based", zoneRules, fallbackToDistanceWhenZoneMissing: false }),
      input({ destination: { ...input().destination, zoneId: "ajah" } }),
      { now: NOW },
    );
    expect(result.status).toBe("needs_input");
    expect(warningsOf(result)).toContain("zone_rule_missing");
    // The blocker is the missing zone price, not the distance — the dispatcher is
    // told exactly what to fix.
    expect(warningsOf(result)).not.toContain("distance_required");
  });

  it("also asks for a price when no zones were supplied at all", () => {
    const result = calculateQuote(
      config({ method: "zone_based", zoneRules }),
      input({ pickup: { address: "12 Allen Avenue" }, destination: { address: "5 Herbert Macaulay Way" } }),
      { now: NOW },
    );
    expect(result.status).toBe("needs_input");
    expect(warningsOf(result)).toContain("zone_rule_missing");
  });
});

describe("method D — hybrid", () => {
  it("adds the zone adjustment on top of the distance calculation", () => {
    const { quote: q } = quote({
      method: "hybrid",
      zoneRules: [
        { fromZoneId: "ikeja", toZoneId: "yaba", chargeMinor: 0, adjustmentMinor: 20_000 },
      ],
    });
    expect(q.lines.map((line) => line.code)).toEqual(["base_charge", "distance_charge", "zone_adjustment"]);
    expect(q.lines[2]?.amountMinor).toBe(20_000);
    expect(q.recommendedCustomerChargeMinor).toBe(216_000);
  });

  it("notes, without blocking, when no zone adjustment applies", () => {
    const { quote: q } = quote({ method: "hybrid", zoneRules: [] });
    expect(q.recommendedCustomerChargeMinor).toBe(196_000);
    expect(q.warnings.map((warning) => warning.code)).toContain("zone_adjustment_missing");
    expect(q.requiresReviewBeforeAccepting).toBe(false);
  });
});

describe("surcharges", () => {
  it("applies a flat surcharge and a percentage surcharge on the same subtotal", () => {
    const { quote: q } = quote({
      surcharges: [
        { id: "fuel", label: "Fuel surcharge", kind: "percentage", percentageBps: 500 },
        { id: "night", label: "Night dispatch", kind: "flat", amountMinor: 10_000 },
      ],
    });
    // subtotal 196,000 → 5% = 9,800
    expect(q.lines.map((line) => line.code)).toEqual([
      "base_charge",
      "distance_charge",
      "surcharge.fuel",
      "surcharge.night",
    ]);
    expect(q.lines[2]?.amountMinor).toBe(9_800);
    expect(q.lines[3]?.amountMinor).toBe(10_000);
    expect(q.recommendedCustomerChargeMinor).toBe(215_800);
  });

  it("only applies a service-level restricted surcharge to that service level", () => {
    const surcharges = [
      { id: "express", label: "Express rider premium", kind: "flat" as const, amountMinor: 30_000, appliesToServiceLevels: ["express" as const] },
    ];
    expect(quote({ surcharges }, {}).quote.recommendedCustomerChargeMinor).toBe(196_000);
    const express = quote({ surcharges }, { serviceLevel: "express" });
    expect(express.quote.recommendedCustomerChargeMinor).toBe(226_000);
    expect(express.quote.lines.at(-1)?.amountMinor).toBe(30_000);
  });

  it("ignores a surcharge configured with a zero value rather than adding a phantom line", () => {
    const { quote: q } = quote({
      surcharges: [{ id: "zero", label: "Nothing", kind: "flat", amountMinor: 0 }],
    });
    expect(q.lines.map((line) => line.code)).toEqual(["base_charge", "distance_charge"]);
  });
});

describe("minimum and maximum charge", () => {
  it("raises a below-minimum price up to the minimum and says so", () => {
    const { quote: q } = quote(
      { baseChargeMinor: 20_000, perKmRateMinor: 0, minimumChargeMinor: 80_000 },
      { distance: { ...input().distance!, metres: 1_000 } },
    );
    expect(q.computedCustomerChargeMinor).toBe(20_000);
    expect(q.recommendedCustomerChargeMinor).toBe(80_000);
    expect(q.appliedMinimumCharge).toBe(true);
    expect(q.warnings.map((warning) => warning.code)).toContain("minimum_charge_applied");
    // The profit is recomputed from the final charge, not the pre-minimum one.
    expect(q.estimatedProfitMinor).toBe(30_000);
  });

  it("waives the minimum for an explicitly authorised override and warns", () => {
    const { quote: q } = quote(
      { minimumChargeMinor: 80_000 },
      {
        customerPriceOverride: { amountMinor: 60_000, reason: "Loyal customer, agreed on the phone", allowBelowMinimum: true },
      },
    );
    expect(q.recommendedCustomerChargeMinor).toBe(60_000);
    expect(q.appliedMinimumCharge).toBe(false);
    expect(q.manualPriceOverrideApplied).toBe(true);
    expect(q.warnings.map((warning) => warning.code)).toContain("minimum_charge_waived");
  });

  it("caps the price at the configured maximum", () => {
    const { quote: q } = quote({ maximumChargeMinor: 150_000 });
    expect(q.recommendedCustomerChargeMinor).toBe(150_000);
    expect(q.appliedMaximumCharge).toBe(true);
    expect(q.warnings.map((warning) => warning.code)).toContain("maximum_charge_applied");
    expect(q.estimatedProfitMinor).toBe(100_000);
  });
});

describe("rounding — applied exactly once, at the end", () => {
  it("rounds to the nearest increment, half up", () => {
    const { quote: q } = quote(
      { baseChargeMinor: 96_300, perKmRateMinor: 0, minimumChargeMinor: 0, roundingIncrementMinor: 5_000 },
      { distance: { ...input().distance!, metres: 1_000 } },
    );
    expect(q.linesTotalBeforeRounding).toBe(96_300);
    expect(q.rounding).toEqual({ incrementMinor: 5_000, beforeMinor: 96_300, afterMinor: 95_000 });
    expect(q.recommendedCustomerChargeMinor).toBe(95_000);
    expect(q.warnings.map((warning) => warning.code)).toContain("rounded");
  });

  it("rounds up and down on request", () => {
    const price = (roundingMode: "nearest" | "up" | "down") =>
      quote(
        { baseChargeMinor: 96_300, perKmRateMinor: 0, minimumChargeMinor: 0, roundingIncrementMinor: 5_000, roundingMode },
        { distance: { ...input().distance!, metres: 1_000 } },
      ).quote.recommendedCustomerChargeMinor;
    expect(price("up")).toBe(100_000);
    expect(price("down")).toBe(95_000);
    expect(price("nearest")).toBe(95_000);
  });

  it("leaves the price untouched when no increment is configured", () => {
    const { quote: q } = quote({ baseChargeMinor: 96_345, perKmRateMinor: 0, minimumChargeMinor: 0, roundingIncrementMinor: 0 });
    expect(q.rounding).toBeNull();
    expect(q.recommendedCustomerChargeMinor).toBe(96_345);
  });

  it("rounds after the minimum is applied, so the minimum itself is not rounded up twice", () => {
    const { quote: q } = quote(
      { baseChargeMinor: 10_000, perKmRateMinor: 0, minimumChargeMinor: 80_400, roundingIncrementMinor: 5_000 },
      { distance: { ...input().distance!, metres: 1_000 } },
    );
    expect(q.appliedMinimumCharge).toBe(true);
    expect(q.rounding?.beforeMinor).toBe(80_400);
    expect(q.recommendedCustomerChargeMinor).toBe(80_000); // 80,400 nearest 5,000 → 80,000
  });
});

describe("target profit", () => {
  it("back-solves an amount target and takes the greater of the two prices", () => {
    // cost = rider 50,000 + delivery costs 10,000 = 60,000; target 20,000 → 80,000
    const { quote: q } = quote(
      { targetProfitAmountMinor: 20_000 },
      { additionalCosts: [{ id: "waiting", label: "Waiting time", amountMinor: 10_000, kind: "delivery" }] },
    );
    // the distance price (196,000) already exceeds the back-solve, so it wins
    expect(q.recommendedCustomerChargeMinor).toBe(196_000);
    expect(q.targetProfit).toEqual({ kind: "amount", value: 20_000, satisfied: true, shortfallMinor: 0 });

    // force the back-solve to win
    const cheap = quote(
      { baseChargeMinor: 5_000, perKmRateMinor: 0, minimumChargeMinor: 0, targetProfitAmountMinor: 200_000 },
      { distance: { ...input().distance!, metres: 1_000 } },
    );
    expect(cheap.quote.recommendedCustomerChargeMinor).toBe(250_000); // 50,000 cost + 200,000
    expect(cheap.quote.estimatedProfitMinor).toBe(200_000);
    expect(cheap.quote.targetProfit.satisfied).toBe(true);
  });

  it("back-solves a margin target by dividing by (1 − margin), rounding up", () => {
    // cost 60,000 at a 25% margin needs 60,000 / 0.75 = 80,000 exactly
    const exact = quote(
      { baseChargeMinor: 30_000, perKmRateMinor: 0, minimumChargeMinor: 0, targetProfitMarginBps: 2_500 },
      {
        distance: { ...input().distance!, metres: 1_000 },
        additionalCosts: [{ id: "waiting", label: "Waiting time", amountMinor: 10_000, kind: "delivery" }],
      },
    );
    expect(exact.quote.recommendedCustomerChargeMinor).toBe(80_000);
    expect(exact.quote.estimatedProfitMinor).toBe(20_000);
    expect(exact.quote.estimatedMarginBps).toBe(2_500);
    expect(exact.quote.targetProfit.satisfied).toBe(true);
  });

  it("rounds the required charge up so the requested margin is never shaved", () => {
    const { requiredMinor } = requiredChargeForTarget(60_001, null, 2_500);
    expect(requiredMinor).toBe(80_002); // ceil(60,001 / 0.75)
    const { quote: q } = quote(
      { baseChargeMinor: 1, perKmRateMinor: 0, minimumChargeMinor: 0, targetProfitMarginBps: 2_500, riderCost: { method: "fixed", defaultFeeMinor: 60_001 } },
      { distance: { ...input().distance!, metres: 1_000 } },
    );
    expect(q.estimatedProfitMinor * 10_000 >= 2_500 * q.recommendedCustomerChargeMinor).toBe(true);
    expect(q.targetProfit.satisfied).toBe(true);
  });

  it("warns when the final price misses the target but is still profitable", () => {
    const { quote: q } = quote(
      { targetProfitMarginBps: 9_000 }, // a 90% margin was asked for
      { customerPriceOverride: { amountMinor: 60_000, reason: "Customer haggled on the phone", allowBelowMinimum: true } },
    );
    // The override pins the price: 60,000 − 50,000 rider = 10,000 profit, far short of 54,000.
    expect(q.estimatedProfitMinor).toBe(10_000);
    expect(q.targetProfit.satisfied).toBe(false);
    expect(q.targetProfit.shortfallMinor).toBe(44_000);
    expect(q.warnings.map((warning) => warning.code)).toContain("below_target_profit");
    // Still profitable, so it is a warning to weigh up — not a money-losing block.
    expect(q.warnings.some((warning) => warning.code === "price_below_cost")).toBe(false);
    expect(q.requiresReviewBeforeAccepting).toBe(false);
  });

  it("treats a 100% or greater margin as unusable and asks for input", () => {
    const result = calculateQuote(config(), input({ targetProfitOverride: { marginBps: 10_000 } }), { now: NOW });
    expect(result.status).toBe("needs_input");
    expect(warningsOf(result)).toContain("invalid_target_margin");
    expect(requiredChargeForTarget(50_000, null, 10_000).problem).toBe("invalid_target_margin");
  });
});

describe("missed inputs", () => {
  it("asks for a distance when none is known", () => {
    const result = calculateQuote(config(), input({ distance: null }), { now: NOW });
    expect(result.status).toBe("needs_input");
    expect(warningsOf(result)).toContain("distance_required");
    expect(result.quote).toBeUndefined();
  });

  it("rejects a zero or negative distance", () => {
    for (const metres of [0, -1, Number.NaN]) {
      const result = calculateQuote(config(), input({ distance: { ...input().distance!, metres } }), { now: NOW });
      expect(result.status).toBe("needs_input");
      expect(warningsOf(result)).toContain("distance_required");
    }
  });

  it("flags a manually entered distance as needing acknowledgement", () => {
    const { quote: q } = quote({}, { distance: { ...input().distance!, metres: 5_000, source: "manual", provider: "manual", summary: undefined } });
    const manual = q.warnings.find((warning) => warning.code === "manual_distance");
    expect(manual?.severity).toBe("info");
    expect(q.requiresReviewBeforeAccepting).toBe(true);
    expect(REVIEW_REQUIRED_WARNING_CODES).toContain("manual_distance");
  });
});

describe("rider cost estimation", () => {
  it("accepts a confirmed manual override and labels it", () => {
    const estimate = estimateRiderCost(config(), 8_400, "ikeja", "yaba", {
      amountMinor: 80_000,
      reason: "Rider quoted ₦800 for the trip",
    });
    expect(estimate).toEqual({
      amountMinor: 80_000,
      method: "manual",
      confidence: "confirmed",
      note: "Rider quoted ₦800 for the trip",
    });
  });

  it("uses the company default fee without flagging it", () => {
    const estimate = estimateRiderCost(config(), 8_400, null, null);
    expect(estimate.amountMinor).toBe(50_000);
    expect(estimate.confidence).toBe("estimated");
  });

  it("prices a rider from distance bands", () => {
    const { quote: q } = quote(
      riderCostOverrideOf(
        config({
          riderCost: {
            method: "distance_bands",
            bands: [
              { upToMetres: 5_000, chargeMinor: 30_000, label: "Short trip" },
              { upToMetres: null, chargeMinor: 70_000, label: "Long trip" },
            ],
          },
        }),
      ),
    );
    expect(q.estimatedRiderCost.method).toBe("distance_bands");
    expect(q.estimatedRiderCost.amountMinor).toBe(70_000);
    expect(q.estimatedProfitMinor).toBe(196_000 - 70_000);
  });

  it("prices a rider per kilometre", () => {
    const { quote: q } = quote(
      riderCostOverrideOf(config({ riderCost: { method: "distance_rate", perKmRateMinor: 8_000 } })),
    );
    // 8.4 km × ₦80 = ₦672
    expect(q.estimatedRiderCost.amountMinor).toBe(67_200);
    expect(q.estimatedProfitMinor).toBe(128_800);
  });

  it("signals an unconfirmed cost when a historical average is all that is known", () => {
    const configWithHistory = config({
      riderCost: { method: "historical_average", historicalAverageMinor: 55_000, historicalSampleSize: 40 },
    });
    const { quote: q } = quote(riderCostOverrideOf(configWithHistory));
    expect(q.estimatedRiderCost.confidence).toBe("unconfirmed");
    expect(q.estimatedRiderCost.note).toContain("40");
    expect(q.warnings.map((warning) => warning.code)).toContain("rider_cost_unconfirmed");
    expect(q.requiresReviewBeforeAccepting).toBe(true);
  });

  it("never lets an unconfigured rider cost silently become zero profit", () => {
    const unconfigured = config({ riderCost: { method: "zone_based", zoneRules: [] } });
    const { quote: q } = quote(riderCostOverrideOf(unconfigured));
    expect(q.estimatedRiderCost.amountMinor).toBe(0);
    expect(q.estimatedRiderCost.confidence).toBe("unconfirmed");
    expect(q.requiresReviewBeforeAccepting).toBe(true);
  });

  it("respects a configured minimum rider cost", () => {
    const floored = config({ riderCost: { method: "distance_rate", perKmRateMinor: 1_000, minimumCostMinor: 40_000 } });
    const estimate = estimateRiderCost(floored, 5_000, null, null);
    expect(estimate.amountMinor).toBe(40_000); // 5 km × ₦10 = ₦50 < ₦400 floor
  });
});

function riderCostOverrideOf(candidate: PricingConfig): Partial<PricingConfig> {
  // The engine takes rider costing from the config, so this returns the config
  // overrides needed to instantiate it in a quote call.
  return { riderCost: candidate.riderCost };
}

describe("delivery expenses", () => {
  it("counts only delivery-level additional costs against the delivery", () => {
    const { quote: q } = quote(
      {},
      {
        additionalCosts: [
          { id: "waiting", label: "Waiting time", amountMinor: 3_000, kind: "delivery" },
          { id: "airtime", label: "Airtime", amountMinor: 7_000, kind: "operating" },
        ],
      },
    );
    expect(q.estimatedAdditionalDeliveryCostsMinor).toBe(3_000);
    expect(q.estimatedTotalDeliveryCostMinor).toBe(53_000);
    expect(q.estimatedProfitMinor).toBe(143_000);
  });

  it("ignores a duplicated cost id instead of double-charging", () => {
    const { quote: q } = quote(
      {},
      {
        additionalCosts: [
          { id: "waiting", label: "Waiting time", amountMinor: 3_000, kind: "delivery" },
          { id: "waiting", label: "Waiting time", amountMinor: 3_000, kind: "delivery" },
        ],
      },
    );
    expect(q.estimatedAdditionalDeliveryCostsMinor).toBe(3_000);
    expect(q.warnings.map((warning) => warning.code)).toContain("duplicate_additional_cost");
  });
});

describe("profit, margin and loss safety", () => {
  it("recomputes profit after every price adjustment", () => {
    const { quote: q } = quote({ maximumChargeMinor: 40_000 }, {});
    expect(q.recommendedCustomerChargeMinor).toBe(40_000);
    expect(q.estimatedProfitMinor).toBe(-10_000);
    expect(q.warnings.map((warning) => warning.code)).toContain("price_below_cost");
    expect(q.requiresReviewBeforeAccepting).toBe(true);
  });

  it("distinguishes delivery contribution from net company profit", () => {
    expect(deliveryContributionMinor(196_000, 50_000, 5_000)).toBe(141_000);
    expect(deliveryContributionMinor(196_000, 0, 0)).toBe(196_000);
  });

  it("marks a free delivery as an undefinable margin instead of dividing by zero", () => {
    const { quote: q } = quote(
      { minimumChargeMinor: 0 },
      { customerPriceOverride: { amountMinor: 0, reason: "Free replacement delivery", allowBelowMinimum: true } },
    );
    expect(q.estimatedMarginIsDefined).toBe(false);
    expect(q.estimatedMarginBps).toBe(0);
    expect(q.warnings.map((warning) => warning.code)).toContain("zero_customer_charge");
  });
});

describe("pricing snapshot (auditability)", () => {
  it("captures the rules that produced the price", () => {
    const { quote: q } = quote({ targetProfitMarginBps: 2_000 });
    expect(q.pricingSnapshot).toMatchObject({
      engineVersion: PRICING_ENGINE_VERSION,
      pricingConfigId: "config-1",
      pricingConfigVersion: 3,
      pricingConfigName: "Standard pricing",
      pricingMethod: "base_plus_distance",
      currency: "NGN",
      baseChargeMinor: 70_000,
      perKmRateMinor: 15_000,
      minimumChargeMinor: 80_000,
      targetProfitMarginBps: 2_000,
      capturedAt: NOW.toISOString(),
    });
    expect(q.pricingSnapshot.riderCostConfig).toEqual({ method: "fixed", defaultFeeMinor: 50_000 });
  });

  it("records the engine version so a price can be explained years later", () => {
    const { quote: q } = quote();
    expect(q.engineVersion).toBe(PRICING_ENGINE_VERSION);
    expect(q.pricingSnapshot.engineVersion).toBe(q.engineVersion);
  });
});
