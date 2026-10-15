# Compile checklist — Articals Logistics

**Purpose.** This environment has no Kotlin compiler, no Android SDK and no Xcode, so
the shared Kotlin has been *written and structurally checked*, never *compiled*. This
file is the honest record of that: what has been verified by running something, what
has only been read, and what to do on the first real build.

Nothing in this repository should be described as "compiles" or "renders" until the
commands in §4 have been run on a machine with the Android SDK and Xcode.

---

## 1. What has actually been run

| Check | Command | Result |
| --- | --- | --- |
| Backend unit tests | `cd functions && npm install --no-audit --no-fund && npx vitest run --project unit` | 163/163 passing, 7 files (last run before the P3 client batch) |
| Named arguments against every `class` in the tree | `python3 tools/check_dto_args.py composeApp/src` | 1 734 named arguments across 141 files, **0 problems** |
| Member access on the demo objects | `python3 tools/check_member_calls.py composeApp/src store=DemoStore controls=DemoControls simulator=DemoQuoteSimulator world=DemoWorld` | 3 387 accesses, **2 problems — both false positives**, see below |
| Imports resolve inside the tree | `python3 tools/check_imports.py composeApp/src` | 1 345 imports across 141 files, **0 problems** |
| Named arguments against our own functions | `python3 tools/check_named_args.py composeApp/src` | 1 658 named arguments across 141 files, **0 problems** |
| Every file is structurally closed | `python3 tools/check_balance.py .` | 143 `.kt` files, **0 problems** |
| Rules tests | `cd functions && npm run test:rules` (Firestore emulator) | **Not run here** — the emulator JAR is not downloadable from this sandbox. Owner's machine only. |

The five checkers are structural, not a type checker. Between them they catch: a named
argument a class or function does not declare, a member that does not exist, an import
that names something that is not there, and an unbalanced file. They cannot catch a
type mismatch, a wrong overload, a bad operator, or anything about an external library
— that is what §4 is for.

Two standing false positives, kept rather than silenced because silencing them would
hide a real one if it ever appeared:

* `check_member_calls.py` reports `Settings.ACTION_APP_NOTIFICATION_SETTINGS` and
  `Settings.EXTRA_APP_PACKAGE` in `androidMain/…/Platform.android.kt`. Those are
  `android.provider.Settings`, not this app's `Settings` string object; the static pass
  cannot tell two same-named types apart across modules.
* `check_balance.py` counts Kotlin, not Kotlin-in-a-string: a brace inside a plain string
  literal is ignored, which is correct, and a `${...}` template is parsed as code.

**`check_balance.py` itself was broken and is fixed.** The first version emitted the
closing brace of every `${...}` template without its opening one, and reported 359
"problems" in files that were fine. It was checked against three deliberately broken
files afterwards (a missing `}`, a `)` where a `}` belonged, and a file whose braces live
inside strings and templates) and it now catches the first two and passes the third.

### Defects the checkers found (all fixed in this branch)

* `OrderDto` had no `pricingSnapshot`, although the backend writes it onto every order
  document (`functions/src/functions/orders.ts:388`). Added.
* `CompanyDto` had no `createdBy`; the backend writes it (`company.ts:132`). Added.
* `DemoBypass` had no `notes`; the admin panel supplies internal notes. Added.
* `BackendFunctions.exportCompanyReport` was referenced by `FirebaseInsightsRepository`
  but never declared, even though the callable is deployed (`functions/src/index.ts:195`). Added.
* **`expect`/`actual` package mismatch (ADR-004 rule: same package required).** The
  platform actuals lived in `com.articals.logistics.platform` while their expects are in
  `com.articals.logistics.core` (`Platform.kt`) and `com.articals.logistics.data`
  (`PlatformPreferences.kt`, `StorageData.kt`), which does not compile. The actuals now
  live in the package that declares the expect
  (`androidMain|iosMain/kotlin/com/articals/logistics/{core,data}/`), and the two
  Android/iOS bridges that have no `expect` (`AndroidPlatform`, `IosPlatformBridge`)
  moved to `.../platform/`, imported from there.
