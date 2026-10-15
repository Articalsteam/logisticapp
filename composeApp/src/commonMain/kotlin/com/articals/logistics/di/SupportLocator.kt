package com.articals.logistics.di

import com.articals.logistics.core.AppConfig
import com.articals.logistics.data.api.DiagnosticsControls
import com.articals.logistics.data.api.InAppNotifications
import com.russhwolf.settings.Settings
import org.koin.core.KoinApplication

/**
 * The few bindings the interface reaches for *outside* a view model.
 *
 * Almost nothing in this app is resolved this way: screens take their repositories
 * through `koinViewModel`, which is what keeps them testable. Three things are
 * genuinely ambient and would be noise in every constructor:
 *
 *  1. the [AppConfig] — every screen may need to say "DEMO" or point at `docs/SETUP.md`;
 *  2. the diagnostics controls — present only in a demo (or debug) build;
 *  3. the in-app notification list — the bell, which several screens want;
 *  4. the device preferences store, which the theme reads before any screen exists.
 *
 * This is the only place the UI touches Koin directly, and every accessor returns
 * null rather than throwing when the graph was never started (previews, unit tests),
 * so a composable can be rendered on its own without a fake application.
 */
object SupportLocator {

    private var started: KoinApplication? = null
    private var activeConfig: AppConfig? = null

    /** Called by [startArticalsLogistics] once the graph is up. */
    internal fun record(application: KoinApplication, config: AppConfig) {
        started = application
        activeConfig = config
    }

    /** True once the graph exists; false in previews and isolated tests. */
    val isStarted: Boolean get() = started != null

    /**
     * The configuration of the running app.
     *
     * Falls back to [AppConfig.Demo] so that previews and tests of the interface need
     * no setup at all — and so a preview can never accidentally claim to be a
     * configured Firebase build.
     */
    fun config(): AppConfig = activeConfig ?: AppConfig.Demo

    /** The demo control panel, or null when this build has none. */
    fun diagnosticsOrNull(): DiagnosticsControls? = started?.koin?.getOrNull<DiagnosticsControls>()

    /** The in-app notification list, used by the bell and by the rider's home. */
    fun notificationsOrNull(): InAppNotifications? = started?.koin?.getOrNull<InAppNotifications>()

    /**
     * The device preferences store.
     *
     * Only the appearance choices (theme, font scale) are read this early, before any
     * screen has a view model — the rest of the app goes through [AppPreferences].
     */
    fun settingsOrNull(): Settings? = started?.koin?.getOrNull<Settings>()

    /** Test-only: forgets a previously started graph. */
    internal fun clear() {
        started = null
        activeConfig = null
    }
}
