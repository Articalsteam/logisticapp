import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// =============================================================================
// :composeApp — shared Kotlin Multiplatform code and the whole Compose UI.
//
// Targets: android (library), iosArm64 (devices) and iosSimulatorArm64
// (Apple-silicon simulator). No iosX64 — see ADR-015.
// Desktop and web are deliberately absent: the MVP ships Android and iOS only
// (spec §1, ADR-006).
//
// Why this module is a *library* rather than an app: AGP 9 does not allow
// `com.android.application` alongside the Kotlin Multiplatform plugin in one
// module, so the Android application entry point lives in :androidApp (ADR-012).
// =============================================================================
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvmToolchain(17)

    android {
        namespace = "com.articals.logistics.shared"
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()
        // Compose Multiplatform resources (@Composable Res.* accessors) are packaged
        // through the Android resource pipeline.
        androidResources.enable = true
        withHostTest {}
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // iosArm64 = real devices; iosSimulatorArm64 = the simulator on Apple-silicon Macs.
    // There is deliberately no iosX64 (the Intel-Mac simulator): Compose Multiplatform
    // 1.12.x publishes no iosX64 artifacts — the last was 1.11.0-alpha01 — so that
    // target's metadata classpath can never resolve. See ADR-015.
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            // Consumed by iosApp/ through Xcode; see iosApp/README.md.
            baseName = "ArticalsLogistics"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            // --- Compose Multiplatform ------------------------------------------
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(compose.components.resources)

            // --- JetBrains multiplatform lifecycle, navigation, paging-free ----
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.androidx.navigation.compose)

            // --- Dependency injection (Koin: KMP-safe, no code generation) -----
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)

            // --- Kotlin standard ecosystem ------------------------------------
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)

            // --- Firebase (Kotlin Multiplatform wrapper) ----------------------
            implementation(libs.firebase.kmp.common)
            implementation(libs.firebase.kmp.auth)
            implementation(libs.firebase.kmp.firestore)
            implementation(libs.firebase.kmp.functions)
            implementation(libs.firebase.kmp.storage)
            implementation(libs.firebase.kmp.crashlytics)

            // --- Preferences (token hints, last workspace, theme choice) ------
            implementation(libs.multiplatform.settings)
            implementation(libs.multiplatform.settings.no.arg)

            // --- Images --------------------------------------------------------
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }

        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            implementation(libs.ktor.client.okhttp)

            // Android-only Firebase products, versioned by the BoM that the Kotlin
            // wrapper (2.7.0) was built against.
            implementation(project.dependencies.platform(libs.firebase.bom))
            implementation(libs.firebase.android.appcheck.playintegrity)
            implementation(libs.firebase.android.appcheck.debug)
            implementation(libs.firebase.android.messaging)
            implementation(libs.firebase.android.crashlytics)

            // Maps: opening a delivery address in the device map application.
            implementation(libs.play.services.maps)
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "com.articals.logistics.resources"
    generateResClass = auto
}
