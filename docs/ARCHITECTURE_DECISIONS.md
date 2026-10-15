# Architecture decisions — Articals Logistics

Each decision below is recorded with what was chosen, why, and what it rules out.
Numbers are cited from source comments (`ADR-00x`), so they are stable.

---

## ADR-001 — One Kotlin Multiplatform module, Koin for injection, verified versions

**Decision.** `:composeApp` holds all shared code and the entire Compose UI for
Android and iOS. Dependency injection uses **Koin** (runtime graph, no code
generation) rather than Hilt or kotlin-inject. Every library version in
`gradle/libs.versions.toml` was read from the real registry on 2026-10-14 and is
listed with its evidence in `docs/VERSIONS.md`.

**Why.** One module keeps a small team honest: a change that breaks iOS shows up in
the same review as the Android change. Hilt is Android-only and would split the
shared module in two; kotlin-inject adds an annotation processor to every build on
every platform, which Koin avoids on Kotlin/Native.

**Ruled out.** Duplicate iOS/Android view layers, Hilt in shared code, and library
versions taken from blog posts rather than the registry.

---

## ADR-002 — Backend-authoritative pricing, implemented once

**Decision.** The pricing engine lives in `functions/src/pricing/` (TypeScript,
pure functions, 68 unit tests). The app calls `calculateDeliveryQuote` and renders
what comes back. The Kotlin code contains **no pricing formula** — only formatting
(`core/Money.kt`).

**Why.** The price of a delivery is money. If two clients could compute it, two
dispatchers could quote different prices for the same job, and a change to a
company's rate card would need an app release. With one implementation, the
snapshot stored on the order is always the truth that produced the number.

**Ruled out.** A "preview" formula in Kotlin that can drift, offline price
computation, and client-supplied prices being trusted.

---

## ADR-003 — Clients never write business documents

**Decision.** `firestore.rules` grants **read** access, scoped by membership and
role, and forbids every client write. All mutations are Cloud Functions
(`functions/src/functions/*`). The client never calls `set`, `update`, `add` or
`delete` on a business collection; `StorageRepository` writes only the three file
paths whose rules allow it.

**Why.** Order status, rider assignment, money and billing have rules that must
hold no matter which app version is installed. Rules that validate document *shapes*
become unreadable and still cannot enforce cross-document invariants (a delivery
cannot be completed by a rider who does not own it). Server-side validation with
typed errors is both safer and easier to test.

**Ruled out.** Client-side role checks as a security control, optimistic writes that
can bypass the state machine, and offline write queues for financial operations.

---

## ADR-004 — Money is exact integer minor units, everywhere

**Decision.** Every amount is a whole number of minor units (kobo/cents) with a
`*Minor` suffix: `customerChargeMinor`, `baseChargeMinor`, `amountMinor`. The
backend helper `functions/src/shared/money.ts` owns arithmetic and rounding; the app
has `core/Money.kt` for display and parsing only. `Number.isSafeInteger` guards
every arithmetic step.

**Why.** `0.1 + 0.2 != 0.3` is not a curiosity when a dispatcher settles with a
rider at the end of a day. Integers are also exactly what JSON, Firestore and
Kotlin `Long` represent without negotiation.

**Ruled out.** `Float`/`Double` for money, decimal strings in storage, and rounding
at more than one point in the pipeline (the engine rounds once, at the end, and
reports what it did).

---

## ADR-005 — Tenant isolation is enforced by rules and the backend, never the UI

**Decision.** Authority is a `memberships/{tenantId}_{uid}` document with
`status == "active"`, holding the role and any per-member permission overrides.
Rules mirror `functions/src/domain/permissions.ts`. Every backend handler resolves
the caller's membership before touching data and refuses cross-tenant ids with
`not_found` — the same answer for "does not exist" and "belongs to someone else" —
so the API cannot be used to discover other companies' data.

**Why.** A tenant filter that lives in the client is a suggestion. The composite
membership id makes the rules' lookup cheap and unambiguous, and keeps one
authority document per user per company.

**Ruled out.** Company id in a custom claim as the sole authority (a user may work
for two logistics companies), and error messages that distinguish missing from
forbidden.

---

## ADR-006 — Android and iOS only; API levels chosen for the pilot fleet

**Decision.** Targets are `androidTarget` (minSdk 26, compileSdk/targetSdk 37 — the
maximum AGP 9.4 supports) and `iosArm64`/`iosSimulatorArm64`/`iosX64` with a
deployment target of iOS 16. No desktop, no web client.

