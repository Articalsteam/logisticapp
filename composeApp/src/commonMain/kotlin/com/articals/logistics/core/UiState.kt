package com.articals.logistics.core

/**
 * The four states every screen has to be able to show.
 *
 * Screens render [UiStateScaffold] (in `ui.component`) rather than branching on
 * their own, which is what keeps loading, empty, error and offline looking the same
 * across the app — and what makes it hard to forget one of them.
 */
sealed interface UiState<out T> {
    /** First load: skeleton placeholders, never a bare spinner where content will go. */
    data object Loading : UiState<Nothing>

    /** Has data. [UiState.Empty] is for "loaded successfully, nothing here yet". */
    data class Content<T>(val data: T) : UiState<T>

    /**
     * Successfully loaded, but there is nothing to show and that is a normal state
     * (a new company with no deliveries yet), not an error.
     */
    data class Empty(
        val title: String,
        val message: String? = null,
        /** Optional call to action, e.g. "Create the first delivery". */
        val actionLabel: String? = null,
    ) : UiState<Nothing>

    /** Something went wrong. [AppError] already carries user-safe copy and retryability. */
    data class Error(val error: AppError) : UiState<Nothing>

    val dataOrNull: T? get() = (this as? Content)?.data
    val isContent: Boolean get() = this is Content
}

/** Maps a result to a UI state, giving [emptyCheck] the chance to say "empty". */
inline fun <T> AppResult<T>.toUiState(emptyCheck: (T) -> Boolean, empty: () -> UiState.Empty): UiState<T> =
    when (this) {
        is AppResult.Success -> if (emptyCheck(value)) empty() else UiState.Content(value)
        is AppResult.Failure -> UiState.Error(error)
    }

/** Convenience for lists, the most common case. */
fun <T> AppResult<List<T>>.toListUiState(
    emptyTitle: String,
    emptyMessage: String? = null,
    actionLabel: String? = null,
): UiState<List<T>> = toUiState({ it.isEmpty() }) { UiState.Empty(emptyTitle, emptyMessage, actionLabel) }
