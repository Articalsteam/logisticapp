/**
 * Order updates: editing, rider assignment, status transitions and actuals.
 *
 * These are the operations a dispatcher performs dozens of times a day, so the
 * rules are enforced once, here, and the mobile client mirrors the transition table
 * only to disable buttons it knows will be rejected.
 */

import { z } from "zod";
import { bumpDailyRollup } from "../data/rollups";
import {
  canTransition,
  isOrderStatus,
  labelFor,
  isRevenueBearing,
  type OrderStatus,
} from "../domain/orderStatus";
import { writeAuditLog } from "../shared/audit";
import { assertPermission, requireAuth, requireEntitlementForWrite, requireMembership } from "../shared/auth";
import { errors } from "../shared/errors";
import { stableHash } from "../shared/ids";
import { businessDate } from "../shared/time";
import { addressSchema, minorSchema, notesSchema, phoneSchema, zoneIdSchema } from "../shared/validation";
import { ok, type Handler } from "./deps";
import {
  appendDeliveryEvent,
  loadAssignableRider,
  orderCurrency,
  orderTimeZone,
  parseOrderInput,
  queueNotification,
  riderUserId,
  syncPublicTracking,
  type OrderRecord,
} from "./orders";

// ---------------------------------------------------------------------------
// Loading helpers
// ---------------------------------------------------------------------------

interface LoadedOrder {
  id: string;
  data: Record<string, unknown>;
  status: OrderStatus;
  tenantId: string;
}

async function loadOrder(deps: Parameters<Handler<unknown, unknown>>[2], tenantId: string, orderId: string): Promise<LoadedOrder> {
  const doc = await deps.store.get(`orders/${orderId}`);
  if (!doc || doc.data["tenantId"] !== tenantId) {
    // Cross-tenant access answers "not found": a member of another company must not
    // even learn that the id exists.
    throw errors.notFound("order_missing", "That delivery was not found.");
  }
  const status = doc.data["status"];
  if (!isOrderStatus(status)) {
    throw errors.internal("order_status_corrupt");
  }
  return { id: doc.id, data: doc.data, status, tenantId };
}

/** True when the signed-in user is the rider this delivery is assigned to. */
async function isAssignedRider(
  deps: Parameters<Handler<unknown, unknown>>[2],
  tenantId: string,
  userId: string,
  order: LoadedOrder,
): Promise<boolean> {
  const riderId = order.data["assignedRiderId"];
  if (typeof riderId !== "string" || riderId.length === 0) return false;
  const linkedUserId = await riderUserId(deps.store, tenantId, riderId);
  return linkedUserId === userId;
}

// ---------------------------------------------------------------------------
// Assign rider
// ---------------------------------------------------------------------------

export const assignRiderSchema = z.object({
  tenantId: z.string().min(1).max(64),
  orderId: z.string().min(1).max(64),
  riderId: z.string().min(1).max(64).nullable(),
  reason: z.string().trim().max(240).optional(),
});

export type AssignRiderInput = z.infer<typeof assignRiderSchema>;

export interface AssignRiderResult {
  orderId: string;
  riderId: string | null;
  status: OrderStatus;
}

