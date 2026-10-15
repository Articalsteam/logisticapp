package com.articals.logistics.platform

import com.articals.logistics.data.createPlatformPreferences
import com.russhwolf.settings.ExperimentalSettingsImplementation
import com.russhwolf.settings.NSUserDefaultsSettings
import com.russhwolf.settings.Settings
import platform.Foundation.NSUserDefaults

/**
 * iOS preferences.
 *
 * NSUserDefaults is the platform's own preference store, so a backup and restore
 * behaves the way the operating system expects. Nothing sensitive is stored here:
 * the signed-in session belongs to Firebase Auth, not to us.
 */
@OptIn(ExperimentalSettingsImplementation::class)
actual fun createPlatformPreferences(): Settings =
    NSUserDefaultsSettings(NSUserDefaults.standardUserDefaults)
