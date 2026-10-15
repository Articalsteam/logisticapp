/**
 * Expenses (spec §15).
 *
 * Two kinds, and the distinction matters for reporting:
 *  - `delivery` — incurred for one delivery (waiting fee, parking, extra trip).
 *    These are deducted from the delivery's contribution, so they are excluded from
 *    company-level expense totals to avoid double counting.
 *  - `operating` — company overhead (fuel for standby bikes, office, airtime). These
 *    are company-level and are never deducted from a single delivery.
 *
 * Manual entry only in the MVP: no payroll, no tax filing, no accounting engine.
 */

import { z } from "zod";
import { bumpDailyRollup } from "../data/rollups";
import { writeAuditLog } from "../shared/audit";
import { requireAuth, requireEntitlementForWrite, requireMembership } from "../shared/auth";
import { errors } from "../shared/errors";
import { requireIdempotencyKey, withIdempotency } from "../shared/idempotency";
import { businessDate } from "../shared/time";
import {
  currencySchema,
  dateKeySchema,
  minorSchema,
  notesSchema,
  parseWith,
  shortTextSchema,
} from "../shared/validation";
import { ok, type Handler } from "./deps";

export const EXPENSE_CATEGORIES = [
  "fuel",
  "rider_advance",
  "parking",
  "toll",
  "waiting_fee",
  "maintenance",
  "airtime",
  "rent",
  "salaries",
  "other",
] as const;

export const createExpenseSchema = z.object({
  tenantId: z.string().min(1).max(64),
  idempotencyKey: z.string(),
  category: z.enum(EXPENSE_CATEGORIES),
  label: shortTextSchema.optional(),
  amountMinor: minorSchema,
  currency: currencySchema.optional(),
  expenseDate: dateKeySchema,
  description: notesSchema.optional(),
  /** Set for a delivery-level expense; null/absent for company overhead. */
  orderId: z.string().max(64).nullable().optional(),
  /** Explicitly marks the expense as company overhead rather than delivery cost. */
  kind: z.enum(["delivery", "operating"]).optional(),
});

export type CreateExpenseInput = z.infer<typeof createExpenseSchema>;

export interface CreateExpenseResult {
  expenseId: string;
  kind: "delivery" | "operating";
  amountMinor: number;
  expenseDate: string;
}