export const assignRider: Handler<AssignRiderInput, ReturnType<typeof ok<AssignRiderResult>>> = async (input, context, deps) => {
  const uid = requireAuth(context);
  const parsed = parseOrderInput(assignRiderSchema, input, {
    detail: "order.invalid_assign_input",
    userMessage: "That rider assignment is not valid.",
  });
  await requireMembership(deps.store, context, parsed.tenantId, "orders.assign");
  await requireEntitlementForWrite(deps.store, parsed.tenantId);

  const order = await loadOrder(deps, parsed.tenantId, parsed.orderId);
  const now = deps.store.now();
  const company = await deps.store.get(`companies/${parsed.tenantId}`);

  if (parsed.riderId === null) {
    if (order.data["assignedRiderId"] === null || order.data["assignedRiderId"] === undefined) {
      throw errors.failedPrecondition("order.no_rider_assigned", "This delivery has no rider to remove.");
    }
    if (order.status !== "assigned") {
      // Once a rider has collected the parcel, unassigning would erase who is
      // responsible for it. That is a correction, not a reassignment.
      throw errors.failedPrecondition(
        "order.cannot_unassign",
        "A rider can only be removed before the delivery is picked up.",
      );
    }
    await deps.store.set(
      `orders/${order.id}`,
      { assignedRiderId: null, riderSnapshot: null, status: "confirmed", updatedAt: now.toISOString() },
      { merge: true },
    );
    await appendDeliveryEvent(deps.store, {
      tenantId: parsed.tenantId,
      orderId: order.id,
      status: "confirmed",
      actorUserId: uid,
      notes: parsed.reason ?? "Rider removed",
    });
    await syncPublicTracking(deps.store, {
      tenantId: parsed.tenantId,
      orderId: order.id,
      reference: String(order.data["trackingReference"]),
      status: "confirmed",
      company: company?.data ?? null,
      pickupArea: areaOf(order.data["pickup"]),
      destinationArea: areaOf(order.data["destination"]),
      customerPhone: phoneOf(order.data["customerSnapshot"]),
      occurredAt: now,
      statusLabel: "confirmed",
      createIfMissing: false,
    });
    await writeAuditLog(deps.store, {
      tenantId: parsed.tenantId,
      actorUserId: uid,
      action: "order.rider_unassigned",
      resourceType: "order",
      resourceId: order.id,
      metadata: { reason: parsed.reason ?? null },
    });
    return ok({ orderId: order.id, riderId: null, status: "confirmed" });
  }

  const rider = await loadAssignableRider(deps.store, parsed.tenantId, parsed.riderId);
  if (order.status === "delivered" || order.status === "cancelled" || order.status === "failed") {
    throw errors.failedPrecondition(
      "order.not_open",
      `A rider cannot be assigned to a ${labelFor(order.status)} delivery.`,
    );
  }

  const nextStatus: OrderStatus = order.status === "pending" || order.status === "confirmed" ? "assigned" : order.status;
  await deps.store.set(
    `orders/${order.id}`,
    {
      assignedRiderId: rider.id,
      riderSnapshot: rider.snapshot,
      status: nextStatus,
      updatedAt: now.toISOString(),
      ...(order.status === "pending" || order.status === "confirmed" ? { assignedAt: now.toISOString() } : {}),
    },
    { merge: true },
  );

  await appendDeliveryEvent(deps.store, {
    tenantId: parsed.tenantId,
    orderId: order.id,
    status: nextStatus,
    actorUserId: uid,
    notes: `Assigned to ${String(rider.snapshot["name"] ?? "rider")}`,
    metadata: { riderId: rider.id, previousStatus: order.status },
  });

  await syncPublicTracking(deps.store, {
    tenantId: parsed.tenantId,
    orderId: order.id,
    reference: String(order.data["trackingReference"]),
    status: nextStatus,
    company: company?.data ?? null,
    pickupArea: areaOf(order.data["pickup"]),
    destinationArea: areaOf(order.data["destination"]),
    customerPhone: phoneOf(order.data["customerSnapshot"]),
    occurredAt: now,
    statusLabel: "rider_assigned",
    createIfMissing: true,
  });

  if (nextStatus === "assigned") {
    const timezone = orderTimeZone(company?.data ?? null);
    await bumpDailyRollup(deps.store, parsed.tenantId, businessDate(now, timezone), { ordersAssigned: 1 }, orderCurrency(company?.data ?? null));
  }

  const linkedUserId = await riderUserId(deps.store, parsed.tenantId, rider.id);
  if (linkedUserId) {
    await queueNotification(deps.store, {
      tenantId: parsed.tenantId,
      type: "rider_assignment",
      recipientUserIds: [linkedUserId],
      title: "New delivery assigned",
      // Minimal payload: no customer name, address or phone in a notification.
      body: `${String(order.data["orderNumber"] ?? "A delivery")} is ready for pickup.`,
      data: { orderId: order.id, type: "rider_assignment" },
    });
  }

  await writeAuditLog(deps.store, {
    tenantId: parsed.tenantId,
    actorUserId: uid,
    action: "order.rider_assigned",
    resourceType: "order",
    resourceId: order.id,
    metadata: { riderId: rider.id, previousStatus: order.status, nextStatus },
  });

  return ok({ orderId: order.id, riderId: rider.id, status: nextStatus });
};

