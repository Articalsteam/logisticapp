package com.articals.logistics.platform

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.articals.logistics.core.appCheckToken
import com.articals.logistics.core.appVersionName
import com.articals.logistics.core.copyToClipboard
import com.articals.logistics.core.devicePushToken
import com.articals.logistics.core.isDebugBuild
import com.articals.logistics.core.logEvent
import com.articals.logistics.core.openDialer
import com.articals.logistics.core.openInExternalMap
import com.articals.logistics.core.platformName
import com.articals.logistics.core.sharePlainText
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Holds the application context for the platform layer.
 *
 * Set once from the Android entry point's `Application.onCreate`, before any
 * repository can be reached. Keeping it here — rather than passing a Context
 * through every call — is what lets the shared code stay context-free.
 */
object AndroidPlatform {
    private var application: Application? = null

    fun install(app: Application) {
        application = app
    }

    fun context(): Context = requireNotNull(application) {
        "AndroidPlatform.install() was not called: the Android entry point must install the context before use."
    }
}

actual fun platformName(): String = "Android ${Build.VERSION.RELEASE}"

actual fun isDebugBuild(): Boolean {
    val flags = AndroidPlatform.context().applicationInfo.flags
    return (flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
}

actual fun appVersionName(): String = runCatching {
    val context = AndroidPlatform.context()
    @Suppress("DEPRECATION")
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    "${info.versionName ?: "1.0"} (${info.longVersionCode})"
}.getOrDefault("1.0")

actual fun logEvent(tag: String, message: String, throwable: Throwable?) {
    // Crashlytics survives the app being killed; plain Logcat does not, which is why
    // operational breadcrumbs go there and not to the console.
    runCatching {
        FirebaseCrashlytics.getInstance().log("$tag: $message")
        if (throwable != null) FirebaseCrashlytics.getInstance().recordException(throwable)
    }
}

actual suspend fun devicePushToken(): String? = runCatching {
    suspendCancellableCoroutine { continuation ->
        FirebaseMessaging.getInstance().token
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    continuation.resume(task.result)
                } else {
                    // No Play services, no network, or notifications blocked: the app
                    // simply does not register a token rather than inventing one.
                    continuation.resume(null)
                }
            }
    }
}.getOrNull()

actual suspend fun appCheckToken(): String? = runCatching {
    suspendCancellableCoroutine { continuation ->
        FirebaseAppCheck.getInstance().getAppCheckToken(false)
            .addOnCompleteListener { task ->
                continuation.resume(if (task.isSuccessful) task.result?.token else null)
            }
    }
}.getOrNull()

actual suspend fun openInExternalMap(latitude: Double, longitude: Double, label: String): Boolean = runCatching {
    val context = AndroidPlatform.context()
    val uri = Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude(${Uri.encode(label)})")
    val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
    true
}.getOrDefault(false)

actual suspend fun openDialer(phoneNumber: String): Boolean = runCatching {
    val digits = phoneNumber.filter { it.isDigit() || it == '+' }
    if (digits.isEmpty()) return@runCatching false
    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    AndroidPlatform.context().startActivity(intent)
    true
}.getOrDefault(false)

actual suspend fun sharePlainText(subject: String, text: String): Boolean = runCatching {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    val chooser = Intent.createChooser(intent, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    AndroidPlatform.context().startActivity(chooser)
    true
}.getOrDefault(false)

actual fun copyToClipboard(text: String, label: String) {
    runCatching {
        val context = AndroidPlatform.context()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }
}

/** Opens this app's notification settings so a declined permission can be granted. */
fun openNotificationSettings(): Boolean = runCatching {
    val context = AndroidPlatform.context()
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
    true
}.getOrDefault(false)

/** True when the platform package actually lists a handler for [uri]. */
fun canResolve(uri: Uri): Boolean {
    val context = AndroidPlatform.context()
    val intent = Intent(Intent.ACTION_VIEW, uri)
    return context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()
}
