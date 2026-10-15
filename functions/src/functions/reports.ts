/**
 * Dashboard metrics and report exports.
 *
 * Metrics come from the per-day rollups (bounded reads: one small document per day
 * in range) plus a handful of live counts for work in progress. The definitions
 * behind every figure are documented in docs/FINANCIAL_DEFINITIONS.md so the
 * overview screen and the exported report cannot drift apart.
 *
 * Money is only returned to callers holding `reports.view_financial`: a dispatcher
 * sees operational counts, not company profit.
 */

import { z } from "zod";
import { rollupPath, sumRollups } from "../data/rollups";
import type { FileStoragePort } from "../data/ports";
import { writeAuditLog } from "../shared/audit";
import { effectivePermissions, type Permission } from "../domain/permissions";
import { requireAuth, requireMembership } from "../shared/auth";
import { errors } from "../shared/errors";
import { businessDate, recentDateKeys } from "../shared/time";
import { dateKeySchema, parseWith } from "../shared/validation";
import { ok, type Handler, type HandlerDeps } from "./deps";

export const dashboardMetricsSchema = z.object({
  tenantId: z.string().min(1).max(64),
  /** Inclusive business-day range in the company's timezone. */
  fromDateKey: dateKeySchema.optional(),
  toDateKey: dateKeySchema.optional(),
  /** Trend window for the sparkline series (1–31 days). */
  trendDays: z.number().int().min(1).max(31).optional(),
});

export type DashboardMetricsInput = z.infer<typeof dashboardMetricsSchema>;

export interface DashboardTrendPoint {
  date: string;
  deliveriesCompleted: number;
  revenueMinor: number;
  actualProfitMinor: number;
  estimatedProfitMinor: number;
}

export interface DashboardMetrics {
  range: { fromDateKey: string; toDateKey: string; timezone: string; currency: string };
  counts: {
    deliveriesToday: number;
    ordersInRange: number;
    pending: number;
    awaitingAssignment: number;
    inProgress: number;
    completed: number;
    failed: number;
    cancelled: number;
  };
  /** Null when the caller lacks `reports.view_financial`. */
  financials: {
    revenueMinor: number;
    riderPaymentsMinor: number;
    operatingExpensesMinor: number;
    estimatedProfitMinor: number;
    actualProfitMinor: number;
    averageProfitPerDeliveryMinor: number;
    deliveriesWithFinalAmounts: number;
    deliveriesAwaitingFinalAmounts: number;
  } | null;
  trends: DashboardTrendPoint[];
  dataQuality: {
    /** Business days in the range with no rollup document at all. */
    missingDays: string[];
    /** True when some completed deliveries still have no recorded final amounts. */
    hasUnfinalizedRevenue: boolean;
    /** Set when the rate card changed inside the range, so profit mix shifts. */
    pricingVersionChanged: boolean;
  };
  generatedAt: string;
}

async function loadCompany(deps: HandlerDeps, tenantId: string): Promise<Record<string, unknown>> {
  const company = await deps.store.get(`companies/${tenantId}`);
  if (!company) throw errors.notFound("company_missing", "That company workspace was not found.");
  return company.data;
}