function areaOf(value: unknown): string | null {
  if (typeof value !== "object" || value === null) return null;
  const area = (value as Record<string, unknown>)["area"];
  return typeof area === "string" && area.length > 0 ? area : null;
}

function phoneOf(value: unknown): string | null {
  if (typeof value !== "object" || value === null) return null;
  const phone = (value as Record<string, unknown>)["phone"];
  return typeof phone === "string" ? phone : null;
}

// ---------------------------------------------------------------------------
// Status transitions
// ---------------------------------------------------------------------------

export const updateDeliveryStatusSchema = z.object({
  tenantId: z.string().min(1).max(64),
  orderId: z.string().min(1).max(64),
  status: z.enum(["pending", "confirmed", "assigned", "picked_up", "in_transit", "delivered", "failed", "cancelled"]),
  notes: notesSchema.optional(),
  /** Required for corrections (see domain/orderStatus.ts). */
  reason: z.string().trim().min(3).max(240).optional(),
  failureReason: z.enum(["customer_unreachable", "wrong_address", "refused_delivery", "item_damaged", "rider_issue", "other"]).optional(),
});

export type UpdateDeliveryStatusInput = z.infer<typeof updateDeliveryStatusSchema>;

export interface UpdateDeliveryStatusResult {
  orderId: string;
  status: OrderStatus;
  previousStatus: OrderStatus;
  eventId: string;
  allowedNextStatuses: OrderStatus[];
}

export const updateDeliveryStatus: Handler<
  UpdateDeliveryStatusInput,
  ReturnType<typeof ok<UpdateDeliveryStatusResult>>
