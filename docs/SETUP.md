# Setup — from a clean checkout to a running pilot

Nothing in this repository contains credentials: no `google-services.json`, no
`GoogleService-Info.plist`, no service account, no API keys. This document is the
complete list of what you create locally and in Firebase, in order.

---

## 1. Tools

| Tool | Version | Why |
| --- | --- | --- |
| JDK | 17 or newer | AGP 9 requires 17+; the build pins the toolchain to 17 |
| Android Studio | latest stable | SDK 37 platform + build tools 36 |
| Node.js | 22 (see `functions/package.json` `engines`) | Cloud Functions runtime is nodejs22 |
| Firebase CLI | 15.x | deploy, emulators, secrets |
| Xcode | 16+ (macOS only) | iOS build; the shared framework is produced by Gradle |
| XcodeGen | latest (`brew install xcodegen`) | generates `iosApp/*.xcodeproj` from `iosApp/project.yml` |

Android also needs `local.properties` with your SDK path (copy
`local.properties.example`). The Gradle build does **not** need a Firebase
configuration file to configure successfully — see step 3.

---

## 2. Firebase project

Create the project (or reuse an existing one) and enable:

1. **Authentication** → Sign-in method → Email/Password.
2. **Firestore Database** → production mode, **region `europe-west1`** (matching the
   region the callables deploy to in `functions/src/index.ts`).
3. **Storage** → default bucket.
4. **Cloud Messaging** (FCM) — already on for a new project.
5. **App Check** → register the Android app with **Play Integrity** (release) and
   the iOS app with **App Attest/DeviceCheck**. Add a debug token for your
   development device and keep it out of version control.
6. **Hosting** → for the public tracking page and the two static notices.
7. **Cloud Functions** require the project to be on the Blaze plan.

---

## 3. App configuration files (never committed)

**Android.** Firebase console → Project settings → Your apps → Android → add
`com.articals.logistics` (and, if you build the debug variant, the same
applicationId — the debug suffix is applied by `androidApp/build.gradle.kts`).
Download `google-services.json` into `androidApp/`. The file layout is shown in
`androidApp/google-services.json.example`.

If the file is absent, `:androidApp` logs a warning and still configures: this is
intentional so a new contributor can build the shared module before they are given
credentials. An app built that way cannot sign in — and says so instead of failing
silently.

**iOS.** Download `GoogleService-Info.plist` into `iosApp/`, and copy
`iosApp/Config.xcconfig.example` to `iosApp/Config.xcconfig` (add your bundle id
and the Firebase settings the plist does not carry).

---

## 4. Backend secrets and environment

```bash
cd functions
cp .env.example .env          # then fill in the values you actually have

# Distance providers (optional but recommended for the pilot)
firebase functions:secrets:set GOOGLE_ROUTES_API_KEY
firebase functions:secrets:set MAPBOX_ACCESS_TOKEN
```

`functions/.env` must contain at least:

| Variable | Purpose |
| --- | --- |
| `ARTICALS_BOOTSTRAP_ADMIN_EMAILS` | comma-separated allow-list; the first time each address signs in it receives the `platformAdmin` claim |
| `PUBLIC_TRACKING_BASE_URL` | e.g. `https://<project>.web.app` — used to build the tracking link shown to dispatchers |
| `DEFAULT_CURRENCY` | `NGN` for the pilot; per-company currency is stored on the company document |
| `ENTITLEMENT_POLICY_*` | trial days (30), grace days (7), bypass max days (90) if you want to override the defaults |

Nothing else is required to start: with no routing key the backend falls back to
the dispatcher-entered distance and records that choice as a warning
(`manual_distance`) instead of inventing a distance.

---

## 5. Install and build the backend

```bash
cd functions
npm install
npm run typecheck        # tsc --noEmit
npm test                 # unit tests (pricing engine, money, statuses, entitlements)
npm run test:rules       # security rules against the emulator suite (needs Java)
```

`npm run test:rules` starts the Firestore/Storage/Auth emulators with
`firebase emulators:exec`; it downloads the emulator binaries on first run.

---

## 6. Run locally with emulators

```bash
firebase emulators:start --project demo-articals \
  --only auth,firestore,functions,storage,hosting
```

Hosting is served at `http://localhost:5000`, the emulator UI at
`http://localhost:4000`. The Android emulator reaches the host suite through
`10.0.2.2`; the iOS simulator through `localhost`. The shared module exposes
`connectToFirebaseEmulators(host)` in `androidMain`/`iosMain` for a debug build to
call from its entry point once the app shells are in place (Phase 2); nothing in a
release build can reach an emulator.

---

## 7. Build the apps

