# iosApp — the iOS shell

The iOS application is a thin SwiftUI shell around the shared framework produced by
`:composeApp`. Everything the user sees comes from the Compose Multiplatform UI in
`composeApp/src/commonMain`, exactly as on Android (ADR-001).

## What exists today

| File | Purpose |
| --- | --- |
| `README.md` | this document |
| `Config.xcconfig.example` | the per-developer settings the Xcode project reads (bundle id, team, Firebase flags) |

The Xcode project itself (`project.yml` + the generated `.xcodeproj`) and the Swift
entry point arrive with the app shell in **Phase 2**, together with the shared root
composable they present. They are deliberately not stubbed here: an Xcode project
that references a Kotlin entry point which does not exist yet would fail to build,
and a placeholder screen inside a shipping shell is exactly the kind of fake UI the
project rules forbid.

## How it will be built (verified approach)

The framework is produced by the Kotlin Multiplatform plugin, not by Xcode:

```bash
# 1. Generate the Xcode project from the spec (XcodeGen, once it exists)
cd iosApp && xcodegen generate

# 2. Build the shared framework for the simulator
cd .. && ./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
```

Inside Xcode, the project will run this as a pre-build step through the standard
Kotlin task, which reads Xcode's environment and copies the right framework for the
selected SDK and configuration:

```
./gradlew :composeApp:embedAndSignAppleFrameworkForXcode
```

The framework name is **`ArticalsLogistics`** (a static framework), declared in
`composeApp/build.gradle.kts`.

## Two schemes

| Scheme | Purpose | Bundle id |
| --- | --- | --- |
| `ArticalsLogistics` | the company app (owner, manager, dispatcher, rider) | `com.articals.logistics` |
| `ArticalsLogisticsAdmin` | the Articals Studio console | `com.articals.logistics.admin` |

The admin scheme compiles with `ARTICALS_ADMIN_BUILD` set, which is what the shared
code reads to offer the platform console. The security boundary is not the scheme:
every administrative callable checks the `platformAdmin` claim on the server, so an
admin build used by an ordinary account can only see the ordinary app.

## Firebase on iOS

1. Download `GoogleService-Info.plist` from the Firebase console into `iosApp/`
   (never commit it; the layout is the one Firebase gives you).
2. Copy `Config.xcconfig.example` to `Config.xcconfig` and fill in
   `DEVELOPMENT_TEAM`, `PRODUCT_BUNDLE_IDENTIFIER` and the App Check Debug token
   **only for debug builds**.
3. `FirebaseApp.configure()` must run before the shared framework is created;
   Firebase's own `AppDelegate` hook does this when the plist is present.

## What needs a Mac

Nothing in this repository can build the iOS app in a Linux environment: Xcode,
the iOS SDKs and code signing are Apple-only. The Kotlin side is written so the
framework compiles without them, and `docs/SETUP.md` lists the exact commands for a
machine that has Xcode.
