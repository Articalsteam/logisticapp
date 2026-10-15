package com.articals.logistics.ui.screen.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.articals.logistics.core.AppError
import com.articals.logistics.core.AppResult
import com.articals.logistics.core.UiState
import com.articals.logistics.core.businessDateKey
import com.articals.logistics.core.currentTimeMillis
import com.articals.logistics.data.api.InsightsRepository
import com.articals.logistics.data.api.OrdersRepository
import com.articals.logistics.data.dto.DashboardMetricsDto
import com.articals.logistics.data.dto.OrderDto
import com.articals.logistics.domain.AttentionReasons
import com.articals.logistics.domain.order.OrderStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One delivery that needs a human decision, with the reason it is on the list. */
data class AttentionItem(
    val order: OrderDto,
    val reason: String,
) {
    val reasonLabel: String get() = AttentionReasons.label(reason)
}

data class DashboardState(
    val metrics: UiState<DashboardMetricsDto> = UiState.Loading,
    val attention: UiState<List<AttentionItem>> = UiState.Loading,
    val businessDate: String = "",
    val refreshing: Boolean = false,
) {
    /** True when the dashboard has nothing to show for a brand-new company. */
    val isEmpty: Boolean
        get() = metrics.dataOrNull?.let { value ->
            value.counts.ordersInRange == 0 && value.counts.pending == 0 && value.counts.inProgress == 0
        } ?: false
}

/**
 * The dashboard.
 *
 * Two sources, deliberately: the figures come from the backend's roll-ups
 * (`getCompanyDashboardMetrics`), while the "needs attention" list is built from the
 * day's delivery documents, because "which delivery, exactly?" is not something a
 * roll-up can answer.
 *
 * Money is absent for a role without `reports.view_financial` — not hidden by this
 * screen, but simply not sent: the backend omits the whole financial block, and the
 * dashboard renders that as an explanation rather than as zeroes, which would be a
 * lie of a different kind.
 */
class DashboardViewModel(
    private val insights: InsightsRepository,
    private val orders: OrdersRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = mutableState.asStateFlow()

    private var tenantId: String? = null
    private var loadedTenantId: String? = null

    fun load(tenantId: String, timeZoneId: String) {
        this.tenantId = tenantId
        val today = businessDateKey(currentTimeMillis(), timeZoneId)
        mutableState.update { it.copy(businessDate = today) }

        if (loadedTenantId == tenantId) return
        loadedTenantId = tenantId

        viewModelScope.launch {
            orders.observeOrdersForDay(tenantId, today, limit = 200).collect { dayOrders ->
                mutableState.update { current -> current.copy(attention = attentionOf(dayOrders)) }
            }
        }
        refresh()
    }

    /** Re-reads the figures. Retry and pull-to-refresh both land here. */
    fun refresh() {
        val tenantId = tenantId ?: return
        mutableState.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            when (val result = insights.dashboard(tenantId = tenantId, trendDays = TREND_DAYS)) {
                is AppResult.Success -> mutableState.update {
                    it.copy(metrics = UiState.Content(result.value), refreshing = false)
                }

                is AppResult.Failure -> mutableState.update {
                    it.copy(metrics = UiState.Error(result.error), refreshing = false)
                }
            }
        }
    }

    /**
     * Why a delivery is on the attention list.
     *
     * The order of the checks is the order of urgency: something already broken beats
     * something unfinished, and an unassigned job beats one whose final amounts are
     * missing.
     */
    private fun attentionOf(dayOrders: List<OrderDto>): UiState<List<AttentionItem>> {
        val items = dayOrders
            .filter { it.status !in setOf(OrderStatus.Cancelled, OrderStatus.Delivered) }
            .mapNotNull { order ->
                val reason = when {
                    order.status == OrderStatus.Failed -> AttentionReasons.DeliveryFailed
                    order.riderCostConfidence == "unconfirmed" -> AttentionReasons.RiderCostUnconfirmed
                    order.assignedRiderId == null && order.status in setOf(OrderStatus.Pending, OrderStatus.Confirmed) ->
                        AttentionReasons.Unassigned

                    order.distance?.source == "manual" -> AttentionReasons.ManualDistanceReview
                    else -> null
                }
                reason?.let { AttentionItem(order = order, reason = it) }
            }
            .sortedWith(compareBy({ reasonRank(it.reason) }, { it.order.orderNumber }))

        return if (items.isEmpty()) {
            UiState.Empty(title = com.articals.logistics.ui.text.ArticalsStrings.Dashboard.attentionEmpty)
        } else {
            UiState.Content(items)
        }
    }

    private fun reasonRank(reason: String): Int = when (reason) {
        AttentionReasons.DeliveryFailed -> 0
        AttentionReasons.UnreportedProblem -> 1
        AttentionReasons.Unassigned -> 2
        AttentionReasons.RiderCostUnconfirmed -> 3
        AttentionReasons.ManualDistanceReview -> 4
        else -> 5
    }

    private companion object {
        /** Two weeks: long enough to see a pattern, short enough to stay readable on a phone. */
        const val TREND_DAYS = 14
    }
}

/** Convenience so a screen can test the state without importing UiState's guts. */
val UiState<DashboardMetricsDto>.errorOrNull: AppError?
    get() = (this as? UiState.Error)?.error
