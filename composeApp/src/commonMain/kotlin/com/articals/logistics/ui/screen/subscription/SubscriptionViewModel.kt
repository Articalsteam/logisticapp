package com.articals.logistics.ui.screen.subscription

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.articals.logistics.core.AppError
import com.articals.logistics.core.AppResult
import com.articals.logistics.core.UiState
import com.articals.logistics.data.api.BillingRepository
import com.articals.logistics.data.dto.PlanDto
import com.articals.logistics.data.dto.SubscriptionViewDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SubscriptionState(
    val subscription: SubscriptionViewDto? = null,
    val plans: List<PlanDto> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: AppError? = null,
) {
    /** True while the company can still write — the same test the backend makes. */
    val writesAllowed: Boolean get() = subscription?.writesAllowed ?: true

    val state: UiState<SubscriptionViewDto>
        get() = when {
            loading -> UiState.Loading
            error != null && subscription == null -> UiState.Error(error)
            subscription == null -> UiState.Empty()
            else -> UiState.Content(subscription)
        }
}

/**
 * What plan the company is on, and what it may still do.
 *
 * The app never sells anything (ADR-009): there is no checkout here and no price to
 * pay. What this screen does is tell the truth about the current state — which, in the
 * trial, is a date; with an offline payment, is a period; with a bypass, is a note that
 * Articals Studio granted access until a day — and makes the way to renew obvious:
 * contact the studio.
 */
class SubscriptionViewModel(
    private val billing: BillingRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(SubscriptionState())
    val state: StateFlow<SubscriptionState> = mutableState.asStateFlow()

    private var tenantId: String? = null

    fun load(tenantId: String) {
        this.tenantId = tenantId
        refresh(showLoading = mutableState.value.subscription == null)
    }

    /** Re-reads the status; used by the refresh button and after coming back to the app. */
    fun refresh(showLoading: Boolean = false) {
        val tenantId = tenantId ?: return
        mutableState.update {
            it.copy(loading = showLoading || it.loading, refreshing = !showLoading, error = null)
        }
        viewModelScope.launch {
            when (val result = billing.mySubscription(tenantId)) {
                is AppResult.Failure -> mutableState.update {
                    it.copy(loading = false, refreshing = false, error = result.error)
                }

                is AppResult.Success -> mutableState.update {
                    it.copy(
                        subscription = result.value,
                        loading = false,
                        refreshing = false,
                        error = null,
                    )
                }
            }
        }
    }

    /** The catalogue, so the screen can name the plan the company is on. */
    fun loadPlans() {
        if (mutableState.value.plans.isNotEmpty()) return
        viewModelScope.launch {
            when (val result = billing.plans()) {
                is AppResult.Success -> mutableState.update { it.copy(plans = result.value) }
                is AppResult.Failure -> Unit
            }
        }
    }

    fun planNameFor(subscription: SubscriptionViewDto): String =
        subscription.planName.ifBlank {
            mutableState.value.plans.firstOrNull { it.id == subscription.planId }?.name ?: subscription.planId
        }
}
