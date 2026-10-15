/**
 * Delivery status-transition tests (spec §22: "valid and invalid status transitions").
 *
 * The state machine lives in one place so that the rider app, the dispatcher app and
 * the backend agree. These tests pin the legal flows for each actor and the illegal
 * ones that must never be accepted — especially anything that would let a delivery
 * be closed without evidence, or reopened without an audited correction.
 */

import { describe, expect, it } from "vitest";
import {
  ACTIVE_DELIVERY_STATUSES,
  allowedNextStatuses,
  canTransition,
  isOpen,
  isOrderStatus,
  isRevenueBearing,
  labelFor,
  ORDER_STATUSES,
  parseOrderStatus,
  TERMINAL_STATUSES,
  type OrderStatus,
  type TransitionActor,
} from "./orderStatus";

const dispatcher: TransitionActor = { isAssignedRider: false, canCorrectStatus: false };
const owner: TransitionActor = { isAssignedRider: false, canCorrectStatus: true };
const rider: TransitionActor = { isAssignedRider: true, canCorrectStatus: false };
// A rider who somehow also holds the correction permission is still a rider.
const riderWithCorrection: TransitionActor = { isAssignedRider: true, canCorrectStatus: true };

describe("the happy path", () => {
  it("walks pending → confirmed → assigned → picked_up → in_transit → delivered", () => {
    const path: OrderStatus[] = ["pending", "confirmed", "assigned", "picked_up", "in_transit", "delivered"];
    for (let index = 0; index < path.length - 1; index += 1) {
      const from = path[index] as OrderStatus;
      const to = path[index + 1] as OrderStatus;
      const check = canTransition(from, to, dispatcher);
      expect(check.allowed, `${from} → ${to}`).toBe(true);
      expect(check.kind).toBe("standard");
    }
  });

  it("lets a dispatch team skip confirmation and assign straight away", () => {
    expect(canTransition("pending", "assigned", dispatcher).allowed).toBe(true);
  });

  it("lets the assigned rider move their own delivery forward", () => {
    expect(canTransition("assigned", "picked_up", rider).allowed).toBe(true);
    expect(canTransition("picked_up", "in_transit", rider).allowed).toBe(true);
    expect(canTransition("picked_up", "delivered", rider).allowed).toBe(true);
    expect(canTransition("in_transit", "delivered", rider).allowed).toBe(true);
  });
});

describe("states that cannot move", () => {
  it("treats delivered, failed and cancelled as terminal for standard operations", () => {
    for (const status of TERMINAL_STATUSES) {
      expect(allowedNextStatuses(status, dispatcher)).toEqual([]);
      for (const candidate of ORDER_STATUSES) {
        if (candidate === status) continue;
        expect(canTransition(status, candidate, dispatcher).allowed, `${status} → ${candidate}`).toBe(false);
      }
    }
  });

  it("refuses a no-op update, which would only add noise to the event log", () => {
    for (const status of ORDER_STATUSES) {
      const check = canTransition(status, status, dispatcher);
      expect(check.allowed).toBe(false);
      expect(check.reason).toBe("status_unchanged");
    }
  });

  it("refuses to invent states", () => {
    expect(isOrderStatus("out_for_delivery")).toBe(false);
    expect(isOrderStatus(null)).toBe(false);
    expect(() => parseOrderStatus("teleported")).toThrow(/Unknown order status/);
    expect(parseOrderStatus("picked_up")).toBe("picked_up");
  });
});

describe("illegal jumps an operations user might attempt", () => {
  const illegal: Array<[OrderStatus, OrderStatus]> = [
    ["pending", "picked_up"],
    ["pending", "in_transit"],
    ["pending", "delivered"],
    ["confirmed", "delivered"],
    ["assigned", "delivered"], // a delivery cannot be delivered without being picked up
    ["assigned", "in_transit"], // …or be in transit before pickup
    ["in_transit", "picked_up"],
  ];

  for (const [from, to] of illegal) {
    it(`rejects ${from} → ${to} without the correction permission`, () => {
      const check = canTransition(from, to, dispatcher);
      expect(check.allowed).toBe(false);
      expect(check.reason).toBe("transition_not_allowed");
      expect(check.message).toBeTruthy();
    });
  }
});

