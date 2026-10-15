package com.articals.logistics.data

import com.articals.logistics.core.AppError
import com.articals.logistics.core.installErrorMapper
import dev.gitlive.firebase.auth.FirebaseAuthException
import dev.gitlive.firebase.auth.code as authCode
import dev.gitlive.firebase.firestore.FirebaseFirestoreException
import dev.gitlive.firebase.firestore.FirestoreExceptionCode
import dev.gitlive.firebase.firestore.code as firestoreCode
import dev.gitlive.firebase.functions.FirebaseFunctionsException
import dev.gitlive.firebase.functions.FunctionsExceptionCode
import dev.gitlive.firebase.functions.code as functionsCode
import dev.gitlive.firebase.functions.details

/**
 * Turns SDK exceptions into the app's error model.
 *
 * The backend always sends a payload of `{ code, detail, message, meta }`, where
 * `meta` carries field-level validation errors. We prefer the backend's message
 * (it is written for the end user) and keep the machine code for logging and for
 * the few places that branch on it, such as the billing banner.
 */
object FirebaseErrorMapper {

    /** Installed once at start-up, before any repository call. */
    fun install() {
        installErrorMapper(FirebaseErrorMapper::map)
    }

    fun map(throwable: Throwable): AppError = when (throwable) {
        is FirebaseFunctionsException -> fromCallable(throwable)
        is FirebaseFirestoreException -> fromFirestore(throwable)
        is FirebaseAuthException -> fromAuth(throwable)
        else -> AppError.Unknown(throwable)
    }

    private fun fromCallable(exception: FirebaseFunctionsException): AppError {
        val payload = exception.details.asStringKeyMap()
        val detail = payload.string("detail")
        val message = payload.string("message").ifBlank { null }
        return when (exception.code) {
            FunctionsExceptionCode.UNAVAILABLE, FunctionsExceptionCode.DEADLINE_EXCEEDED ->
                AppError.Offline(exception)

            FunctionsExceptionCode.UNAUTHENTICATED ->
                AppError.Unauthenticated(detail = detail, cause = exception, message = message)

            FunctionsExceptionCode.PERMISSION_DENIED ->
                if (detail == "membership_missing" || payload.string("code") == "not_a_member") {
                    AppError.NotAMember(detail = detail, cause = exception)
                } else {
                    AppError.PermissionDenied(detail = detail, message = message, cause = exception)
                }

            FunctionsExceptionCode.INVALID_ARGUMENT ->
                AppError.InvalidInput(
                    detail = detail,
                    message = message,
                    fields = payload.fieldErrors(),
                    cause = exception,
                )

            FunctionsExceptionCode.NOT_FOUND ->
                AppError.NotFound(detail = detail, message = message, cause = exception)

            FunctionsExceptionCode.RESOURCE_EXHAUSTED ->
                AppError.RateLimited(exception)

            FunctionsExceptionCode.FAILED_PRECONDITION ->
                if (detail?.startsWith("entitlement_") == true) {
                    AppError.EntitlementLimited(detail = detail, message = message, cause = exception)
                } else {
                    AppError.Rejected(detail = detail, message = message, cause = exception)
                }

            FunctionsExceptionCode.ALREADY_EXISTS, FunctionsExceptionCode.ABORTED ->
                AppError.Rejected(detail = detail, message = message, cause = exception)

            FunctionsExceptionCode.UNIMPLEMENTED, FunctionsExceptionCode.OUT_OF_RANGE ->
                AppError.Server(exception, detail = detail ?: "unsupported")

            else -> AppError.Server(exception, detail = detail)
        }
    }

    private fun fromFirestore(exception: FirebaseFirestoreException): AppError = when (exception.code) {
        FirestoreExceptionCode.UNAVAILABLE -> AppError.Offline(exception)
        FirestoreExceptionCode.PERMISSION_DENIED -> AppError.PermissionDenied(detail = "firestore_denied", cause = exception)
        FirestoreExceptionCode.NOT_FOUND -> AppError.NotFound(detail = "firestore_not_found", cause = exception)
        FirestoreExceptionCode.RESOURCE_EXHAUSTED -> AppError.RateLimited(exception)
        else -> AppError.Server(exception, detail = "firestore_error")
    }

    private fun fromAuth(exception: FirebaseAuthException): AppError = when (exception.code) {
        "ERROR_NETWORK_REQUEST_FAILED", "ERROR_USER_DISABLED" -> AppError.Server(exception, detail = exception.code)
        "ERROR_INVALID_EMAIL", "ERROR_WRONG_PASSWORD", "ERROR_INVALID_CREDENTIAL" ->
            AppError.InvalidInput(
                detail = exception.code,
                message = "That email address and password do not match.",
                cause = exception,
            )
        "ERROR_EMAIL_ALREADY_IN_USE" ->
            AppError.Rejected(
                detail = exception.code,
                message = "An account already exists for that email address. Sign in instead.",
                cause = exception,
            )
        "ERROR_WEAK_PASSWORD" ->
            AppError.InvalidInput(detail = exception.code, message = "Choose a longer password.", cause = exception)
        "ERROR_TOO_MANY_REQUESTS" -> AppError.RateLimited(exception)
        "ERROR_USER_NOT_FOUND" ->
            AppError.NotFound(detail = exception.code, message = "No account exists for that email address.", cause = exception)
        else -> AppError.Unknown(exception)
    }

    private fun Any?.asStringKeyMap(): Map<*, *> = (this as? Map<*, *>) ?: emptyMap<Any?, Any?>()

    private fun Map<*, *>.string(key: String): String = (this[key] as? String).orEmpty()

    /** `meta` carries `{ field: "message" }` when a form failed validation. */
    private fun Map<*, *>.fieldErrors(): Map<String, String> {
        val meta = this["meta"] as? Map<*, *> ?: return emptyMap()
        val fields = mutableMapOf<String, String>()
        meta.forEach { (key, value) ->
            val name = key as? String ?: return@forEach
            val text = value as? String ?: return@forEach
            fields[name] = text
        }
        return fields
    }
}
