package com.articals.logistics.core

import com.articals.logistics.platform.IosPlatformBridge
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.crashlytics.crashlytics
import dev.gitlive.firebase.crashlytics.log
import dev.gitlive.firebase.crashlytics.recordException
import kotlin.native.Platform as KotlinNativePlatform
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSBundle
import platform.Foundation.NSURL
import platform.Foundation.NSURLComponents
import platform.Foundation.NSURLQueryItem
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UIKit.UIDevice
import platform.UIKit.UIPasteboard

actual fun platformName(): String = runCatching {
    val device = UIDevice.currentDevice
    "${device.systemName} ${device.systemVersion}"
}.getOrDefault("iOS")

actual fun isDebugBuild(): Boolean = runCatching { KotlinNativePlatform.isDebugBinary }.getOrDefault(false)

actual fun appVersionName(): String = runCatching {
    val info = NSBundle.mainBundle.infoDictionary
    val version = info?.get("CFBundleShortVersionString") as? String ?: "1.0"
    val build = info?.get("CFBundleVersion") as? String ?: "1"
    "$version ($build)"
}.getOrDefault("1.0")

actual fun logEvent(tag: String, message: String, throwable: Throwable?) {
    runCatching {
        Firebase.crashlytics.log("$tag: $message")
        if (throwable != null) Firebase.crashlytics.recordException(throwable)
    }
}

/**
 * Returns the token Swift handed over, or null when APNs has not produced one yet.
 * Null is a real answer: it means "not registered", and the app will try again when
 * the delegate fires.
 */
actual suspend fun devicePushToken(): String? = IosPlatformBridge.fcmToken

actual suspend fun appCheckToken(): String? = IosPlatformBridge.appCheckToken

@OptIn(ExperimentalForeignApi::class)
actual suspend fun openInExternalMap(latitude: Double, longitude: Double, label: String): Boolean = runCatching {
    val components = NSURLComponents()
    components.scheme = "https"
    components.host = "maps.apple.com"
    components.queryItems = listOf(
        NSURLQueryItem(name = "ll", value = "$latitude,$longitude"),
        NSURLQueryItem(name = "q", value = label),
    )
    val url = components.URL ?: return@runCatching false
    UIApplication.sharedApplication.openURL(url)
}.getOrDefault(false)

@OptIn(ExperimentalForeignApi::class)
actual suspend fun openDialer(phoneNumber: String): Boolean = runCatching {
    val digits = phoneNumber.filter { it.isDigit() || it == '+' }
    if (digits.isEmpty()) return@runCatching false
    val url = NSURL(string = "tel:$digits")
    UIApplication.sharedApplication.openURL(url)
}.getOrDefault(false)

// The Android side puts `subject` in EXTRA_SUBJECT. The iOS share sheet has no subject
// for the targets this app shares to — only Mail reads one, and only through an item
// source this app does not implement — so the parameter is accepted and ignored here.
// The shared text is what WhatsApp and every other target actually sends.
// unused-params-allow: subject
@OptIn(ExperimentalForeignApi::class)
actual suspend fun sharePlainText(subject: String, text: String): Boolean = runCatching {
    val presenter = IosPlatformBridge.presentingViewController ?: return@runCatching false
    val controller = UIActivityViewController(activityItems = listOf(text), applicationActivities = null)
    // iPad drops a popover share sheet that has no anchor view.
    controller.popoverPresentationController?.sourceView = presenter.view
    presenter.presentViewController(controller, animated = true, completion = null)
    true
}.getOrDefault(false)

// Android labels the clip with ClipData.newPlainText; UIPasteboard has no label field.
// unused-params-allow: label
actual fun copyToClipboard(text: String, label: String) {
    runCatching { UIPasteboard.generalPasteboard.string = text }
}

/** Opens this app's page in Settings so a declined permission can be granted. */
@OptIn(ExperimentalForeignApi::class)
fun openNotificationSettings(): Boolean = runCatching {
    val url = NSURL(string = UIApplicationOpenSettingsURLString)
    UIApplication.sharedApplication.openURL(url)
}.getOrDefault(false)