describe("corrections", () => {
  it("reopens a delivery marked delivered by mistake", () => {
    const check = canTransition("delivered", "in_transit", owner);
    expect(check.allowed).toBe(true);
    expect(check.kind).toBe("correction");
  });

  it("is refused for an actor without the correction permission, with a reason that names the permission", () => {
    const check = canTransition("delivered", "in_transit", dispatcher);
    expect(check.allowed).toBe(false);
    expect(check.reason).toBe("correction_not_permitted");
    expect(check.kind).toBe("correction");
  });

  it("does not let a rider borrow the correction flow", () => {
    const unassign = canTransition("picked_up", "assigned", rider);
    expect(unassign.allowed).toBe(false);
    expect(unassign.reason).toBe("rider_transition_not_allowed");

    // Even with the permission (which the backend would never grant a rider),
    // the rider flow is evaluated first.
    const withPermission = canTransition("picked_up", "assigned", riderWithCorrection);
    expect(withPermission.allowed).toBe(false);
    expect(withPermission.reason).toBe("rider_transition_not_allowed");
  });

  it("reopens a cancelled delivery back to pending when authorised, and names the missing permission otherwise", () => {
    expect(canTransition("cancelled", "pending", owner).allowed).toBe(true);
    const refused = canTransition("cancelled", "pending", dispatcher);
    expect(refused.allowed).toBe(false);
    expect(refused.reason).toBe("correction_not_permitted");
  });

  it("does not let an operations user close a delivery that is already closed without a correction", () => {
    const check = canTransition("delivered", "in_transit", dispatcher);
    expect(check.allowed).toBe(false);
    expect(check.reason).toBe("correction_not_permitted");
  });
});

describe("rider restrictions", () => {
  it("cannot confirm, cancel, or handle an order that is not assigned", () => {
    expect(canTransition("pending", "confirmed", rider).allowed).toBe(false);
    expect(canTransition("confirmed", "assigned", rider).allowed).toBe(false);
    expect(canTransition("assigned", "cancelled", rider).allowed).toBe(false);
    expect(canTransition("pending", "cancelled", rider).allowed).toBe(false);
  });

  it("cannot move a terminal delivery", () => {
    expect(canTransition("delivered", "in_transit", rider).allowed).toBe(false);
    expect(canTransition("cancelled", "pending", rider).allowed).toBe(false);
  });

  it("may report a failure from the road, with a reason validated by the handler", () => {
    expect(canTransition("assigned", "failed", rider).allowed).toBe(true);
    expect(canTransition("picked_up", "failed", rider).allowed).toBe(true);
    expect(canTransition("in_transit", "failed", rider).allowed).toBe(true);
  });

  it("offers the rider only their own transitions in the interface", () => {
    expect(allowedNextStatuses("assigned", rider)).toEqual(["picked_up", "failed"]);
    expect(allowedNextStatuses("in_transit", rider)).toEqual(["delivered", "failed"]);
    expect(allowedNextStatuses("delivered", rider)).toEqual([]);
  });
});

describe("what each status means to reporting", () => {
  it("counts delivered as the only revenue-bearing status", () => {
    expect(isRevenueBearing("delivered")).toBe(true);
    expect(isRevenueBearing("failed")).toBe(false);
    expect(isRevenueBearing("cancelled")).toBe(false);
    expect(isRevenueBearing("in_transit")).toBe(false);
  });

  it("knows which deliveries are still open", () => {
    expect(isOpen("pending")).toBe(true);
    expect(isOpen("in_transit")).toBe(true);
    expect(isOpen("delivered")).toBe(false);
    expect(isOpen("cancelled")).toBe(false);
  });

  it("calls an in-flight delivery active but not a pending one", () => {
    expect([...ACTIVE_DELIVERY_STATUSES]).toEqual(["assigned", "picked_up", "in_transit"]);
  });

  it("has an operator-facing label for every status", () => {
    for (const status of ORDER_STATUSES) {
      expect(labelFor(status)).toBeTruthy();
      expect(labelFor(status)).not.toBe(status); // never a raw enum value
    }
    expect(labelFor("in_transit")).toBe("In transit");
    expect(labelFor("pending")).toBe("Awaiting confirmation");
  });
});
