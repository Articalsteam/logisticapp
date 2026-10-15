/**
 * Firestore security-rules tests.
 *
 * Run with:  npm run test:rules
 * which wraps `vitest --project rules` in `firebase emulators:exec` (Firestore + Auth
 * + Storage). These tests prove the two things the specification insists on:
 *
 *   1. NO client can write business data — every mutation goes through a Cloud
 *      Function, so a modified APK cannot forge a price, a role or a payment.
 *   2. A tenant's data is unreadable from any other tenant, using the caller's own
 *      membership document rather than anything the client supplies.
 *
 * The emulator needs a JDK; see docs/SETUP.md if `npm run test:rules` cannot start.
 */

import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { afterAll, beforeAll, beforeEach, describe, it } from "vitest";
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
  type RulesTestEnvironment,
} from "@firebase/rules-unit-testing";
import { collection, doc, getDoc, getDocs, limit, query, setDoc, where } from "firebase/firestore";

let testEnv: RulesTestEnvironment;

const MX = "mx-logistics";
const OTHER = "other-logistics";
const OWNER = "uid-owner";
const MANAGER = "uid-manager";
const DISPATCHER = "uid-dispatcher";
const RIDER_A = "uid-rider-a";
const RIDER_B = "uid-rider-b";
const DISABLED = "uid-disabled";
const OTHER_OWNER = "uid-other-owner";
const PLATFORM_ADMIN = "uid-articals-admin";

function membership(tenantId: string, userId: string, extra: Record<string, unknown>) {
  return {
    id: `${tenantId}_${userId}`,
    data: { tenantId, userId, status: "active", createdAt: "2026-10-14T09:00:00.000Z", ...extra },
  };
}