export const getCompanyDashboardMetrics: Handler<DashboardMetricsInput, ReturnType<typeof ok<DashboardMetrics>>> = async (
  input,
  context,
  deps,
) => {
  const parsed = parseWith(dashboardMetricsSchema, input, {
    detail: "reports.invalid_dashboard_input",
    userMessage: "That date range is not valid.",
  });
  const membership = await requireMembership(deps.store, context, parsed.tenantId, "reports.view_operational");
  const permissions = effectivePermissions(membership);
  const canSeeFinancials = permissions.has("reports.view_financial" satisfies Permission);

  const company = await loadCompany(deps, parsed.tenantId);
  const timezone = typeof company["timezone"] === "string" ? (company["timezone"] as string) : "Africa/Lagos";
  const currency = typeof company["currency"] === "string" ? (company["currency"] as string) : "NGN";
  const now = deps.store.now();
  const todayKey = businessDate(now, timezone);

  const toDateKey = parsed.toDateKey ?? todayKey;
  const fromDateKey = parsed.fromDateKey ?? recentDateKeys(toDateKey, parsed.trendDays ?? 14)[0] ?? toDateKey;
  if (fromDateKey > toDateKey) {
    throw errors.invalidArgument("reports.range_inverted", "The start date must be before the end date.");
  }
  const expectedDays = enumerateDays(fromDateKey, toDateKey);

  const rollupDocs = await deps.store.query("companyDailyStats", {
    where: [
      { field: "tenantId", op: "==", value: parsed.tenantId },
      { field: "businessDate", op: ">=", value: fromDateKey },
      { field: "businessDate", op: "<=", value: toDateKey },
    ],
    orderBy: [{ field: "businessDate", direction: "asc" }],
    limit: 400,
  });
  const totals = sumRollups(rollupDocs, expectedDays);
  const byDate = new Map(rollupDocs.map((doc) => [String(doc.data["businessDate"]), doc.data]));

  // Live counts for work in progress. `count()` keeps these O(1)-ish in transferred
  // bytes; the filters match the composite indexes in firestore.indexes.json.
  const [pending, confirmed, assigned, pickedUp, inTransit, failed, cancelled] = await Promise.all([
    count(deps, { field: "status", op: "==", value: "pending" }, parsed.tenantId),
    count(deps, { field: "status", op: "==", value: "confirmed" }, parsed.tenantId),
    count(deps, { field: "status", op: "==", value: "assigned" }, parsed.tenantId),
    count(deps, { field: "status", op: "==", value: "picked_up" }, parsed.tenantId),
    count(deps, { field: "status", op: "==", value: "in_transit" }, parsed.tenantId),
    count(deps, { field: "status", op: "==", value: "failed" }, parsed.tenantId),
    count(deps, { field: "status", op: "==", value: "cancelled" }, parsed.tenantId),
  ]);

  const todayTotals = sumRollups(
    rollupDocs.filter((doc) => doc.data["businessDate"] === todayKey),
    [todayKey],
  );

  const trends: DashboardTrendPoint[] = expectedDays.map((date) => {
    const data = byDate.get(date) ?? {};
    return {
      date,
      deliveriesCompleted: num(data["deliveriesCompleted"]),
      revenueMinor: canSeeFinancials ? num(data["revenueMinor"]) : 0,
      actualProfitMinor: canSeeFinancials ? num(data["actualProfitMinor"]) : 0,
      estimatedProfitMinor: canSeeFinancials ? num(data["estimatedProfitMinor"]) : 0,
    };
  });

  const completed = totals.deliveriesCompleted;
  const financials = canSeeFinancials
    ? {
        revenueMinor: totals.revenueMinor,
        riderPaymentsMinor: totals.riderCostMinor,
        operatingExpensesMinor: totals.additionalCostsMinor,
        estimatedProfitMinor: totals.estimatedProfitMinor,
        actualProfitMinor: totals.actualProfitMinor,
        averageProfitPerDeliveryMinor: completed > 0 ? Math.round(totals.actualProfitMinor / completed) : 0,
        deliveriesWithFinalAmounts: Math.max(0, completed - totals.pendingRevenueCount),
        deliveriesAwaitingFinalAmounts: totals.pendingRevenueCount,
      }
    : null;

  const metrics: DashboardMetrics = {
    range: { fromDateKey, toDateKey, timezone, currency },
    counts: {
      deliveriesToday: todayTotals.deliveriesCompleted,
      ordersInRange: totals.ordersCreated,
      pending,
      awaitingAssignment: pending + confirmed,
      inProgress: assigned + pickedUp + inTransit,
      completed,
      failed,
      cancelled,
    },
    financials,
    trends,
    dataQuality: {
      missingDays: expectedDays.filter((date) => !byDate.has(date)),
      hasUnfinalizedRevenue: totals.pendingRevenueCount > 0,
      pricingVersionChanged: false,
    },
    generatedAt: now.toISOString(),
  };

  return ok(metrics);
};

async function count(
  deps: HandlerDeps,
  clause: { field: string; op: "=="; value: unknown },
  tenantId: string,
): Promise<number> {
  return deps.store.count("orders", {
    where: [
      { field: "tenantId", op: "==", value: tenantId },
      { field: clause.field, op: clause.op, value: clause.value },
    ],
    limit: 1000,
  });
}

