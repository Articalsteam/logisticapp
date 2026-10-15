package com.articals.logistics.data

import com.articals.logistics.core.AppError
import dev.gitlive.firebase.functions.FirebaseFunctionsException
import dev.gitlive.firebase.functions.FunctionsExceptionCode
import dev.gitlive.firebase.functions.code
import dev.gitlive.firebase.functions.details

/**
 * Maps a failed callable onto [AppError].
 *
 * `code` and `details` are extension properties in `dev.gitlive.firebase.functions`,
 * not members of the exception class, so both imports above are load-bearing: without
 * them the compiler resolves `exception.code` to the standard library's `Char.code`
 * and reports a receiver mismatch instead of the missing import.
 */
internal object FunctionsErrorMapper {

    fun map(exception: FirebaseFunctionsException): AppError {
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

    private fun Any?.asStringKeyMap(): Map<*, *> = this as? Map<*, *> ?: emptyMap<Any?, Any?>()

    /**
     * Reads a string the way the backend writes one.
     *
     * Deliberately `entries`-based: `Map<*, *>.get(key)` puts the key type in an `in`
     * position, which a star projection refuses, so `this[key]` is not callable here.
     */
    private fun Map<*, *>.string(key: String): String =
        entries.firstOrNull { it.key == key }?.value as? String ?: ""

    /** `meta` carries `{ field: "message" }` when a form failed validation. */
    private fun Map<*, *>.fieldErrors(): Map<String, String> {
        val meta = entries.firstOrNull { it.key == "meta" }?.value as? Map<*, *> ?: return emptyMap()
        val fields = mutableMapOf<String, String>()
        meta.forEach { entry ->
            val name = entry.key as? String ?: return@forEach
            val text = entry.value as? String ?: return@forEach
            fields[name] = text
        }
        return fields
    }
}