const SEED: Array<{ path: string; data: Record<string, unknown> }> = [
  { path: `companies/${MX}`, data: { name: "MX Logistics", currency: "NGN", timezone: "Africa/Lagos" } },
  { path: `companies/${OTHER}`, data: { name: "Other Logistics", currency: "NGN", timezone: "Africa/Lagos" } },
  { path: `memberships/${MX}_${OWNER}`, data: membership(MX, OWNER, { role: "owner" }).data },
  { path: `memberships/${MX}_${MANAGER}`, data: membership(MX, MANAGER, { role: "manager" }).data },
  { path: `memberships/${MX}_${DISPATCHER}`, data: membership(MX, DISPATCHER, { role: "dispatcher" }).data },
  { path: `memberships/${MX}_${RIDER_A}`, data: membership(MX, RIDER_A, { role: "rider", riderId: "rider-1" }).data },
  { path: `memberships/${MX}_${RIDER_B}`, data: membership(MX, RIDER_B, { role: "rider", riderId: "rider-2" }).data },
  { path: `memberships/${MX}_${DISABLED}`, data: membership(MX, DISABLED, { role: "manager", status: "disabled" }).data },
  { path: `memberships/${OTHER}_${OTHER_OWNER}`, data: membership(OTHER, OTHER_OWNER, { role: "owner" }).data },

  { path: `riders/rider-1`, data: { tenantId: MX, userId: RIDER_A, name: "Musa", status: "active" } },
  { path: `riders/rider-2`, data: { tenantId: MX, userId: RIDER_B, name: "Chidi", status: "active" } },

  {
    path: "orders/order-a",
    data: { tenantId: MX, orderNumber: "MXL-20261014-0001", status: "assigned", assignedRiderId: "rider-1", customerChargeMinor: 196_000 },
  },
  {
    path: "orders/order-b",
    data: { tenantId: MX, orderNumber: "MXL-20261014-0002", status: "assigned", assignedRiderId: "rider-2", customerChargeMinor: 130_000 },
  },
  { path: "orders/order-other", data: { tenantId: OTHER, orderNumber: "OTH-20261014-0001", status: "pending" } },
  { path: "orders/order-a/deliveryEvents/ev-1", data: { tenantId: MX, status: "assigned", createdAt: "2026-10-14T09:05:00.000Z" } },
  { path: "orders/order-b/deliveryEvents/ev-1", data: { tenantId: MX, status: "assigned", createdAt: "2026-10-14T09:06:00.000Z" } },
  { path: "orders/order-other/deliveryEvents/ev-1", data: { tenantId: OTHER, status: "pending", createdAt: "2026-10-14T09:07:00.000Z" } },

  { path: "customers/cust-1", data: { tenantId: MX, name: "Grace Pharmacy" } },
  { path: "expenses/exp-1", data: { tenantId: MX, category: "fuel", amountMinor: 5_000 } },
  { path: "pricingConfigs/config-1", data: { tenantId: MX, name: "MX standard pricing", version: 2 } },
  { path: "quotes/quote-1", data: { tenantId: MX, recommendedCustomerChargeMinor: 196_000 } },
  { path: "companyDailyStats/2026-10-14_mx", data: { tenantId: MX, revenueMinor: 190_000, actualProfitMinor: 128_000 } },
  { path: `subscriptions/${MX}`, data: { tenantId: MX, status: "trialing", entitlement: "trialing" } },
  { path: `subscriptions/${MX}/private/billing`, data: { tenantId: MX, lastPaymentReference: "GTB/2026/000123", internalNotes: "Confirmed with Bola" } },
  { path: `subscriptions/${MX}/ledger/entry-1`, data: { tenantId: MX, type: "payment_recorded_offline", amountMinor: 1_500_000 } },
  { path: "auditLogs/log-1", data: { tenantId: MX, action: "subscription.bypass_granted", actorUserId: PLATFORM_ADMIN } },
  { path: "publicTracking/ART-ABCDEFGH12", data: { tenantId: MX, status: "in_transit" } },
  { path: "idempotencyKeys/key-1", data: { tenantId: MX, handler: "createOrder" } },
  { path: "counters/order-number-mx", data: { tenantId: MX, value: 3 } },
  { path: "rateLimits/tracking:caller:abc", data: { count: 5 } },
  { path: "notificationOutbox/entry-1", data: { tenantId: MX, status: "pending" } },
  { path: "invitations/inv-1", data: { tenantId: MX, email: "new@mx.test", status: "pending" } },
  { path: "riderInvitations/token-1", data: { tenantId: MX, riderId: "rider-1" } },
  { path: "platformConfig/plans", data: { plans: [{ id: "starter", priceMinor: 1_500_000 }] } },
  { path: "platformConfig/settings", data: { entitlementPolicy: { trialDays: 14 } } },
  { path: `users/${OWNER}`, data: { displayName: "Bola", email: "owner@mx.test" } },
];

beforeAll(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: "demo-articals-rules",
    firestore: {
      rules: readFileSync(resolve(__dirname, "../../../firestore.rules"), "utf8"),
      host: "127.0.0.1",
      port: Number(process.env["FIRESTORE_EMULATOR_PORT"] ?? 8080),
    },
  });
});

afterAll(async () => {
  await testEnv?.cleanup();
});

beforeEach(async () => {
  await testEnv.clearFirestore();
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    for (const entry of SEED) {
      await setDoc(doc(db, entry.path), entry.data);
    }
  });
});

const owner = () => testEnv.authenticatedContext(OWNER, { email: "owner@mx.test" }).firestore();
const manager = () => testEnv.authenticatedContext(MANAGER).firestore();
const dispatcher = () => testEnv.authenticatedContext(DISPATCHER).firestore();
const riderA = () => testEnv.authenticatedContext(RIDER_A).firestore();
const riderB = () => testEnv.authenticatedContext(RIDER_B).firestore();
const disabled = () => testEnv.authenticatedContext(DISABLED).firestore();
const otherOwner = () => testEnv.authenticatedContext(OTHER_OWNER).firestore();
const platformAdmin = () => testEnv.authenticatedContext(PLATFORM_ADMIN, { platformAdmin: true }).firestore();
const anonymous = () => testEnv.unauthenticatedContext().firestore();

