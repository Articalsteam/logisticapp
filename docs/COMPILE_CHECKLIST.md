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
| Named arguments against every `class` in the tree | `python3 tools/check_dto_args.py composeApp/src` | 1 734 named arguments across 143 files, **0 problems** |
| Member access on the demo objects | `python3 tools/check_member_calls.py composeApp/src store=DemoStore controls=DemoControls simulator=DemoQuoteSimulator world=DemoWorld` | 3 388 accesses, **2 problems — both false positives**, see below |
| Imports resolve inside the tree | `python3 tools/check_imports.py composeApp/src` | 1 344 imports across 143 files, **0 problems** |
| Named arguments against our own functions | `python3 tools/check_named_args.py composeApp/src` | 1 651 named arguments across 143 files, **0 problems** |
| Every file is structurally closed | `python3 tools/check_balance.py .` | 145 `.kt` files, **0 problems** |
| No parameter is accepted and ignored | `python3 tools/check_unused_params.py composeApp/src` | 143 files, **0 problems** — plus 2 exemptions declared in source (`Platform.ios.kt`: the iOS share sheet has no subject, `UIPasteboard` has no label). Every exemption is printed on each run so it cannot be forgotten. |
| Rules tests | `cd functions && npm run test:rules` (Firestore emulator) | **Not run here** — the emulator JAR is not downloadable from this sandbox. Owner's machine only. |
| Codemagic workflows | `codemagic.yaml` (three workflows) | **Run at last.** `android-checks` now reaches Gradle (see the two rows below); the workflow itself no longer fails on configuration or on the wrapper. |
| **Gradle 9.6.0 runs on Codemagic** | `./gradlew --version` — step *Resolve the Gradle distribution* | **Run and passed.** The wrapper found its pinned distribution, downloaded it on the macOS runner and printed the version. The wrapper question (traps 11 and 12) is closed; from here the only question is the code. |
| **The first real Gradle builds** | run 1: `./gradlew --console=plain --stacktrace :composeApp:compileKotlinMetadata`; run 2: `./gradlew --console=plain --stacktrace :composeApp:compileCommonMainKotlinMetadata` — step *Compile commonMain metadata* | **Both failed, and the second one failed honestly: a pinned version was wrong.** Run 1 (12 s) ran a task KGP disables (trap 13), so its tail said nothing. Run 2 (21 s) printed the answer: `Plugin [id: 'com.android.application', version: '9.4.2', apply: false] was not found in any of the following sources … could not resolve plugin artifact 'com.android.application:com.android.application.gradle.plugin:9.4.2'`. **AGP 9.4.2 does not exist** — Google Maven's metadata for that marker and for `com.android.tools.build:gradle` both end the 9.4 line at **9.4.1**. The failure happens while configuring `:androidApp`, before any Kotlin compiles, which is why the log contains no `e:` lines at all. Corrected in `gradle/libs.versions.toml` (9.4.1); evidence and the mistake's shape in `docs/VERSIONS.md`. **Superseded by the rows below: the Kotlin compiler has since run over this code.** |
| **The third run — the first with plugins resolved** | `./gradlew --console=plain --stacktrace :composeApp:compileCommonMainKotlinMetadata` | **Ran for 2 m 28 s and failed on an iOS target that Compose does not publish.** AGP 9.4.1 resolved, configuration completed, dependencies downloaded, and the build reached `:composeApp:transformCommonMainDependenciesMetadata`. It failed because the `iosX64` metadata classpath can never resolve: `Could not resolve all files for configuration ':composeApp:iosX64CompilationDependenciesMetadata' > Could not resolve org.jetbrains.compose.runtime:runtime:1.12.1 … No matching variant … needed 'org.jetbrains.kotlin.native.target' with value 'ios_x64'`. Compose Multiplatform 1.12.x publishes no `iosX64` artifacts (the last was `1.11.0-alpha01`), so `iosX64()` is gone from `:composeApp` — **ADR-015**. The step's own failure block worked as intended: the message was inside the 50 lines the UI shows, no "Load full log" needed. |
| **The Kotlin compiler ran** | `./gradlew --console=plain --stacktrace :composeApp:compileCommonMainKotlinMetadata` (run 4) | **2 m 40 s, 5 tasks executed, and the first real Kotlin errors in this project's history.** Nothing earlier in the pipeline is broken any more: the task executed and the compiler ran on the shared code. It reported `core/Time.kt:41:50 Unresolved reference 'number' on receiver of type 'Month'` plus eight further errors in `data/FirebaseErrorMapper.kt`, where the compiler fell back to the standard library's `Char.code`. Both are the same mistake — extension properties written as if they were members, and the GitLive ones imported under aliases — fixed with `import kotlinx.datetime.number` and by splitting the mapper one file per SDK (trap 17). The step also now prints **every** error line last, with a per-file count, because the 50-line window had truncated the list at eight. **No compile has completed yet, and no `commonTest` test has ever run.** |
| Gradle wrapper restore | `rm -f gradlew gradlew.bat gradle/wrapper/* && bash tools/ci/ensure_gradle_wrapper.sh 9.6.0` | **Run.** Downloads the wrapper from `gradle/gradle@v9.6.0`, validates the jar's manifest, writes the properties, and reports the pin. A second run is a no-op. |
| The wrapper runs here too, as far as the network allows | `JAVA_HOME=<jdk4py Temurin 25> ./gradlew --version` | **Run, and it works up to the network.** The jar loads, `org.gradle.wrapper.GradleWrapperMain` starts, the properties parse, the pinned URL `…/gradle-9.6.0-bin.zip` is resolved, and the `retries=3` / `retryBackOffMs=500` written into those properties are honoured (`Attempt 1/4 … 4/4`). The download itself fails with `SSLHandshakeException` because **this sandbox has no route to services.gradle.org** — so Gradle has still never run, and no dependency has ever been resolved. |
| Wrapper bootstrap under Gradle 8.14.1 (the old approach) | a stub of 8.14.1's behaviour, then twice on Codemagic | **Failed on Codemagic**, twice, and is gone. See trap 11. |

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

