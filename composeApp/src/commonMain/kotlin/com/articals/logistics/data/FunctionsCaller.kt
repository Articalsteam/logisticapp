package com.articals.logistics.data

import com.articals.logistics.core.AppResult
import com.articals.logistics.core.appRunCatching
import com.articals.logistics.data.dto.Envelope
import dev.gitlive.firebase.functions.FirebaseFunctions

/**
 * Invokes trusted backend operations.
 *
 * Every callable answers with `{ ok, data, replayed? }`; this class unwraps the
 * envelope so repositories only ever see the payload. Failures are converted to
 * [com.articals.logistics.core.AppError] by the installed error mapper, which knows
 * the Firebase exception types of both platforms.
 *
 * Writes are never retried automatically: the backend is idempotent, but a blind
 * retry from the app could hide a genuine failure from the user.
 */
class FunctionsCaller(
    @PublishedApi internal val functions: FirebaseFunctions,
    @PublishedApi internal val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    /**
     * Calls [name] and decodes the response payload.
     *
     * The timeout is generous for the quote callable, which may wait on a routing
     * provider; everything else answers from Firestore and is fast.
     */
    suspend inline fun <reified Request : Any, reified Response : Any> call(
        name: String,
        request: Request,
        timeoutMillis: Long = this.timeoutMillis,
    ): AppResult<Response> = appRunCatching {
        val callable = functions.httpsCallable(name, timeoutMillis)
        val envelope: Envelope<Response> = callable.invoke(request).data()
        envelope.data
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS: Long = 30_000
        /** Quotes may resolve a road distance from an external provider. */
        const val QUOTE_TIMEOUT_MILLIS: Long = 45_000
        /** Report exports page through Firestore and build a CSV in Storage. */
        const val REPORT_TIMEOUT_MILLIS: Long = 120_000
    }
}
