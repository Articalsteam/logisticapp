package com.articals.logistics.data.api

import com.articals.logistics.core.AppError
import com.articals.logistics.core.AppResult
import com.articals.logistics.data.UploadPayload
import com.articals.logistics.data.dto.AppNotificationDto
import com.articals.logistics.data.dto.PlaceSuggestionDto
import kotlinx.coroutines.flow.StateFlow

/**
 * Taking the proof-of-delivery photo.
 *
 * Demo mode returns a bundled placeholder image so the flow can be verified without
 * a camera permission; the Firebase implementation reports honestly that the camera
 * module is not compiled in yet (see docs/COMPILE_CHECKLIST.md). [isSimulated] is
 * what lets the screen say "simulated" instead of implying a real photo exists.
 */
interface ProofPhotoCapture {
    val isSimulated: Boolean
    suspend fun capture(): AppResult<UploadPayload>
}

/**
 * Address suggestions while typing.
 *
 * Demo mode uses a fixed table of fictional places; the Firebase implementation
 * searches the company's own delivery history in Firestore. Neither invents a
 * geocoded location, and neither can produce a distance — that stays the
 * backend's job.
 */
interface PlaceSuggestionSource {
    val isSimulated: Boolean
    suspend fun search(tenantId: String, query: String, serviceAreas: List<String>): AppResult<List<PlaceSuggestionDto>>
}

/**
 * Simulated push notifications.
 *
 * In demo mode a rider assignment shows an in-app banner so the notification flow
 * can be seen end to end. The Firebase implementation is silent: real pushes arrive
 * through FCM, and the app never fabricates one.
 */
interface InAppNotifications {
    val notifications: StateFlow<List<AppNotificationDto>>
    fun publish(title: String, body: String, simulated: Boolean)
    fun dismiss(id: String)
}

/**
 * The demo control panel.
 *
 * Registered by the demo module only; screens resolve it optionally and hide the
 * entry point when it is absent, so no Firebase-mode code depends on demo code.
 */
interface DiagnosticsControls {
    val state: StateFlow<DiagnosticsState>
    fun setNetwork(mode: DemoNetworkMode)
    fun forceNextError(error: AppError?)
    fun loadEmptyCompany()
    fun setEntitlement(entitlement: String)
    fun reset()
    fun setFontScale(scale: Float)
    fun setDarkMode(enabled: Boolean?)
}

/** Network behaviour that demo repositories simulate. */
enum class DemoNetworkMode {
    /** Fast and reliable. */
    NORMAL,

    /** Slow enough that loading states are obvious. */
    SLOW,

    /** Every call fails with the offline error until switched back. */
    OFFLINE,

    /** One call in four fails with a random retryable error. */
    FLAKY,
}

data class DiagnosticsState(
    val network: DemoNetworkMode = DemoNetworkMode.NORMAL,
    val forcedError: AppError? = null,
    val entitlementOverride: String? = null,
    val emptyCompanyLoaded: Boolean = false,
    val fontScale: Float = 1f,
    /** Null means "follow the system". */
    val darkMode: Boolean? = null,
    val latencyMillis: Long = 450L,
    /** Bumped on every reset so screens re-read the store. */
    val dataVersion: Int = 0,
)
