package com.articals.logistics.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.articals.logistics.core.AppConfig
import com.articals.logistics.di.SupportLocator
import com.articals.logistics.di.startArticalsLogistics
import com.articals.logistics.ui.nav.ArticalsNavigation
import com.articals.logistics.ui.theme.ArticalsTheme
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings

/**
 * The whole application, shared by Android and iOS.
 *
 * The platform shells (an `Activity` on Android, a SwiftUI host on iOS) do three
 * things and nothing else: install what the shared code needs from the platform,
 * build the dependency graph for the configured mode, and put this composable on
 * screen.
 *
 * If no graph was started — which is what happens in a Compose preview or a
 * screenshot test — this starts the demo one, so a screen can always be looked at
 * without a Firebase project and without a fake application object.
 */
@Composable
fun ArticalsApp(config: AppConfig = SupportLocator.config()) {
    if (!SupportLocator.isStarted) {
        val started = remember { startArticalsLogistics(config) }
        DisposableEffect(started) {
            onDispose { started.close() }
        }
    }

    val preferences = rememberPreferences(SupportLocator.settingsOrNull())
    val fontScale by preferences.fontScale.collectAsState()
    val darkMode by preferences.darkMode.collectAsState()

    ArticalsTheme(darkMode = darkMode, fontScale = fontScale) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                ArticalsNavigation(
                    appPreferences = preferences,
                    config = config,
                )
            }
        }
    }
}

/**
 * The preferences binding, or an in-memory stand-in when the graph is a preview.
 *
 * A preview has no dependency graph and therefore no platform storage, but it must
 * still be able to render the theme, so it gets a map that forgets everything when
 * the preview closes.
 */
@Composable
private fun rememberPreferences(settings: Settings?): AppPreferences {
    val resolved = settings ?: remember { MapSettings() }
    return remember(resolved) { AppPreferences(resolved) }
}
