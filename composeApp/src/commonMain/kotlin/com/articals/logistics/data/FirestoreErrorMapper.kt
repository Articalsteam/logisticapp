package com.articals.logistics.data

import com.articals.logistics.core.AppError
import dev.gitlive.firebase.firestore.FirebaseFirestoreException
import dev.gitlive.firebase.firestore.FirestoreExceptionCode
import dev.gitlive.firebase.firestore.code

/**
 * Maps a failed Firestore operation onto [AppError].
 *
 * `code` is an extension property in `dev.gitlive.firebase.firestore`, so the import
 * above is required — see [FunctionsErrorMapper] for what its absence looks like.
 */
internal object FirestoreErrorMapper {

    fun map(exception: FirebaseFirestoreException): AppError = when (exception.code) {
        FirestoreExceptionCode.UNAVAILABLE -> AppError.Offline(exception)
        FirestoreExceptionCode.PERMISSION_DENIED -> AppError.PermissionDenied(detail = "firestore_denied", cause = exception)
        FirestoreExceptionCode.NOT_FOUND -> AppError.NotFound(detail = "firestore_not_found", cause = exception)
        FirestoreExceptionCode.RESOURCE_EXHAUSTED -> AppError.RateLimited(exception)
        else -> AppError.Server(exception, detail = "firestore_error")
    }
}
