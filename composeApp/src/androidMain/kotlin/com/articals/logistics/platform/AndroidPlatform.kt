package com.articals.logistics.platform

import android.app.Application
import android.content.Context

/**
 * Holds the application context for the platform layer.
 *
 * Set once from the Android entry point's `Application.onCreate`, before any
 * repository can be reached. Keeping it here — rather than passing a Context
 * through every call — is what lets the shared code stay context-free.
 *
 * This object is deliberately *not* part of `com.articals.logistics.core`: the
 * `expect`/`actual` pairs for platform services live in the package that declares
 * them, and everything Android-specific that has no expect goes here.
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