describe("unauthenticated access", () => {
  it("reads nothing at all", async () => {
    await assertFails(getDoc(doc(anonymous(), `companies/${MX}`)));
    await assertFails(getDoc(doc(anonymous(), "orders/order-a")));
    await assertFails(getDoc(doc(anonymous(), "publicTracking/ART-ABCDEFGH12")));
    await assertFails(getDoc(doc(anonymous(), "platformConfig/plans")));
  });
});

describe("tenant isolation", () => {
  it("keeps a member of one company out of another company's documents", async () => {
    // The outsider is a legitimate owner — of a different company, and knows the ids.
    const db = otherOwner();
    await assertFails(getDoc(doc(db, `companies/${MX}`)));
    await assertFails(getDoc(doc(db, "orders/order-a")));
    await assertFails(getDoc(doc(db, "customers/cust-1")));
    await assertFails(getDoc(doc(db, "expenses/exp-1")));
    await assertFails(getDoc(doc(db, "pricingConfigs/config-1")));
    await assertFails(getDoc(doc(db, "companyDailyStats/2026-10-14_mx")));
    await assertFails(getDoc(doc(db, `subscriptions/${MX}`)));
    await assertFails(getDoc(doc(db, `memberships/${MX}_${OWNER}`)));
    await assertFails(getDoc(doc(db, "orders/order-a/deliveryEvents/ev-1")));
  });

  it("cannot list another tenant's orders even with a matching where clause", async () => {
    const db = otherOwner();
    await assertFails(getDocs(query(collection(db, "orders"), where("tenantId", "==", MX), limit(10))));
    // …but their own tenant's query works.
    await assertSucceeds(getDocs(query(collection(db, "orders"), where("tenantId", "==", OTHER), limit(10))));
  });
});

describe("staff reads", () => {
  it("lets an owner read their workspace, pricing, subscription and statistics", async () => {
    const db = owner();
    await assertSucceeds(getDoc(doc(db, `companies/${MX}`)));
    await assertSucceeds(getDoc(doc(db, "pricingConfigs/config-1")));
    await assertSucceeds(getDoc(doc(db, "companyDailyStats/2026-10-14_mx")));
    await assertSucceeds(getDoc(doc(db, `subscriptions/${MX}`)));
    await assertSucceeds(getDoc(doc(db, `memberships/${MX}_${DISPATCHER}`)));
    await assertSucceeds(getDocs(query(collection(db, "orders"), where("tenantId", "==", MX), limit(50))));
  });

  it("lets a dispatcher run deliveries but not see revenue or billing", async () => {
    const db = dispatcher();
    await assertSucceeds(getDoc(doc(db, "orders/order-a")));
    await assertSucceeds(getDoc(doc(db, "customers/cust-1")));
    await assertSucceeds(getDoc(doc(db, "pricingConfigs/config-1")));
    // Financial reporting is owner/manager only.
    await assertFails(getDoc(doc(db, "companyDailyStats/2026-10-14_mx")));
    // Billing is owner only.
    await assertFails(getDoc(doc(db, `subscriptions/${MX}`)));
    // The staff list is owner/manager only.
    await assertFails(getDoc(doc(db, `memberships/${MX}_${OWNER}`)));
  });

  it("honours per-member permission overrides", async () => {
    // A manager whose reports.view_financial permission was revoked cannot read stats…
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await setDoc(doc(context.firestore(), `memberships/${MX}_${MANAGER}`), {
        ...membership(MX, MANAGER, { role: "manager" }).data,
        revokedPermissions: ["reports.view_financial"],
      });
    });
    await assertFails(getDoc(doc(manager(), "companyDailyStats/2026-10-14_mx")));
    await assertSucceeds(getDoc(doc(manager(), "orders/order-a")));

    // …and one who was granted staff.view can read the staff list.
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await setDoc(doc(context.firestore(), `memberships/${MX}_${MANAGER}`), {
        ...membership(MX, MANAGER, { role: "manager" }).data,
        grantedPermissions: ["staff.view"],
      });
    });
    await assertSucceeds(getDoc(doc(manager(), `memberships/${MX}_${DISPATCHER}`)));
  });

  it("cuts off a disabled member immediately, however the document is queried", async () => {
    const db = disabled();
    await assertFails(getDoc(doc(db, `companies/${MX}`)));
    await assertFails(getDoc(doc(db, "orders/order-a")));
    await assertFails(getDoc(doc(db, "customers/cust-1")));
    await assertFails(getDoc(doc(db, `memberships/${MX}_${DISABLED}`)));
  });
});