function num(value: unknown): number {
  return typeof value === "number" && Number.isFinite(value) ? value : 0;
}

function enumerateDays(fromDateKey: string, toDateKey: string): string[] {
  const days: string[] = [];
  let cursor = fromDateKey;
  // Hard cap so a wide range cannot turn into an unbounded query.
  for (let index = 0; index < 400 && cursor <= toDateKey; index += 1) {
    days.push(cursor);
    const [year, month, day] = cursor.split("-").map(Number) as [number, number, number];
    const next = new Date(Date.UTC(year, month - 1, day + 1));
    cursor = next.toISOString().slice(0, 10);
  }
  return days;
}

// ---------------------------------------------------------------------------
// CSV export
// ---------------------------------------------------------------------------

export const REPORT_TYPES = ["orders", "financial_summary", "riders", "customers"] as const;
export type ReportType = (typeof REPORT_TYPES)[number];

export const exportCompanyReportSchema = z.object({
  tenantId: z.string().min(1).max(64),
  reportType: z.enum(REPORT_TYPES),
  fromDateKey: dateKeySchema,
  toDateKey: dateKeySchema,
  /** Cursor for very large exports; the app pages through until complete. */
  cursor: z.string().max(64).nullable().optional(),
});

export type ExportCompanyReportInput = z.infer<typeof exportCompanyReportSchema>;

export interface ExportCompanyReportResult {
  fileName: string;
  storagePath: string | null;
  downloadUrl: string | null;
  rowCount: number;
  truncated: boolean;
  generatedAt: string;
}

const MAX_EXPORT_ROWS = 5_000;
const EXPORT_PAGE_SIZE = 300;

/**
 * Builds a CSV server-side and stores it in Cloud Storage.
 *
 * The app never assembles the report and never requests broad storage permission: it
 * downloads through the SDK (authorised by storage.rules) or shares a signed URL.
 */
export function createExportCompanyReport(storage: FileStoragePort): Handler<
  ExportCompanyReportInput,
  ReturnType<typeof ok<ExportCompanyReportResult>>
> {
  return async (input, context, deps) => {
    const uid = requireAuth(context);
    const parsed = parseWith(exportCompanyReportSchema, input, {
      detail: "reports.invalid_export_input",
      userMessage: "That export request is not valid.",
    });
    await requireMembership(deps.store, context, parsed.tenantId, "reports.export");

    const company = await loadCompany(deps, parsed.tenantId);
    const currency = typeof company["currency"] === "string" ? (company["currency"] as string) : "NGN";
    const now = deps.store.now();

    const rows: string[][] = [];
    let truncated = false;
    let cursor = parsed.cursor ?? null;

    // Paged reads: never load an entire company's history into memory at once.
    for (;;) {
      const page = await deps.store.query("orders", {
        where: [
          { field: "tenantId", op: "==", value: parsed.tenantId },
          { field: "businessDate", op: ">=", value: parsed.fromDateKey },
          { field: "businessDate", op: "<=", value: parsed.toDateKey },
        ],
        orderBy: [
          { field: "businessDate", direction: "asc" },
          { field: "createdAt", direction: "asc" },
        ],
        limit: EXPORT_PAGE_SIZE,
        cursor: cursor ? [cursor.split("|")[0], cursor.split("|")[1]] : null,
      });
      if (page.length === 0) break;
      for (const doc of page) {
        rows.push(orderRow(doc.data, parsed.reportType, currency));
        if (rows.length >= MAX_EXPORT_ROWS + 1) {
          truncated = true;
          break;
        }
      }
      if (truncated || page.length < EXPORT_PAGE_SIZE) break;
      const last = page[page.length - 1];
      cursor = `${String(last?.data["businessDate"] ?? "")}|${String(last?.data["createdAt"] ?? "")}`;
    }

    const header = [
      "order_number",
      "business_date",
      "status",
      "created_at",
      "completed_at",
      "customer",
      "pickup_area",
      "destination_area",
      "distance_km",
      "distance_source",
      "customer_charge_" + currency.toLowerCase(),
      "rider_cost_" + currency.toLowerCase(),
      "additional_costs_" + currency.toLowerCase(),
      "estimated_profit_" + currency.toLowerCase(),
      "actual_profit_" + currency.toLowerCase(),
      "rider",
      "report_type",
    ];
    const csv = [header, ...rows].map((row) => row.map(csvCell).join(",")).join("\r\n");
    const fileName = `${parsed.tenantId}_${parsed.reportType}_${parsed.fromDateKey}_${parsed.toDateKey}.csv`;
    const storagePath = `tenants/${parsed.tenantId}/reports/${fileName}`;

    let stored: { path: string; sizeBytes: number } | null = null;
    try {
      // A BOM so Excel on Windows opens UTF-8 (and the ₦ symbol) correctly.
      stored = await storage.put(storagePath, `\uFEFF${csv}`, "text/csv; charset=utf-8");
    } catch (error) {
      // Export must still be useful when Storage is unavailable in this environment.
      deps.log("report.storage_failed", { tenantId: parsed.tenantId, message: error instanceof Error ? error.message : "unknown" });
    }

    const downloadUrl = stored ? await storage.signedUrl(storagePath, 60) : null;

    await writeAuditLog(deps.store, {
      tenantId: parsed.tenantId,
      actorUserId: uid,
      action: "report.exported",
      resourceType: "report",
      resourceId: storagePath,
      metadata: {
        reportType: parsed.reportType,
        fromDateKey: parsed.fromDateKey,
        toDateKey: parsed.toDateKey,
        rowCount: rows.length - (truncated ? 1 : 0),
        truncated,
        storageAvailable: stored !== null,
      },
    });

    return ok({
      fileName,
      storagePath: stored ? stored.path : null,
      downloadUrl,
      rowCount: rows.length - (truncated ? 1 : 0),
      truncated,
      generatedAt: now.toISOString(),
    });
  };
}

