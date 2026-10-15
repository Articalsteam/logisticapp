/**
 * End-to-end workflow and authorization tests.
 *
 * These run against the same handlers that `src/index.ts` exposes as callables, with
 * an in-memory DataStore instead of Firestore and no routing provider configured
 * (which is itself the honest "no credentials yet" fallback). They cover the
 * acceptance scenario from the specification, step by step, plus the tenant-isolation
 * and role rules that must never regress.
 */

import { beforeEach, describe, expect, it } from "vitest";
import { createInMemoryDataStore } from "../../src/data/inMemoryDataStore";
import type { DataStore } from "../../src/data/types";
import { loadRuntimeConfig, type RuntimeConfig } from "../../src/config/runtimeConfig";
import { createDeps, type HandlerDeps } from "../../src/functions/deps";
import { HANDLERS } from "../../src/functions/registry";
import { runEntitlementSweep } from "../../src/functions/subscriptions";
import { createExportCompanyReport } from "../../src/functions/reports";
import { BackendError } from "../../src/shared/errors";
import type { CallContext } from "../../src/shared/auth";
import type { FileStoragePort, StoredFile } from "../../src/data/ports";

const START = Date.parse("2026-10-14T09:00:00.000Z");

let nowMs = START;
let store: DataStore;
let deps: HandlerDeps;

function testConfig(): RuntimeConfig {
  return loadRuntimeConfig({
    GCLOUD_PROJECT: "demo-articals",
    PUBLIC_TRACKING_BASE_URL: "https://track.articals.test",
    PUBLIC_MARKETING_URL: "https://articals.test",
    TRACKING_RATE_LIMIT_PER_WINDOW: "30",
    TRACKING_RATE_LIMIT_WINDOW_SECONDS: "600",
  } as NodeJS.ProcessEnv);
}

const ownerCtx: CallContext = { uid: "user-owner", claims: { email: "owner@mx.test", email_verified: true }, appCheckAppId: "app-1", ip: "10.0.0.1" };
const dispatcherCtx: CallContext = { uid: "user-dispatcher", claims: { email: "dispatch@mx.test", email_verified: true }, appCheckAppId: "app-1", ip: "10.0.0.2" };
const riderOneCtx: CallContext = { uid: "user-rider-1", claims: { email: "rider1@mx.test", email_verified: true }, appCheckAppId: "app-1", ip: "10.0.0.3" };
const riderTwoCtx: CallContext = { uid: "user-rider-2", claims: { email: "rider2@mx.test", email_verified: true }, appCheckAppId: "app-1", ip: "10.0.0.4" };
const outsiderCtx: CallContext = { uid: "user-outsider", claims: { email: "boss@other.test", email_verified: true }, appCheckAppId: "app-1", ip: "10.0.0.5" };
const adminCtx: CallContext = { uid: "user-admin", claims: { platformAdmin: true }, appCheckAppId: "app-1", ip: "10.0.0.6" };

const fakeStorage: FileStoragePort = {
  async put(path): Promise<StoredFile> {
    return { path, sizeBytes: 10, contentType: "text/csv" };
  },
  async signedUrl(path) {
    return `https://storage.test/${path}?sig=fake`;
  },
  async exists() {
    return true;
  },
};

beforeEach(() => {
  nowMs = START;
  store = createInMemoryDataStore({ now: () => new Date(nowMs) });
  deps = createDeps({ store, config: testConfig(), log: () => undefined });
});

/** Helper: unwrap the structured result envelope. */
function data<T>(result: { data: T }): T {
  return result.data;
}

async function expectBackendError(promise: Promise<unknown>, code: string): Promise<BackendError> {
  try {
    await promise;
  } catch (error) {
    expect(error).toBeInstanceOf(BackendError);
    const backend = error as BackendError;
    expect(backend.code).toBe(code);
    return backend;
  }
  throw new Error(`Expected a BackendError with code ${code}, but the call succeeded`);
}

interface SeedResult {
  tenantId: string;
  pricingConfigId: string;
  trackingReference: string;
  orderId: string;
  quotedChargeMinor: number;
  riderOneId: string;
  riderTwoId: string;
}

