package com.articals.logistics.core

/**
 * Error model.
 *
 * Every failure the user can see is one of these, so that the interface never
 * shows a raw exception or an English string written by a Firebase SDK. The
 * backend supplies a stable machine code (`detail`) and a message that is already
 * safe to display; we prefer that message and fall back to our own copy.
 */
sealed class AppError(
    /** Safe to show in the interface. */
    val userMessage: String,
    /** Stable machine code from the backend, when there is one. */
    val detail: String? = null,
    /** Field-level validation errors, when the backend supplies them. */
    val fieldErrors: Map<String, String> = emptyMap(),
    val cause: Throwable? = null,
) {
    /** True when simply trying again may work. */
    open val isRetryable: Boolean get() = false

    /** True when the action is blocked by billing rather than by a bug. */
    open val isEntitlementBlock: Boolean get() = false

    class Offline(cause: Throwable?) : AppError(
        "You appear to be offline. Check your connection and try again.",
        detail = "network_unavailable",
        cause = cause,
    ) {
        override val isRetryable = true
    }

    class Unauthenticated(detail: String? = null, cause: Throwable? = null, message: String? = null) : AppError(
        message ?: "Your session has expired. Sign in again to continue.",
        detail ?: "unauthenticated",
        cause = cause,
    )

    class NotAMember(detail: String? = null, cause: Throwable? = null) : AppError(
        "You do not have access to this company workspace.",
        detail ?: "not_a_member",
        cause = cause,
    )

    class PermissionDenied(detail: String? = null, message: String? = null, cause: Throwable? = null) : AppError(
        message ?: "Your role in this company does not allow that action.",
        detail ?: "permission_denied",
        cause = cause,
    )

    class NotFound(detail: String? = null, message: String? = null, cause: Throwable? = null) : AppError(
        message ?: "That record no longer exists.",
        detail ?: "not_found",
        cause = cause,
    )

    class InvalidInput(
        detail: String? = null,
        message: String? = null,
        fields: Map<String, String> = emptyMap(),
        cause: Throwable? = null,
    ) : AppError(
        message ?: "Some of the details you entered need correcting.",
        detail ?: "invalid_argument",
        fields,
        cause,
    )

    /** A business rule refused the action (for example a status change out of order). */
    class Rejected(detail: String? = null, message: String? = null, cause: Throwable? = null) : AppError(
        message ?: "That action is not allowed right now.",
        detail,
        cause = cause,
    )

    class EntitlementLimited(detail: String? = null, message: String? = null, cause: Throwable? = null) : AppError(
        message ?: "This workspace is read-only until its subscription is renewed. You can still view and export your data.",
        detail ?: "entitlement_limited",
        cause = cause,
    ) {
        override val isEntitlementBlock = true
    }

    class RateLimited(cause: Throwable? = null) : AppError(
        "Too many attempts. Please wait a moment and try again.",
        detail = "resource_exhausted",
        cause = cause,
    ) {
        override val isRetryable = true
    }

    class Server(cause: Throwable? = null, detail: String? = null) : AppError(
        "Something went wrong on our side. Please try again.",
        detail = detail ?: "internal",
        cause = cause,
    ) {
        override val isRetryable = true
    }

    class Unknown(cause: Throwable?) : AppError(
        "Something unexpected happened. Please try again.",
        detail = "unknown",
        cause = cause,
    ) {
        override val isRetryable = true
    }
}

/** Small helper so screens can offer a retry action only when it would help. */
val AppError.canRetry: Boolean get() = isRetryable
