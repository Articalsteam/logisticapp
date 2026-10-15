package com.articals.logistics.platform

import platform.UIKit.UIViewController

/**
 * The few things iOS must hand to shared code from Swift.
 *
 * The Apple implementations deliberately stay thin: anything that needs a view
 * controller (the share sheet) or a delegate (APNs) is passed in by the Swift entry
 * point, so shared code never reaches for the key window.
 *
 * This object is deliberately *not* part of `com.articals.logistics.core`: the
 * `expect`/`actual` pairs for platform services live in the package that declares
 * them, and everything iOS-specific that has no expect goes here.
 */
object IosPlatformBridge {
    /**
     * Set by the Swift app (`iosApp/`) once the scene is up. The share sheet needs a
     * presenting view controller, and only Swift knows which one is on screen.
     */
    var presentingViewController: UIViewController? = null

    /**
     * FCM registration token, set by Swift from `MessagingDelegate`.
     *
     * Shared code cannot receive it by itself: on iOS the token arrives through an
     * Objective-C delegate that the host app must install before Firebase is used.
     */
    var fcmToken: String? = null

    /** App Check token, when App Check is configured in the Xcode target. */
    var appCheckToken: String? = null
}
