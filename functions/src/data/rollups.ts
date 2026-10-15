/**
 * Daily rollups.
 *
 * Dashboards and reports must not scan the whole orders collection (spec §21).
 * Instead, every lifecycle write bumps one small document per company per business
 * day, and reports read at most one document per day in the requested range.
 *
 * The rollup is a derived cache: `rebuildDailyRollups` can regenerate it from the
 * orders, and every field is documented in docs/FINANCIAL_DEFINITIONS.md so the
 * overview screen and reports cannot drift apart.
 */

import type { DataStore } from "./types";

export interface DailyRollupPatch {
  ordersCreated?: number;
  ordersConfirmed?: number;
  ordersAssigned?: number;
  ordersPickedUp?: number;
  ordersInTransit?: number;
  deliveriesCompleted?: number;
  deliveriesFailed?: number;
  deliveriesCancelled?: number;
  /** Realized revenue: completed deliveries only, from the final customer charge. */
  revenueMinor?: number;
  /** Rider payments actually recorded (falls back to the estimate before actuals). */
  riderCostMinor?: number;
  /** Delivery-level additional costs recorded/estimated. */
  additionalCostsMinor?: number;
  estimatedProfitMinor?: number;
  actualProfitMinor?: number;
  /** Count of deliveries whose provisional revenue is not yet finalized. */
  pendingRevenueCount?: number;
}

export const ROLLUP_NUMERIC_FIELDS: readonly (keyof DailyRollupPatch)[] = [
  "ordersCreated",
  "ordersConfirmed",
  "ordersAssigned",
  "ordersPickedUp",
  "ordersInTransit",
  "deliveriesCompleted",
  "deliveriesFailed",
  "deliveriesCancelled",
  "revenueMinor",
  "riderCostMinor",
  "additionalCostsMinor",
  "estimatedProfitMinor",
  "actualProfitMinor",
  "pendingRevenueCount",
];

export function rollupPath(tenantId: string, businessDateKey: string): string {
  return `companyDailyStats/${tenantId}_${businessDateKey}`;
}

/**
 * Applies additive deltas to the rollup for a business day.
 *
 * Read-modify-write inside a transaction: safe against two dispatchers completing
 * deliveries at the same instant, and cheap (one small document).
 */
export async function bumpDailyRollup(
  store: DataStore,
  tenantId: string,
  businessDateKey: string,
  patch: DailyRollupPatch,
  currency: string,
): Promise<void> {
  const path = rollupPath(tenantId, businessDateKey);
  const cleanPatch: Record<string, number> = {};
  for (const [key, value] of Object.entries(patch)) {
    if (typeof value === "number" && value !== 0) cleanPatch[key] = value;
  }
  if (Object.keys(cleanPatch).length === 0) return;

  await store.runTransaction(async (tx) => {
    const existing = await tx.get(path);
    const current = existing?.data ?? {};
    const next: Record<string, unknown> = {
      tenantId,
      businessDate: businessDateKey,
      currency,
      updatedAt: store.now().toISOString(),
    };
    for (const field of ROLLUP_NUMERIC_FIELDS) {
      const delta = cleanPatch[field] ?? 0;
      const previous = typeof current[field] === "number" ? (current[field] as number) : 0;
      next[field] = previous + delta;
    }
    if (!existing) next["createdAt"] = store.now().toISOString();
    // `set` (not `update`) because the first delivery of a day creates the document.
    tx.set(path, next, { merge: true });
  });
}

export interface RollupTotals {
  ordersCreated: number;
  deliveriesCompleted: number;
  deliveriesFailed: number;
  deliveriesCancelled: number;
  revenueMinor: number;
  riderCostMinor: number;
  additionalCostsMinor: number;
  estimatedProfitMinor: number;
  actualProfitMinor: number;
  pendingRevenueCount: number;
  /** Business days that actually have a document. */
  daysWithData: number;
  /** True when some days in the requested range have no rollup document at all. */
  hasMissingDays: boolean;
}

function numberOrZero(value: unknown): number {
  return typeof value === "number" && Number.isFinite(value) ? value : 0;
}

/**
 * Sums rollups for a date range.
 *
 * Distinguishes "a day with no deliveries" (document exists, all zero) from "no
 * document" (the range includes a day the company did not exist yet). The caller
 * can therefore avoid presenting an empty day as a data gap, or vice versa.
 */
export function sumRollups(
  docs: Array<{ id: string; data: Record<string, unknown> }>,
  expectedDays: string[],
): RollupTotals {
  const byDate = new Map<string, Record<string, unknown>>();
  for (const doc of docs) {
    const date = typeof doc.data["businessDate"] === "string" ? (doc.data["businessDate"] as string) : doc.id.slice(-10);
    byDate.set(date, doc.data);
  }
  const totals: RollupTotals = {
    ordersCreated: 0,
    deliveriesCompleted: 0,
    deliveriesFailed: 0,
    deliveriesCancelled: 0,
    revenueMinor: 0,
    riderCostMinor: 0,
    additionalCostsMinor: 0,
    estimatedProfitMinor: 0,
    actualProfitMinor: 0,
    pendingRevenueCount: 0,
    daysWithData: 0,
    hasMissingDays: false,
  };
  for (const date of expectedDays) {
    const data = byDate.get(date);
    if (!data) {
      totals.hasMissingDays = true;
      continue;
    }
    totals.daysWithData += 1;
    totals.ordersCreated += numberOrZero(data["ordersCreated"]);
    totals.deliveriesCompleted += numberOrZero(data["deliveriesCompleted"]);
    totals.deliveriesFailed += numberOrZero(data["deliveriesFailed"]);
    totals.deliveriesCancelled += numberOrZero(data["deliveriesCancelled"]);
    totals.revenueMinor += numberOrZero(data["revenueMinor"]);
    totals.riderCostMinor += numberOrZero(data["riderCostMinor"]);
    totals.additionalCostsMinor += numberOrZero(data["additionalCostsMinor"]);
    totals.estimatedProfitMinor += numberOrZero(data["estimatedProfitMinor"]);
    totals.actualProfitMinor += numberOrZero(data["actualProfitMinor"]);
    totals.pendingRevenueCount += numberOrZero(data["pendingRevenueCount"]);
  }
  return totals;
}