**Why.** The MVP is used by dispatchers on Android phones and riders on a mix of
Android devices, with iOS for owners. minSdk 26 covers the phones riders actually
carry in Lagos while giving the platform a modern runtime (no multidex gymnastics,
full notification channels). iOS 16 is the floor of the Firebase iOS SDK 13 line.

**Ruled out.** A web dashboard in the MVP (the platform console runs as a separate
Android flavour and iOS scheme), and raising the Android floor to a level that would
exclude rider devices.

---

## ADR-007 — Dates and money travel in one canonical format

**Decision.** Instants are ISO-8601 UTC with milliseconds
(`2026-10-14T09:41:03.000Z`). Business dates are `YYYY-MM-DD` keys computed in the
**company's** time zone (`Africa/Lagos` for the pilot, configurable per company).
Kotlin converts through kotlinx-datetime; the backend through
`functions/src/shared/time.ts`.

**Why.** A delivery completed at 00:30 belongs to that day's report, and "today"
must mean the same thing to a dispatcher in Lagos and an owner checking from
abroad. Storing an offset or a device-local date would make daily roll-ups wrong
once a year and subtly wrong every night.

**Ruled out.** Device-local business dates, epoch numbers on the wire, and
timezone-naive date strings.

---

## ADR-008 — Entitlement is computed, never stored as a boolean

**Decision.** `functions/src/domain/entitlement.ts` derives the state
(`trialing` → `active_paid` / `active_offline` / `active_bypass` → `grace` →
`limited` → `suspended`) from the subscription record and the current time, and
recomputes it on every read and write. The precedence order is explicit:
suspension beats everything; an active bypass beats a recorded paid period; a
recorded paid period beats the trial.

**Why.** A stored `isActive` flag drifts the moment a webhook is missed, and the
failure mode is a paying customer locked out — or a cancelled customer still
delivering for free. Deriving the state makes the sweep a safety net rather than
the source of truth.

**Ruled out.** Flags updated only by scheduled jobs, and grace periods that differ
between the app and the backend.

---

## ADR-009 — Offline payments and billing bypasses are administrator-only, audited, and never in-app purchases

**Decision.** Only a caller carrying the `platformAdmin` custom claim may record an
offline payment or grant/revoke a bypass; a bypass longer than 90 days additionally
requires `platformAdminElevated`. Each action requires a reason (bypass reasons are
an enum), writes an append-only ledger entry under
`subscriptions/{tenantId}/ledger`, and an audit log entry. The MVP app never sells a
subscription or takes a payment — App Store and Play policy forbid external payment
links, and the pilot is invoiced offline.

**Why.** This is the one place where money and authority meet, so it is the one
place with the fewest doors: an allow-listed claim, a mandatory reason, an
expiry, a ledger entry and an audit trail. `manageSubscription` mirrors the same
discipline for plan changes.

**Ruled out.** Payment SDKs in the MVP, client-visible bypass reasons, and bypasses
without an expiry.

---

## ADR-010 — Public tracking is a projection, not a filtered order

**Decision.** `publicTracking/{reference}` physically contains only the fields a
customer may see (status, areas, history, branding). `getPublicTracking` is
rate-limited (60 per caller per 10 minutes, 10 per reference), answers
`tracking_not_found` identically for missing, malformed and other-tenant
references, and never reads the order document. Phone verification (last four
digits, salted hash) is a per-company switch.

**Why.** A projection cannot leak a field that is not in it. Redacting an order at
read time means every future field is a potential leak.

**Ruled out.** Client-side filtering of order documents, and tracking references
derived from order numbers (enumerable).

---

## ADR-011 — Notifications are queued in the database, not sent inline

**Decision.** Handlers write a `notificationOutbox` entry inside the same
transaction as the change; a Firestore trigger resolves device tokens and sends.
Invalid tokens are pruned; a failure marks the entry `failed` or `skipped` with
`messaging_not_configured` instead of pretending a message was delivered.

**Why.** A push provider outage or a malformed token must never roll back a
delivery that was already completed. Notification records also give the pilot an
honest answer to "was the rider actually told?".

**Ruled out.** Sending FCM inside the operation transaction, and fabricating
delivery receipts.

---

## ADR-012 — The Android entry point lives in `:androidApp`

**Decision.** `:composeApp` applies `org.jetbrains.kotlin.multiplatform` **and**
`com.android.kotlin.multiplatform.library`; the `com.android.application` plugin and
the launcher activity live in `:androidApp`. The iOS framework is produced by the
KMP targets in `:composeApp` and consumed by `iosApp/`.

