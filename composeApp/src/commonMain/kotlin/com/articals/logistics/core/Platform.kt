package com.articals.logistics.core

/**
 * Everything platform-specific that the shared code needs, expressed as a narrow
 * set of expect declarations. Each platform supplies its own implementation:
 *
 *   androidMain — Android SDK APIs (Firebase Messaging, Play Integrity, intents)
 *   iosMain     — Apple frameworks (UIBackgroundTask free share sheet, Firebase iOS)
 *
 * Keeping this list short is deliberate: the more platform code, the more places the
 * two applications can drift apart.
 *
 * Offline state is deliberately *not* probed here. The app learns that it is
 * offline from a typed [AppError.Offline], which the error mapper derives from the
 * SDK's own network failure; a hand-rolled reachability check would only duplicate
 * — and occasionally contradict — what Firestore and Functions already know.
 */

/** "Android 14" / "iOS 18.1" — shown in the diagnostics footer, never in the flow. */
expect fun platformName(): String

/** True for debug builds; used to allow the App Check debug provider and verbose logs. */
expect fun isDebugBuild(): Boolean

/** Diagnostic identifier for crash reports: the app version and build number. */
expect fun appVersionName(): String

/** Crash/analytics breadcrumb. Wired to Crashlytics; a no-op in tests. */
expect fun logEvent(tag: String, message: String, throwable: Throwable?)

/**
 * The FCM registration token, or null when push is unavailable on this device
 * (no Play services, no APNs token yet, or the platform bridge has not supplied one).
 *
 * A null result means "do not register" — never a fabricated token.
 */
expect suspend fun devicePushToken(): String?

/**
 * The App Check token for anything the app calls directly, outside the Firebase SDKs.
 * Firebase SDK calls (Auth, Firestore, Functions, Storage) attach App Check themselves.
 */
expect suspend fun appCheckToken(): String?

/** Opens a location in the device's map application. Returns false if it could not. */
expect suspend fun openInExternalMap(latitude: Double, longitude: Double, label: String): Boolean

/** Opens the dialer or another app for a phone number. */
expect suspend fun openDialer(phoneNumber: String): Boolean

/** Native share sheet for a tracking link or an invitation link. */
expect suspend fun sharePlainText(subject: String, text: String): Boolean

/** Copies text to the clipboard. */
expect fun copyToClipboard(text: String, label: String)
