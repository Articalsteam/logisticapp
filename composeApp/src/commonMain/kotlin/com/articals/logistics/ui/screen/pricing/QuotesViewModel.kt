package com.articals.logistics.ui.screen.pricing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.articals.logistics.data.api.PricingRepository
import com.articals.logistics.data.dto.SavedQuoteDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class QuotesState(
    val quotes: List<SavedQuoteDto> = emptyList(),
)

/**
 * The saved-quote history.
 *
 * It is a plain live list from the repository: the history has no actions of its own,
 * and "use these details again" hands the quote back to the calculator rather than
 * doing anything to the record. Nothing here can edit or delete a saved quote, because
 * a price that was quoted is a record — the same reason the ledger has no delete.
 */
class QuotesViewModel(
    private val pricing: PricingRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(QuotesState())
    val state: StateFlow<QuotesState> = mutableState.asStateFlow()

    private var loadedFor: String? = null

    fun load(tenantId: String) {
        if (loadedFor == tenantId) return
        loadedFor = tenantId
        viewModelScope.launch {
            pricing.observeSavedQuotes(tenantId).collect { quotes ->
                mutableState.update { it.copy(quotes = quotes) }
            }
        }
    }
}