> = async (input, context, deps) => {
  const uid = requireAuth(context);
  const parsed = parseOrderInput(updateDeliveryStatusSchema, input, {
    detail: "order.invalid_status_input",
    userMessage: "That status change is not valid.",
  });

  // Riders act through `orders.update_status`; dispatchers/managers need
  // `orders.update_status` as well, plus `orders.view_all` for company-wide orders.
  const membership = await requireMembership(deps.store, context, parsed.tenantId, "orders.update_status");
  await requireEntitlementForWrite(deps.store, parsed.tenantId);

  const order = await loadOrder(deps, parsed.tenantId, parsed.orderId);
  const now = deps.store.now();
  const riderActing = membership.role === "rider";
  const assignedRider = riderActing ? await isAssignedRider(deps, parsed.tenantId, uid, order) : false;

  if (riderActing) {
    if (!assignedRider) {
      throw errors.permissionDenied(
        "order.not_assigned_to_rider",
        "This delivery is not assigned to you, so you cannot update it.",
      );
    }
  } else if (membership.role === "dispatcher" || membership.role === "manager" || membership.role === "owner") {
    assertPermission(membership, "orders.view_all");
  }

  const canCorrect = !riderActing && (membership.role === "owner" || membership.role === "manager");
  const check = canTransition(order.status, parsed.status, {
    isAssignedRider: riderActing && assignedRider,
    canCorrectStatus: canCorrect,
  });
  if (!check.allowed) {
    // A rejected rider transition is a permission question ("this is not your
    // delivery to move"), everything else is a workflow question.
    if (check.reason === "rider_transition_not_allowed") {
      throw errors.permissionDenied(`order.status_transition:${check.reason}`, check.message);
    }
    if (check.reason === "correction_not_permitted") {
      throw errors.permissionDenied(`order.status_transition:${check.reason}`, check.message);
    }
    throw errors.failedPrecondition(`order.status_transition:${check.reason ?? "rejected"}`, check.message ?? "That status change is not allowed.");
  }
  if (check.kind === "correction" && (!parsed.reason || parsed.reason.trim().length < 3)) {
    throw errors.invalidArgument(
      "order.correction_reason_required",
      "Correcting a delivery's status needs a written reason.",
    );
  }
  if (parsed.status === "failed" && !parsed.failureReason && !parsed.notes) {
    throw errors.invalidArgument("order.failure_reason_required", "Say why the delivery failed so the record is useful later.");
  }

  const company = await deps.store.get(`companies/${parsed.tenantId}`);
  const timezone = orderTimeZone(company?.data ?? null);
  const currency = orderCurrency(company?.data ?? null);
  const businessDateKey = businessDate(now, timezone);

  const patch: Record<string, unknown> = {
    status: parsed.status,
    updatedAt: now.toISOString(),
  };
  if (parsed.status === "delivered") patch["completedAt"] = now.toISOString();
  if (parsed.status === "cancelled") patch["cancelledAt"] = now.toISOString();
  if (parsed.status === "failed") {
    patch["failedAt"] = now.toISOString();
    patch["failureReason"] = parsed.failureReason ?? null;
  }
  if (parsed.notes) patch["lastStatusNote"] = parsed.notes;

  await deps.store.set(`orders/${order.id}`, patch, { merge: true });

  const eventId = await appendDeliveryEvent(deps.store, {
    tenantId: parsed.tenantId,
    orderId: order.id,
    status: parsed.status,
    actorUserId: uid,
    notes: parsed.notes ?? parsed.reason ?? null,
    metadata: {
      previousStatus: order.status,
      kind: check.kind,
      ...(parsed.failureReason ? { failureReason: parsed.failureReason } : {}),
      ...(riderActing ? { byRider: true } : {}),
    },
  });

  await syncPublicTracking(deps.store, {
    tenantId: parsed.tenantId,
    orderId: order.id,
    reference: String(order.data["trackingReference"]),
    status: parsed.status,
    company: company?.data ?? null,
    pickupArea: areaOf(order.data["pickup"]),
    destinationArea: areaOf(order.data["destination"]),
    customerPhone: phoneOf(order.data["customerSnapshot"]),
    occurredAt: now,
    statusLabel: parsed.status,
    createIfMissing: true,
  });

  // Rollups: only the two money-relevant terminal states move revenue, and a
  // decision is recorded once (a corrected delivery is adjusted, not double counted).
  const deliveredBefore = order.status === "delivered";
  const deliveredNow = parsed.status === "delivered";
  const chargeMinor = numberOrZero(order.data["actualCustomerChargeMinor"]) || numberOrZero(order.data["customerChargeMinor"]);
  const riderCost = numberOrZero(order.data["actualRiderCostMinor"]) || numberOrZero(order.data["estimatedRiderCostMinor"]);
  const additionalCosts = numberOrZero(order.data["actualAdditionalCostsMinor"]) || numberOrZero(order.data["estimatedAdditionalCostsMinor"]);

  const rollupPatch: Record<string, number> = {};
  if (!deliveredBefore && deliveredNow) {
    rollupPatch["deliveriesCompleted"] = 1;
    rollupPatch["revenueMinor"] = isRevenueBearing(parsed.status) ? chargeMinor : 0;
    rollupPatch["riderCostMinor"] = riderCost;
    rollupPatch["additionalCostsMinor"] = additionalCosts;
    rollupPatch["estimatedProfitMinor"] = numberOrZero(order.data["estimatedProfitMinor"]);
    rollupPatch["pendingRevenueCount"] = numberOrZero(order.data["actualCustomerChargeMinor"]) > 0 ? 0 : 1;
  }
  if (deliveredBefore && !deliveredNow) {
    // A correction moved revenue out: reverse the earlier contribution.
    rollupPatch["deliveriesCompleted"] = -1;
    rollupPatch["revenueMinor"] = -chargeMinor;
    rollupPatch["riderCostMinor"] = -riderCost;
    rollupPatch["additionalCostsMinor"] = -additionalCosts;
    rollupPatch["estimatedProfitMinor"] = -numberOrZero(order.data["estimatedProfitMinor"]);
  }
  if (parsed.status === "failed" && order.status !== "failed") rollupPatch["deliveriesFailed"] = 1;
  if (parsed.status === "cancelled" && order.status !== "cancelled") rollupPatch["deliveriesCancelled"] = 1;
  if (order.status === "failed" && parsed.status !== "failed") rollupPatch["deliveriesFailed"] = -1;
  if (order.status === "cancelled" && parsed.status !== "cancelled") rollupPatch["deliveriesCancelled"] = -1;
  await bumpDailyRollup(deps.store, parsed.tenantId, businessDateKey, rollupPatch, currency);

  await writeAuditLog(deps.store, {
    tenantId: parsed.tenantId,
    actorUserId: uid,
    action: check.kind === "correction" ? "order.status_corrected" : "order.status_changed",
    resourceType: "order",
    resourceId: order.id,
    metadata: {
      previousStatus: order.status,
      status: parsed.status,
      kind: check.kind,
      byRider: riderActing,
      reason: parsed.reason ?? null,
      failureReason: parsed.failureReason ?? null,
    },
  });

  const allowedNextStatuses: OrderStatus[] = [];
  for (const candidate of [
    "pending",
    "confirmed",
    "assigned",
    "picked_up",
    "in_transit",
    "delivered",
    "failed",
    "cancelled",
  ] as OrderStatus[]) {
    if (canTransition(parsed.status, candidate, { isAssignedRider: riderActing, canCorrectStatus: canCorrect }).allowed) {
      allowedNextStatuses.push(candidate);
    }
  }

  return ok({
    orderId: order.id,
    status: parsed.status,
    previousStatus: order.status,
    eventId,
    allowedNextStatuses,
  });
};