**Why.** From AGP 9 the Kotlin Multiplatform plugin cannot be combined with
`com.android.application` in the same module, and there is no
`com.android.kotlin.multiplatform.application`. Apple's own entry point is Swift
regardless. Splitting the module keeps the shared code exactly where the spec puts
it — one multiplatform module — and confines the platform shell to two small
directories.

**Ruled out.** A single module applying `com.android.application` (fails with AGP
9), and moving shared code into the platform shells (which would double it).

---

## ADR-013 — Demo mode is a first-class data source, with one documented exception to ADR-002

**Decision.** The app runs against one of two complete data sources, chosen by a
single configuration value (`articals.mode=demo|firebase`, plus the presence of
`google-services.json` / `GoogleService-Config.plist`):

* **DEMO** — an in-memory store (`composeApp/.../demo/`) that implements the same
  thirteen repository interfaces as the Firebase implementations. No Firebase
  project, no network, no API keys, no configuration file. Every screen, every
  state and every role can be exercised.
* **FIREBASE** — the real backend. If the configuration file is missing the app
  shows an honest "Firebase is not configured" screen pointing at `docs/SETUP.md`;
  it never falls back to demo data.

Screens and view models depend only on `data/api/*Repository.kt`; exactly one graph
is installed at start-up (`di/Startup.kt`), so a Firebase build compiles without the
demo package and a demo build never initialises Firebase.

**The exception.** ADR-002 says no pricing arithmetic in Kotlin.
`demo/DemoQuoteSimulator.kt` is the single, explicitly documented deviation: demo
mode has no backend to call, so the simulator reimplements the pricing rules so that
quotes, warnings, overrides and order pricing behave like production. It is written
to the same contract as the engine (`docs/contracts/pricing.contract.json`), its
outputs are checked against the same canonical vectors as the TypeScript engine in
`commonTest`, and it is only reachable from the demo graph. In FIREBASE mode the
only quoter is `calculateDeliveryQuote` on the server.

**Why.** A prototype with fake buttons proves nothing, and a demo that needs a
Firebase project before it will open cannot be given to a prospective customer. The
demo store also mirrors the backend's refusals (permissions, entitlement, status
machine, tenant boundaries), so the interface is verified against the behaviour the
real system has rather than a permissive imitation of it.

**Ruled out.** A second, separately-written UI for demos; a demo build that talks to
a real Firebase project; a build that silently degrades from Firebase to demo data;
pricing arithmetic anywhere else in Kotlin.

---

## ADR-014 — The admin console, the audit log and the ledger

**Decision.** The platform administration console is compiled into the `admin`
Android flavour only (`ADMIN_CONSOLE=false` in `company`), and every administrative
call requires the `platformAdmin` claim on the server. Two surfaces are handled
differently on purpose:

* **Company list, overview, offline payments, bypasses, subscription management and
  platform settings** go through callables (`listCompanies`, `getPlatformOverview`,
  `recordOfflinePayment`, `setBillingBypass`, `revokeBillingBypass`,
  `manageSubscription`, `updatePlatformSettings`).
* **The audit log and the ledger are not client-readable at all**
  (`firestore.rules` denies `auditLogs`, `ledger`, `subscriptions/*/private`,
  `invitations`, `idempotencyKeys`, `counters`, `rateLimits`,
  `notificationOutbox`). In demo mode the console shows them because the demo store
  *is* the server; in FIREBASE mode `PlatformAdminRepository.auditLog` and `.ledger`
  return `AppError.Unsupported`, and the console says so instead of showing an empty
  list that would read as "nothing ever happened".

**Why.** An audit trail that the audited party can query is not an audit trail, and
a ledger that a modified client can read is a privacy problem. The pilot's
administrators read those records through the Firebase console or `firebase-tools`
(see `docs/PILOT_RUNBOOK.md`); giving the app a read path would mean granting
`platformAdmin` holders a broad Firestore read, which the rules deliberately deny.
Every offline payment, bypass and subscription change is therefore made through an
audited callable that writes the ledger entry and the audit entry server-side in the
same transaction as the change.

**Ruled out.** A client-side query for `auditLogs`/`ledger`; an empty list standing
in for "denied"; administrative writes from the app that bypass a callable.

**Consequence to be aware of.** The console is complete in demo mode and complete
for everything that changes state in FIREBASE mode, but its two read-only history
panels are explicitly shown as unavailable in FIREBASE mode until a dedicated
administrative report callable is added.