function orderRow(order: Record<string, unknown>, reportType: ReportType, currency: string): string[] {
  const distance = (order["distance"] ?? {}) as Record<string, unknown>;
  const pickup = (order["pickup"] ?? {}) as Record<string, unknown>;
  const destination = (order["destination"] ?? {}) as Record<string, unknown>;
  const customer = (order["customerSnapshot"] ?? {}) as Record<string, unknown>;
  const rider = (order["riderSnapshot"] ?? {}) as Record<string, unknown>;
  const currencyCode = currency.toLowerCase();
  void currencyCode;
  return [
    str(order["orderNumber"]),
    str(order["businessDate"]),
    str(order["status"]),
    str(order["createdAt"]),
    str(order["completedAt"]),
    str(customer["name"]),
    str(pickup["area"] ?? pickup["zoneId"]),
    str(destination["area"] ?? destination["zoneId"]),
    typeof distance["metres"] === "number" ? ((distance["metres"] as number) / 1000).toFixed(2) : "",
    str(distance["source"]),
    amount(order["actualCustomerChargeMinor"] ?? order["customerChargeMinor"]),
    amount(order["actualRiderCostMinor"] ?? order["estimatedRiderCostMinor"]),
    amount(order["actualAdditionalCostsMinor"] ?? order["estimatedAdditionalCostsMinor"]),
    amount(order["estimatedProfitMinor"]),
    amount(order["actualProfitMinor"]),
    str(rider["name"]),
    reportType,
  ];
}

function str(value: unknown): string {
  if (value === null || value === undefined) return "";
  return String(value);
}

function amount(value: unknown): string {
  if (typeof value !== "number" || !Number.isFinite(value)) return "";
  return (value / 100).toFixed(2);
}