/** Creates a company with staff, riders, pricing and one quoted order. */
async function seedTenant(): Promise<SeedResult> {
  const created = data(
    await HANDLERS.createCompany(
      {
        idempotencyKey: "seed-company-key-1",
        companyName: "MX Logistics",
        contactPhone: "0803 000 0000",
        contactEmail: "owner@mx.test",
        businessAddress: "12 Allen Avenue, Ikeja, Lagos",
        serviceAreas: ["ikeja", "yaba", "lekki"],
        orderNumberPrefix: "MXL",
      },
      ownerCtx,
      deps,
    ),
  );
  const tenantId = created.tenantId;

  // Owner invites the dispatcher, who accepts through their own account.
  const invitation = data(
    await HANDLERS.inviteCompanyMember(
      { tenantId, idempotencyKey: "invite-dispatcher-key", email: "dispatch@mx.test", role: "dispatcher", displayName: "Ada" },
      ownerCtx,
      deps,
    ),
  );
  await HANDLERS.acceptInvitation({ invitationId: invitation.invitationId, displayName: "Ada" }, dispatcherCtx, deps);

  // Pricing rules for the company (₦700 base + ₦150/km, ₦800 minimum, 25% target margin).
  const pricing = data(
    await HANDLERS.updateCompanyPricing(
      {
        tenantId,
        settings: {
          name: "MX standard pricing",
          isActive: true,
          currency: "NGN",
          method: "base_plus_distance",
          baseChargeMinor: 70_000,
          perKmRateMinor: 15_000,
          distanceBands: [],
          zoneRules: [],
          fallbackToDistanceWhenZoneMissing: true,
          minimumChargeMinor: 80_000,
          maximumChargeMinor: null,
          roundingIncrementMinor: 0,
          roundingMode: "nearest",
          surcharges: [],
          riderCost: { method: "fixed", defaultFeeMinor: 50_000 },
          targetProfitAmountMinor: null,
          targetProfitMarginBps: 2_500,
        },
      },
      ownerCtx,
      deps,
    ),
  );

  const riderOne = data(
    await HANDLERS.createRider(
      { tenantId, idempotencyKey: "rider-one-key", name: "Musa", phone: "0803 111 1111", email: "rider1@mx.test" },
      ownerCtx,
      deps,
    ),
  );
  const invitationOne = data(
    await HANDLERS.createRiderInvitation({ tenantId, riderId: riderOne.id, email: "rider1@mx.test" }, ownerCtx, deps),
  );
  await HANDLERS.acceptRiderInvitation({ token: invitationOne.token }, riderOneCtx, deps);

  const riderTwo = data(
    await HANDLERS.createRider(
      { tenantId, idempotencyKey: "rider-two-key", name: "Chidi", phone: "0803 222 2222", email: "rider2@mx.test" },
      ownerCtx,
      deps,
    ),
  );
  const invitationTwo = data(
    await HANDLERS.createRiderInvitation({ tenantId, riderId: riderTwo.id, email: "rider2@mx.test" }, ownerCtx, deps),
  );
  await HANDLERS.acceptRiderInvitation({ token: invitationTwo.token }, riderTwoCtx, deps);

  const quote = data(
    await HANDLERS.calculateDeliveryQuote(
      {
        tenantId,
        pickup: { address: "12 Allen Avenue, Ikeja", zoneId: "ikeja", manualDistanceMetres: 8_400 },
        destination: { address: "5 Herbert Macaulay Way, Yaba", zoneId: "yaba" },
        serviceLevel: "standard",
        saveQuote: true,
      },
      dispatcherCtx,
      deps,
    ),
  );

  const order = data(
    await HANDLERS.createOrder(
      {
        tenantId,
        idempotencyKey: "order-key-1",
        pickup: { address: "12 Allen Avenue, Ikeja", zoneId: "ikeja", area: "Ikeja", manualDistanceMetres: 8_400 },
        destination: { address: "5 Herbert Macaulay Way, Yaba", zoneId: "yaba", area: "Yaba" },
        serviceLevel: "standard",
        description: "Two cartons of medicine",
        itemCategory: "medicine",
        customerSnapshot: { name: "Grace Pharmacy", phone: "0805 555 1234", customerType: "business" },
        acknowledgedWarningCodes: ["manual_distance"],
      },
      dispatcherCtx,
      deps,
    ),
  );

  return {
    tenantId,
    pricingConfigId: pricing.configId,
    trackingReference: order.trackingReference,
    orderId: order.orderId,
    quotedChargeMinor: order.customerChargeMinor,
    riderOneId: riderOne.id,
    riderTwoId: riderTwo.id,
  };
}