export const createExpense: Handler<CreateExpenseInput, ReturnType<typeof ok<CreateExpenseResult>>> = async (
  input,
  context,
  deps,
) => {
  const uid = requireAuth(context);
  const parsed = parseWith(createExpenseSchema, input, {
    detail: "expense.invalid_create_input",
    userMessage: "Some expense details are not valid.",
  });
  const idempotencyKey = requireIdempotencyKey(parsed.idempotencyKey);
  await requireMembership(deps.store, context, parsed.tenantId, "expenses.manage");
  await requireEntitlementForWrite(deps.store, parsed.tenantId);

  const company = await deps.store.get(`companies/${parsed.tenantId}`);
  if (!company) throw errors.notFound("company_missing", "That company workspace was not found.");
  const currency = parsed.currency ?? (typeof company.data["currency"] === "string" ? (company.data["currency"] as string) : "NGN");

  const outcome = await withIdempotency<CreateExpenseResult & Record<string, unknown>>(
    deps.store,
    { scope: "createExpense", tenantId: parsed.tenantId, actorUserId: uid, key: idempotencyKey },
    async () => {
      const now = deps.store.now();
      const expenseId = deps.store.newId("expenses");

      // Kind is derived from what the expense is attached to unless the dispatcher
      // says otherwise; a delivery-linked expense is a delivery cost.
      let kind: "delivery" | "operating" = parsed.kind ?? (parsed.orderId ? "delivery" : "operating");
      if (parsed.orderId) {
        const order = await deps.store.get(`orders/${parsed.orderId}`);
        if (!order || order.data["tenantId"] !== parsed.tenantId) {
          throw errors.notFound("order_missing", "That delivery was not found.");
        }
        if (order.data["status"] === "delivered") {
          throw errors.failedPrecondition(
            "expense.order_closed_use_actuals",
            "This delivery is already complete. Record the cost through 'record final amounts' so the delivery totals stay consistent.",
          );
        }
        kind = "delivery";
      } else if (parsed.kind === undefined) {
        kind = "operating";
      }

      await deps.store.create(`expenses/${expenseId}`, {
        tenantId: parsed.tenantId,
        orderId: parsed.orderId ?? null,
        kind,
        category: parsed.category,
        label: parsed.label ?? null,
        amountMinor: parsed.amountMinor,
        currency,
        description: parsed.description ?? null,
        expenseDate: parsed.expenseDate,
        businessDate: parsed.expenseDate,
        recordedBy: uid,
        createdAt: now.toISOString(),
        updatedAt: now.toISOString(),
      });

      await bumpDailyRollup(
        deps.store,
        parsed.tenantId,
        parsed.expenseDate,
        // Delivery-level costs are already part of the delivery's contribution, so
        // only overhead flows into the company expense total.
        kind === "operating" ? { additionalCostsMinor: parsed.amountMinor } : {},
        currency,
      );

      await writeAuditLog(deps.store, {
        tenantId: parsed.tenantId,
        actorUserId: uid,
        action: "expense.created",
        resourceType: "expense",
        resourceId: expenseId,
        metadata: { kind, category: parsed.category, amountMinor: parsed.amountMinor, orderId: parsed.orderId ?? null },
      });

      return { expenseId, kind, amountMinor: parsed.amountMinor, expenseDate: parsed.expenseDate } as CreateExpenseResult &
        Record<string, unknown>;
    },
  );

  return ok(outcome.result, outcome.replayed);
};

export const deleteExpenseSchema = z.object({
  tenantId: z.string().min(1).max(64),
  expenseId: z.string().min(1).max(64),
  reason: z.string().trim().min(3).max(240),
});

export type DeleteExpenseInput = z.infer<typeof deleteExpenseSchema>;

/** Removes a mis-keyed expense. The audit log keeps the record of what happened. */
export const deleteExpense: Handler<DeleteExpenseInput, ReturnType<typeof ok<{ expenseId: string }>>> = async (
  input,
  context,
  deps,
) => {
  const uid = requireAuth(context);
  const parsed = parseWith(deleteExpenseSchema, input, {
    detail: "expense.invalid_delete_input",
    userMessage: "That change is not valid.",
  });
  await requireMembership(deps.store, context, parsed.tenantId, "expenses.manage");

  const doc = await deps.store.get(`expenses/${parsed.expenseId}`);
  if (!doc || doc.data["tenantId"] !== parsed.tenantId) {
    throw errors.notFound("expense_missing", "That expense was not found.");
  }
  const amountMinor = typeof doc.data["amountMinor"] === "number" ? (doc.data["amountMinor"] as number) : 0;
  const kind = doc.data["kind"] === "delivery" ? "delivery" : "operating";
  const expenseDate = typeof doc.data["expenseDate"] === "string" ? (doc.data["expenseDate"] as string) : businessDate(deps.store.now(), "Africa/Lagos");

  await deps.store.delete(`expenses/${parsed.expenseId}`);
  await bumpDailyRollup(
    deps.store,
    parsed.tenantId,
    expenseDate,
    kind === "operating" ? { additionalCostsMinor: -amountMinor } : {},
    typeof doc.data["currency"] === "string" ? (doc.data["currency"] as string) : "NGN",
  );
  await writeAuditLog(deps.store, {
    tenantId: parsed.tenantId,
    actorUserId: uid,
    action: "expense.deleted",
    resourceType: "expense",
    resourceId: parsed.expenseId,
    metadata: { amountMinor, kind, reason: parsed.reason },
  });
  return ok({ expenseId: parsed.expenseId });
};