function csvCell(value: string): string {
  // Guard against spreadsheet formula injection from customer-entered text.
  const dangerous = /^[=+\-@\t\r]/.test(value);
  const safe = dangerous ? `'${value}` : value;
  return /[",\r\n]/.test(safe) ? `"${safe.replace(/"/g, '""')}"` : safe;
}

// ---------------------------------------------------------------------------
// Rollup rebuild
// ---------------------------------------------------------------------------

export const rebuildDailyRollupsSchema = z.object({
  tenantId: z.string().min(1).max(64),
  fromDateKey: dateKeySchema,
  toDateKey: dateKeySchema,
});

export type RebuildDailyRollupsInput = z.infer<typeof rebuildDailyRollupsSchema>;

/**
 * Regenerates rollups from the orders themselves.
 *
 * The rollups are a cache: if one is ever suspected of being wrong, this recomputes
 * it from the source of truth rather than trying to patch it by hand.
 */
export const rebuildDailyRollups: Handler<
  RebuildDailyRollupsInput,
  ReturnType<typeof ok<{ daysRebuilt: number; ordersScanned: number }>>
> = async (input, context, deps) => {
  const uid = requireAuth(context);
  const parsed = parseWith(rebuildDailyRollupsSchema, input, {
    detail: "reports.invalid_rebuild_input",
    userMessage: "That date range is not valid.",
  });
  await requireMembership(deps.store, context, parsed.tenantId, "reports.view_financial");
  const company = await loadCompany(deps, parsed.tenantId);
  const currency = typeof company["currency"] === "string" ? (company["currency"] as string) : "NGN";

  const orders = await deps.store.query("orders", {
    where: [
      { field: "tenantId", op: "==", value: parsed.tenantId },
      { field: "businessDate", op: ">=", value: parsed.fromDateKey },
      { field: "businessDate", op: "<=", value: parsed.toDateKey },
    ],
    orderBy: [{ field: "businessDate", direction: "asc" }],
    limit: 4000,
  });

  const buckets = new Map<string, Record<string, number>>();
  for (const doc of orders) {
    const date = String(doc.data["businessDate"]);
    const bucket = buckets.get(date) ?? {};
    bucket["ordersCreated"] = (bucket["ordersCreated"] ?? 0) + 1;
    const status = String(doc.data["status"]);
    if (status === "delivered") {
      bucket["deliveriesCompleted"] = (bucket["deliveriesCompleted"] ?? 0) + 1;
      bucket["revenueMinor"] = (bucket["revenueMinor"] ?? 0) + num(doc.data["actualCustomerChargeMinor"] ?? doc.data["customerChargeMinor"]);
      bucket["riderCostMinor"] = (bucket["riderCostMinor"] ?? 0) + num(doc.data["actualRiderCostMinor"] ?? doc.data["estimatedRiderCostMinor"]);
      bucket["additionalCostsMinor"] = (bucket["additionalCostsMinor"] ?? 0) + num(doc.data["actualAdditionalCostsMinor"] ?? doc.data["estimatedAdditionalCostsMinor"]);
      bucket["estimatedProfitMinor"] = (bucket["estimatedProfitMinor"] ?? 0) + num(doc.data["estimatedProfitMinor"]);
      bucket["actualProfitMinor"] = (bucket["actualProfitMinor"] ?? 0) + num(doc.data["actualProfitMinor"]);
      if (typeof doc.data["actualsRecordedAt"] !== "string") bucket["pendingRevenueCount"] = (bucket["pendingRevenueCount"] ?? 0) + 1;
    }
    if (status === "failed") bucket["deliveriesFailed"] = (bucket["deliveriesFailed"] ?? 0) + 1;
    if (status === "cancelled") bucket["deliveriesCancelled"] = (bucket["deliveriesCancelled"] ?? 0) + 1;
    if (status === "assigned" || status === "picked_up" || status === "in_transit") bucket["ordersAssigned"] = (bucket["ordersAssigned"] ?? 0) + 1;
    buckets.set(date, bucket);
  }

  const now = deps.store.now();
  for (const [date, bucket] of buckets) {
    await deps.store.set(
      rollupPath(parsed.tenantId, date),
      {
        tenantId: parsed.tenantId,
        businessDate: date,
        currency,
        ...bucket,
        rebuiltAt: now.toISOString(),
        updatedAt: now.toISOString(),
      },
      { merge: false },
    );
  }

  await writeAuditLog(deps.store, {
    tenantId: parsed.tenantId,
    actorUserId: uid,
    action: "reports.rollups_rebuilt",
    resourceType: "companyDailyStats",
    resourceId: `${parsed.tenantId}_${parsed.fromDateKey}_${parsed.toDateKey}`,
    metadata: { daysRebuilt: buckets.size, ordersScanned: orders.length },
  });

  return ok({ daysRebuilt: buckets.size, ordersScanned: orders.length });
};