describe("acceptance workflow — from registration to reports", () => {
  let seed: SeedResult;

  beforeEach(async () => {
    seed = await seedTenant();
  });

  it("creates the company, membership, pricing and subscription on registration", async () => {
    const company = await store.get(`companies/${seed.tenantId}`);
    expect(company?.data["name"]).toBe("MX Logistics");
    expect(company?.data["currency"]).toBe("NGN");
    expect(company?.data["timezone"]).toBe("Africa/Lagos");

    const membership = await store.get(`memberships/${seed.tenantId}_user-owner`);
    expect(membership?.data).toMatchObject({ role: "owner", status: "active" });

    const subscription = await store.get(`subscriptions/${seed.tenantId}`);
    expect(subscription?.data).toMatchObject({ status: "trialing", entitlement: "trialing", billingMode: "trial" });

    const audit = await store.query("auditLogs", { where: [{ field: "action", op: "==", value: "company.created" }] });
    expect(audit).toHaveLength(1);
  });

  it("calculates the recommended charge and the profit breakdown", async () => {
    const quote = data(
      await HANDLERS.calculateDeliveryQuote(
        {
          tenantId: seed.tenantId,
          pickup: { address: "12 Allen Avenue, Ikeja", zoneId: "ikeja", manualDistanceMetres: 8_400 },
          destination: { address: "5 Herbert Macaulay Way, Yaba", zoneId: "yaba" },
          serviceLevel: "standard",
        },
        dispatcherCtx,
        deps,
      ),
    );
    // ₦700 base + (8.4 km × ₦150) = ₦1,960; rider ₦500 → ₦1,460 contribution (74.49%).
    expect(quote.quote.recommendedCustomerChargeMinor).toBe(196_000);
    expect(quote.quote.estimatedProfitMinor).toBe(146_000);
    expect(quote.quote.estimatedMarginBps).toBe(7_449);
    expect(quote.quote.distance.source).toBe("manual");
    expect(quote.quote.pricingSnapshot.pricingConfigVersion).toBe(2); // starter v1 → saved v2
  });

  it("refuses to create an order until the dispatcher acknowledges a manual distance", async () => {
    const base = {
      tenantId: seed.tenantId,
      idempotencyKey: "order-needs-ack",
      pickup: { address: "12 Allen Avenue, Ikeja", zoneId: "ikeja", manualDistanceMetres: 4_000 },
      destination: { address: "5 Herbert Macaulay Way, Yaba", zoneId: "yaba" },
      serviceLevel: "standard" as const,
      description: "Unacknowledged order",
      customerSnapshot: { name: "Walk-in sender", phone: "0805 000 1111" },
    };
    const error = await expectBackendError(HANDLERS.createOrder(base, dispatcherCtx, deps), "failed_precondition");
    expect(error.detail).toBe("quote.needs_review");
    expect(JSON.stringify(error.meta)).toContain("manual_distance");

    const accepted = data(
      await HANDLERS.createOrder({ ...base, acknowledgedWarningCodes: ["manual_distance"] }, dispatcherCtx, deps),
    );
    expect(accepted.orderId).toBeTruthy();
    expect(accepted.status).toBe("pending");
    expect(accepted.customerChargeMinor).toBe(130_000); // 700 + 4×150
  });

  it("replays a repeated create request instead of creating a second delivery", async () => {
    const again = data(
      await HANDLERS.createOrder(
        {
          tenantId: seed.tenantId,
          idempotencyKey: "order-key-1",
          pickup: { address: "12 Allen Avenue, Ikeja", zoneId: "ikeja", manualDistanceMetres: 8_400 },
          destination: { address: "5 Herbert Macaulay Way, Yaba", zoneId: "yaba" },
          serviceLevel: "standard",
          description: "Two cartons of medicine",
          customerSnapshot: { name: "Grace Pharmacy", phone: "0805 555 1234", customerType: "business" },
          acknowledgedWarningCodes: ["manual_distance"],
        },
        dispatcherCtx,
        deps,
      ),
    );
    expect(again.orderId).toBe(seed.orderId);
    const orders = await store.query("orders", { where: [{ field: "tenantId", op: "==", value: seed.tenantId }] });
    expect(orders).toHaveLength(1);
  });

  it("assigns a rider, notifies them, and lets only that rider progress the delivery", async () => {
    const assigned = data(
      await HANDLERS.assignRider({ tenantId: seed.tenantId, orderId: seed.orderId, riderId: seed.riderOneId }, dispatcherCtx, deps),
    );
    expect(assigned.status).toBe("assigned");

    const outbox = await store.query("notificationOutbox", {
      where: [{ field: "tenantId", op: "==", value: seed.tenantId }],
    });
    expect(outbox).toHaveLength(1);
    expect(outbox[0]?.data["recipientUserIds"]).toEqual(["user-rider-1"]);
    // Minimal payloads only: no addresses, names, phone numbers or money.
    expect(JSON.stringify(outbox[0]?.data["data"])).not.toContain("Herbert");

    // A different rider cannot touch this delivery.
    await expectBackendError(
      HANDLERS.updateDeliveryStatus(
        { tenantId: seed.tenantId, orderId: seed.orderId, status: "picked_up" },
        riderTwoCtx,
        deps,
      ),
      "permission_denied",
    );

    for (const status of ["picked_up", "in_transit", "delivered"] as const) {
      const result = data(
        await HANDLERS.updateDeliveryStatus({ tenantId: seed.tenantId, orderId: seed.orderId, status }, riderOneCtx, deps),
      );
      expect(result.status).toBe(status);
    }

    const events = await store.query(`orders/${seed.orderId}/deliveryEvents`, {
      orderBy: [{ field: "createdAt", direction: "asc" }],
    });
    expect(events.map((event) => event.data["status"])).toEqual(["pending", "assigned", "picked_up", "in_transit", "delivered"]);
  });

  it("blocks a rider from confirming, cancelling or touching money", async () => {
    await HANDLERS.assignRider({ tenantId: seed.tenantId, orderId: seed.orderId, riderId: seed.riderOneId }, dispatcherCtx, deps);

    await expectBackendError(
      HANDLERS.updateDeliveryStatus({ tenantId: seed.tenantId, orderId: seed.orderId, status: "cancelled" }, riderOneCtx, deps),
      "permission_denied",
    );
    await expectBackendError(
      HANDLERS.recordDeliveryActuals(
        { tenantId: seed.tenantId, orderId: seed.orderId, finalCustomerChargeMinor: 1_000, actualRiderCostMinor: 100 },
        riderOneCtx,
        deps,
      ),
      "permission_denied",
    );
  });

  it("records actuals and compares them with the estimate", async () => {
    await HANDLERS.assignRider({ tenantId: seed.tenantId, orderId: seed.orderId, riderId: seed.riderOneId }, dispatcherCtx, deps);
    for (const status of ["picked_up", "in_transit", "delivered"] as const) {
      await HANDLERS.updateDeliveryStatus({ tenantId: seed.tenantId, orderId: seed.orderId, status }, riderOneCtx, deps);
    }

    const actuals = data(
      await HANDLERS.recordDeliveryActuals(
        {
          tenantId: seed.tenantId,
          orderId: seed.orderId,
          finalCustomerChargeMinor: 190_000,
          actualRiderCostMinor: 60_000,
          actualAdditionalCosts: [{ id: "parking", label: "Parking", amountMinor: 2_000 }],
          notes: "Rider quoted more than the estimate",
        },
        dispatcherCtx,
        deps,
      ),
    );
    expect(actuals.actualProfitMinor).toBe(128_000);
    expect(actuals.estimatedProfitMinor).toBe(146_000);
    expect(actuals.varianceMinor).toBe(-18_000);
  });

  it("shows the owner consistent overview metrics after completion", async () => {
    await HANDLERS.assignRider({ tenantId: seed.tenantId, orderId: seed.orderId, riderId: seed.riderOneId }, dispatcherCtx, deps);
    for (const status of ["picked_up", "in_transit", "delivered"] as const) {
      await HANDLERS.updateDeliveryStatus({ tenantId: seed.tenantId, orderId: seed.orderId, status }, riderOneCtx, deps);
    }
    await HANDLERS.recordDeliveryActuals(
      {
        tenantId: seed.tenantId,
        orderId: seed.orderId,
        finalCustomerChargeMinor: 190_000,
        actualRiderCostMinor: 60_000,
        actualAdditionalCosts: [{ id: "parking", label: "Parking", amountMinor: 2_000 }],
      },
      dispatcherCtx,
      deps,
    );

    const metrics = data(
      await HANDLERS.getCompanyDashboardMetrics(
        { tenantId: seed.tenantId, fromDateKey: "2026-10-14", toDateKey: "2026-10-14" },
        ownerCtx,
        deps,
      ),
    );
    expect(metrics.counts.deliveriesToday).toBe(1);
    expect(metrics.counts.completed).toBe(1);
    expect(metrics.counts.ordersInRange).toBe(1);
    expect(metrics.financials?.revenueMinor).toBe(190_000);
    expect(metrics.financials?.riderPaymentsMinor).toBe(60_000);
    expect(metrics.financials?.actualProfitMinor).toBe(128_000);
    expect(metrics.financials?.deliveriesAwaitingFinalAmounts).toBe(0);

    // A dispatcher sees operations but not company profit.
    const dispatcherView = data(
      await HANDLERS.getCompanyDashboardMetrics(
        { tenantId: seed.tenantId, fromDateKey: "2026-10-14", toDateKey: "2026-10-14" },
        dispatcherCtx,
        deps,
      ),
    );
    expect(dispatcherView.financials).toBeNull();
  });

  it("exposes only approved information through public tracking", async () => {
    await HANDLERS.assignRider({ tenantId: seed.tenantId, orderId: seed.orderId, riderId: seed.riderOneId }, dispatcherCtx, deps);
    await HANDLERS.updateDeliveryStatus({ tenantId: seed.tenantId, orderId: seed.orderId, status: "picked_up" }, riderOneCtx, deps);

    const tracking = data(
      await HANDLERS.getPublicTracking({ reference: seed.trackingReference }, outsiderCtx, deps),
    );
    expect(tracking.status).toBe("picked_up");
    expect(tracking.pickupArea).toBe("Ikeja");
    expect(tracking.destinationArea).toBe("Yaba");
    expect(tracking.history.length).toBeGreaterThan(0);

    const serialized = JSON.stringify(tracking);
    expect(serialized).not.toContain("Allen Avenue");
    expect(serialized).not.toContain("Herbert Macaulay");
    expect(serialized).not.toContain("0805 555 1234");
    expect(serialized).not.toContain("Musa");
    expect(serialized).not.toContain("196000");
    expect(serialized).not.toContain("146000");
  });

  it("rejects an unknown tracking reference without revealing whether it exists", async () => {
    const error = await expectBackendError(
      HANDLERS.getPublicTracking({ reference: "ART-ZZZZZZZZZZ" }, outsiderCtx, deps),
      "not_found",
    );
    expect(error.detail).toBe("tracking_not_found");
  });

  it("rate limits tracking lookups", async () => {
    for (let index = 0; index < 30; index += 1) {
      await HANDLERS.getPublicTracking({ reference: seed.trackingReference }, outsiderCtx, deps);
    }
    await expectBackendError(HANDLERS.getPublicTracking({ reference: seed.trackingReference }, outsiderCtx, deps), "resource_exhausted");
  });

  it("requires the correction permission with a reason to fix a status", async () => {
    await HANDLERS.assignRider({ tenantId: seed.tenantId, orderId: seed.orderId, riderId: seed.riderOneId }, dispatcherCtx, deps);
    for (const status of ["picked_up", "in_transit", "delivered"] as const) {
      await HANDLERS.updateDeliveryStatus({ tenantId: seed.tenantId, orderId: seed.orderId, status }, riderOneCtx, deps);
    }

    // A dispatcher cannot correct (no orders.correct_status permission).
    await expectBackendError(
      HANDLERS.updateDeliveryStatus(
        { tenantId: seed.tenantId, orderId: seed.orderId, status: "in_transit", reason: "Marked delivered too early" },
        dispatcherCtx,
        deps,
      ),
      "permission_denied",
    );

    // The owner can, but only with a written reason.
    await expectBackendError(
      HANDLERS.updateDeliveryStatus({ tenantId: seed.tenantId, orderId: seed.orderId, status: "in_transit" }, ownerCtx, deps),
      "invalid_argument",
    );
    const corrected = data(
      await HANDLERS.updateDeliveryStatus(
        { tenantId: seed.tenantId, orderId: seed.orderId, status: "in_transit", reason: "Rider marked it delivered by mistake" },
        ownerCtx,
        deps,
      ),
    );
    expect(corrected.status).toBe("in_transit");

    const audit = await store.query("auditLogs", {
      where: [{ field: "action", op: "==", value: "order.status_corrected" }],
    });
    expect(audit).toHaveLength(1);
  });

  it("keeps tenants isolated even when the caller knows a document id", async () => {
    const other = data(
      await HANDLERS.createCompany(
        {
          idempotencyKey: "other-company-key-1",
          companyName: "Other Logistics",
          contactPhone: "0807 000 0000",
          contactEmail: "boss@other.test",
        },
        outsiderCtx,
        deps,
      ),
    );

    // The outsider is a legitimate owner — of a different company.
    const error = await expectBackendError(
      HANDLERS.updateDeliveryStatus(
        { tenantId: other.tenantId, orderId: seed.orderId, status: "confirmed" },
        outsiderCtx,
        deps,
      ),
      "not_found",
    );
    expect(error.detail).toBe("order_missing");

    // And cannot read the first company's customers or pricing either.
    await expectBackendError(
      HANDLERS.updateCustomer({ tenantId: other.tenantId, customerId: "whatever", name: "Hacked" }, outsiderCtx, deps),
      "not_found",
    );

    // Using the victim tenant id is refused outright: not a member.
    await expectBackendError(
      HANDLERS.updateCompanySettings({ tenantId: seed.tenantId, companyName: "Hijacked" }, outsiderCtx, deps),
      "not_a_member",
    );
  });

  it("stops members from promoting themselves and dispatchers from changing pricing", async () => {
    await expectBackendError(
      HANDLERS.updateMemberRole({ tenantId: seed.tenantId, userId: "user-dispatcher", role: "owner" }, dispatcherCtx, deps),
      "permission_denied",
    );
    await expectBackendError(
      HANDLERS.updateMemberRole({ tenantId: seed.tenantId, userId: "user-owner", role: "owner" }, ownerCtx, deps),
      "permission_denied",
    );
    await expectBackendError(
      HANDLERS.updateCompanyPricing({ tenantId: seed.tenantId, settings: { name: "Hacked" } }, dispatcherCtx, deps),
      "permission_denied",
    );
  });

  it("exports a CSV report server-side", async () => {
    await HANDLERS.assignRider({ tenantId: seed.tenantId, orderId: seed.orderId, riderId: seed.riderOneId }, dispatcherCtx, deps);
    const handler = createExportCompanyReport(fakeStorage);
    const exported = data(
      await handler(
        { tenantId: seed.tenantId, reportType: "orders", fromDateKey: "2026-10-14", toDateKey: "2026-10-14" },
        ownerCtx,
        deps,
      ),
    );
    expect(exported.rowCount).toBe(1);
    expect(exported.fileName).toContain("orders");
    expect(exported.storagePath).toContain(`tenants/${seed.tenantId}/reports/`);

    // A rider cannot export company data.
    await expectBackendError(
      handler({ tenantId: seed.tenantId, reportType: "orders", fromDateKey: "2026-10-14", toDateKey: "2026-10-14" }, riderOneCtx, deps),
      "permission_denied",
    );
  });
});

