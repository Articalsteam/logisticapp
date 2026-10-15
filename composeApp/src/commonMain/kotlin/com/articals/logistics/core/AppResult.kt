package com.articals.logistics.core

import kotlin.coroutines.cancellation.CancellationException

/**
 * Result type used across the app.
 *
 * Repository functions return this rather than throwing, so a screen has one
 * predictable branch to render and the error has already been translated into
 * something safe to display.
 */
sealed class AppResult<out T> {
    data class Success<T>(val value: T) : AppResult<T>()
    data class Failure(val error: AppError) : AppResult<Nothing>()

    fun getOrNull(): T? = when (this) {
        is Success -> value
        is Failure -> null
    }

    fun errorOrNull(): AppError? = when (this) {
        is Success -> null
        is Failure -> error
    }

    inline fun <R> map(transform: (T) -> R): AppResult<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }

    inline fun onSuccess(action: (T) -> Unit): AppResult<T> {
        if (this is Success) action(value)
        return this
    }

    inline fun onFailure(action: (AppError) -> Unit): AppResult<T> {
        if (this is Failure) action(error)
        return this
    }
}

/**
 * Runs a suspending block and converts any failure into an [AppError].
 *
 * Cancellation is re-thrown so that a screen leaving the composition cancels its
 * work instead of displaying a spurious error.
 */
suspend inline fun <T> appRunCatching(block: () -> T): AppResult<T> = try {
    AppResult.Success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Throwable) {
    AppResult.Failure(failure.toAppError())
}

/** Maps an arbitrary throwable onto the app's error model. */
fun Throwable.toAppError(): AppError = errorMapper(this)

/**
 * Replaced at start-up by the platform layer, which knows the Firebase exception
 * types available on that platform (Android and Apple expose different ones).
 */
internal var errorMapper: (Throwable) -> AppError = { throwable ->
    when {
        throwable.message?.contains("offline", ignoreCase = true) == true -> AppError.Offline(throwable)
        else -> AppError.Unknown(throwable)
    }
}

internal fun installErrorMapper(mapper: (Throwable) -> AppError) {
    errorMapper = mapper
}
