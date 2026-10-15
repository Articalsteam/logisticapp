# Articals Logistics

Multi-tenant logistics management for delivery businesses — pricing, dispatch,
riders, customers, expenses and reports — built by **Articals Studio** as a Kotlin
Multiplatform / Compose Multiplatform app for **Android and iOS**, on a Firebase
backend.

The first pilot customer is MX Logistics. Nothing about MX Logistics is hard-coded:
its currency, time zone, service areas, rate card, rider costs and branding are all
data.

---

## What this is, in one paragraph

A company owner signs in, creates a workspace and describes how they price
deliveries (base charge, per-kilometre rate or distance bands or zone pairs, rider
cost, surcharges, minimums, rounding). Dispatchers then quote a delivery, place the
order, assign a rider and follow it to completion; riders see only their own
deliveries and move them through a state machine; the owner sees what each delivery
earned and cost, where the money went, and can export it. Every price, status
change and financial figure is produced by the backend, so two devices can never
disagree and a modified app cannot cheat.

---

## Repository map

| Path | What lives there |
| --- | --- |
| `composeApp/` | the shared Kotlin Multiplatform module: domain and data layers, Compose UI, design system, platform-specific code |
| `androidApp/` | the Android application entry point (manifest, launcher, icons, Firebase config file) |
| `iosApp/` | the iOS shell: config and documentation today, Xcode project and Swift entry point in Phase 2 |
| `functions/` | the trusted backend: TypeScript Cloud Functions, the pricing engine, security-rule tests |
| `firestore.rules`, `storage.rules`, `firestore.indexes.json` | the security model and query indexes |
| `hosting/public/` | the public tracking page, the rider-invitation landing page and the `/admin` notice |
| `docs/` | `ARCHITECTURE_DECISIONS.md` (the ADRs), `SETUP.md`, `VERSIONS.md`, `FINANCIAL_DEFINITIONS.md`, `contracts/` |

---

## Where the project stands

This repository is built phase by phase (spec §28). Honest status:

| Area | State |
| --- | --- |
| **Pricing engine** (highest priority) | **Done.** Pure TypeScript engine: base + per-km, distance bands, zone pairs with distance fallback, surcharges, rider cost (six methods), minimum/maximum, single-point rounding, target-profit back-solve, manual overrides with reasons, warning codes. 68 unit tests. |
| **Backend contract** | **Done and tested.** 31 callables (company, staff, directory, pricing, orders, lifecycle, tracking, expenses, reports, subscriptions, platform admin, notifications) with typed errors, idempotency, rate limits and audit logging. 163 unit tests green, including a full delivery workflow and a contract test that walks the documented wire format. |
| **Security rules** | **Written.** Clients are read-only; tenant authority is a membership document; riders are scoped to their own deliveries; billing internals are administrator-only. Rules tests run against the emulator suite (`npm run test:rules`). |
| **Data layer (client)** | **Done.** Typed DTOs for every request and response, a callable transport that unwraps the envelope and maps failures to user-safe errors, and one repository per area. No pricing arithmetic in Kotlin. |
| **Platform layer (client)** | **Done.** Time-zone and business-date handling, preferences, proof-photo storage bridge, map/dial/share/clipboard, FCM token and App Check token plumbing — with per-platform implementations. |
| **UI (client)** | **Not started.** Screens, navigation and the design system arrive in Phase 2, together with the Android/iOS app shells. This is deliberate: no static prototypes and no screens backed by fake data. |
| **Reports, expenses, admin console UI** | The backend operations exist and are tested; their screens are Phase 2+. |
| **Push notifications** | Queued in the database and sent by a trigger; delivery is reported honestly (`skipped` / `messaging_not_configured`) rather than assumed. |

Commands that work today:

```bash
cd functions
npm install
npm test              # 163 unit tests
npm run typecheck     # tsc --noEmit
npm run test:rules    # security rules against the emulator suite (needs Java)
```

Everything else — building the apps, deploying, running the emulators — is
documented step by step in **[docs/SETUP.md](docs/SETUP.md)**.

---

## The rules this codebase enforces

These are not style preferences; each one has an ADR behind it
(`docs/ARCHITECTURE_DECISIONS.md`).

1. **Pricing lives in the backend only.** The app renders a breakdown it cannot
   compute (ADR-002).
2. **Clients never write business documents.** Reads are scoped by rules; every
   change is a callable (ADR-003).
3. **Money is integer minor units** (kobo) with a `*Minor` suffix — never a float —
   and rounding happens once, at the end (ADR-004).
4. **Tenant isolation is enforced by rules and the backend**, never by the UI
   (ADR-005).
5. **Instants are ISO-8601 UTC; business dates belong to the company's time zone**
   (ADR-007).
6. **Entitlement is computed from the subscription and the clock**, never stored as
   a boolean, so a missed webhook cannot lock out a paying customer or leave a
   cancelled one delivering free (ADR-008).
7. **Offline payments and billing bypasses are administrator-only**, need a reason,
   expire, and are written to an append-only ledger and the audit log (ADR-009).
8. **The MVP never sells a subscription and never takes a payment** (App Store and
   Play policy; ADR-009).
9. **Public tracking is a projection**, not a filtered order, and answers the same
   way for missing and foreign references (ADR-010).
10. **No live GPS tracking, no contacts, no background location, no broad storage
    permissions** (spec §26).

---

## What is deliberately absent

- **Desktop and web clients.** The MVP ships Android and iOS.
- **Live rider tracking.** The delivery state machine is the source of truth; a map
  of moving dots is not in scope.
- **A payment provider.** Invoices are settled offline and recorded by an
  administrator.
- **A demo mode.** Every screen will be backed by real, persisted state; there are
  no seeded fake deliveries in the app.

---

## Licence and ownership

Proprietary — © Articals Studio. Not for redistribution without written permission.
