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

// ---------------------------------------------------------------------------
// Which data source this build talks to
//
// One property switches the whole app (ADR-013). "demo" needs no Firebase project,
// no google-services.json, no network and no API keys — it is the default so that a
// fresh clone runs. "firebase" uses the real backend and requires the configuration
// file below.
//
// It is deliberately impossible to ship a demo build by accident:
//   ./gradlew :androidApp:assembleRelease -Particals.mode=demo
// fails with an explanation unless -Pallow.demo.release=true is also passed.
// ---------------------------------------------------------------------------
val articalsMode = ((findProperty("articals.mode") as String?)?.trim()?.lowercase() ?: "demo")
val allowDemoRelease = (findProperty("allow.demo.release") as String?)?.toBoolean() ?: false
/** The platform console is compiled into the admin flavour only (ADR-014). */
val adminFlavor = (findProperty("articals.admin") as String?)?.toBoolean() ?: true

require(articalsMode == "demo" || articalsMode == "firebase") {
    "articals.mode must be either \"demo\" or \"firebase\" (got \"$articalsMode\")."
}

val releaseRequested = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }
if (articalsMode == "demo" && releaseRequested && !allowDemoRelease) {
    throw GradleException(
        """
        |--------------------------------------------------------------------
        | Refusing to build a RELEASE in demo mode.
        |
        | Demo mode exists to show the product without a Firebase project. A
        | release build must talk to the real backend, otherwise customers would
        | see fictional companies, fictional riders and fictional money.
        |
        | Choose one:
        |   * build the demo on purpose:  -Particals.mode=demo -Pallow.demo.release=true
        |   * build the real thing:      -Particals.mode=firebase
        |
        | (Debug builds in demo mode need no flag: ./gradlew :androidApp:installDebug)
        |--------------------------------------------------------------------
        """.trimMargin(),
    )
}

if (articalsMode == "firebase" && !file("google-services.json").exists()) {
    logger.warn(
        """
        |--------------------------------------------------------------------
        | articals.mode=firebase but androidApp/google-services.json is missing.
        |
        | The build will continue so that the configuration can be checked, but the
        | installed app will show "Firebase is not configured" and point at
        | docs/SETUP.md. It will NOT silently fall back to demo data.
        |--------------------------------------------------------------------
        """.trimMargin(),
    )
}

/** The demo control panel is a development aid: debug builds only, never a release. */
val allowDiagnostics = articalsMode == "demo" && !releaseRequested

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

    // Two flavours, one difference: the platform administration console. A company
    // build cannot open it at all, and the backend refuses every administrative call
    // without the platform-admin claim regardless (ADR-009, ADR-014).
    flavorDimensions += "console"
    productFlavors {
        create("company") {
            dimension = "console"
            buildConfigField("boolean", "ADMIN_CONSOLE", "false")
        }
        create("admin") {
            dimension = "console"
            buildConfigField("boolean", "ADMIN_CONSOLE", "true")
            // A distinct application id so the company build and the console build can
            // both be installed on the same device during a pilot demonstration.
            applicationIdSuffix = ".admin"
        }
    }

    defaultConfig {
        buildConfigField("String", "ARTICALS_MODE", "\"$articalsMode\"")
        buildConfigField("boolean", "FIREBASE_CONFIGURED", "$firebaseConfigured")
        buildConfigField("boolean", "ADMIN_FLAVOR_DEFAULT", "$adminFlavor")
        // Demo controls appear in debug builds and in admin-flavour demos; a company
        // release never shows the panel (see core/AppConfig.kt).
        buildConfigField("boolean", "ALLOW_DIAGNOSTICS", "$allowDiagnostics")
    }

    // ---------------------------------------------------------------------------
    // Release signing
    //
    // CI (codemagic.yaml) exports CM_KEYSTORE_PATH, CM_KEYSTORE_PASSWORD, CM_KEY_ALIAS
    // and CM_KEY_PASSWORD from the keystore uploaded under "Code signing identities".
    // When they are absent — every contributor's machine, a fresh clone, the demo
    // workflows — nothing is registered and the release variant is built unsigned, so
    // nobody is ever blocked by a keystore they do not have. The keystore itself is
    // never committed (.gitignore covers *.keystore, *.jks and *.p12).
    // ---------------------------------------------------------------------------
    val ciKeystorePath = System.getenv("CM_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
    signingConfigs {
        if (ciKeystorePath != null) {
            create("release") {
                storeFile = file(ciKeystorePath)
                storePassword = System.getenv("CM_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CM_KEY_ALIAS")
                keyPassword = System.getenv("CM_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // null locally: an unsigned release, exactly as before this was added.
            signingConfig = signingConfigs.findByName("release")
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
