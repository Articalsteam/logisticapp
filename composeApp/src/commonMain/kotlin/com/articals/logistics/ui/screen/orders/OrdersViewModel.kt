package com.articals.logistics.ui.screen.orders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.articals.logistics.core.UiState
import com.articals.logistics.core.businessDateKey
import com.articals.logistics.core.currentTimeMillis
import com.articals.logistics.core.shiftDateKey
import com.articals.logistics.data.api.OrdersRepository
import com.articals.logistics.data.dto.OrderDto
import com.articals.logistics.domain.AttentionReasons
import com.articals.logistics.domain.order.OrderStatus
import com.articals.logistics.ui.text.ArticalsStrings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The filters a dispatcher actually uses, in the order they use them. */
enum class OrderFilter { All, Open, NeedsAttention, Today }

enum class OrderSort { Newest, Oldest, HighestValue, Status }

data class OrdersState(
    val orders: UiState<List<OrderDto>> = UiState.Loading,
    val search: String = "",
    val filter: OrderFilter = OrderFilter.All,
    val sort: OrderSort = OrderSort.Newest,
    val windowDays: Int = DEFAULT_WINDOW_DAYS,
    val visibleCount: Int = PAGE_SIZE,
    val loadingMore: Boolean = false,
    /** The business date the screen is showing, so "today" is decided once. */
    val businessDate: String = "",
) {
    /** Everything the filters allow, before paging. */
    val filtered: List<OrderDto>
        get() {
            val all = orders.dataOrNull.orEmpty()
            val needle = search.trim().lowercase()
            val matching = all.filter { order ->
                val matchesSearch = needle.isEmpty() ||
                    order.orderNumber.lowercase().contains(needle) ||
                    order.customerSnapshot?.name?.lowercase()?.contains(needle) == true ||
                    order.pickup.area?.lowercase()?.contains(needle) == true ||
                    order.destination.area?.lowercase()?.contains(needle) == true ||
                    order.trackingReference.lowercase().contains(needle)

                val matchesFilter = when (filter) {
                    OrderFilter.All -> true
                    OrderFilter.Open -> order.status !in OrderStatus.terminal
                    OrderFilter.Today -> order.businessDate == businessDate
                    OrderFilter.NeedsAttention -> needsAttention(order)
                }
                matchesSearch && matchesFilter
            }
            return when (sort) {
                OrderSort.Newest -> matching.sortedByDescending { it.createdAt ?: "" }
                OrderSort.Oldest -> matching.sortedBy { it.createdAt ?: "" }
                OrderSort.HighestValue -> matching.sortedByDescending { it.customerChargeMinor }
                OrderSort.Status -> matching.sortedWith(
                    compareBy({ OrderStatus.sortRank(it.status) }, { it.orderNumber }),
                )
            }
        }

    /** The page currently shown. */
    val page: List<OrderDto> get() = filtered.take(visibleCount)

    val hasMore: Boolean get() = filtered.size > visibleCount

    val totalMatching: Int get() = filtered.size

    private fun needsAttention(order: OrderDto): Boolean =
        order.status == OrderStatus.Failed ||
            order.riderCostConfidence == "unconfirmed" ||
            (order.assignedRiderId == null && order.status in setOf(OrderStatus.Pending, OrderStatus.Confirmed)) ||
            order.distance?.source == "manual" ||
            (order.status in OrderStatus.terminal && order.actualsRecordedAt == null)

    companion object {
        const val DEFAULT_WINDOW_DAYS = 14
        const val PAGE_SIZE = 20
    }
}

/**
 * The delivery list.
 *
 * It reads a fortnight of deliveries — a bounded number of small documents — and does
 * the searching, filtering, sorting and paging on the device. For a company running a
 * few dozen deliveries a day that is instant, and it means a dispatcher can narrow the
 * list while the network is having a bad morning.
 *
 * The window is stated on screen: a list that silently stopped at some date would be
 * worse than one that says how far back it looks.
 */
class OrdersViewModel(
    private val orders: OrdersRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(OrdersState())
    val state: StateFlow<OrdersState> = mutableState.asStateFlow()

    private var loadedTenantId: String? = null

    fun load(tenantId: String, timeZoneId: String) {
        if (loadedTenantId == tenantId) return
        loadedTenantId = tenantId

        val today = businessDateKey(currentTimeMillis(), timeZoneId)
        mutableState.update { it.copy(businessDate = today) }

        val days = (0 until OrdersState.DEFAULT_WINDOW_DAYS).map { offset ->
            shiftDateKey(today, -offset, timeZoneId)
        }

        viewModelScope.launch {
            combineDayFlows(tenantId, days).collect { collected ->
                val all = collected.sortedByDescending { it.createdAt ?: "" }
                mutableState.update { current ->
                    current.copy(
                        orders = if (all.isEmpty()) {
                            UiState.Empty(
                                title = ArticalsStrings.Orders.empty,
                                message = ArticalsStrings.Orders.emptyBody,
                            )
                        } else {
                            UiState.Content(all)
                        },
                        loadingMore = false,
                    )
                }
            }
        }
    }

    fun onSearchChange(value: String) = mutableState.update { it.copy(search = value, visibleCount = OrdersState.PAGE_SIZE) }

    fun onFilterChange(filter: OrderFilter) = mutableState.update { it.copy(filter = filter, visibleCount = OrdersState.PAGE_SIZE) }

    fun onSortChange(sort: OrderSort) = mutableState.update { it.copy(sort = sort, visibleCount = OrdersState.PAGE_SIZE) }

    fun clearSearch() = mutableState.update { it.copy(search = "") }

    fun loadMore() {
        mutableState.update { it.copy(loadingMore = true) }
        mutableState.update {
            it.copy(visibleCount = it.visibleCount + OrdersState.PAGE_SIZE, loadingMore = false)
        }
    }

    /**
     * One stream of "this day's deliveries", repeated for the window.
     *
     * Each day is its own small query, which is what lets the client stay on equality
     * filters only: no composite index can be missing, so the list cannot break on a
     * freshly created project.
     */
    private fun combineDayFlows(tenantId: String, days: List<String>) =
        kotlinx.coroutines.flow.combine(
            days.map { day -> orders.observeOrdersForDay(tenantId, day, limit = 200) },
        ) { perDay -> perDay.toList().flatten() }
}

/** A one-line explanation of why a delivery is flagged, for the list. */
fun attentionLabelOf(order: OrderDto): String? = when {
    order.status == OrderStatus.Failed -> ArticalsStrings.attentionLabel(AttentionReasons.DeliveryFailed)
    order.riderCostConfidence == "unconfirmed" -> ArticalsStrings.attentionLabel(AttentionReasons.RiderCostUnconfirmed)
    order.assignedRiderId == null && order.status in setOf(OrderStatus.Pending, OrderStatus.Confirmed) ->
        ArticalsStrings.attentionLabel(AttentionReasons.Unassigned)

    order.distance?.source == "manual" -> ArticalsStrings.attentionLabel(AttentionReasons.ManualDistanceReview)
    order.status in OrderStatus.terminal && order.actualsRecordedAt == null ->
        ArticalsStrings.attentionLabel(AttentionReasons.AwaitingFinalAmounts)

    else -> null
}
