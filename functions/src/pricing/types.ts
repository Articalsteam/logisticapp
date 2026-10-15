/**
 * Pricing engine contracts.
 *
 * The Kotlin app never re-implements these formulas: it sends inputs to
 * `calculateDeliveryQuote` and renders the returned breakdown (see ADR-005).
 * These types are mirrored by hand-written DTOs in Kotlin — the contract tests in
 * functions/src/pricing/contract.test.ts lock the wire shape down.
 */

import type { Minor } from "../shared/money";

export const PRICING_ENGINE_VERSION = "pricing-engine-1.0.0";

export const PRICING_METHODS = ["base_plus_distance", "distance_bands", "zone_based", "hybrid"] as const;
export type PricingMethod = (typeof PRICING_METHODS)[number];

export const RIDER_COST_METHODS = [
  "distance_rate",
  "distance_bands",
  "zone_based",
  "fixed",
  "historical_average",
  "manual",
] as const;
export type RiderCostMethod = (typeof RIDER_COST_METHODS)[number];

export const SERVICE_LEVELS = ["standard", "express", "scheduled"] as const;
export type ServiceLevel = (typeof SERVICE_LEVELS)[number];

export const ROUNDING_MODES = ["nearest", "up", "down"] as const;
export type PricingRoundingMode = (typeof ROUNDING_MODES)[number];

/** A distance band. `upToMetres: null` marks the final catch-all band. */
export interface DistanceBand {
  /** Inclusive upper bound of the band in whole metres; null = "and above". */
  upToMetres: number | null;
  chargeMinor: Minor;
  label?: string;
}

/** A price for a specific pickup-zone → destination-zone pair. */
export interface ZoneRule {
  fromZoneId: string;
  toZoneId: string;
  chargeMinor: Minor;
  /** Optional adjustment added on top of a hybrid distance calculation. */
  adjustmentMinor?: Minor;
}

export interface SurchargeRule {
  id: string;
  label: string;
  kind: "flat" | "percentage";
  /** Required when kind === "flat". */
  amountMinor?: Minor;
  /** Required when kind === "percentage"; 10000 bps = 100%. */
  percentageBps?: number;
  /** Restricts the surcharge to certain service levels; empty/absent = all. */
  appliesToServiceLevels?: readonly ServiceLevel[];
}

export interface RiderCostConfig {
  method: RiderCostMethod;
  perKmRateMinor?: Minor;
  bands?: DistanceBand[];
  zoneRules?: ZoneRule[];
  /** Flat fallback used when nothing more specific is configured. */
  defaultFeeMinor?: Minor;
  /** Average of historical rider fees; treated as an *estimate*, never a quote. */
  historicalAverageMinor?: Minor;
  historicalSampleSize?: number;
  /** Floor applied to any computed estimate. */
  minimumCostMinor?: Minor;
}

export interface PricingConfig {
  id: string;
  tenantId: string;
  name: string;
  isActive: boolean;
  /** Incremented by the backend on every change; recorded in quotes/orders. */
  version: number;
  currency: string;
  method: PricingMethod;
  baseChargeMinor: Minor;
  perKmRateMinor: Minor;
  distanceBands: DistanceBand[];
  zoneRules: ZoneRule[];
  /** When a zone pair is not configured: fall back to distance pricing. */
  fallbackToDistanceWhenZoneMissing: boolean;
  minimumChargeMinor: Minor;
  maximumChargeMinor: Minor | null;
  roundingIncrementMinor: Minor;
  roundingMode: PricingRoundingMode;
  surcharges: SurchargeRule[];
  riderCost: RiderCostConfig;
  targetProfitAmountMinor: Minor | null;
  /** Target margin in basis points; must be >= 0 and < 10000 (i.e. < 100%). */
  targetProfitMarginBps: number | null;
}

/** Where the road distance came from. Straight-line distance is never used. */
export type DistanceSource = "provider" | "manual";

export interface QuoteDistance {
  /** Whole metres of *road* distance as reported by the routing provider. */
  metres: number;
  source: DistanceSource;
  /** Provider id, e.g. "google_routes" | "mapbox_directions" | "manual". */
  provider: string;
  /** Provider's textual summary, e.g. "8.4 km via Ikorodu Rd". */
  summary?: string;
  durationSeconds?: number;
  /** When the provider answered; used to flag stale distances. */
  calculatedAt: string;
  /** Pickup/destination coordinates actually used for the calculation. */
  pickupCoordinates?: { latitude: number; longitude: number };
  destinationCoordinates?: { latitude: number; longitude: number };
}

export interface QuoteLocation {
  address: string;
  zoneId?: string | null;
  latitude?: number | null;
  longitude?: number | null;
}

