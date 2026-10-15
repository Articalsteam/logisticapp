# Compile checklist — Articals Logistics

**Purpose.** This environment has no Kotlin compiler and no Android SDK, so the
shared Kotlin has been *written and structurally checked*, never *compiled*. This
file is the honest record of that: what has been verified by running something, what
has only been read, and what to do on the first real build.

Nothing in this repository should be described as "compiles" or "renders" until the
commands in §4 have been run on a machine with the Android SDK and Xcode.

---

## 1. What has actually been run

| Check | Command | Result |
| --- | --- | --- |
| Backend unit tests | `cd functions && npm install --no-audit --no-fund && npx vitest run --project unit` | 163/163 passing, 7 files (last run before the client work below) |
| Named arguments against every `class` in the tree | `python3 tools/check_dto_args.py composeApp/src/commonMain/kotlin/com/articals/logistics` | 1079 named arguments checked in 64 files, **0 problems** |
| Member access on the demo objects | `python3 tools/check_member_calls.py composeApp/src/commonMain/kotlin/com/articals/logistics store=DemoStore controls=DemoControls simulator=DemoQuoteSimulator` | 749 accesses checked, **0 problems** |
| Rules tests | `cd functions && npm run test:rules` (Firestore emulator) | **Not run here** — the emulator JAR is not downloadable from this sandbox. Owner's machine only. |

The two checkers are structural, not a type checker: they catch a named argument
that the target class does not declare and a member that does not exist. They cannot
catch a type mismatch, a wrong overload, or an unresolved import.

### Defects the checkers found (all fixed)

* `OrderDto` had no `pricingSnapshot`, although the backend writes it onto every
  order document (`functions/src/functions/orders.ts:388`). Added.
* `CompanyDto` had no `createdBy`; the backend writes it (`company.ts:132`). Added.
* `DemoBypass` had no `notes`; the admin panel supplies internal notes. Added.
* `BackendFunctions.exportCompanyReport` was referenced by
  `FirebaseInsightsRepository` but never declared, even though the callable is
  deployed (`functions/src/index.ts:195`). Added.
* **`expect`/`actual` package mismatch (ADR-004 rule: same package required).** The
  platform actuals lived in `com.articals.logistics.platform` while their expects are
  in `com.articals.logistics.core` (`Platform.kt`) and
  `com.articals.logistics.data` (`PlatformPreferences.kt`, `StorageData.kt`), which
  does not compile. The actuals now live in the package that declares the expect
  (`androidMain|iosMain/kotlin/com/articals/logistics/{core,data}/`), and the two
  Android/iOS bridges that have no `expect` (`AndroidPlatform`, `IosPlatformBridge`)
  moved to `.../platform/`, imported from there.

## 2. Written but not compiled

Everything under `composeApp/src/`:

* `core/` — money, errors, results, permissions, `AppConfig`/`AppMode`, `UiState`.
* `data/` — DTOs (116 data classes), callable transport, error mapper, the thirteen
  repository interfaces, the thirteen `Firebase*Repository` implementations.
* `di/` — `firebaseModule`, `demoModule`, `startArticalsLogistics`.
* `domain/` — order status machine and the vocabulary mirrors.
* `demo/` — the demo store, the seeded world, the quote simulator, the thirteen
  `Demo*Repository` implementations, the control panel, the assets.
* `androidMain` / `iosMain` — platform actuals only. **No Compose UI has been written
  yet**: the theme, navigation host, screens and app shells are still to come, so the
  app cannot be launched even after it compiles.

Written but not compiled on the build side: `androidApp/build.gradle.kts`
(`articals.mode`, the company/admin flavours and the demo-release guard),
`composeApp/build.gradle.kts`, `gradle/libs.versions.toml`.

## 3. Known traps for the first build

1. **Firebase DTOs.** GitLive's Firestore mapper has no `ignoreUnknownKeys` knob, so
   every field of every document DTO must be nullable or defaulted. Keep it that way
   when adding fields, and mirror new backend fields as nullable.
2. **Firestore queries in the client avoid `orderBy`.** All client reads use equality
   filters and sort in Kotlin, so a missing composite index cannot break a screen.
   `firestore.indexes.json` (21 composites) exists for the backend and the console.
3. **Money is `Long` minor units.** No `Float`/`Double` in anything that is money —
   that includes progress bars and chart maths.
4. **`RIDER_COST_METHODS` has no `percentage_of_charge`.** Do not add it to the UI.
5. **`SessionRepository` must not regain `cacheClaimsOnNextLaunch`.**
6. **`.mts` matters.** `functions/vitest.config.mts` must stay `.mts`.
7. **Rules tests need the emulator JAR**; that is why `npm run test:rules` is the
   owner's job.
8. **Task names.** With AGP 9's `com.android.kotlin.multiplatform.library`, the
   Android variant compile task names differ from the old `androidTarget`. Run
   `./gradlew :composeApp:tasks --all` once and record the real names here.

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

Record the outcome of each line here as it is run, and update §2 as the UI lands.

## 5. Still to write

* Compose UI: design system, navigation, screens in the priority order given in the
  Phase 2 brief, the app shells (`articals.mode` → `AppConfig`), the demo controls
  panel, the DEMO badge.
* `commonTest`: repository contract tests against the demo implementations and the
  `DemoQuoteSimulator` canonical vectors.
* `iosApp/`: the Swift entry point and `project.yml` (XcodeGen).
* Documentation: `DEMO_WALKTHROUGH.md`, `DEMO_TO_FIREBASE.md`, `PILOT_RUNBOOK.md`,
  `MX_RATE_CARD_WORKSHEET.md` (+ CSV).