describe("billing, offline payments and bypasses", () => {
  let seed: SeedResult;

  beforeEach(async () => {
    seed = await seedTenant();
  });

  it("lets a platform administrator record an offline payment", async () => {
    const payment = data(
      await HANDLERS.recordOfflinePayment(
        {
          tenantId: seed.tenantId,
          idempotencyKey: "offline-payment-key-1",
          amountMinor: 1_500_000,
          currency: "NGN",
          method: "bank_transfer",
          externalReference: "GTB/2026/000123",
          receivedAt: "2026-10-14T08:00:00.000Z",
          periodStart: "2026-10-14T00:00:00.000Z",
          periodEnd: "2026-11-13T00:00:00.000Z",
          reason: "Bank transfer received and confirmed on the company statement",
          internalNotes: "Confirmed with Bola on WhatsApp — receipt in the shared drive",
          planId: "starter",
        },
        adminCtx,
        deps,
      ),
    );
    expect(payment.entitlement).toBe("active_offline");

    const subscription = await store.get(`subscriptions/${seed.tenantId}`);
    expect(subscription?.data).toMatchObject({ billingMode: "offline", status: "active", entitlement: "active_offline" });
    // Internal notes and the external reference must not live on the company-visible document.
    expect(JSON.stringify(subscription?.data)).not.toContain("GTB/2026/000123");
    expect(JSON.stringify(subscription?.data)).not.toContain("shared drive");

    const privateBilling = await store.get(`subscriptions/${seed.tenantId}/private/billing`);
    expect(privateBilling?.data["lastPaymentReference"]).toBe("GTB/2026/000123");

    const ledger = await store.query(`subscriptions/${seed.tenantId}/ledger`);
    expect(ledger).toHaveLength(1);
    expect(ledger[0]?.data).toMatchObject({ type: "payment_recorded_offline", actorUserId: "user-admin" });

    const audit = await store.query("auditLogs", {
      where: [{ field: "action", op: "==", value: "subscription.offline_payment_recorded" }],
    });
    expect(audit).toHaveLength(1);

    // The owner sees status, dates and honest labelling — never internal notes.
    const view = data(await HANDLERS.getMySubscription({ tenantId: seed.tenantId }, ownerCtx, deps));
    expect(view.entitlement).toBe("active_offline");
    expect(view.billingNotice).toBe("Recorded offline by Articals Studio");
    expect(view.daysRemaining).toBe(30);
    expect(JSON.stringify(view)).not.toContain("shared drive");
  });

  it("refuses offline payments and bypasses from company users, including the owner", async () => {
    await expectBackendError(
      HANDLERS.recordOfflinePayment(
        {
          tenantId: seed.tenantId,
          idempotencyKey: "self-payment-attempt",
          amountMinor: 1_000_000,
          currency: "NGN",
          method: "cash",
          externalReference: "self",
          receivedAt: "2026-10-14T08:00:00.000Z",
          periodStart: "2026-10-14T00:00:00.000Z",
          periodEnd: "2026-11-13T00:00:00.000Z",
          reason: "I paid myself",
        },
        ownerCtx,
        deps,
      ),
      "permission_denied",
    );

    await expectBackendError(
      HANDLERS.setBillingBypass(
        {
          tenantId: seed.tenantId,
          idempotencyKey: "self-bypass-attempt",
          reason: "complimentary_pilot",
          until: "2026-12-31T00:00:00.000Z",
        },
        ownerCtx,
        deps,
      ),
      "permission_denied",
    );

    // A forged claim in the request body is not a custom claim and changes nothing.
    const forger: CallContext = { uid: "user-forger", claims: { email: "forger@mx.test", email_verified: true }, appCheckAppId: null, ip: "10.0.0.9" };
    await expectBackendError(
      HANDLERS.setBillingBypass(
        {
          tenantId: seed.tenantId,
          idempotencyKey: "forged-bypass-attempt",
          reason: "partner_access",
          until: "2026-12-31T00:00:00.000Z",
        },
        forger,
        deps,
      ),
      "permission_denied",
    );
  });

  it("grants a time-limited bypass, enforces the maximum duration, and audits it", async () => {
    const shortBypass = data(
      await HANDLERS.setBillingBypass(
        {
          tenantId: seed.tenantId,
          idempotencyKey: "bypass-short-key",
          reason: "complimentary_pilot",
          until: "2026-11-30T00:00:00.000Z",
          internalNotes: "Pilot extension agreed with the founder",
        },
        adminCtx,
        deps,
      ),
    );
    expect(shortBypass.entitlement).toBe("active_bypass");
    expect(shortBypass.durationDays).toBeLessThanOrEqual(90);

    const ledger = await store.query(`subscriptions/${seed.tenantId}/ledger`);
    expect(ledger.some((entry) => entry.data["type"] === "bypass_granted")).toBe(true);

    // A 200-day bypass needs the elevated permission.
    const error = await expectBackendError(
      HANDLERS.setBillingBypass(
        {
          tenantId: seed.tenantId,
          idempotencyKey: "bypass-long-key",
          reason: "custom_commercial_terms",
          until: "2027-06-01T00:00:00.000Z",
        },
        adminCtx,
        deps,
      ),
      "permission_denied",
    );
    expect(error.detail).toBe("subscription.bypass_too_long");

    const elevatedCtx: CallContext = { ...adminCtx, claims: { platformAdmin: true, platformAdminElevated: true } };
    const longBypass = data(
      await HANDLERS.setBillingBypass(
        {
          tenantId: seed.tenantId,
          idempotencyKey: "bypass-long-key-2",
          reason: "custom_commercial_terms",
          until: "2027-06-01T00:00:00.000Z",
        },
        elevatedCtx,
        deps,
      ),
    );
    expect(longBypass.entitlement).toBe("active_bypass");
  });

  it("expires a bypass on schedule, writes a ledger entry and notifies", async () => {
    await HANDLERS.setBillingBypass(
      {
        tenantId: seed.tenantId,
        idempotencyKey: "bypass-expiry-key",
        reason: "complimentary_pilot",
        until: "2026-11-01T00:00:00.000Z",
      },
      adminCtx,
      deps,
    );

    // Move time past the bypass and past the trial + grace window.
    nowMs = Date.parse("2026-12-20T09:00:00.000Z");
    const summary = await runEntitlementSweep(deps, ["user-admin"]);
    expect(summary.scanned).toBeGreaterThan(0);

    const subscription = await store.get(`subscriptions/${seed.tenantId}`);
    expect(subscription?.data["entitlement"]).toBe("limited");

    const ledger = await store.query(`subscriptions/${seed.tenantId}/ledger`);
    expect(ledger.some((entry) => entry.data["type"] === "entitlement_changed")).toBe(true);

    // The company keeps read and export access; only writes are blocked.
    const view = data(await HANDLERS.getMySubscription({ tenantId: seed.tenantId }, ownerCtx, deps));
    expect(view.entitlement).toBe("limited");
    expect(view.writesAllowed).toBe(false);

    await expectBackendError(
      HANDLERS.createExpense(
        {
          tenantId: seed.tenantId,
          idempotencyKey: "expense-after-expiry",
          category: "fuel",
          amountMinor: 5_000,
          expenseDate: "2026-12-20",
        },
        ownerCtx,
        deps,
      ),
      "entitlement_limited",
    );
  });

  it("lets suspension override an active bypass", async () => {
    await HANDLERS.setBillingBypass(
      {
        tenantId: seed.tenantId,
        idempotencyKey: "bypass-then-suspend",
        reason: "partner_access",
        until: "2026-12-31T00:00:00.000Z",
      },
      adminCtx,
      deps,
    );
    const suspended = data(
      await HANDLERS.manageSubscription(
        { tenantId: seed.tenantId, action: "suspend", reason: "Abuse investigation opened" },
        adminCtx,
        deps,
      ),
    );
    expect(suspended.entitlement).toBe("suspended");

    const view = data(await HANDLERS.getMySubscription({ tenantId: seed.tenantId }, ownerCtx, deps));
    expect(view.entitlement).toBe("suspended");
    expect(view.writesAllowed).toBe(false);
  });

  it("revokes a bypass with a reason and records who did it", async () => {
    await HANDLERS.setBillingBypass(
      {
        tenantId: seed.tenantId,
        idempotencyKey: "bypass-to-revoke",
        reason: "support_goodwill",
        until: "2026-11-15T00:00:00.000Z",
      },
      adminCtx,
      deps,
    );
    const revoked = data(
      await HANDLERS.revokeBillingBypass(
        { tenantId: seed.tenantId, reason: "Payment received by bank transfer", suspendAccount: false },
        adminCtx,
        deps,
      ),
    );
    expect(revoked.entitlement).toBe("trialing"); // still inside the trial window

    const ledger = await store.query(`subscriptions/${seed.tenantId}/ledger`);
    expect(ledger.some((entry) => entry.data["type"] === "bypass_revoked")).toBe(true);
    const subscription = await store.get(`subscriptions/${seed.tenantId}`);
    expect((subscription?.data["bypass"] as Record<string, unknown>)["active"]).toBe(false);
  });

  it("lists companies and platform revenue categories for the administrator", async () => {
    await HANDLERS.recordOfflinePayment(
      {
        tenantId: seed.tenantId,
        idempotencyKey: "offline-for-overview",
        amountMinor: 1_500_000,
        currency: "NGN",
        method: "bank_transfer",
        externalReference: "REF-1",
        receivedAt: "2026-10-14T08:00:00.000Z",
        periodStart: "2026-10-14T00:00:00.000Z",
        periodEnd: "2026-11-13T00:00:00.000Z",
        reason: "Recorded from the company's transfer confirmation",
      },
      adminCtx,
      deps,
    );

    const overview = data(await HANDLERS.getPlatformOverview({}, adminCtx, deps));
    expect(overview.companies.total).toBe(1);
    expect(overview.companies.activeOffline).toBe(1);
    expect(overview.revenue.offline).toBe(1);
    expect(overview.revenue.nonRevenue).toBe(0);

    const list = data(await HANDLERS.listCompanies({}, adminCtx, deps));
    expect(list.companies[0]?.name).toBe("MX Logistics");
    expect(list.companies[0]?.entitlement).toBe("active_offline");

    // Company users get nothing from the platform surface.
    await expectBackendError(HANDLERS.getPlatformOverview({}, ownerCtx, deps), "permission_denied");
    await expectBackendError(HANDLERS.listCompanies({}, ownerCtx, deps), "permission_denied");
  });
});

describe("expenses", () => {
  it("counts delivery expenses against the delivery, not the company total", async () => {
    const seed = await seedTenant();
    const deliveryExpense = data(
      await HANDLERS.createExpense(
        {
          tenantId: seed.tenantId,
          idempotencyKey: "expense-delivery-1",
          category: "waiting_fee",
          amountMinor: 3_000,
          expenseDate: "2026-10-14",
          orderId: seed.orderId,
          label: "Waited 40 minutes at pickup",
        },
        dispatcherCtx,
        deps,
      ),
    );
    expect(deliveryExpense.kind).toBe("delivery");

    const operatingExpense = data(
      await HANDLERS.createExpense(
        {
          tenantId: seed.tenantId,
          idempotencyKey: "expense-operating-1",
          category: "airtime",
          amountMinor: 7_000,
          expenseDate: "2026-10-14",
          label: "Dispatch line airtime",
        },
        ownerCtx,
        deps,
      ),
    );
    expect(operatingExpense.kind).toBe("operating");
  });
});
