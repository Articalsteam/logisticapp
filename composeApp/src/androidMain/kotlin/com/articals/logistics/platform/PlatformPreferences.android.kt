package com.articals.logistics.platform

import android.content.Context
import com.articals.logistics.data.createPlatformPreferences
import com.russhwolf.settings.ExperimentalSettingsImplementation
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings

/**
 * Android preferences.
 *
 * Plain `SharedPreferences`: everything stored here is a preference, never a
 * credential — no tokens are persisted, because Firebase keeps its own session in
 * the platform's encrypted store.
 */
@OptIn(ExperimentalSettingsImplementation::class)
actual fun createPlatformPreferences(): Settings = SharedPreferencesSettings(
    delegate = AndroidPlatform.context().getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE),
)

private const val PREFERENCES_FILE = "articals_logistics_preferences"
