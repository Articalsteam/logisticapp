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
| Backend unit tests | `cd functions && npm install --no-audit --no-fund && npx vitest run --project unit` | 163/163 passing, 7 files (last run before the last client batch) |
| Named arguments against every `class` in the tree | `python3 tools/check_dto_args.py composeApp/src/commonMain/kotlin/com/articals/logistics` | 1 185 named arguments across 98 files, **0 problems** |
| Member access on the demo objects | `python3 tools/check_member_calls.py composeApp/src/commonMain/kotlin/com/articals/logistics store=DemoStore controls=DemoControls simulator=DemoQuoteSimulator` | 1 595 accesses, **0 problems** |
| Imports resolve inside the tree | `python3 tools/check_imports.py composeApp/src/commonMain/kotlin/com/articals/logistics` | 845 imports across 98 files, **0 problems** |
| Named arguments against our own functions | `python3 tools/check_named_args.py composeApp/src` | 642 named arguments across 107 files, **0 problems** |
| Every file is structurally closed | brace/parenthesis balance over all `.kt` files (inline script, see §3) | 107 files, all balanced |
| Rules tests | `cd functions && npm run test:rules` (Firestore emulator) | **Not run here** — the emulator JAR is not downloadable from this sandbox. Owner's machine only. |

The four checkers are structural, not a type checker. Between them they catch: a named
argument a class or function does not declare, a member that does not exist, an import
that names something that is not there, and an unbalanced file. They cannot catch a
type mismatch, a wrong overload, a bad operator, or anything about an external library
— that is what §4 is for.

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

## 2. Written but not compiled

Everything under `composeApp/src/` — 107 Kotlin files:

* `core/` — money, errors, results, permissions, `AppConfig`/`AppMode`, `UiState`,
  validation, time/date helpers.
* `data/` — DTOs, callable transport, error mapper, the thirteen repository interfaces,
  the thirteen `Firebase*Repository` implementations.
* `di/` — `firebaseModule`, `demoModule`, `uiModule`, `SupportLocator`,
  `startArticalsLogistics`.
* `domain/` — order status machine, entitlement states, permissions, the vocabulary
  mirrors.
* `demo/` — the store, the seeded world (two companies, ~60 deliveries, 6 riders, 15
  customers), the quote simulator, the thirteen `Demo*Repository` implementations, the
  control panel, the assets.
* `ui/` — theme, components, strings, the shell and these screens:
  * session: splash, not-configured, sign-in (with one-tap demo accounts), register,
    reset password, verify email;
  * workspace: no-company setup, workspace chooser, the four-step onboarding wizard;
  * working day: dashboard, delivery list (search/filter/sort/paging), delivery detail
    (assignment, status changes, final amounts, tracking message), settings hub,
    notifications;
  * rider: my deliveries, one job with the rider's own steps, proof upload, report a
    problem.
* `androidMain` / `iosMain` — platform actuals and the two platform bridges. The
  Android entry point (`androidApp/`) and the iOS Swift host (`iosApp/`) are still to be
  written, so the app cannot be launched even once it compiles.

Written but not compiled on the build side: `androidApp/build.gradle.kts`
(`articals.mode`, the company/admin flavours and the demo-release guard),
`composeApp/build.gradle.kts`, `gradle/libs.versions.toml`.

**Not written yet** (in the order they will be built): the price calculator and the
variants/create-delivery flow it feeds, quotes history, customers, riders, expenses,
pricing settings, reports and CSV export, company settings, staff and permissions,
subscription screen, customer tracking lookup, WhatsApp share sheet polish, and the
whole platform console (company list/detail, offline payments, bypasses, ledger,
overview, settings, audit log). The tab bar and the settings menu deliberately show
only destinations that exist: nothing in the app offers a button that leads nowhere.

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

* **Screens** — the remaining P1 (calculator, create delivery from a quote, quote
  history), P2 (customers, riders, expenses, pricing settings, tracking lookup) and P3
  (reports/CSV, staff and permissions, company settings, subscription, the platform
  console) screens, each added to `ui/nav/ArticalsNavigation.kt` as it lands.
* `commonTest`: repository contract tests against the demo implementations and the
  `DemoQuoteSimulator` canonical vectors.
* `androidApp/` entry point (Activity, `AppConfig` from `BuildConfig`, the demo-release
  guard) and `iosApp/` (Swift host, `project.yml` for XcodeGen).
* Documentation: `DEMO_WALKTHROUGH.md`, `DEMO_TO_FIREBASE.md`, `PILOT_RUNBOOK.md`,
  `MX_RATE_CARD_WORKSHEET.md` (+ CSV).
