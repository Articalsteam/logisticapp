/**
 * Smart delivery pricing engine.
 *
 * This is the single authoritative implementation of delivery pricing for the
 * whole product. The mobile app never recomputes these numbers; it renders the
 * breakdown returned here (ADR-005).
 *
 * The pipeline is deliberately ordered and documented, because "apply rounding at
 * a clearly defined point" is a correctness requirement, not a style choice:
 *
 *   1. guard: config active, distance present (or ask for it)
 *   2. method subtotal            (A base+distance, B bands, C zones, D hybrid)
 *   3. surcharges                 (flat and percentage, weighted by service level)
 *   4. rider cost estimate        (independent of the customer charge)
 *   5. delivery-level costs       (never company overhead — avoids double counting)
 *   6. target-profit back-solve   (amount or margin)
 *   7. take the greater of the method result and the target back-solve
 *   8. manual override, if authorized
 *   9. minimum charge, then maximum charge
 *  10. rounding — exactly once, at the end of the price pipeline
 *  11. final profit/margin recomputation from the *final* charge, plus warnings
 *
 * All arithmetic uses integer minor units and BigInt-backed helpers; there is no
 * floating-point money anywhere.
 */

import {
  addMinor,
  marginBps as computeMarginBps,
  percentageMinor,
  roundToIncrement,
  scaleMinor,
  subMinor,
  type Minor,
} from "../shared/money";
import {
  PRICING_ENGINE_VERSION,
  type AdditionalCostInput,
  type DistanceBand,
  type PricingConfig,
  type PricingSnapshot,
  type QuoteBreakdown,
  type QuoteInput,
  type QuoteLine,
  type QuoteResult,
  type QuoteWarning,
  type RiderCostEstimate,
  type SurchargeRule,
  type TargetProfitStatus,
  type ZoneRule,
} from "./types";

export interface EngineContext {
  now: Date;
}

const BLOCKER = "blocker" as const;
const WARNING = "warning" as const;
const INFO = "info" as const;

/**
 * Warnings that must be acknowledged before an order can be created from a quote.
 * A blocker always requires acknowledgement; so does an unconfirmed rider cost
 * (spec §8: "require review before accepting the delivery").
 */
export const REVIEW_REQUIRED_WARNING_CODES: readonly string[] = [
  "distance_required",
  // A manually entered distance directly determines the price, so it must be
  // acknowledged rather than slipping through as an informational note.
  "manual_distance",
  "no_matching_band",
  "zone_rule_missing",
  "invalid_target_margin",
  "zero_customer_charge",
  "price_below_cost",
  "rider_cost_unconfirmed",
];

/** Fail fast on a config that would make pricing meaningless. */
function assertConfigUsable(config: PricingConfig): void {
  if (!config.isActive) {
    throw new Error("pricing_config_inactive");
  }
}

/**
 * Back-solves the customer charge required to reach the configured target profit.
 *
 * Target profit amount:  required = total cost + target
 * Target margin:         required = total cost / (1 − margin)
 *                        valid for 0 <= margin < 1
 */
export function requiredChargeForTarget(
  totalCostMinor: Minor,
  targetProfitAmountMinor: Minor | null,
  targetProfitMarginBps: number | null,
): { requiredMinor: Minor | null; problem?: string } {
  if (targetProfitMarginBps !== null) {
    if (!Number.isInteger(targetProfitMarginBps) || targetProfitMarginBps < 0 || targetProfitMarginBps >= 10_000) {
      return { requiredMinor: null, problem: "invalid_target_margin" };
    }
    // cost / (1 − margin) == cost * 10000 / (10000 − marginBps)
    // Rounded UP on purpose: rounding a required charge down would quietly shave
    // basis points off the margin the company asked for.
    const numerator = BigInt(totalCostMinor) * 10_000n;
    const denominator = BigInt(10_000 - targetProfitMarginBps);
    const required = Number((numerator + denominator - 1n) / denominator); // ceiling
    return { requiredMinor: required };
  }
  if (targetProfitAmountMinor !== null) {
    return { requiredMinor: addMinor(totalCostMinor, targetProfitAmountMinor) };
  }
  return { requiredMinor: null };
}

function matchBand(bands: DistanceBand[], metres: number): DistanceBand | null {
  const sorted = [...bands].sort((a, b) => (a.upToMetres ?? Number.POSITIVE_INFINITY) - (b.upToMetres ?? Number.POSITIVE_INFINITY));
  for (const band of sorted) {
    if (band.upToMetres === null) return band; // catch-all
    if (metres <= band.upToMetres) return band;
  }
  return null;
}

