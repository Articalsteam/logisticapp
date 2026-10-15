package com.articals.logistics.demo

import com.articals.logistics.core.AppError
import com.articals.logistics.core.AppResult
import com.articals.logistics.core.installErrorMapper
import kotlin.coroutines.cancellation.CancellationException

/**
 * The failure type demo repositories throw.
 *
 * It carries a fully formed [AppError], which is what lets demo mode fail exactly
 * the way Firebase mode does: the same error types, the same field errors, the same
 * retryability. Screens cannot tell the difference, which is the whole point —
 * otherwise the UI would be verified against errors that never happen.
 */
class DemoFailure(val error: AppError) : Exception(error.userMessage)

/** Maps demo failures onto the app's error model. Installed only in demo mode. */
object DemoErrorMapper {
    fun install() {
        installErrorMapper(::map)
    }

    fun map(throwable: Throwable): AppError = when (throwable) {
        is DemoFailure -> throwable.error
        is CancellationException -> throw throwable
        else -> AppError.Unknown(throwable)
    }
}

/**
 * A tiny deterministic generator.
 *
 * Demo data has to look the same on every device and in every screenshot, so the
 * seed is fixed and the algorithm is spelled out instead of using a platform
 * random.
 */
class DemoRandom(seed: Long) {
    private var state: Long = if (seed == 0L) 0x9E3779B97F4A7C15uL.toLong() else seed

    fun nextLong(): Long {
        // xorshift64*
        var x = state
        x = x xor (x ushr 12)
        x = x xor (x shl 25)
        x = x xor (x ushr 27)
        state = x
        return x * 0x2545F4914F6CDD1DL
    }

    /** A non-negative value below [bound]. */
    fun nextInt(bound: Int): Int = ((nextLong() ushr 1) % bound.toLong()).toInt()

    fun pick(values: List<String>): String = values[nextInt(values.size)]

    fun chance(percent: Int): Boolean = nextInt(100) < percent

    fun between(min: Int, max: Int): Int = min + nextInt((max - min + 1).coerceAtLeast(1))
}

/**
 * Runs a demo operation the way a repository does: simulated network conditions
 * first, then the work, with [DemoFailure] becoming an [AppResult.Failure] that
 * carries exactly the error the backend would have produced.
 *
 * Cancellation is re-thrown so a screen leaving the composition does not show an
 * error about work it deliberately abandoned.
 */
suspend inline fun <T> DemoStore.demoCall(block: () -> T): AppResult<T> = try {
    controls.simulateCall()
    AppResult.Success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: DemoFailure) {
    AppResult.Failure(failure.error)
} catch (other: Throwable) {
    AppResult.Failure(AppError.Unknown(other))
}

/** Builders for the demo's fake-but-plausible identifiers. */
object DemoIds {
    private val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    fun trackingReference(random: DemoRandom): String =
        "ART-" + (1..9).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")

    /** A single-use rider invitation token. Deterministic in demo, unguessable in production. */
    fun riderInvitationToken(): String =
        "RDR" + (1..28).map { alphabet[DemoRandom(20261014L + it).nextInt(alphabet.length)] }.joinToString("")

    fun orderNumber(prefix: String, dateKey: String, sequence: Int): String =
        "$prefix-${dateKey.replace("-", "")}-${sequence.toString().padStart(4, '0')}"
}
