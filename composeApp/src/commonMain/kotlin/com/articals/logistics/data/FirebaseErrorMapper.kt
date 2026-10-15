package com.articals.logistics.data

import com.articals.logistics.core.AppError
import com.articals.logistics.core.installErrorMapper
import dev.gitlive.firebase.auth.FirebaseAuthException
import dev.gitlive.firebase.firestore.FirebaseFirestoreException
import dev.gitlive.firebase.functions.FirebaseFunctionsException

/**
 * Turns SDK exceptions into the app's error model.
 *
 * The backend always sends a payload of `{ code, detail, message, meta }`, where
 * `meta` carries field-level validation errors. We prefer the backend's message
 * (it is written for the end user) and keep the machine code for logging and for
 * the few places that branch on it, such as the billing banner.
 *
 * The per-SDK work lives in [FunctionsErrorMapper], [FirestoreErrorMapper] and
 * [AuthErrorMapper] — one file each, and that is deliberate: these SDKs all expose
 * `code` on their exception as an *extension property* (`dev.gitlive.firebase.*.code`),
 * and a single file that imports three same-named extension properties is asking for
 * trouble. A file that imports exactly one of them cannot go wrong.
 */
object FirebaseErrorMapper {

    /** Installed once at start-up, before any repository call. */
    fun install() {
        installErrorMapper(FirebaseErrorMapper::map)
    }

    fun map(throwable: Throwable): AppError = when (throwable) {
        is FirebaseFunctionsException -> FunctionsErrorMapper.map(throwable)
        is FirebaseFirestoreException -> FirestoreErrorMapper.map(throwable)
        is FirebaseAuthException -> AuthErrorMapper.map(throwable)
        else -> AppError.Unknown(throwable)
    }
}
