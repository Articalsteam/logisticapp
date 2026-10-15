# Verified versions

Every version below was read from the real registry on **2026-10-14** — Google
Maven or Maven Central `maven-metadata.xml`, the GitHub release feed, or the npm
registry — not from a blog post. `gradle/libs.versions.toml` and
`functions/package.json` are the single sources of truth for the build; this file
records the evidence.

## Build tooling

| Component | Version | Evidence |
| --- | --- | --- |
| Android Gradle Plugin | 9.4.1 | plugin-marker metadata `com/android/application/com.android.application.gradle.plugin/maven-metadata.xml` and `com/android/tools/build/gradle/maven-metadata.xml` — see the correction below |
| Kotlin | 2.4.21 | Maven Central metadata for `org.jetbrains.kotlin:kotlin-stdlib` |
| Gradle | 9.6.0 (minimum for AGP 9.4) | AGP 9.4 release notes |
| Compose Multiplatform | 1.12.1 | `org.jetbrains.compose` plugin marker on Maven Central |
| JDK toolchain | 17 | AGP 9 requirement |

**Correction (2026-10-15): AGP 9.4.2 does not exist, and this file said it did.**
`android-checks` failed with `Plugin [id: 'com.android.application', version: '9.4.2',
apply: false] was not found in any of the following sources … could not resolve plugin
artifact 'com.android.application:com.android.application.gradle.plugin:9.4.2'`.
Re-reading the registry explains it: the 9.4 line of that marker — and of
`com.android.tools.build:gradle` itself — is `9.4.0`, `9.4.1`, then `9.5.0-alpha01` …
`9.5.0-alpha10`. There is no 9.4.2 to resolve, so the build could never have started.
The pin is now **9.4.1**, which is the newest stable in the line and the version whose
plugin markers for both `com.android.application` and
`com.android.kotlin.multiplatform.library` are published.

Every other version in this file and in `gradle/libs.versions.toml` was re-checked the
same day and is correct:
`org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21`, `org.jetbrains.compose:compose-gradle-plugin:1.12.1`,
`com.google.gms:google-services:4.5.0`, `com.google.firebase:firebase-crashlytics-gradle:3.0.8`
(and their markers), `org.jetbrains.androidx.navigation:navigation-compose:2.9.2`,
`org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0`,
`io.insert-koin:koin-core:4.2.2`, `io.coil-kt.coil3:coil-compose:3.6.3`,
`androidx.compose.ui:ui-test-junit4:1.12.1`, `com.google.firebase:firebase-bom:34.18.0`.

**Reading a version list:** the head of a `maven-metadata.xml` is `<latest>`/`<release>`,
and `<latest>` is frequently a prerelease (`9.5.0-alpha10` in this case), while the
`<version>` list is chronological. Take the last entry that is *not* an alpha/beta/rc, and
never assume a patch number exists because the previous one did. See
`docs/COMPILE_CHECKLIST.md` §3 trap 14.

## Android SDK levels

| Setting | Value | Why |
| --- | --- | --- |
| compileSdk / targetSdk | 37 | maximum supported by AGP 9.4 |
| minSdk | 26 | Android 8.0 — covers the rider devices in the pilot fleet |
| Build Tools | 36.0.0 | default for AGP 9.4 |

## Client libraries

| Library | Version | Notes |
| --- | --- | --- |
| org.jetbrains.androidx.lifecycle | 2.11.0 | viewmodel/runtime-compose, KMP |
| org.jetbrains.androidx.navigation | 2.9.2 | navigation-compose, KMP |
| kotlinx-coroutines | 1.11.0 | |
| kotlinx-serialization-json | 1.11.0 | |
| kotlinx-datetime | 0.8.0 | business-date and time-zone conversion |
| Koin | 4.2.2 | core / compose / compose-viewmodel |
| Coil | 3.6.3 | compose + ktor3 network layer |
| Ktor | 3.6.0 | network engine Coil uses (OkHttp on Android, Darwin on iOS) |
| multiplatform-settings | 1.3.0 | non-sensitive preferences |
| Turbine | 1.2.1 | flow assertions in tests |
| androidx.activity:activity-compose | 1.13.0 | Android entry point |
| androidx.core:core-ktx | 1.19.1 | |
| play-services-maps | 20.1.0 | "open in maps" intent helper |
| androidx.compose.ui:ui-test-* | 1.12.1 | matches the CMP 1.12.1 base |
| androidx.test.ext:junit | 1.3.0 | |

## Firebase

| Component | Version | Notes |
| --- | --- | --- |
| dev.gitlive:firebase-* (KMP wrapper) | 2.7.0 | newest stable; `3.0.0-alpha*` exists and is not used |
| Firebase Android BOM | 34.18.0 | pinned to what GitLive 2.7.0 is built and tested against — do not bump independently |
| com.google.gms:google-services | 4.5.0 | Gradle plugin |
| com.google.firebase:firebase-crashlytics | 3.0.8 | Gradle plugin |
| Firebase iOS SDK | 13.0.1 | consumed by the iOS app through Swift Package Manager |
| GoogleMaps iOS SDK | 11.2.0 | only if the iOS app embeds a map |
| GoogleSignIn iOS SDK | 10.0.0 | not used in the MVP (email/password only) |

## Backend (`functions/package.json`)

| Package | Version |
| --- | --- |
| firebase-functions | 7.4.0 |
| firebase-admin | 14.5.0 |
| zod | 4.6.7 |
| typescript | 7.0.2 (dev) |
| vitest | 5.0.4 (dev) |
| @firebase/rules-unit-testing | 6.0.0 (dev) |
| firebase-tools | 15.34.0 (dev) |
| Node.js runtime | 22 (`engines`, and the Functions runtime in `firebase.json`) |

## Deliberate exclusions

- **No Hilt, no kotlin-inject, no KSP-based framework** in shared code: they either
  do not exist for Kotlin/Native or add a code-generation step to every build
  (ADR-001).
- **No payment SDK**: the MVP does not sell subscriptions (ADR-009).
- **No desktop or web Kotlin target**: Android and iOS only (ADR-006/012).
- **No `google-services.json` / `GoogleService-Info.plist` in the repository**; their
  shapes are documented with `.example` files.