function matchZoneRule(rules: ZoneRule[], fromZoneId?: string | null, toZoneId?: string | null): ZoneRule | null {
  if (!fromZoneId || !toZoneId) return null;
  return rules.find((rule) => rule.fromZoneId === fromZoneId && rule.toZoneId === toZoneId) ?? null;
}

function surchargeLines(surcharges: SurchargeRule[], subtotalMinor: Minor, serviceLevel: string): QuoteLine[] {
  const lines: QuoteLine[] = [];
  for (const surcharge of surcharges) {
    const restricted = surcharge.appliesToServiceLevels;
    if (restricted && restricted.length > 0 && !(restricted as readonly string[]).includes(serviceLevel)) continue;
    if (surcharge.kind === "flat") {
      const amount = surcharge.amountMinor ?? 0;
      if (amount <= 0) continue;
      lines.push({ code: `surcharge.${surcharge.id}`, label: surcharge.label, amountMinor: amount });
    } else {
      const bps = surcharge.percentageBps ?? 0;
      if (bps <= 0) continue;
      lines.push({
        code: `surcharge.${surcharge.id}`,
        label: surcharge.label,
        amountMinor: percentageMinor(subtotalMinor, bps),
        note: `${(bps / 100).toFixed(2)}% of ${subtotalMinor} minor units`,
      });
    }
  }
  return lines;
}

/**
 * Estimates what the rider will be paid.
 *
 * Deliberately separate from the customer charge: the two are negotiated
 * independently in the Nigerian market, and the whole point of the product is to
 * show the dispatcher the gap between them.
 */
export function estimateRiderCost(
  config: PricingConfig,
  metres: number,
  fromZoneId: string | null | undefined,
  toZoneId: string | null | undefined,
  override?: QuoteInput["riderCostOverride"],
): RiderCostEstimate {
  if (override && override.amountMinor >= 0) {
    return {
      amountMinor: override.amountMinor,
      method: "manual",
      confidence: "confirmed",
      note: override.note ? `${override.reason} — ${override.note}` : override.reason,
    };
  }

  const riderConfig = config.riderCost;
  let amount: Minor | null = null;
  let note: string | undefined;

  switch (riderConfig.method) {
    case "distance_rate": {
      if (riderConfig.perKmRateMinor !== undefined) {
        amount = scaleMinor(riderConfig.perKmRateMinor, metres, 1000);
      }
      break;
    }
    case "distance_bands": {
      const band = riderConfig.bands ? matchBand(riderConfig.bands, metres) : null;
      if (band) {
        amount = band.chargeMinor;
        note = band.label;
      }
      break;
    }
    case "zone_based": {
      const rule = riderConfig.zoneRules ? matchZoneRule(riderConfig.zoneRules, fromZoneId, toZoneId) : null;
      if (rule) {
        amount = rule.chargeMinor;
      } else {
        note = "No rider cost configured for this zone pair";
      }
      break;
    }
    case "fixed": {
      if (riderConfig.defaultFeeMinor !== undefined) {
        amount = riderConfig.defaultFeeMinor;
        note = "Company default rider fee";
      }
      break;
    }
    case "historical_average": {
      if (riderConfig.historicalAverageMinor !== undefined) {
        amount = riderConfig.historicalAverageMinor;
        note = `Average of ${riderConfig.historicalSampleSize ?? 0} recorded rider fees`;
      }
      break;
    }
    case "manual":
    default:
      break;
  }

  if (amount === null && riderConfig.defaultFeeMinor !== undefined) {
    return {
      amountMinor: riderConfig.defaultFeeMinor,
      method: riderConfig.method,
      confidence: "unconfirmed",
      note: "Fell back to the company default rider fee — confirm the rider's price.",
    };
  }

  if (amount === null) {
    return {
      amountMinor: 0,
      method: riderConfig.method,
      confidence: "unconfirmed",
      note: "No rider cost could be estimated — enter the rider's price.",
    };
  }

  const floored =
    riderConfig.minimumCostMinor !== undefined && amount < riderConfig.minimumCostMinor
      ? riderConfig.minimumCostMinor
      : amount;

  // A historical average is explicitly *not* a quote (spec §8): it is flagged as
  // unconfirmed so the dispatcher must review it before accepting the delivery.
  const confidence: RiderCostEstimate["confidence"] =
    riderConfig.method === "historical_average" ? "unconfirmed" : "estimated";

  return { amountMinor: floored, method: riderConfig.method, confidence, note };
}

