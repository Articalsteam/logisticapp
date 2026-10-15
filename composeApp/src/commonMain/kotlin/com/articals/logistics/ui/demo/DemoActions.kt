package com.articals.logistics.ui.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.articals.logistics.core.AppError
import com.articals.logistics.data.api.DiagnosticsControls
import com.articals.logistics.di.SupportLocator

/**
 * The trigger set the demo control panel provides.
 *
 * Screens depend on *this* class, never on `com.articals.logistics.demo.*`, which is
 * what keeps demo code unreachable from a Firebase build. In a release build it is
 * inactive and every method below does nothing at all.
 *
 * The controls themselves are small and compiled in both modes, because their whole
 * purpose is to make the same screens fail on demand. What keeps them out of a
 * customer's hands is [active], which the shells set from the build configuration:
 *
 *  * demo *debug* build — available;
 *  * firebase *debug* build — available (useful before the project exists);
 *  * any release build — never, in either mode.
 */
class DemoActions internal constructor(
    /** Whether the panel may be shown at all. */
    val active: Boolean,
    private val controls: DiagnosticsControls?,
) {

    /** True when the app is currently showing fictional companies and deliveries. */
    val isDemoMode: Boolean get() = active && controls != null

    /**
     * Makes the *next* repository call fail with [error].
     *
     * Consumed by exactly one call, so it can be used while clicking through a flow:
     * force an error, tap Save, see the error state, tap "Try again", see success.
     */
    fun failNextWith(error: AppError) {
        controls?.forceNextError(error)
    }

    fun forceOffline() = failNextWith(AppError.Offline(cause = null))

    fun forcePermissionDenied() =
        failNextWith(AppError.PermissionDenied(detail = "demo_forced_permission"))

    fun forceServerError() =
        failNextWith(AppError.Server(detail = "demo_forced_server"))

    fun forceRejected() = failNextWith(
        AppError.Rejected(
            detail = "demo_forced_rejected",
            message = "This delivery is already finished and cannot be edited.",
        ),
    )

    fun forceValidationError() = failNextWith(
        AppError.InvalidInput(
            detail = "demo_forced_validation",
            message = "Some of the details you entered need correcting.",
            fields = mapOf("pickup.address" to "Enter the pickup address"),
        ),
    )

    companion object {
        /** Behaves like a normal build: used by previews and by unit tests. */
        val Inactive = DemoActions(active = false, controls = null)
    }
}

/** Resolves the trigger set from the running graph. */
@Composable
fun rememberDemoActions(): DemoActions {
    val config = SupportLocator.config()
    val controls = SupportLocator.diagnosticsOrNull()
    val active = config.allowDiagnostics
    return remember(active, controls) {
        DemoActions(active = active, controls = controls)
    }
}
