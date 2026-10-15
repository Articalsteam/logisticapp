// =============================================================================
// :androidApp — the Android application entry point (Artials Logistics).
//
// Deliberately thin: it owns the manifest, the launcher activity, app icons and
// the Firebase configuration file. Everything else lives in :composeApp so that
// Android and iOS share one implementation (ADR-012).
//
// AGP 9 ships built-in Kotlin support, so no separate Kotlin Android plugin is
// applied here.
// =============================================================================
plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)

    // Declared (not applied) so that they are on this script's classpath and can be
    // applied conditionally below: the Firebase plugins require google-services.json,
    // and the build must still work for a developer who has not been given the
    // project's Firebase configuration yet (see README + docs/SETUP.md).
    alias(libs.plugins.googleServices) apply false
    alias(libs.plugins.firebaseCrashlytics) apply false
}

val googleServicesFile = file("google-services.json")
val firebaseConfigured = googleServicesFile.exists()

if (firebaseConfigured) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
} else {
    logger.warn(
        """
        |--------------------------------------------------------------------
        | google-services.json was not found in androidApp/.
        |
        | The build will continue, but Firebase Auth / Firestore / Messaging will
        | not be initialised at runtime and the app will show a configuration
        | message instead of signing in. That is intentional: no credentials are
        | committed to this repository.
        |
        | To enable Firebase:
        |   1. create a Firebase project (see docs/SETUP.md, "Firebase project");
        |   2. download google-services.json into androidApp/;
        |   3. copy androidApp/google-services.json.example if you need the layout.
        |--------------------------------------------------------------------
        """.trimMargin(),
    )
}

android {
    namespace = "com.articals.logistics"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.articals.logistics"
        minSdk = libs.versions.androidMinSdk.get().toInt()
        targetSdk = libs.versions.androidTargetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"
        // App Check debug token guidance lives in docs/SETUP.md; never committed.
        manifestPlaceholders["appName"] = "Articals Logistics"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            // A distinct package keeps debug installs from overwriting a pilot build.
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        // The interface itself is written in Compose Multiplatform inside
        // :composeApp; this module only needs the Compose compiler for the thin
        // Activity wrapper.
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        // Missing translations, unused resources and icon-shape opinions are not
        // release blockers for a pilot; real errors still fail the build.
        abortOnError = true
        warningsAsErrors = false
    }
}

dependencies {
    implementation(project(":composeApp"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(project.dependencies.platform(libs.firebase.bom))
}