function summariseAdditionalCosts(costs: AdditionalCostInput[] | undefined): {
  deliveryTotal: Minor;
  duplicates: string[];
} {
  const seen = new Set<string>();
  const duplicates: string[] = [];
  let total = 0;
  for (const cost of costs ?? []) {
    if (seen.has(cost.id)) {
      duplicates.push(cost.id);
      continue; // never count the same expense twice
    }
    seen.add(cost.id);
    if (cost.kind === "delivery") {
      total = addMinor(total, cost.amountMinor);
    }
  }
  return { deliveryTotal: total, duplicates };
}

export function buildPricingSnapshot(config: PricingConfig, now: Date): PricingSnapshot {
  return {
    engineVersion: PRICING_ENGINE_VERSION,
    pricingConfigId: config.id,
    pricingConfigVersion: config.version,
    pricingConfigName: config.name,
    pricingMethod: config.method,
    currency: config.currency,
    baseChargeMinor: config.baseChargeMinor,
    perKmRateMinor: config.perKmRateMinor,
    distanceBands: config.distanceBands,
    zoneRules: config.zoneRules,
    minimumChargeMinor: config.minimumChargeMinor,
    maximumChargeMinor: config.maximumChargeMinor,
    roundingIncrementMinor: config.roundingIncrementMinor,
    roundingMode: config.roundingMode,
    surcharges: config.surcharges,
    riderCostConfig: config.riderCost,
    targetProfitAmountMinor: config.targetProfitAmountMinor,
    targetProfitMarginBps: config.targetProfitMarginBps,
    capturedAt: now.toISOString(),
  };
}

