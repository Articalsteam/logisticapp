package com.articals.logistics.data

import com.russhwolf.settings.Settings

/**
 * Small, non-sensitive settings: the last selected workspace, dismissed banners,
 * the rider's chosen filter. Nothing here is a credential or a business record —
 * those live in Firestore and are never cached in plain preferences.
 *
 * Android backs this with SharedPreferences, iOS with NSUserDefaults; both are
 * cleared when the user signs out of the app.
 */
expect fun createPlatformPreferences(): Settings