function numberOrZero(value: unknown): number {
  return typeof value === "number" && Number.isFinite(value) ? value : 0;
}

// ---------------------------------------------------------------------------
// Actuals
// ---------------------------------------------------------------------------

export const recordDeliveryActualsSchema = z.object({
  tenantId: z.string().min(1).max(64),
  orderId: z.string().min(1).max(64),
  finalCustomerChargeMinor: minorSchema,
  actualRiderCostMinor: minorSchema,
  actualAdditionalCosts: z
    .array(
      z.object({
        id: z.string().trim().min(1).max(64),
        label: z.string().trim().min(1).max(60),
        amountMinor: minorSchema,
      }),
    )
    .max(30)
    .optional(),
  notes: notesSchema.optional(),
});

export type RecordDeliveryActualsInput = z.infer<typeof recordDeliveryActualsSchema>;

export interface RecordDeliveryActualsResult {
  orderId: string;
  actualCustomerChargeMinor: number;
  actualRiderCostMinor: number;
  actualAdditionalCostsMinor: number;
  actualProfitMinor: number;
  estimatedProfitMinor: number;
  varianceMinor: number;
}

/**
 * Records what actually happened financially.
 *
 * Allowed only once a delivery is finished (delivered, failed or cancelled) so that
 * the numbers compared against the estimate are final. Cancelled deliveries can
 * carry a cancellation fee; failed ones can carry the rider's wasted-trip cost.
 */
export const recordDeliveryActuals: Handler<
  RecordDeliveryActualsInput,
  ReturnType<typeof ok<RecordDeliveryActualsResult>>