export interface AdditionalCostInput {
  /** Stable id so the same expense can never be counted twice. */
  id: string;
  label: string;
  amountMinor: Minor;
  /**
   * "delivery" = incurred for this delivery (deducted from delivery contribution).
   * "operating" = company overhead, surfaced in company reports instead — it is
   * deliberately NOT deducted here to avoid double counting.
   */
  kind: "delivery" | "operating";
}

export interface RiderCostOverride {
  amountMinor: Minor;
  /** Why the estimate was changed (rider's own quote, negotiation, etc.). */
  reason: string;
  /** Free-text, optional; e.g. the rider's name so the dispatcher remembers. */
  note?: string;
}

export interface CustomerPriceOverride {
  amountMinor: Minor;
  reason: string;
  /** Explicitly allow a price below the configured minimum charge. */
  allowBelowMinimum?: boolean;
}

export interface QuoteInput {
  pickup: QuoteLocation;
  destination: QuoteLocation;
  distance: QuoteDistance | null;
  customerId?: string | null;
  deliveryType?: string | null;
  serviceLevel: ServiceLevel;
  additionalCosts?: AdditionalCostInput[];
  riderCostOverride?: RiderCostOverride | null;
  customerPriceOverride?: CustomerPriceOverride | null;
  /** Per-request override of the company's target profit. */
  targetProfitOverride?: { amountMinor?: Minor; marginBps?: number } | null;
}

export type WarningSeverity = "info" | "warning" | "blocker";

export interface QuoteWarning {
  code: string;
  severity: WarningSeverity;
  message: string;
}

export interface QuoteLine {
  code: string;
  label: string;
  amountMinor: Minor;
  note?: string;
}

export interface RiderCostEstimate {
  amountMinor: Minor;
  method: RiderCostMethod;
  /**
   * confirmed   — the dispatcher/rider supplied the actual amount
   * estimated   — computed from the company's configured rider rules
   * unconfirmed — a fallback (default fee / historical average) that must be
   *               reviewed before the delivery is accepted
   */
  confidence: "confirmed" | "estimated" | "unconfirmed";
  note?: string;
}

export interface TargetProfitStatus {
  kind: "amount" | "margin" | "none";
  /** The configured target, in minor units or basis points. */
  value: number;
  satisfied: boolean;
  /** How far below the target the final result is (0 when satisfied). */
  shortfallMinor: Minor;
}

export interface PricingSnapshot {
  engineVersion: string;
  pricingConfigId: string | null;
  pricingConfigVersion: number | null;
  pricingConfigName: string | null;
  pricingMethod: PricingMethod;
  currency: string;
  baseChargeMinor: Minor;
  perKmRateMinor: Minor;
  distanceBands: DistanceBand[];
  zoneRules: ZoneRule[];
  minimumChargeMinor: Minor;
  maximumChargeMinor: Minor | null;
  roundingIncrementMinor: Minor;
  roundingMode: PricingRoundingMode;
  surcharges: SurchargeRule[];
  riderCostConfig: RiderCostConfig;
  targetProfitAmountMinor: Minor | null;
  targetProfitMarginBps: number | null;
  capturedAt: string;
}

export interface QuoteBreakdown {
  currency: string;
  method: PricingMethod;
  engineVersion: string;
  lines: QuoteLine[];
  distance: QuoteDistance | null;
  distanceKm: number | null;
  /** Charge the engine recommends before any manual override. */
  computedCustomerChargeMinor: Minor;
  /** Charge after overrides, minimum, maximum and rounding — what we suggest. */
  recommendedCustomerChargeMinor: Minor;
  /** Sum of the method + surcharge lines before minimum/maximum/rounding. */
  linesTotalBeforeRounding: Minor;
  appliedMinimumCharge: boolean;
  appliedMaximumCharge: boolean;
  rounding: { incrementMinor: Minor; beforeMinor: Minor; afterMinor: Minor } | null;
  manualPriceOverrideApplied: boolean;
  estimatedRiderCost: RiderCostEstimate;
  estimatedAdditionalDeliveryCostsMinor: Minor;
  /** Rider cost + delivery-level additional costs. */
  estimatedTotalDeliveryCostMinor: Minor;
  /** Customer charge − total delivery cost. Delivery contribution, not net profit. */
  estimatedProfitMinor: Minor;
  estimatedMarginBps: number;
  estimatedMarginIsDefined: boolean;
  targetProfit: TargetProfitStatus;
  warnings: QuoteWarning[];
  requiresReviewBeforeAccepting: boolean;
  pricingSnapshot: PricingSnapshot;
  computedAt: string;
}

export type QuoteStatus = "ok" | "needs_input";

export interface QuoteResult {
  status: QuoteStatus;
  /** Present when status === "ok". */
  quote?: QuoteBreakdown;
  /** Always present; explains blockers and informs the dispatcher. */
  warnings: QuoteWarning[];
}