describe("rider scoping", () => {
  it("lets a rider read the delivery assigned to them and its history", async () => {
    const db = riderA();
    await assertSucceeds(getDoc(doc(db, "orders/order-a")));
    await assertSucceeds(getDoc(doc(db, "orders/order-a/deliveryEvents/ev-1")));
    await assertSucceeds(getDoc(doc(db, "riders/rider-1")));
  });

  it("keeps a rider out of other riders' deliveries, customers, pricing and money", async () => {
    const db = riderA();
    await assertFails(getDoc(doc(db, "orders/order-b")));
    await assertFails(getDoc(doc(db, "orders/order-b/deliveryEvents/ev-1")));
    await assertFails(getDoc(doc(db, "riders/rider-2")));
    await assertFails(getDoc(doc(db, "customers/cust-1")));
    await assertFails(getDoc(doc(db, "pricingConfigs/config-1")));
    await assertFails(getDoc(doc(db, "expenses/exp-1")));
    await assertFails(getDoc(doc(db, "companyDailyStats/2026-10-14_mx")));
    await assertFails(getDoc(doc(db, `subscriptions/${MX}`)));
    await assertFails(getDoc(doc(db, `companies/${MX}`)));
  });

  it("cannot list the whole order book with a tenant-wide query", async () => {
    await assertFails(
      getDocs(query(collection(riderA(), "orders"), where("tenantId", "==", MX), limit(50))),
    );
    // Scoped to their own deliveries it is fine.
    await assertSucceeds(
      getDocs(
        query(
          collection(riderA(), "orders"),
          where("tenantId", "==", MX),
          where("assignedRiderId", "==", "rider-1"),
          limit(50),
        ),
      ),
    );
  });
});