> = async (input, context, deps) => {
  const uid = requireAuth(context);
  const parsed = parseOrderInput(recordDeliveryActualsSchema, input, {
    detail: "order.invalid_actuals_input",
    userMessage: "Some of the final amounts are not valid.",
  });
  await requireMembership(deps.store, context, parsed.tenantId, "orders.record_actuals");
  await requireEntitlementForWrite(deps.store, parsed.tenantId);

  const order = await loadOrder(deps, parsed.tenantId, parsed.orderId);
  if (!["delivered", "failed", "cancelled"].includes(order.status)) {
    throw errors.failedPrecondition(
      "order.actuals_require_completion",
      "Record the final amounts after the delivery is completed, failed or cancelled.",
    );
  }

  const now = deps.store.now();
  const company = await deps.store.get(`companies/${parsed.tenantId}`);
  const currency = orderCurrency(company?.data ?? null);
  const timezone = orderTimeZone(company?.data ?? null);

  const seen = new Set<string>();
  let additionalTotal = 0;
  for (const cost of parsed.actualAdditionalCosts ?? []) {
    if (seen.has(cost.id)) {
      throw errors.invalidArgument("order.duplicate_cost", `The cost "${cost.label}" was entered twice.`);
    }
    seen.add(cost.id);
    additionalTotal += cost.amountMinor;
  }

  const actualProfit = parsed.finalCustomerChargeMinor - parsed.actualRiderCostMinor - additionalTotal;

  await deps.store.set(
    `orders/${order.id}`,
    {
      actualCustomerChargeMinor: parsed.finalCustomerChargeMinor,
      actualRiderCostMinor: parsed.actualRiderCostMinor,
      actualAdditionalCostsMinor: additionalTotal,
      actualAdditionalCosts: parsed.actualAdditionalCosts ?? [],
      actualProfitMinor: actualProfit,
      actualsNotes: parsed.notes ?? null,
      actualsRecordedAt: now.toISOString(),
      actualsRecordedBy: uid,
      updatedAt: now.toISOString(),
    },
    { merge: true },
  );

  const estimatedProfit = numberOrZero(order.data["estimatedProfitMinor"]);
  const variance = actualProfit - estimatedProfit;

  // Adjust the rollup: replace whatever was counted for this delivery (the estimate
  // when it was marked delivered, or a previous set of actuals) with the new actuals.
  // Deltas, not replacements, so other deliveries on the same day are untouched.
  const businessDateKey = typeof order.data["businessDate"] === "string" ? (order.data["businessDate"] as string) : businessDate(now, timezone);
  const wasCounted = order.status === "delivered";
  const hadActuals = typeof order.data["actualsRecordedAt"] === "string";
  const previousRevenue = hadActuals ? numberOrZero(order.data["actualCustomerChargeMinor"]) : numberOrZero(order.data["customerChargeMinor"]);
  const previousRiderCost = hadActuals ? numberOrZero(order.data["actualRiderCostMinor"]) : numberOrZero(order.data["estimatedRiderCostMinor"]);
  const previousAdditional = hadActuals ? numberOrZero(order.data["actualAdditionalCostsMinor"]) : numberOrZero(order.data["estimatedAdditionalCostsMinor"]);
  const previousProfit = hadActuals ? numberOrZero(order.data["actualProfitMinor"]) : 0;

  await bumpDailyRollup(
    deps.store,
    parsed.tenantId,
    businessDateKey,
    {
      ...(wasCounted
        ? {
            actualProfitMinor: actualProfit - previousProfit,
            revenueMinor: parsed.finalCustomerChargeMinor - previousRevenue,
            riderCostMinor: parsed.actualRiderCostMinor - previousRiderCost,
            additionalCostsMinor: additionalTotal - previousAdditional,
            // The delivery's revenue is now finalized, so it leaves the pending pile.
            ...(hadActuals ? {} : { pendingRevenueCount: -1 }),
          }
        : {}),
    },
    currency,
  );

  await writeAuditLog(deps.store, {
    tenantId: parsed.tenantId,
    actorUserId: uid,
    action: "order.actuals_recorded",
    resourceType: "order",
    resourceId: order.id,
    metadata: {
      finalCustomerChargeMinor: parsed.finalCustomerChargeMinor,
      actualRiderCostMinor: parsed.actualRiderCostMinor,
      actualAdditionalCostsMinor: additionalTotal,
      actualProfitMinor: actualProfit,
      estimatedProfitMinor: estimatedProfit,
      varianceMinor: variance,
    },
  });

  return ok({
    orderId: order.id,
    actualCustomerChargeMinor: parsed.finalCustomerChargeMinor,
    actualRiderCostMinor: parsed.actualRiderCostMinor,
    actualAdditionalCostsMinor: additionalTotal,
    actualProfitMinor: actualProfit,
    estimatedProfitMinor: estimatedProfit,
    varianceMinor: variance,
  });
};

// ---------------------------------------------------------------------------
// Editing an open order
// ---------------------------------------------------------------------------

