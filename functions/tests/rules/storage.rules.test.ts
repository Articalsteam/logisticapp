/**
 * Cloud Storage security-rules tests.
 *
 * Run with:  npm run test:rules
 *
 * Proof-of-delivery photos are the one kind of business data the app uploads
 * directly, so these rules carry real weight: a rider may add evidence only to a
 * delivery assigned to them, an upload must be an image within the size cap, and
 * evidence can never be overwritten or deleted afterwards.
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
import { doc, setDoc } from "firebase/firestore";
import { deleteObject, getBytes, ref, uploadBytes } from "firebase/storage";

let testEnv: RulesTestEnvironment;

const MX = "mx-logistics";
const OTHER = "other-logistics";
const OWNER = "uid-owner";
const DISPATCHER = "uid-dispatcher";
const RIDER_A = "uid-rider-a";
const RIDER_B = "uid-rider-b";
const OTHER_OWNER = "uid-other-owner";
const PLATFORM_ADMIN = "uid-articals-admin";

const IMAGE = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46]);
const SMALL_PDF = new Uint8Array([0x25, 0x50, 0x44, 0x46]);
const BIG_IMAGE = new Uint8Array(5 * 1024 * 1024 + 1);

const SEED = [
  { path: `memberships/${MX}_${OWNER}`, data: { tenantId: MX, userId: OWNER, role: "owner", status: "active" } },
  { path: `memberships/${MX}_${DISPATCHER}`, data: { tenantId: MX, userId: DISPATCHER, role: "dispatcher", status: "active" } },
  { path: `memberships/${MX}_${RIDER_A}`, data: { tenantId: MX, userId: RIDER_A, role: "rider", riderId: "rider-1", status: "active" } },
  { path: `memberships/${MX}_${RIDER_B}`, data: { tenantId: MX, userId: RIDER_B, role: "rider", riderId: "rider-2", status: "active" } },
  { path: `memberships/${OTHER}_${OTHER_OWNER}`, data: { tenantId: OTHER, userId: OTHER_OWNER, role: "owner", status: "active" } },
  { path: "orders/order-a", data: { tenantId: MX, status: "in_transit", assignedRiderId: "rider-1" } },
  { path: "orders/order-b", data: { tenantId: MX, status: "in_transit", assignedRiderId: "rider-2" } },
];

beforeAll(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: "demo-articals-rules",
    firestore: {
      rules: readFileSync(resolve(__dirname, "../../../firestore.rules"), "utf8"),
      host: "127.0.0.1",
      port: Number(process.env["FIRESTORE_EMULATOR_PORT"] ?? 8080),
    },
    storage: {
      rules: readFileSync(resolve(__dirname, "../../../storage.rules"), "utf8"),
      host: "127.0.0.1",
      port: Number(process.env["STORAGE_EMULATOR_PORT"] ?? 9199),
    },
  });
});

afterAll(async () => {
  await testEnv?.cleanup();
});

beforeEach(async () => {
  await testEnv.clearFirestore();
  await testEnv.clearStorage();
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    for (const entry of SEED) {
      await setDoc(doc(db, entry.path), entry.data);
    }
  });
});

const owner = () => testEnv.authenticatedContext(OWNER).storage();
const dispatcher = () => testEnv.authenticatedContext(DISPATCHER).storage();
const riderA = () => testEnv.authenticatedContext(RIDER_A).storage();
const riderB = () => testEnv.authenticatedContext(RIDER_B).storage();
const otherOwner = () => testEnv.authenticatedContext(OTHER_OWNER).storage();
const platformAdmin = () => testEnv.authenticatedContext(PLATFORM_ADMIN, { platformAdmin: true }).storage();
const anonymous = () => testEnv.unauthenticatedContext().storage();

describe("proof of delivery", () => {
  it("lets the assigned rider upload a photo", async () => {
    await assertSucceeds(
      uploadBytes(ref(riderA(), `tenants/${MX}/proof/order-a/delivered.jpg`), IMAGE, { contentType: "image/jpeg" }),
    );
  });

  it("refuses a rider uploading evidence for someone else's delivery", async () => {
    await assertFails(
      uploadBytes(ref(riderA(), `tenants/${MX}/proof/order-b/delivered.jpg`), IMAGE, { contentType: "image/jpeg" }),
    );
    await assertFails(
      uploadBytes(ref(riderB(), `tenants/${MX}/proof/order-a/delivered.jpg`), IMAGE, { contentType: "image/jpeg" }),
    );
  });

  it("refuses a rider from another company entirely", async () => {
    await assertFails(
      uploadBytes(ref(otherOwner(), `tenants/${MX}/proof/order-a/delivered.jpg`), IMAGE, { contentType: "image/jpeg" }),
    );
  });

  it("allows only images inside the size cap", async () => {
    await assertFails(
      uploadBytes(ref(riderA(), `tenants/${MX}/proof/order-a/evidence.pdf`), SMALL_PDF, { contentType: "application/pdf" }),
    );
    await assertFails(
      uploadBytes(ref(riderA(), `tenants/${MX}/proof/order-a/huge.jpg`), BIG_IMAGE, { contentType: "image/jpeg" }),
    );
  });

  it("lets staff read the evidence but not a competing company", async () => {
    await assertSucceeds(
      uploadBytes(ref(riderA(), `tenants/${MX}/proof/order-a/delivered.jpg`), IMAGE, { contentType: "image/jpeg" }),
    );
    await assertSucceeds(getBytes(ref(owner(), `tenants/${MX}/proof/order-a/delivered.jpg`)));
    await assertSucceeds(getBytes(ref(dispatcher(), `tenants/${MX}/proof/order-a/delivered.jpg`)));
    await assertFails(getBytes(ref(otherOwner(), `tenants/${MX}/proof/order-a/delivered.jpg`)));
    await assertFails(getBytes(ref(anonymous(), `tenants/${MX}/proof/order-a/delivered.jpg`)));
  });

  it("never allows evidence to be replaced or deleted", async () => {
    await assertSucceeds(
      uploadBytes(ref(riderA(), `tenants/${MX}/proof/order-a/delivered.jpg`), IMAGE, { contentType: "image/jpeg" }),
    );
    await assertFails(
      uploadBytes(ref(riderA(), `tenants/${MX}/proof/order-a/delivered.jpg`), IMAGE, { contentType: "image/jpeg" }),
    );
    await assertFails(deleteObject(ref(owner(), `tenants/${MX}/proof/order-a/delivered.jpg`)));
  });
});

describe("report exports", () => {
  it("are written by the server and read by the owner only", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await uploadBytes(ref(context.storage(), `tenants/${MX}/reports/orders-2026-10-14.csv`), new Uint8Array([1, 2, 3]), {
        contentType: "text/csv",
      });
    });
    await assertSucceeds(getBytes(ref(owner(), `tenants/${MX}/reports/orders-2026-10-14.csv`)));
    await assertFails(getBytes(ref(dispatcher(), `tenants/${MX}/reports/orders-2026-10-14.csv`)));
    await assertFails(getBytes(ref(otherOwner(), `tenants/${MX}/reports/orders-2026-10-14.csv`)));
    await assertFails(
      uploadBytes(ref(owner(), `tenants/${MX}/reports/fake.csv`), SMALL_PDF, { contentType: "text/csv" }),
    );
  });
});

describe("company logo", () => {
  it("is public to read and owner-writable as an image", async () => {
    await assertSucceeds(uploadBytes(ref(owner(), `tenants/${MX}/logo/logo.png`), IMAGE, { contentType: "image/png" }));
    await assertSucceeds(getBytes(ref(anonymous(), `tenants/${MX}/logo/logo.png`)));
    await assertFails(uploadBytes(ref(riderA(), `tenants/${MX}/logo/logo.png`), IMAGE, { contentType: "image/png" }));
    await assertFails(uploadBytes(ref(owner(), `tenants/${MX}/logo/payload.svg`), IMAGE, { contentType: "image/svg+xml" }));
  });
});

describe("billing evidence", () => {
  it("is visible only to Articals Studio administrators", async () => {
    await assertSucceeds(
      uploadBytes(ref(platformAdmin(), `tenants/${MX}/billing/receipt.pdf`), SMALL_PDF, { contentType: "application/pdf" }),
    );
    await assertSucceeds(getBytes(ref(platformAdmin(), `tenants/${MX}/billing/receipt.pdf`)));
    await assertFails(getBytes(ref(owner(), `tenants/${MX}/billing/receipt.pdf`)));
    await assertFails(
      uploadBytes(ref(owner(), `tenants/${MX}/billing/self-receipt.pdf`), SMALL_PDF, { contentType: "application/pdf" }),
    );
  });
});

describe("everything else", () => {
  it("is closed by default", async () => {
    await assertFails(uploadBytes(ref(anonymous(), `tenants/${MX}/proof/order-a/x.jpg`), IMAGE, { contentType: "image/jpeg" }));
    await assertFails(uploadBytes(ref(owner(), "tenants/mx-logistics/unknown/x.txt"), SMALL_PDF, { contentType: "text/plain" }));
    await assertFails(getBytes(ref(owner(), "some/other/path.txt")));
  });

  it("keeps marketing assets read-only", async () => {
    await assertSucceeds(getBytes(ref(anonymous(), "public/logo.svg")));
    await assertFails(uploadBytes(ref(owner(), "public/injected.svg"), IMAGE, { contentType: "image/svg+xml" }));
  });
});
