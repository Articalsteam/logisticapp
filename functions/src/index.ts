/**
 * Articals Logistics — Cloud Functions entry point.
 *
 * This file is the only place that knows about the Firebase runtime. It:
 *  - builds the dependencies (Firestore-backed DataStore, routing provider, config),
 *  - maps every registered handler onto a callable with the right App Check policy,
 *  - converts typed backend errors into structured HttpsErrors,
 *  - hosts the two event-driven pieces: the notification outbox trigger and the
 *    scheduled entitlement sweep.
 *
 * Every handler re-checks authentication, membership, permission and entitlement,
 * because the Admin SDK bypasses Firestore security rules.
 */

import { initializeApp, getApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { HttpsError, onCall, type CallableRequest, type FunctionsErrorCode } from "firebase-functions/v2/https";
import { onDocumentCreated } from "firebase-functions/v2/firestore";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { defineSecret } from "firebase-functions/params";
import { setGlobalOptions } from "firebase-functions/v2";

import { createAdminDataStore } from "./data/adminDataStore";
import { createAdminAuthPort, createAdminFileStorage, createAdminPush } from "./data/adminPorts";
import { loadRuntimeConfig } from "./config/runtimeConfig";
import { toBackendError, type BackendErrorCode } from "./shared/errors";
import type { BackendError } from "./shared/errors";
import { createDeps, type HandlerDeps } from "./functions/deps";
import { HANDLERS } from "./functions/registry";
import { createProcessNotificationOutbox, type OutboxEntry } from "./functions/notifications";
import { runEntitlementSweep } from "./functions/subscriptions";
import { createExportCompanyReport } from "./functions/reports";
import { createSetPlatformAdmin } from "./functions/admin";
import { contactPayload, resolveCallContext } from "./functions/callContext";
import { ensurePlatformConfig } from "./functions/admin";
import { createAdminDataStoreForApp } from "./data/adminStoreFactory";

// Routing keys are server-side secrets only (spec §8). They never reach the app.
const googleRoutesKey = defineSecret("GOOGLE_ROUTES_API_KEY");
const mapboxToken = defineSecret("MAPBOX_ACCESS_TOKEN");

setGlobalOptions({
  region: "europe-west1", // closest Firebase region to Lagos with all required services
  maxInstances: 40,
  concurrency: 40,
  memory: "512MiB",
  timeoutSeconds: 60,
});

function ensureApp(): void {
  try {
    getApp();
  } catch {
    initializeApp();
  }
}

function buildDeps(): HandlerDeps {
  const store = createAdminDataStoreForApp(getFirestore(getApp()));
  const config = loadRuntimeConfig();
  return createDeps({
    store,
    config,
    log: (event, fields) => {
      // Structured logs without secrets; see shared/audit.ts for redaction rules.
      console.log(JSON.stringify({ event, ...(fields ?? {}) }));
    },
  });
}

const ERROR_CODE_MAP: Record<BackendErrorCode, FunctionsErrorCode> = {
  unauthenticated: "unauthenticated",
  permission_denied: "permission-denied",
  not_a_member: "permission-denied",
  membership_inactive: "permission-denied",
  invalid_argument: "invalid-argument",
  failed_precondition: "failed-precondition",
  not_found: "not-found",
  already_exists: "already-exists",
  conflict: "aborted",
  resource_exhausted: "resource-exhausted",
  entitlement_limited: "failed-precondition",
  entitlement_suspended: "failed-precondition",
  unsupported: "unimplemented",
  provider_unavailable: "unavailable",
  internal: "internal",
};

function toHttpsError(error: unknown): HttpsError {
  const backend: BackendError = toBackendError(error);
  if (backend.code === "internal") {
    // Log the detail server-side; never return it to the client.
    console.error(JSON.stringify({ event: "internal_error", detail: backend.detail }));
  }
  return new HttpsError(ERROR_CODE_MAP[backend.code], backend.userMessage, backend.toPayload());
}

/** Wraps a handler into a callable with consistent context mapping and error shape. */
function callable<TOutput>(
  name: keyof typeof HANDLERS,
  options: { enforceAppCheck?: boolean; secrets?: ReturnType<typeof defineSecret>[] } = {},
) {
  const handler = HANDLERS[name] as (input: unknown, context: ReturnType<typeof resolveCallContext>, deps: HandlerDeps) => Promise<TOutput>;
  return onCall(
    {
      enforceAppCheck: options.enforceAppCheck ?? false,
      secrets: options.secrets ?? [],
      cors: true,
    },
    async (request: CallableRequest<unknown>) => {
      try {
        const deps = buildDeps();
        const context = resolveCallContext(request);
        return await handler(request.data, context, deps);
      } catch (error) {
        throw toHttpsError(error);
      }
    },
  );
}

// ---------------------------------------------------------------------------
// Callable functions
// ---------------------------------------------------------------------------

export const createCompany = callable("createCompany");
export const updateCompanySettings = callable("updateCompanySettings");
export const inviteCompanyMember = callable("inviteCompanyMember");
export const acceptInvitation = callable("acceptInvitation");
export const updateMemberRole = callable("updateMemberRole");
export const setMemberStatus = callable("setMemberStatus");

export const createCustomer = callable("createCustomer");
export const updateCustomer = callable("updateCustomer");

export const createRider = callable("createRider");
export const updateRider = callable("updateRider");
export const createRiderInvitation = callable("createRiderInvitation");
export const acceptRiderInvitation = callable("acceptRiderInvitation");

export const updateCompanyPricing = callable("updateCompanyPricing");
export const calculateDeliveryQuote = callable("calculateDeliveryQuote", { secrets: [googleRoutesKey, mapboxToken] });
export const createOrder = callable("createOrder", { secrets: [googleRoutesKey, mapboxToken] });
export const updateOrder = callable("updateOrder");
export const assignRider = callable("assignRider");
export const updateDeliveryStatus = callable("updateDeliveryStatus");
export const recordDeliveryActuals = callable("recordDeliveryActuals");

export const createExpense = callable("createExpense");
export const deleteExpense = callable("deleteExpense");

/** Anonymous, App Check protected when configured, rate limited inside the handler. */
export const getPublicTracking = onCall(
  { enforceAppCheck: loadRuntimeConfig().enforceAppCheck, cors: true },
  async (request) => {
    try {
      const deps = buildDeps();
      const context = resolveCallContext(request);
      return await HANDLERS.getPublicTracking(request.data, context, deps);
    } catch (error) {
      throw toHttpsError(error);
    }
  },
);

export const getCompanyDashboardMetrics = callable("getCompanyDashboardMetrics");
export const rebuildDailyRollups = callable("rebuildDailyRollups");
export const getMySubscription = callable("getMySubscription");

export const recordOfflinePayment = callable("recordOfflinePayment");
export const setBillingBypass = callable("setBillingBypass");
export const revokeBillingBypass = callable("revokeBillingBypass");
export const manageSubscription = callable("manageSubscription");

export const listCompanies = callable("listCompanies");
export const getPlatformOverview = callable("getPlatformOverview");
export const updatePlatformSettings = callable("updatePlatformSettings");
export const registerDeviceToken = callable("registerDeviceToken");

export const setPlatformAdmin = onCall({ cors: true }, async (request) => {
  try {
    const deps = buildDeps();
    const context = resolveCallContext(request);
    const handler = createSetPlatformAdmin(createAdminAuthPort(), (process.env.ARTICALS_BOOTSTRAP_ADMIN_EMAILS ?? "")
      .split(",")
      .map((entry) => entry.trim().toLowerCase())
      .filter((entry) => entry.length > 0));
    return await handler(request.data, context, deps);
  } catch (error) {
    throw toHttpsError(error);
  }
});

/** Report export needs Storage; the port is injected so tests can fake it. */
export const exportCompanyReport = onCall({ cors: true }, async (request) => {
  try {
    const deps = buildDeps();
    const context = resolveCallContext(request);
    const handler = createExportCompanyReport(createAdminFileStorage());
    return await handler(request.data, context, deps);
  } catch (error) {
    throw toHttpsError(error);
  }
});

// ---------------------------------------------------------------------------
// Event-driven functions
// ---------------------------------------------------------------------------

/**
 * Sends one queued push notification. Kept out of the request path so a messaging
 * outage can never fail a delivery operation.
 */
export const onNotificationOutboxCreated = onDocumentCreated("notificationOutbox/{entryId}", async (event) => {
  ensureApp();
  const snapshot = event.data;
  if (!snapshot) return;
  const deps = buildDeps();
  const handler = createProcessNotificationOutbox(createAdminPush());
  const entry = { id: snapshot.id, ...(snapshot.data() as Omit<OutboxEntry, "id">) };
  try {
    await handler(entry, contactPayload({ uid: null }), deps);
  } catch (error) {
    console.error(JSON.stringify({ event: "notification_outbox_failed", entryId: snapshot.id, error: toBackendError(error).detail }));
  }
});

/**
 * Daily entitlement sweep (Cloud Scheduler): recomputes entitlement, warns owners
 * before an expiry and notifies administrators when an account changes state.
 */
export const entitlementSweep = onSchedule(
  { schedule: "every day 06:00", timeZone: "Africa/Lagos" },
  async () => {
    ensureApp();
    const deps = buildDeps();
    const adminRecipients = (process.env.ARTICALS_ADMIN_USER_IDS ?? "")
      .split(",")
      .map((entry) => entry.trim())
      .filter((entry) => entry.length > 0);
    const summary = await runEntitlementSweep(deps, adminRecipients);
    console.log(JSON.stringify({ event: "entitlement_sweep", ...summary }));
  },
);

/** Idempotent seeding of the plan catalogue and entitlement policy. */
export const bootstrapPlatformConfig = onCall({ cors: true }, async (request) => {
  try {
    const deps = buildDeps();
    await ensurePlatformConfig(deps.store);
    void request;
    return { ok: true, data: { seeded: true } };
  } catch (error) {
    throw toHttpsError(error);
  }
});

export { createAdminDataStore };