export const updateOrderSchema = z.object({
  tenantId: z.string().min(1).max(64),
  orderId: z.string().min(1).max(64),
  description: z.string().trim().min(2).max(240).optional(),
  itemCategory: z.enum(["food", "medicine", "documents", "parcel", "errand", "furniture", "other"]).nullable().optional(),
  internalNotes: notesSchema.nullable().optional(),
  scheduledAt: z
    .string()
    .refine((value) => !Number.isNaN(Date.parse(value)), "Use an ISO date-time")
    .nullable()
    .optional(),
  destination: z
    .object({
      address: addressSchema.optional(),
      zoneId: zoneIdSchema.nullable().optional(),
      contactName: z.string().trim().max(80).nullable().optional(),
      contactPhone: phoneSchema.nullable().optional(),
      notes: notesSchema.nullable().optional(),
    })
    .optional(),
});

export type UpdateOrderInput = z.infer<typeof updateOrderSchema>;

/**
 * Edits the descriptive parts of an open order.
 *
 * Location or price changes go back through the pricing calculator: a delivery whose
 * destination changed needs a new quote, not a quietly edited price.
 */
export const updateOrder: Handler<UpdateOrderInput, ReturnType<typeof ok<{ orderId: string; fields: string[] }>>> = async (
  input,
  context,
  deps,
) => {
  const uid = requireAuth(context);
  const parsed = parseOrderInput(updateOrderSchema, input, {
    detail: "order.invalid_update_input",
    userMessage: "Some delivery details are not valid.",
  });
  await requireMembership(deps.store, context, parsed.tenantId, "orders.update");
  await requireEntitlementForWrite(deps.store, parsed.tenantId);

  const order = await loadOrder(deps, parsed.tenantId, parsed.orderId);
  if (order.status === "delivered" || order.status === "cancelled") {
    throw errors.failedPrecondition(
      "order.closed",
      `A ${labelFor(order.status)} delivery can no longer be edited.`,
    );
  }

  const now = deps.store.now();
  const patch: Record<string, unknown> = { updatedAt: now.toISOString() };
  if (parsed.description !== undefined) patch["description"] = parsed.description;
  if (parsed.itemCategory !== undefined) patch["itemCategory"] = parsed.itemCategory;
  if (parsed.internalNotes !== undefined) patch["internalNotes"] = parsed.internalNotes;
  if (parsed.scheduledAt !== undefined) patch["scheduledAt"] = parsed.scheduledAt;
  if (parsed.destination !== undefined) {
    const existingDestination = (order.data["destination"] ?? {}) as Record<string, unknown>;
    patch["destination"] = {
      ...existingDestination,
      ...(parsed.destination.address !== undefined ? { address: parsed.destination.address } : {}),
      ...(parsed.destination.zoneId !== undefined ? { zoneId: parsed.destination.zoneId } : {}),
      ...(parsed.destination.contactName !== undefined ? { contactName: parsed.destination.contactName } : {}),
      ...(parsed.destination.contactPhone !== undefined ? { contactPhone: parsed.destination.contactPhone } : {}),
      ...(parsed.destination.notes !== undefined ? { notes: parsed.destination.notes } : {}),
      changedAt: now.toISOString(),
    };
    if (parsed.destination.address !== undefined) {
      // Changing an address invalidates the distance the price was based on.
      patch["distanceStaleAt"] = now.toISOString();
    }
  }

  await deps.store.set(`orders/${order.id}`, patch, { merge: true });
  await appendDeliveryEvent(deps.store, {
    tenantId: parsed.tenantId,
    orderId: order.id,
    status: order.status,
    actorUserId: uid,
    notes: "Order details updated",
    metadata: { fields: Object.keys(patch).filter((key) => key !== "updatedAt") },
  });
  await writeAuditLog(deps.store, {
    tenantId: parsed.tenantId,
    actorUserId: uid,
    action: "order.updated",
    resourceType: "order",
    resourceId: order.id,
    metadata: { fields: Object.keys(patch).filter((key) => key !== "updatedAt") },
  });

  return ok({ orderId: order.id, fields: Object.keys(patch).filter((key) => key !== "updatedAt") });
};

export { loadOrder, isAssignedRider, stableHash, type OrderRecord };