export function calculateQuote(config: PricingConfig, input: QuoteInput, context: EngineContext): QuoteResult {
  assertConfigUsable(config);
  const warnings: QuoteWarning[] = [];

  // ---- 1. distance guard ---------------------------------------------------
  const distance = input.distance;
  if (!distance || !Number.isFinite(distance.metres) || distance.metres <= 0) {
    warnings.push({
      code: "distance_required",
      severity: BLOCKER,
      message:
        "A road distance is required. Enter pickup and destination so the route can be calculated, or record a manually measured distance.",
    });
    return { status: "needs_input", warnings };
  }
  if (distance.source === "manual") {
    warnings.push({
      code: "manual_distance",
      severity: INFO,
      message: "This quote uses a manually entered distance instead of a calculated road distance.",
    });
  }

  const metres = Math.round(distance.metres);
  const fromZoneId = input.pickup.zoneId ?? null;
  const toZoneId = input.destination.zoneId ?? null;

  // ---- 2. method subtotal --------------------------------------------------
  const lines: QuoteLine[] = [];
  const distanceKm = metres / 1000;

  switch (config.method) {
    case "base_plus_distance": {
      lines.push({ code: "base_charge", label: "Base charge", amountMinor: config.baseChargeMinor });
      lines.push({
        code: "distance_charge",
        label: "Distance charge",
        amountMinor: scaleMinor(config.perKmRateMinor, metres, 1000),
        note: `${distanceKm.toFixed(1)} km × rate`,
      });
      break;
    }
    case "distance_bands": {
      const band = matchBand(config.distanceBands, metres);
      if (!band) {
        warnings.push({
          code: "no_matching_band",
          severity: BLOCKER,
          message: "No distance band covers this distance. Add a catch-all band in pricing settings.",
        });
        return { status: "needs_input", warnings };
      }
      lines.push({
        code: "distance_band",
        label: band.label ?? "Distance band",
        amountMinor: band.chargeMinor,
        note: `${distanceKm.toFixed(1)} km`,
      });
      break;
    }
    case "zone_based": {
      const rule = matchZoneRule(config.zoneRules, fromZoneId, toZoneId);
      if (rule) {
        lines.push({
          code: "zone_charge",
          label: "Zone price",
          amountMinor: rule.chargeMinor,
          note: `${fromZoneId} → ${toZoneId}`,
        });
      } else if (config.fallbackToDistanceWhenZoneMissing) {
        warnings.push({
          code: "zone_rule_missing_fallback",
          severity: INFO,
          message: "No zone price is configured for this pair, so the distance-based fallback was used.",
        });
        lines.push({ code: "base_charge", label: "Base charge (fallback)", amountMinor: config.baseChargeMinor });
        lines.push({
          code: "distance_charge",
          label: "Distance charge (fallback)",
          amountMinor: scaleMinor(config.perKmRateMinor, metres, 1000),
          note: `${distanceKm.toFixed(1)} km × rate`,
        });
      } else {
        warnings.push({
          code: "zone_rule_missing",
          severity: BLOCKER,
          message: "No zone price is configured for this pickup/destination pair. Add one or set a price manually.",
        });
        return { status: "needs_input", warnings };
      }
      break;
    }
    case "hybrid":
    default: {
      lines.push({ code: "base_charge", label: "Base charge", amountMinor: config.baseChargeMinor });
      lines.push({
        code: "distance_charge",
        label: "Distance charge",
        amountMinor: scaleMinor(config.perKmRateMinor, metres, 1000),
        note: `${distanceKm.toFixed(1)} km × rate`,
      });
      const zoneRule = matchZoneRule(config.zoneRules, fromZoneId, toZoneId);
      const adjustment = zoneRule?.adjustmentMinor ?? zoneRule?.chargeMinor ?? 0;
      if (adjustment !== 0) {
        lines.push({
          code: "zone_adjustment",
          label: "Zone adjustment",
          amountMinor: adjustment,
          note: `${fromZoneId} → ${toZoneId}`,
        });
      } else if (!zoneRule) {
        warnings.push({
          code: "zone_adjustment_missing",
          severity: INFO,
          message: "No zone adjustment applies to this pair; the distance-based price was used.",
        });
      }
      break;
    }
  }

  // ---- 3. surcharges -------------------------------------------------------
  const subtotalBeforeSurcharges = lines.reduce((total, line) => addMinor(total, line.amountMinor), 0 as Minor);
  lines.push(...surchargeLines(config.surcharges, subtotalBeforeSurcharges, input.serviceLevel));
  const computedCustomerCharge = lines.reduce((total, line) => addMinor(total, line.amountMinor), 0 as Minor);

  // ---- 4. rider cost -------------------------------------------------------
  const riderCost = estimateRiderCost(config, metres, fromZoneId, toZoneId, input.riderCostOverride);
  if (riderCost.confidence === "unconfirmed") {
    warnings.push({
      code: "rider_cost_unconfirmed",
      severity: WARNING,
      message: riderCost.note ?? "The rider cost is an estimate and must be confirmed before accepting this delivery.",
    });
  }

  // ---- 5. delivery-level costs --------------------------------------------
  const { deliveryTotal: additionalDeliveryCosts, duplicates } = summariseAdditionalCosts(input.additionalCosts);
  if (duplicates.length > 0) {
    warnings.push({
      code: "duplicate_additional_cost",
      severity: WARNING,
      message: `Duplicated cost entries were ignored: ${duplicates.join(", ")}.`,
    });
  }

  const estimatedTotalCost = addMinor(riderCost.amountMinor, additionalDeliveryCosts);

  // ---- 6. target profit back-solve ---------------------------------------
  const targetAmount = input.targetProfitOverride?.amountMinor ?? config.targetProfitAmountMinor;
  const targetMargin = input.targetProfitOverride?.marginBps ?? config.targetProfitMarginBps;
  const { requiredMinor, problem } = requiredChargeForTarget(estimatedTotalCost, targetAmount ?? null, targetMargin ?? null);
  if (problem) {
    warnings.push({
      code: problem,
      severity: BLOCKER,
      message: "The target profit margin must be at least 0% and below 100%.",
    });
    return { status: "needs_input", warnings };
  }

  // ---- 7–8. recommended charge, manual override --------------------------
  let recommended = requiredMinor !== null ? Math.max(computedCustomerCharge, requiredMinor) : computedCustomerCharge;
  let manualOverrideApplied = false;

  const override = input.customerPriceOverride;
  if (override) {
    manualOverrideApplied = true;
    recommended = override.amountMinor;
    warnings.push({
      code: "manual_price_override",
      severity: INFO,
      message: `Price set manually to ${override.amountMinor} minor units: ${override.reason}`,
    });
  }

  // ---- 9. minimum and maximum -------------------------------------------
  let appliedMinimum = false;
  let appliedMaximum = false;
  const belowMinimum = recommended < config.minimumChargeMinor;
  const minimumWaived = manualOverrideApplied && override?.allowBelowMinimum === true;
  if (belowMinimum && !minimumWaived) {
    recommended = config.minimumChargeMinor;
    appliedMinimum = true;
    warnings.push({
      code: "minimum_charge_applied",
      severity: INFO,
      message: `The configured minimum delivery charge was applied, increasing the price.`,
    });
  } else if (belowMinimum && minimumWaived) {
    warnings.push({
      code: "minimum_charge_waived",
      severity: WARNING,
      message: "This price is below the company's minimum charge because an override was explicitly authorized.",
    });
  }
  if (config.maximumChargeMinor !== null && recommended > config.maximumChargeMinor) {
    recommended = config.maximumChargeMinor;
    appliedMaximum = true;
    warnings.push({
      code: "maximum_charge_applied",
      severity: WARNING,
      message: "The price was capped at the company's maximum configured charge.",
    });
  }

  // ---- 10. rounding (single point) --------------------------------------
  const beforeRounding = recommended;
  const rounded = roundToIncrement(recommended, config.roundingIncrementMinor, config.roundingMode);
  recommended = rounded;
  const rounding =
    config.roundingIncrementMinor > 1
      ? { incrementMinor: config.roundingIncrementMinor, beforeMinor: beforeRounding, afterMinor: rounded }
      : null;
  if (rounding && rounded !== beforeRounding) {
    warnings.push({
      code: "rounded",
      severity: INFO,
      message: `The price was rounded to the nearest ${config.roundingIncrementMinor / 100} ${config.currency}.`,
    });
  }

  // ---- 11. final profit and warnings ------------------------------------
  const profit = subMinor(recommended, estimatedTotalCost);
  const marginIsDefined = recommended > 0;
  const margin = computeMarginBps(profit, recommended);

  if (!marginIsDefined) {
    warnings.push({
      code: "zero_customer_charge",
      severity: BLOCKER,
      message: "The customer charge is zero, so a profit margin cannot be calculated.",
    });
  }
  if (profit < 0) {
    warnings.push({
      code: "price_below_cost",
      severity: BLOCKER,
      message: "This price is below the estimated rider cost plus delivery expenses. Accepting it loses money.",
    });
  }

  let targetProfit: TargetProfitStatus = { kind: "none", value: 0, satisfied: true, shortfallMinor: 0 };
  if (targetMargin !== null && targetMargin !== undefined) {
    const requiredProfit = percentageMinor(recommended, targetMargin);
    const satisfied = profit >= requiredProfit;
    targetProfit = {
      kind: "margin",
      value: targetMargin,
      satisfied,
      shortfallMinor: satisfied ? 0 : subMinor(requiredProfit, profit),
    };
  } else if (targetAmount !== null && targetAmount !== undefined) {
    const satisfied = profit >= targetAmount;
    targetProfit = {
      kind: "amount",
      value: targetAmount,
      satisfied,
      shortfallMinor: satisfied ? 0 : subMinor(targetAmount, profit),
    };
  }
  if (targetProfit.kind !== "none" && !targetProfit.satisfied) {
    warnings.push({
      code: "below_target_profit",
      severity: WARNING,
      message: "This delivery does not reach the company's target profit. Consider adjusting the price or the rider cost.",
    });
  }

  const breakdown: QuoteBreakdown = {
    currency: config.currency,
    method: config.method,
    engineVersion: PRICING_ENGINE_VERSION,
    lines,
    distance: { ...distance, metres },
    distanceKm,
    computedCustomerChargeMinor: computedCustomerCharge,
    recommendedCustomerChargeMinor: recommended,
    linesTotalBeforeRounding: computedCustomerCharge,
    appliedMinimumCharge: appliedMinimum,
    appliedMaximumCharge: appliedMaximum,
    rounding,
    manualPriceOverrideApplied: manualOverrideApplied,
    estimatedRiderCost: riderCost,
    estimatedAdditionalDeliveryCostsMinor: additionalDeliveryCosts,
    estimatedTotalDeliveryCostMinor: estimatedTotalCost,
    estimatedProfitMinor: profit,
    estimatedMarginBps: margin,
    estimatedMarginIsDefined: marginIsDefined,
    targetProfit,
    warnings,
    requiresReviewBeforeAccepting: warnings.some(
      (warning) => warning.severity === BLOCKER || REVIEW_REQUIRED_WARNING_CODES.includes(warning.code),
    ),
    pricingSnapshot: buildPricingSnapshot(config, context.now),
    computedAt: context.now.toISOString(),
  };

  return { status: "ok", quote: breakdown, warnings };
}

/** Convenience used by the dashboard and by reports: profit of one delivery. */
export function deliveryContributionMinor(
  customerChargeMinor: Minor,
  riderCostMinor: Minor,
  additionalDeliveryCostsMinor: Minor,
): Minor {
  return subMinor(customerChargeMinor, addMinor(riderCostMinor, additionalDeliveryCostsMinor));
}