describe("writes are never allowed from a client", () => {
  const attemptedWrites: Array<[string, () => Promise<unknown>]> = [
    [`companies/${MX}`, () => setDoc(doc(owner(), `companies/${MX}`), { name: "Hijacked", currency: "NGN", timezone: "Africa/Lagos" })],
    ["orders/order-a", () => setDoc(doc(owner(), "orders/order-a"), { tenantId: MX, status: "delivered", customerChargeMinor: 1 })],
    ["orders/order-new", () => setDoc(doc(dispatcher(), "orders/order-new"), { tenantId: MX, status: "pending" })],
    ["orders/order-a/deliveryEvents/forged", () => setDoc(doc(riderA(), "orders/order-a/deliveryEvents/forged"), { tenantId: MX, status: "delivered" })],
    [`memberships/${MX}_${OWNER}`, () => setDoc(doc(owner(), `memberships/${MX}_${OWNER}`), { tenantId: MX, userId: OWNER, role: "owner", status: "active" })],
    [`subscriptions/${MX}`, () => setDoc(doc(owner(), `subscriptions/${MX}`), { tenantId: MX, entitlement: "active_bypass", status: "active" })],
    [`subscriptions/${MX}/private/billing`, () => setDoc(doc(owner(), `subscriptions/${MX}/private/billing`), { tenantId: MX, internalNotes: "self-written" })],
    ["pricingConfigs/config-1", () => setDoc(doc(owner(), "pricingConfigs/config-1"), { tenantId: MX, baseChargeMinor: 0 })],
    ["companyDailyStats/2026-10-14_mx", () => setDoc(doc(owner(), "companyDailyStats/2026-10-14_mx"), { tenantId: MX, revenueMinor: 999_999_999 })],
    ["auditLogs/forged", () => setDoc(doc(owner(), "auditLogs/forged"), { tenantId: MX, action: "subscription.bypass_granted" })],
    ["users/" + OWNER, () => setDoc(doc(owner(), `users/${OWNER}`), { displayName: "Someone else" })],
    ["platformConfig/settings", () => setDoc(doc(platformAdmin(), "platformConfig/settings"), { entitlementPolicy: { trialDays: 3650 } })],
    ["counters/order-number-mx", () => setDoc(doc(owner(), "counters/order-number-mx"), { tenantId: MX, value: 9_999 })],
    ["publicTracking/ART-ABCDEFGH12", () => setDoc(doc(owner(), "publicTracking/ART-ABCDEFGH12"), { tenantId: MX, status: "delivered" })],
  ];

  for (const [path, attempt] of attemptedWrites) {
    it(`refuses a direct write to ${path}`, async () => {
      await assertFails(attempt());
    });
  }

  it("refuses an unauthenticated write", async () => {
    await assertFails(setDoc(doc(anonymous(), "orders/order-a"), { tenantId: MX, status: "delivered" }));
  });
});

describe("documents the client must never read", () => {
  const paths = [
    "auditLogs/log-1",
    "publicTracking/ART-ABCDEFGH12",
    "idempotencyKeys/key-1",
    "counters/order-number-mx",
    "rateLimits/tracking:caller:abc",
    "notificationOutbox/entry-1",
    "invitations/inv-1",
    "riderInvitations/token-1",
    `subscriptions/${MX}/private/billing`,
    `subscriptions/${MX}/ledger/entry-1`,
    "platformConfig/settings",
  ];

  for (const path of paths) {
    it(`${path} is closed to owners, riders, disabled members, platform admins and the public`, async () => {
      await assertFails(getDoc(doc(owner(), path)));
      await assertFails(getDoc(doc(manager(), path)));
      await assertFails(getDoc(doc(riderA(), path)));
      await assertFails(getDoc(doc(platformAdmin(), path)));
      await assertFails(getDoc(doc(anonymous(), path)));
    });
  }
});

describe("platform administrators", () => {
  it("may read the company register but not company delivery data", async () => {
    await assertSucceeds(getDoc(doc(platformAdmin(), `companies/${MX}`)));
    await assertSucceeds(getDoc(doc(platformAdmin(), `users/${OWNER}`)));
    // The administrator manages billing and onboarding; delivery records are none of
    // their business, and the rule reflects that.
    await assertFails(getDoc(doc(platformAdmin(), "orders/order-a")));
    await assertFails(getDoc(doc(platformAdmin(), "customers/cust-1")));
  });

  it("does not become an administrator through a document field", async () => {
    const forged = testEnv.authenticatedContext("uid-forger", { role: "admin", platformAdmin: "true" }).firestore();
    await assertFails(getDoc(doc(forged, `companies/${MX}`)));
  });

  it("is the only context that can read the plan catalogue — which is also public to members", async () => {
    await assertSucceeds(getDoc(doc(owner(), "platformConfig/plans")));
    await assertSucceeds(getDoc(doc(riderA(), "platformConfig/plans")));
  });
});

describe("public tracking is served by a function, never by a read", () => {
  it("denies every client-side query on the tracking index", async () => {
    const db = owner();
    await assertFails(getDocs(query(collection(db, "publicTracking"), limit(10))));
    await assertFails(getDocs(query(collection(db, "publicTracking"), where("tenantId", "==", MX), limit(10))));
  });
});
