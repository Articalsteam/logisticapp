# Verified versions

Every version below was read from the real registry on **2026-10-14** — Google
Maven or Maven Central `maven-metadata.xml`, the GitHub release feed, or the npm
registry — not from a blog post. `gradle/libs.versions.toml` and
`functions/package.json` are the single sources of truth for the build; this file
records the evidence.

## Build tooling

| Component | Version | Evidence |
| --- | --- | --- |
| Android Gradle Plugin | 9.4.2 | `dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml` |
| Kotlin | 2.4.21 | Maven Central metadata for `org.jetbrains.kotlin:kotlin-stdlib` |
| Gradle | 9.6.0 (minimum for AGP 9.4) | AGP 9.4 release notes |
| Compose Multiplatform | 1.12.1 | `org.jetbrains.compose` plugin marker on Maven Central |
| JDK toolchain | 17 | AGP 9 requirement |

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