```bash
# Android (debug)
./gradlew :androidApp:assembleCompanyDebug

# Android (release, requires a keystore you own — never commit it)
./gradlew :androidApp:assembleCompanyRelease

# iOS (macOS): generate the Xcode project, then build the framework
cd iosApp && xcodegen generate && cd ..
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
```

The Android application has two flavours, `company` and `admin`; the admin flavour
is the platform console and is useless without the `platformAdmin` claim. iOS uses
the `ArticalsLogistics` scheme for companies and the `ArticalsLogisticsAdmin`
scheme for the console.

---

## 7.1 Continuous integration (Codemagic)

`codemagic.yaml` at the repository root defines three workflows. In Codemagic, add the
app with project type **Android** and project path **the repository root** — the build is
Gradle from the root, not a Flutter/React Native/MAUI project.

| Workflow | Trigger | What it does | Secrets |
| --- | --- | --- | --- |
| `android-checks` | every push | `./gradlew :composeApp:allTests` — compiles the shared module for Android and iOS and runs the 88 tests. This is the first thing that ever compiles this code. | none |
| `android-demo` | manual | `assembleCompanyDebug` + `assembleAdminDebug` → two installable demo APKs (company app, platform console). | none |
| `android-release` | manual | `assembleCompanyRelease` + `bundleCompanyRelease` in `-Particals.mode=firebase`, signed, with the Firebase configuration packaged in. | `articals_keystore`, `GOOGLE_SERVICES_JSON_BASE64` |

Two secrets to create before `android-release` can run:

1. **Android keystore** — Team settings → Code signing identities → Android keystores,
   with the reference name `articals_keystore`. Codemagic then exports `CM_KEYSTORE_PATH`,
   `CM_KEYSTORE_PASSWORD`, `CM_KEY_ALIAS` and `CM_KEY_PASSWORD`, which
   `androidApp/build.gradle.kts` reads to sign the release variant. Without them a release
   is built unsigned — locally, behaviour is unchanged.
2. **`google-services.json`** — put it in an environment group named `firebase` as a
   variable `GOOGLE_SERVICES_JSON_BASE64`:

   ```bash
   base64 < androidApp/google-services.json | tr -d '\n'
   ```

   The workflow refuses to run without it, rather than shipping an app that cannot sign
   in (that is what a firebase-mode build without the file produces).

**The Gradle wrapper is committed** (`gradlew`, `gradlew.bat`,
`gradle/wrapper/gradle-wrapper.jar`, `gradle-wrapper.properties`, pinned to **Gradle
9.6.0** — AGP 9.4's minimum). Committing it matters here rather than being a nicety: the
CI images ship Gradle 8.14.1, which cannot configure this build, and generating a
replacement wrapper needs a JDK that a bare checkout may not have.

If the binary files are ever missing — an over-eager clean, a checkout that skipped them
— restore them without any Gradle or JDK installed:

```bash
bash tools/ci/ensure_gradle_wrapper.sh 9.6.0
```

It downloads the wrapper Gradle publishes in its own repository at the release tag
(`github.com/gradle/gradle@v9.6.0`), checks that the jar really is the wrapper (a ZIP
whose manifest names `org.gradle.wrapper.GradleWrapperMain`) before copying anything, and
writes the properties file if that is gone too. In a normal checkout it does nothing.

**iOS** gets its own workflow when `iosApp/` exists: it needs a macOS instance and the
Xcode project that XcodeGen generates from `iosApp/project.yml`.

---

## 8. Deploy

```bash
firebase deploy --only functions,firestore:rules,firestore:indexes,storage,hosting
```

Order matters on the first deploy: functions first (so the callables exist), then
rules and indexes, then hosting. `firebase.json` wires the build step
(`npm --prefix "$RESOURCE_DIR" run build`) as a predeploy hook, so the TypeScript
is always compiled before it is uploaded.

---

## 9. First pilot run

1. Sign in with the address listed in `ARTICALS_BOOTSTRAP_ADMIN_EMAILS`; the
   `setPlatformAdmin` callable grants the claim and the console becomes reachable.
2. Create the company workspace (`createCompany`) — the owner membership, the
   first pricing configuration and the trial subscription are created with it.
3. Enter the rate card, add riders and customers, then place one real delivery end
   to end (quote → order → assign → status → actuals) before showing it to the
   customer. That sequence is exactly what `functions/tests/unit/workflow.test.ts`
   exercises.
4. Record the pilot's invoice with `recordOfflinePayment` and confirm the ledger
   entry and audit log line are present.

---

## 10. What you cannot do yet, on purpose

- The app cannot sell a subscription or take a card payment (App Store and Play
  policy, ADR-009).
- The app does not track riders live, read contacts, use background location or
  request broad storage permission (spec §26).
- Nothing is written to Firestore from a client: if the backend is unreachable, the
  app says so instead of queueing the change (ADR-003).
