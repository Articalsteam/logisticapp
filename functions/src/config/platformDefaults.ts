/**
 * Platform defaults and plan catalogue.
 *
 * These are *starter* values, not universal Nigerian delivery rates. Every
 * company edits them during onboarding and they live in Firestore
 * (`pricingConfigs`) — nothing here is hardcoded into a screen.
 */

import type { PricingConfig } from "../pricing/types";

export const DEFAULT_CURRENCY = "NGN";
export const DEFAULT_TIMEZONE = "Africa/Lagos";

/**
 * Starter pricing configuration created for every new company.
 *
 * Chosen so that a brand new workspace produces sensible quotes immediately, then
 * gets tuned by the owner. Values are in kobo (see shared/money.ts).
 */
export function starterPricingConfig(params: {
  tenantId: string;
  id: string;
  currency?: string;
  baseChargeMinor?: number;
  perKmRateMinor?: number;
  minimumChargeMinor?: number;
  defaultRiderCostMinor?: number;
  targetProfitMarginBps?: number;
}): PricingConfig {
  return {
    id: params.id,
    tenantId: params.tenantId,
    name: "Default pricing",
    isActive: true,
    version: 1,
    currency: params.currency ?? DEFAULT_CURRENCY,
    method: "base_plus_distance",
    baseChargeMinor: params.baseChargeMinor ?? 70_000, // ₦700.00
    perKmRateMinor: params.perKmRateMinor ?? 15_000, // ₦150.00 per km
    distanceBands: [
      { upToMetres: 3_000, chargeMinor: 100_000, label: "0–3 km" },
      { upToMetres: 7_000, chargeMinor: 160_000, label: "Over 3–7 km" },
      { upToMetres: 15_000, chargeMinor: 240_000, label: "Over 7–15 km" },
      { upToMetres: null, chargeMinor: 300_000, label: "Over 15 km (review)" },
    ],
    zoneRules: [],
    fallbackToDistanceWhenZoneMissing: true,
    minimumChargeMinor: params.minimumChargeMinor ?? 80_000, // ₦800.00
    maximumChargeMinor: null,
    roundingIncrementMinor: 5_000, // round to the nearest ₦50
    roundingMode: "nearest",
    surcharges: [],
    riderCost: {
      method: "fixed",
      defaultFeeMinor: params.defaultRiderCostMinor ?? 50_000, // ₦500.00
      minimumCostMinor: 30_000,
    },
    targetProfitAmountMinor: null,
    targetProfitMarginBps: params.targetProfitMarginBps ?? 2_500, // 25%
  };
}

export interface PlanDefinition {
  id: string;
  name: string;
  description: string;
  /** Monthly price in minor units. Displayed from this central config only. */
  priceMinor: number;
  currency: string;
  periodDays: number;
  trialDays: number;
  features: string[];
  isActive: boolean;
}

/**
 * Plan catalogue. Stored in `platformConfig/plans` in Firestore and seeded from
 * here. Prices are shown to companies from the stored config — never hardcoded in
 * a screen (spec §19).
 */
export const DEFAULT_PLANS: PlanDefinition[] = [
  {
    id: "pilot",
    name: "Pilot",
    description: "For the first logistics partners validating the platform with us.",
    priceMinor: 0,
    currency: DEFAULT_CURRENCY,
    periodDays: 30,
    trialDays: 30,
    features: ["Up to 3 staff accounts", "Unlimited deliveries", "Pricing engine", "Public tracking"],
    isActive: true,
  },
  {
    id: "starter",
    name: "Starter",
    description: "For a growing dispatch business running daily deliveries.",
    priceMinor: 1_500_000, // ₦15,000 / month
    currency: DEFAULT_CURRENCY,
    periodDays: 30,
    trialDays: 14,
    features: [
      "Up to 10 staff accounts",
      "Catalogue of pricing methods",
      "Rider app access",
      "Daily and monthly reports",
      "CSV export",
    ],
    isActive: true,
  },
  {
    id: "business",
    name: "Business",
    description: "For established logistics companies with multiple dispatchers.",
    priceMinor: 3_500_000, // ₦35,000 / month
    currency: DEFAULT_CURRENCY,
    periodDays: 30,
    trialDays: 14,
    features: ["Unlimited staff", "Zone-based pricing", "Financial reporting", "Priority support"],
    isActive: true,
  },
];

export const DEFAULT_PLAN_ID = "pilot";
