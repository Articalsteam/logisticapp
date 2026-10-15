package com.articals.logistics

import android.app.Application
import com.articals.logistics.core.AppConfig
import com.articals.logistics.di.startArticalsLogistics
import com.articals.logistics.platform.AndroidPlatform
import com.articals.logistics.platform.enableAndroidPush
import com.articals.logistics.platform.initializeAndroidFirebase

/**
 * The Android application object.
 *
 * Its whole job is to work out which data source this build runs against, and then
 * build the dependency graph for exactly that one:
 *
 *  * **DEMO** — nothing platform-side happens. No Firebase initialisation (the
 *    automatic initialiser is removed from the manifest), no push registration, no
 *    network. The in-memory demo store is the only data source.
 *  * **FIREBASE** — Firebase is initialised here, before any repository can be
 *    reached, and Cloud Messaging is switched on.
 *
 * A FIREBASE build without its configuration file does *not* fall back to demo data:
 * it starts, and shows an honest "Firebase is not configured" screen that points at
 * `docs/SETUP.md`.
 */
class ArticalsApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // The shared code is context-free; this is the one place the context is handed
        // over, and it is handed over before the graph is built.
        AndroidPlatform.install(this)

        val config = AppConfig.fromParts(
            mode = BuildConfig.ARTICALS_MODE,
            firebaseConfigured = BuildConfig.FIREBASE_CONFIGURED,
            allowDiagnostics = BuildConfig.ALLOW_DIAGNOSTICS,
            adminFlavor = BuildConfig.ADMIN_CONSOLE,
        )

        if (config.mode.isFirebase && config.firebaseConfigured) {
            initializeAndroidFirebase(this)
            enableAndroidPush()
        }

        startArticalsLogistics(config)
    }
}
