package com.articals.logistics.ui.screen.tracking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.articals.logistics.core.AppError
import com.articals.logistics.core.AppResult
import com.articals.logistics.core.UiState
import com.articals.logistics.data.api.TrackingRepository
import com.articals.logistics.data.dto.TrackingResultDto
import com.articals.logistics.ui.text.ArticalsStrings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TrackingState(
    val reference: String = "",
    val digits: String = "",
    val result: TrackingResultDto? = null,
    val loading: Boolean = false,
    val error: AppError? = null,
    /** True once a lookup has run, so an empty screen is not shown before the first one. */
    val searched: Boolean = false,
) {
    val referenceValid: Boolean get() = reference.trim().length >= 4

    val digitsValid: Boolean get() = digits.isBlank() || digits.trim().length == 4

    val canSearch: Boolean get() = referenceValid && digitsValid && !loading

    val resultState: UiState<TrackingResultDto>?
        get() = when {
            !searched -> null
            loading -> UiState.Loading
            error != null -> UiState.Error(error)
            result == null -> UiState.Empty(title = ArticalsStrings.Tracking.notFound)
            else -> UiState.Content(result)
        }
}

/**
 * Looking a delivery up by its tracking reference.
 *
 * This is the *same* lookup a customer with the shared link gets, done from inside the
 * app: whatever the server decides to show for a reference without a phone check is
 * what appears here. The screen never widens that — it has no privileged branch, which
 * is what makes it honest about what a customer sees when they open the link.
 */
class TrackingViewModel(
    private val tracking: TrackingRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(TrackingState())
    val state: StateFlow<TrackingState> = mutableState.asStateFlow()

    fun setReference(reference: String) = mutableState.update { it.copy(reference = reference) }

    fun setDigits(digits: String) = mutableState.update {
        it.copy(digits = digits.filter { character -> character.isDigit() }.take(4))
    }

    fun clearResult() = mutableState.update {
        it.copy(result = null, error = null, searched = false)
    }

    fun search() {
        val current = mutableState.value
        if (!current.canSearch) return

        mutableState.update { it.copy(loading = true, error = null, searched = true) }
        viewModelScope.launch {
            val digits = current.digits.trim().takeIf { it.length == 4 }
            when (val result = tracking.track(current.reference.trim(), digits)) {
                is AppResult.Failure -> mutableState.update {
                    it.copy(loading = false, error = result.error, result = null)
                }

                is AppResult.Success -> mutableState.update {
                    it.copy(loading = false, result = result.value, error = null)
                }
            }
        }
    }
}
