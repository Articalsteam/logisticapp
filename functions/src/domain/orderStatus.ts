/**
 * Delivery status workflow — the authoritative transition table.
 *
 * Mirrored in Kotlin (`com.articals.logistics.domain.order.OrderStatus`) for UX:
 * the app disables actions it knows are invalid so dispatchers and riders get
 * immediate feedback, but the rules that actually take effect live here and are
 * enforced inside the `updateDeliveryStatus` callable.
 *
 * Any change to this table must be made in both places and is covered by tests
 * on both sides (see functions/src/domain/orderStatus.test.ts and
 * composeApp/src/commonTest/kotlin/.../OrderStatusTest.kt).
 */

export const ORDER_STATUSES = [
  "pending",
  "confirmed",
  "assigned",
  "picked_up",
  "in_transit",
  "delivered",
  "failed",
  "cancelled",
] as const;

export type OrderStatus = (typeof ORDER_STATUSES)[number];

/** Transitions permitted to ordinary operational actors (dispatcher, manager, owner). */
const STANDARD_TRANSITIONS: Record<OrderStatus, OrderStatus[]> = {
  pending: ["confirmed", "assigned", "cancelled"],
  confirmed: ["assigned", "cancelled"],
  assigned: ["picked_up", "failed", "cancelled"],
  picked_up: ["in_transit", "delivered", "failed"],
  in_transit: ["delivered", "failed"],
  delivered: [],
  failed: [],
  cancelled: [],
};

/**
 * Corrections are exceptional, explicitly authorized edits that fix mistakes
 * (for example an order marked delivered by mistake). They always require the
 * `orders.correct_status` permission and a written reason, and they are audited.
 */
const CORRECTION_TRANSITIONS: Record<OrderStatus, OrderStatus[]> = {
  pending: [],
  confirmed: ["pending"],
  assigned: ["confirmed", "pending"],
  // Correcting a delivery that was closed too early.
  delivered: ["in_transit", "picked_up", "failed"],
  failed: ["delivered", "in_transit", "cancelled"],
  cancelled: ["pending", "confirmed"],
  picked_up: ["assigned"],
  in_transit: [],
};

/** Transitions a rider may perform, and only on an order assigned to them. */
const RIDER_TRANSITIONS: Record<OrderStatus, OrderStatus[]> = {
  assigned: ["picked_up", "failed"],
  picked_up: ["in_transit", "delivered", "failed"],
  in_transit: ["delivered", "failed"],
  pending: [],
  confirmed: [],
  delivered: [],
  failed: [],
  cancelled: [],
};

export const TERMINAL_STATUSES: readonly OrderStatus[] = ["delivered", "failed", "cancelled"];

export type TransitionKind = "standard" | "correction" | "none";

export interface TransitionCheck {
  allowed: boolean;
  kind: TransitionKind;
  /** Machine-readable reason when `allowed` is false. */
  reason?: string;
  /** Human-readable explanation for the app. */
  message?: string;
}

export interface TransitionActor {
  /** True when the caller is acting as the assigned rider for this order. */
  isAssignedRider: boolean;
  /** True when the caller holds `orders.correct_status`. */
  canCorrectStatus: boolean;
}

export function isOrderStatus(value: unknown): value is OrderStatus {
  return typeof value === "string" && (ORDER_STATUSES as readonly string[]).includes(value);
}

/** Validates a status string from the network and returns it or throws. */
export function parseOrderStatus(value: unknown): OrderStatus {
  if (!isOrderStatus(value)) {
    throw new Error(`Unknown order status: ${String(value)}`);
  }
  return value;
}

/**
 * The single place that decides whether a status change is legal.
 *
 * Order of evaluation matters:
 *  1. same-status updates are rejected (they would add noise to the event log);
 *  2. riders are restricted to their own delivery flow before anything else, so a
 *     rider can never borrow a standard or correction transition;
 *  3. standard transitions are checked next;
 *  4. corrections require the extra permission and are only then considered.
 */
export function canTransition(from: OrderStatus, to: OrderStatus, actor: TransitionActor): TransitionCheck {
  if (from === to) {
    return {
      allowed: false,
      kind: "none",
      reason: "status_unchanged",
      message: "The delivery is already in that state.",
    };
  }

  if (actor.isAssignedRider) {
    const allowed = RIDER_TRANSITIONS[from].includes(to);
    return allowed
      ? { allowed: true, kind: "standard" }
      : {
          allowed: false,
          kind: "none",
          reason: "rider_transition_not_allowed",
          message: `A rider cannot move a delivery from ${labelFor(from)} to ${labelFor(to)}.`,
        };
  }

  if (STANDARD_TRANSITIONS[from].includes(to)) {
    return { allowed: true, kind: "standard" };
  }

  if (CORRECTION_TRANSITIONS[from].includes(to)) {
    if (!actor.canCorrectStatus) {
      return {
        allowed: false,
        kind: "correction",
        reason: "correction_not_permitted",
        message: `Correcting a delivery from ${labelFor(from)} to ${labelFor(to)} needs the correction permission.`,
      };
    }
    return { allowed: true, kind: "correction" };
  }

  return {
    allowed: false,
    kind: "none",
    reason: "transition_not_allowed",
    message: `A delivery cannot move from ${labelFor(from)} to ${labelFor(to)}.`,
  };
}

/** Statuses a given actor may move to from `from` (used to disable UI actions). */
export function allowedNextStatuses(from: OrderStatus, actor: TransitionActor): OrderStatus[] {
  return ORDER_STATUSES.filter((candidate) => canTransition(from, candidate, actor).allowed);
}

/** Statuses that count as money actually earned (used by reporting definitions). */
export function isRevenueBearing(status: OrderStatus): boolean {
  // Only a completed delivery is treated as realized revenue. Failed and
  // cancelled deliveries keep their history but are excluded from revenue unless
  // a cancellation/refund fee is explicitly recorded as a separate adjustment.
  return status === "delivered";
}

export function isOpen(status: OrderStatus): boolean {
  return !TERMINAL_STATUSES.includes(status);
}

/** Operator-facing copy. Never show a raw enum value in the interface. */
const LABELS: Record<OrderStatus, string> = {
  pending: "Awaiting confirmation",
  confirmed: "Confirmed",
  assigned: "Rider assigned",
  picked_up: "Picked up",
  in_transit: "In transit",
  delivered: "Delivered",
  failed: "Failed",
  cancelled: "Cancelled",
};

export function labelFor(status: OrderStatus): string {
  return LABELS[status];
}

/**
 * Which statuses a rider's device should surface as "today's work".
 * Kept here so the client and the backend agree on one list.
 */
export const ACTIVE_DELIVERY_STATUSES: readonly OrderStatus[] = ["assigned", "picked_up", "in_transit"];
