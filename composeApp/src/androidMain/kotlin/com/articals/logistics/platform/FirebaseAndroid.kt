package com.articals.logistics.platform

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging

/**
 * Starting Firebase on Android.
 *
 * Called from the application object *only* when the build's mode is FIREBASE and its
 * configuration file was found. The automatic initialiser is removed from the
 * manifest (see `androidApp/src/main/AndroidManifest.xml`), so demo mode genuinely
 * starts nothing — which is what makes the demo runnable on a device with no
 * Play services and no network at all.
 */
fun initializeAndroidFirebase(context: Context) {
    if (FirebaseApp.getApps(context).isNotEmpty()) return
    FirebaseApp.initializeApp(context)
}

/**
 * Turns on Cloud Messaging auto-initialisation.
 *
 * Off by default in the manifest, because a demo build must not register for push;
 * switched on here for the builds that really do receive notifications.
 */
fun enableAndroidPush() {
    runCatching { FirebaseMessaging.getInstance().isAutoInitEnabled = true }
}
