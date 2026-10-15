package com.articals.logistics.data

import com.articals.logistics.core.AppError
import dev.gitlive.firebase.auth.FirebaseAuthException
import dev.gitlive.firebase.auth.code

/**
 * Maps a failed Auth operation onto [AppError].
 *
 * `code` is an extension property in `dev.gitlive.firebase.auth` returning a nullable
 * string (`ERROR_*` on Android, the equivalent string on Apple platforms), so the import
 * above is required — see [FunctionsErrorMapper] for what its absence looks like.
 */
internal object AuthErrorMapper {

    fun map(exception: FirebaseAuthException): AppError = when (exception.code) {
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
}
