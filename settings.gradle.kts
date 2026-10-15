rootProject.name = "ArticalsLogistics"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

// ---------------------------------------------------------------------------
// Module layout (see docs/ARCHITECTURE_DECISIONS.md):
//
//   :composeApp — Kotlin Multiplatform shared code and the entire Compose
//                 Multiplatform interface. Targets: android (as a library),
//                 iosArm64, iosSimulatorArm64 (no iosX64 — see ADR-015).
//   :androidApp — the Android application entry point. AGP 9 does not allow
//                 `com.android.application` and the Kotlin Multiplatform plugin in
//                 the same module, so the entry point is its own thin module.
//
// iOS is built from iosApp/ with Xcode, which consumes the framework produced by
// :composeApp (see iosApp/README.md and docs/SETUP.md).
// ---------------------------------------------------------------------------
include(":composeApp")
include(":androidApp")