* `AricalsLogo` (a misspelling of the product's own name) renamed to `ArticalsLogo`
  across every use; the navigation host is `AricalsNavigation` no longer.
* Six imports of `DemoStrip` named `com.articals.logistics.ui.component.DemoStrip`
  although the composable lives in `ui.demo` — a compile error in five screen files.
  Found by `check_imports.py` the first time it ran.
* `OnboardingViewModel` imported `com.articals.logistics.domain.Roles`; `Roles` is
  declared in `com.articals.logistics.core` (`Permissions.kt`). Same checker, same run.
* `UiState.dataOrNull` is a property, not a function; three call sites used it as
  `dataOrNull()`.
* `AttentionReasons` had no `DeliveryFailed` constant although the dashboard and the
  order list both needed one, and its `UnreportedProblem` label said "Delivery failed".
* `ArticalsStrings.Share.trackingMessage` and the `Shell` string object were added while
  writing the shell; a bad patch had renamed the `Settings` string object to
  `Notifications`, which `check_member_calls.py` caught on the next run (341 → 34 → 0
  problems). This is the clearest argument for running the checkers after every batch.
* **`tools/check_member_calls.py` did not parse at all** — the docstring added in the
  previous batch ended with two quotes instead of three, so the whole tool was a syntax
  error and every run after that commit failed. Fixed; the numbers above are from the
  fixed tool. A checker that cannot run is worse than no checker, because its silence
  looks like a pass.
* **A rider could move a colleague's delivery.** `DemoOrdersRepository.updateStatus`
  decided the transition table by permission alone, and the rider role *has*
  `orders.update_status`, so a rider acting on a delivery that was not assigned to them
  fell through to the staff workflow and was allowed. The backend refuses this with
  `order.not_assigned_to_rider` (`functions/src/functions/orderLifecycle.ts:285`). The
  demo now refuses it with the same code, and `DemoOrdersContractTest` asserts both the
  refusal and the assigned rider's success.
* **Proof of delivery had no ownership check either.** `DemoStorageRepository.uploadProof`
  checked the permission but not the delivery, while the storage rules allow only an
  assigned rider or the staff roles that record proof
  (`storage.rules`, `tenants/{tenantId}/proof/{orderId}`). The demo now mirrors the rule.
* **The seeded world had no delivery with an unconfirmed rider cost**, although the
  walkthrough points at the "needs attention" list for exactly that case and
  `DashboardViewModel`/`OrdersViewModel` both look for `riderCostConfidence ==
  "unconfirmed"`. One seeded delivery now carries it, with the acknowledged
  `rider_cost_unconfirmed` warning that accepting such a delivery requires and without
  final amounts, so it stays open. The random stream is unchanged: the chance is still
  rolled for every delivered delivery.
* `DemoControls.reset()` cleared the store's entitlement override but not the one in its
  own `DiagnosticsState`, so the panel kept showing the state chip for a company that had
  just been reset.
* `DemoStorageRepository.uploadCompanyLogo` contained a stray `StorageRepository::class`
  expression that did nothing; removed (it was on the housekeeping list).

## 2. Written but not compiled

Everything under `composeApp/src/` — 132 Kotlin files, plus 9 test files:

* `core/` — money, errors, results, permissions, `AppConfig`/`AppMode`, `UiState`,
  validation, time/date helpers.
* `data/` — DTOs, callable transport, error mapper, the thirteen repository interfaces,
  the thirteen `Firebase*Repository` implementations.
* `di/` — `firebaseModule`, `demoModule`, `uiModule`, `SupportLocator`,
  `startArticalsLogistics`.
* `domain/` — order status machine, entitlement states, permissions, the vocabulary
  mirrors.
* `demo/` — the store, the seeded world (two companies, 60 deliveries, 6 riders, 15
  customers), the quote simulator, the thirteen `Demo*Repository` implementations, the
  control panel, the assets.
* `ui/` — theme, components, strings, the shell and every screen of P1, P2 and P3:
  * session: splash, not-configured, sign-in (with one-tap demo accounts), register,
    reset password, verify email;
  * workspace: no-company setup, workspace chooser, the four-step onboarding wizard;
  * working day: dashboard, delivery list (search/filter/sort/paging), delivery detail
    (assignment, status changes, final amounts, tracking message), settings hub,
    notifications;
  * pricing: the calculator (full breakdown, rider-cost and price overrides, warnings,
    save quote), quote history, create a delivery from a quote;
  * records: customers, riders, expenses, pricing settings (four methods, bands, zones,
    surcharges, rounding, rider-cost methods, versioning), reports and CSV export,
    company settings, staff and permissions, subscription status, customer tracking
    lookup;
  * rider: my deliveries, one job with the rider's own steps, proof upload, report a
    problem;
  * the platform console: overview, company list and detail, offline payments, bypass
    grants, the ledger, platform settings, the audit log.
* `commonTest` — 88 tests in 9 files (§2.1 below).
* `androidMain` / `iosMain` — platform actuals and the two platform bridges.
  `androidApp/` (the entry point) is written; `iosApp/` is not built yet, so the app
  cannot be launched even once it compiles.

Written but not compiled on the build side: `androidApp/build.gradle.kts`
(`articals.mode`, the company/admin flavours and the demo-release guard),
`composeApp/build.gradle.kts`, `gradle/libs.versions.toml`.

## 2.1 The tests — written, never run

`composeApp/src/commonTest/kotlin/com/articals/logistics/` — 88 `@Test` functions. They
are Kotlin tests over the demo data layer; nothing here touches Firebase, and every test
builds its own demo world with **zero latency** (`DemoWorld`), so none of them sleeps and
none of them shares state with another.

| File | What it pins |
| --- | --- |
| `demo/DemoWorld.kt` | The fixture: controls, store, seed, the thirteen repositories, the simulator. |
| `demo/DemoWorldTest.kt` | The claims demo mode makes: seven accounts sign in and land in the advertised role; a wrong password starts no session; a rider sees only their own deliveries and the two riders' lists are disjoint; a query for one company never returns the other's rows; a dispatcher's dashboard has no `financials` while the owner's does; only the admin account carries the platform claim; the seed is deterministic. |
| `demo/DemoQuoteSimulatorTest.kt` | The canonical pricing example (₦700 + ₦150/km + ₦500 rider = ₦1 960 / profit ₦1 460 / 7 449 bps), the band method, the catch-all band, `needs_input` instead of an invented distance, the manual-distance warning, minimum and maximum, rounding once at the end, extra costs as costs, duplicate extras, the pricing snapshot, and that the lines add up to the line total. |
| `demo/DemoOrdersContractTest.kt` | Create (priced, visible, pending), idempotency by key, role gating, assignment, illegal transitions, the returned allowed-next list, final amounts only after the delivery is closed, variance, rider ownership (`order.not_assigned_to_rider`) both ways, cross-company refusal, proof-of-delivery ownership and the file rules, the permission matrix around `orders.record_actuals`. |
| `demo/DemoRepositoriesContractTest.kt` | Customers (add, archive, still listed after archiving), riders (a rider record is not an account; the invitation is a separate step), expenses (company dates, deletion with a reason, a delivery expense needs a delivery), pricing (saving rates makes a new version and leaves the old one alone; a saved quote appears in history), company settings, staff invitations and the refusal a dispatcher gets, changing a role being audited, console payments and ledger, a company owner refused the console, the console's own actions audited, tracking working for a real reference and failing for an invented one. |
| `demo/DemoControlsTest.kt` | The control panel as the error lab: zero latency is instant, slow triples the wait, offline fails everything with `AppError.Offline`, a forced error is spent on exactly one call, flaky is deterministic for a seed and never invents another error, latency and font scale are clamped, all seven entitlement states, a limited workspace that reads but refuses writes (`entitlement_limited`), a suspended one says so, the empty company, reset, and that the seeded deliveries really do cover the states the walkthrough points at. |
| `core/MoneyAndTimeTest.kt` | Money as whole minor units both ways, negatives kept, nonsense refused, percentages to basis points, the business date in the company's time zone rather than the phone's, date-key arithmetic and boundaries, and the distance parser's limits. |
| `data/DtoContractTest.kt` | The callable envelope, a response with optional fields omitted, money as numbers of minor units on the wire (and a rate card that survives a round trip), an unknown pricing method carried as a string, a quote round-tripping with its lines, rounding and snapshot, and the vocabulary lists the interface switches on. |
| `ui/nav/RoutesTest.kt` | Who can open what: a rider reaches only the rider routes, staff reach the company app and not the rider app, no signed-in role is nobody, the console needs the platform claim, patterns and built routes agree, and an unknown route is closed. |

**Two of these tests found real defects while being written** — the rider-ownership hole
and the missing unconfirmed-rider-cost seed case, both listed under §1.

## 3. Known traps for the first build

1. **Firebase DTOs.** GitLive's Firestore mapper has no `ignoreUnknownKeys` knob, so
   every field of every document DTO must be nullable or defaulted. Keep it that way
   when adding fields, and mirror new backend fields as nullable.
2. **Firestore queries in the client avoid `orderBy`.** All client reads use equality
   filters and sort in Kotlin, so a missing composite index cannot break a screen.
   `firestore.indexes.json` (21 composites) exists for the backend and the console.
3. **Money is `Long` minor units.** No `Float`/`Double` in anything that is money —
   including the trend bars on the dashboard, which are counts, not currency.
4. **`RIDER_COST_METHODS` has no `percentage_of_charge`.** Do not add it to the UI.
5. **`SessionRepository` must not regain `cacheClaimsOnNextLaunch`.**
6. **`.mts` matters.** `functions/vitest.config.mts` must stay `.mts`.
7. **Rules tests need the emulator JAR**; that is why `npm run test:rules` is the
   owner's job.
8. **Task names.** With AGP 9's `com.android.kotlin.multiplatform.library`, the Android
   variant compile task names differ from the old `androidTarget`. Run
   `./gradlew :composeApp:tasks --all` once and record the real names here.
9. **Koin's `viewModel { }` DSL** comes from `org.koin.core.module.dsl` and the bound
   view models are declared in `di/UiModule.kt`. `koinViewModel()` resolves by type, so
   a screen whose view model is not registered fails at composition, not at compile.
10. **Compose Multiplatform navigation** is `androidx.navigation.compose` (the JetBrains
    KMP artefact). Route strings live in `ui/nav/Routes.kt`; the session decides the
    start destination, and `Routes.canOpen` is enforced inside the graph.

Balance check used in §1 (no compiler needed):

```bash
python3 - <<'PY'
from pathlib import Path
from importlib.util import module_from_spec, spec_from_file_location
spec = spec_from_file_location("chk", "tools/check_dto_args.py")
chk = module_from_spec(spec); spec.loader.exec_module(chk)
for path in sorted(Path("composeApp/src").rglob("*.kt")):
    masked = chk.mask_comments(path.read_text())
    for o, c in (("{", "}"), ("(", ")")):
        assert masked.count(o) == masked.count(c), f"{path}: {o}{c} unbalanced"
print("balanced")
PY
```

## 4. First real build — in this order

```bash
# 0. Sanity: the demo build must fail without the opt-in flag.
./gradlew :androidApp:assembleCompanyRelease -Particals.mode=demo        # expect a clear failure
./gradlew :androidApp:assembleCompanyRelease -Particals.mode=demo -Pallow.demo.release=true  # expect success

# 1. Metadata only (fastest way to see unresolved references in shared code)
./gradlew :composeApp:compileKotlinMetadata

# 2. Android debug (demo mode is the default)
./gradlew :androidApp:assembleCompanyDebug

# 3. iOS framework (macOS only)
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64

# 4. Tests (repository contract tests + the quote simulator vectors)
./gradlew :composeApp:allTests
```

Record the outcome of each line here as it is run.

## 5. Still to write

* `iosApp/` — the Swift host, `project.yml` for XcodeGen, `Config.xcconfig.example`
  values wired into the framework, and the push/App Check notes.
* The four documents in §7 of the spec: `DEMO_WALKTHROUGH.md`, `DEMO_TO_FIREBASE.md`,
  `PILOT_RUNBOOK.md`, `MX_RATE_CARD_WORKSHEET.md` (+ CSV).
* One ADR owed: the pricing vocabulary (`base_plus_distance | distance_bands |
  zone_based | hybrid`, and "flat price" expressed as a single open-ended band).
* Housekeeping: the duplicated `ui/demo/DemoActions.kt`; `SettingsScreen.kt` uses `2.dp`
  without importing `dp`; bodies patched blind in `SupportLocator.kt`, `Startup.kt`,
  `ArticalsApp.kt`, `UiModule.kt`, `SystemScreens.kt` still need a re-read against the
  components they call.
* The standing truth for §4: **none of the above is compiled.** The first person with an
  Android SDK and a Mac owes this file the results.