Everything under `composeApp/src/` — 134 Kotlin files, plus 9 test files. It has been
through the compiler **once** (see the last two rows of §1); none of it has compiled
cleanly yet:

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
(`articals.mode`, the company/admin flavours, the demo-release guard, and release
signing that reads Codemagic's `CM_KEYSTORE_PATH` / `CM_KEYSTORE_PASSWORD` /
`CM_KEY_ALIAS` / `CM_KEY_PASSWORD` when they are present — and builds an unsigned
release, exactly as before, when they are not), `composeApp/build.gradle.kts`,
`gradle/libs.versions.toml`, `codemagic.yaml` and `tools/ci/ensure_gradle_wrapper.sh`.

**There is no Gradle wrapper in the repository.** `./gradlew` therefore does not exist on
a fresh clone: the CI workflow generates it, and so must anybody building locally:

```bash
bash tools/ci/ensure_gradle_wrapper.sh 9.6.0
```

The script generates it with whatever Gradle is installed, in a scratch directory — it
cannot be generated inside the checkout, because this build's settings apply AGP 9.4,
which refuses to configure on Gradle 8.x (the version the CI images ship). It is
idempotent, so committing the wrapper later retires it.

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

11. **Generating a wrapper with an installed Gradle does not work here — do not try.**
    Twice, `android-checks` died at "Bootstrap the Gradle wrapper" with
    `Directory '/private/var/folders/…/tmp.…' does not contain a Gradle build`. Gradle 8.x
    refuses to run *any* task, `wrapper` included, in a directory that is not a Gradle
    build — and this project cannot BE a Gradle build for 8.x, because its settings apply
    AGP 9.4, which requires Gradle 9.6.0. Adding an empty `settings.gradle.kts` to the
    scratch directory satisfies the first complaint but cannot satisfy the second, and it
    would have been papering over the real problem.
    **The wrapper is now committed** (`gradlew`, `gradlew.bat`,
    `gradle/wrapper/gradle-wrapper.jar`, `gradle-wrapper.properties` pinned to 9.6.0),
    fetched from `gradle/gradle@v9.6.0` — Gradle publishes its own wrapper files in its
    repository, so no Gradle and no JDK are needed to obtain them. `./gradlew` then
    downloads the 9.6.0 distribution itself on first use.
    `tools/ci/ensure_gradle_wrapper.sh` only restores the files if they go missing.

    Provenance of the committed wrapper, so it can be checked rather than trusted:

    | File | sha256 |
    | --- | --- |
    | `gradle/wrapper/gradle-wrapper.jar` | `497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7` |
    | `gradlew` | `e01b5c97892572c82405c02b96a3382379100e7d825ce7c48883d95c26928750` |

    Fetched from `https://api.github.com/repos/gradle/gradle/contents/gradle/wrapper/gradle-wrapper.jar?ref=v9.6.0`
    (and the same for `gradlew`, `gradlew.bat`) at release tag `v9.6.0`, published
    2026-06-19 and not a prerelease (`api.github.com/repos/gradle/gradle/releases/tags/v9.6.0`).
    `gradle-wrapper.properties` is ours, not Gradle's: `gradle/wrapper/gradle-wrapper.properties`
    at that tag points at the 9.6.0 **rc-3** distribution, which is not what this project
    builds against.

    `distributionSha256Sum` is deliberately **not** set: it would pin the distribution
    archive's hash, and services.gradle.org — the only place that hash comes from — is
    unreachable from the sandbox where these files were prepared. A guessed checksum
    would fail every build. Add it on a machine that can download the archive
    (`./gradlew --version` on the owner's machine, then the published `.sha256` file).


12. **Codemagic rejects `max_build_duration: 180` before any step runs.** The validation
    error reads `value 180 is out of expected bounds (1, 120)` — the field's ceiling is
    120 minutes, and a rejected configuration means *nothing* runs, so it looks like a
    build failure when it is a schema failure. All three workflows now sit at 120.
    The pressure that made 180 look attractive is real: `:composeApp:allTests` compiles the
    Kotlin/Native iOS targets, and a cold run downloads that toolchain (~1 GB) before the
    first test executes. `$HOME/.konan` is cached, so only the first run pays. If a cold
    run does approach the ceiling, split `:composeApp:iosSimulatorArm64Test` into its own
    workflow instead of raising the number — two workflows, each comfortably inside the
    cap, and the commonMain compile error arrives in minutes rather than after an hour of
    toolchain download.


13. **`:composeApp:compileKotlinMetadata` compiles nothing in Kotlin 2.4.x.** The
    metadata target's legacy `main` compilation is configured with
    `compileTaskProvider.configure { it.onlyIf { false } }`, and its classpath is cleared
    (`compileDependencyFiles = project.files()`). Verified in
    `KotlinMetadataTargetConfigurator.kt` at tag `v2.4.21` of `JetBrains/kotlin`
    (`libraries/tools/kotlin-gradle-plugin/src/common/kotlin/org/jetbrains/kotlin/gradle/targets/metadata/`).
    Since the hierarchical layout, the real work is done by *per-source-set* metadata
    compilations: KGP creates one for every common source set that more than one target
    compiles, named after the source set. The task that actually compiles commonMain is
    **`:composeApp:compileCommonMainKotlinMetadata`**. The legacy task therefore proves
    nothing — it either succeeds without compiling a line, or fails because of some other
    task in its graph, which is why its failure says nothing about the shared code.
    The fast pass in `codemagic.yaml` now runs the per-source-set task.

    Reading a CI log: the fast-pass step keeps the whole output in
    `/tmp/gradle-metadata-compile.log` and, when it fails, prints the Kotlin `e:` lines and
    Gradle's `* What went wrong:` block **last**, because the Codemagic UI shows only the
    last 50 lines of a step and a `--stacktrace` tail is a wall of Gradle internals with no
    message in it. `--stacktrace` is still passed, so the trace is in the full log.

14. **`Configuration cache entry stored.` is not evidence that configuration succeeded.**
    The first `android-checks` run printed it immediately before `BUILD FAILED`, and this
    checklist briefly recorded "the build configured fine" on the strength of it. That was
    wrong: Gradle stores the entry for whichever projects did configure, and the build had
    failed *while configuring* `:androidApp`. Only the `* What went wrong:` block says what
    happened.
    Related, from the same two runs:

    * A plugin that cannot be resolved is reported by its **marker coordinates**
      (`com.android.application:com.android.application.gradle.plugin:9.4.2`), never with
      the reason. "was not found in any of the following sources" means the version is
      almost certainly not published — read the registry metadata as a *list* before
      changing anything else. That is how AGP 9.4.2 (which does not exist) became 9.4.1.
    * A failure at configuration ends in daemon plumbing frames with no task name in them.
      The last 50 lines of such a log carry no information; the message is above them,
      which is why the fast-pass step now prints the `* What went wrong:` block last.
    * 12–21 s means the failure is in configuration or in a task that runs before
      compilation — never in the Kotlin compiler itself. A cold Kotlin compile of this
      module takes minutes.

15. **Do not "tidy" the `android.*` properties in `gradle.properties` on a blog's
    say-so.** AGP 9.0's release notes list exactly two *removed* properties
    (`android.defaults.buildfeatures.aidl`, `android.defaults.buildfeatures.renderscript`)
    and exactly two *enforced* ones — AGP throws an error if they are set:
    `android.r8.integratedResourceShrinking` and
    `android.enableNewResourceShrinker.preciseShrinking`. This project sets none of the
    four. `android.builtInKotlin=true` is a supported AGP 9 property (it is the default;
    the DSL/API roadmap documents pairing it with `android.newDsl=true` to rehearse AGP 10
    behaviour), `android.useAndroidX=true`, `android.nonTransitiveRClass=true` and
    `android.enableJetifier=false` are all still accepted. Several third-party write-ups
    claim AGP 9 hard-fails on `enableJetifier`/`useAndroidX`; the release notes, which are
    what the Upgrade Assistant enforces, say otherwise — and the fast checks in §1 were
    re-run against those notes on 2026-10-15.

16. **Reading a KMP variant-resolution failure.** The message names the configuration, the
    module it could not resolve, and the attributes the *consumer* asked for
    (`org.jetbrains.kotlin.platform.type = native`,
    `org.jetbrains.kotlin.native.target = ios_x64`); then it lists the variants the module
    actually has. When the variants it lists are all `android*` or `jvm`, the publisher
    has no artifact for that target at that version — it is not a mistake on our side.
    Check it in two reads, neither of which needs a JDK:
    `https://repo1.maven.org/maven2/<group-path>/` lists every per-target module a group
    publishes (`runtime-iosarm64`, `runtime-iosx64`, …), and
    `…/<group-path>/<artifact>-<target>/maven-metadata.xml` lists the versions that exist
    for that target. Compose Multiplatform 1.12.x: `runtime-iosarm64` → 1.12.1,
    `runtime-iosx64` → `1.11.0-alpha01` only. GitLive, Koin and Coil all publish `iosx64`,
    so this was Compose's limit and the target was dropped (ADR-015).
    A related shape worth recognising: **"Configuration cache state could not be cached …
    error writing value of type `DefaultConfigurableFileCollection`"** was not the problem
    — it was Gradle failing to serialise a task input it could not resolve. The cause is
    in the `> Could not resolve …` lines underneath it.

17. **Extension properties need imports, and an import alias takes the name out of scope.**
    The first compiler run produced errors that were one mistake in two libraries: code
    written as if a property were a member of the class.
    * kotlinx-datetime: `Month.number` is `public val Month.number: Int get() = ordinal + 1`
      in package `kotlinx.datetime`, not a member of the enum — hence
      `import kotlinx.datetime.number`. `DayOfWeek.isoDayNumber` has the same shape, and
      the `toLocalDateTime`/`toInstant` conversions are extension *functions* on
      `kotlin.time.Instant` (Time.kt already imported those).
    * GitLive: `FirebaseFunctionsException.code` and `.details`,
      `FirebaseFirestoreException.code`, and `FirebaseAuthException.code` (a nullable
      string) are all extension properties in their own packages — as are the
      `Firebase.auth`/`.firestore`/`.functions`/`.storage` accessors, which were already
      imported by name.
    The symptom is what wastes the time. With
    `import dev.gitlive.firebase.functions.code as functionsCode` and `exception.code` in
    the body, the compiler does **not** report an unresolved reference at the import; it
    finds the standard library's `Char.code` instead and says
    `Candidate 'val Char.code: Int' is inapplicable because of a receiver type mismatch`,
    then treats the `when` subject as `Int` and adds
    `Comparison of incompatible enums 'Int' and 'FunctionsExceptionCode'` for every branch.
    Both messages point at the comparison, not at the import.
    An alias renames a declaration, and for an extension property that leaves the original
    name — the only name member access can spell — out of scope. Rather than depend on
    whether an aliased extension property can be reached under its alias, the fix was
    structural: `FirebaseErrorMapper` is a dispatcher over `FunctionsErrorMapper`,
    `FirestoreErrorMapper` and `AuthErrorMapper`, one file per SDK, each importing exactly
    one `code` extension. Do not "tidy" them back into a single file.

18. **A parameter that is accepted and ignored compiles perfectly.** Nothing in Kotlin,
    Gradle or the other checkers in this directory notices it; `tools/check_unused_params.py`
    (§1) exists for exactly this. Its first run found two real ones and one platform
    limitation:
    * `ArticalsPasswordField` took an `onDone` — both the sign-in and the register screen
      pass their submit function — and never wired `keyboardActions`, so the keyboard's
      Done key did nothing at all;
    * `CompanyLogo` took a `logoUrl` and drew the initial instead, so a logo the owner had
      uploaded was never shown, and Coil sat in the dependency list unused.
    Two false positives in the checker's own first version are worth remembering because
    they have the same shape as anything it reports: it read only the rest of the `=` line,
    so every multi-line expression body looked empty (~30 findings, nearly all wrong), and
    it counted angle brackets, so the `>` in `->` unbalanced the split and a lambda's own
    parameter (`extras` in `ActualsDialog`) was reported as the composable's.
    The checker is deliberately conservative — comments count as usage, so a clean run is
    not a proof — and a declaration that must ignore a parameter says so in writing,
    `// unused-params-allow: subject`, which is reprinted on every run.

## 4. First real build — in this order

```bash
# 0. Sanity: the demo build must fail without the opt-in flag.
./gradlew :androidApp:assembleCompanyRelease -Particals.mode=demo        # expect a clear failure
./gradlew :androidApp:assembleCompanyRelease -Particals.mode=demo -Pallow.demo.release=true  # expect success

# 1. Metadata only (fastest way to see unresolved references in shared code).
#    NOT :composeApp:compileKotlinMetadata — that task is disabled by KGP 2.4.x and
#    compiles nothing (trap 13).
./gradlew :composeApp:compileCommonMainKotlinMetadata

# 2. Android debug (demo mode is the default)
./gradlew :androidApp:assembleCompanyDebug

# 3. iOS framework (macOS only)
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64

# 4. Tests (repository contract tests + the quote simulator vectors)
./gradlew :composeApp:allTests
```

On a repository with CI connected, the same four steps run as:

```bash
bash tools/ci/ensure_gradle_wrapper.sh 9.6.0   # the wrapper, once
./gradlew :composeApp:allTests                  # workflow: android-checks (every push)
./gradlew :androidApp:assembleCompanyDebug      # workflow: android-demo
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64   # needs macOS
```

Record the outcome of each line here as it is run. As of 2026-10-15 the Kotlin compiler
has run over `commonMain` and reported real errors in our own source — which is further
than this project had ever got — but **no compile has completed** and **no test in
`commonTest` has ever executed**.

Two things the compiler cannot verify, to look at in those runs:

* the company logo — `CompanyLogo` draws the initial and loads an http(s) URL over it with
  Coil. Android should show a pasted image; on iOS, Coil 3 is expected to pick the Ktor
  engine up from `coil-network-ktor3` on the classpath. If it does not, the initial stays
  and nothing crashes — but the logo should be visible on both platforms.
* the password fields' Done key — pressing it on the sign-in and register screens should
  submit, now that `ArticalsPasswordField` wires `keyboardActions` to `onDone`.

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
* The standing truth for §4: **none of the above is compiled, and no test has run.** The
  first person with an Android SDK and a Mac owes this file the results — and, with
  Codemagic connected, `android-checks` produces them on the first push.
