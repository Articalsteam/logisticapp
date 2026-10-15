package com.articals.logistics.ui.screen.orders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.articals.logistics.core.AppError
import com.articals.logistics.core.AppResult
import com.articals.logistics.core.UiState
import com.articals.logistics.data.api.OrdersRepository
import com.articals.logistics.data.api.RidersRepository
import com.articals.logistics.data.dto.AdditionalCostDto
import com.articals.logistics.data.dto.AssignRiderRequest
import com.articals.logistics.data.dto.DeliveryEventDto
import com.articals.logistics.data.dto.OrderDto
import com.articals.logistics.data.dto.RecordActualsRequest
import com.articals.logistics.data.dto.RiderDto
import com.articals.logistics.data.dto.UpdateDeliveryStatusRequest
import com.articals.logistics.ui.text.ArticalsStrings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OrderDetailState(
    val order: UiState<OrderDto> = UiState.Loading,
    val events: List<DeliveryEventDto> = emptyList(),
    val riders: List<RiderDto> = emptyList(),
    /** A write is in flight; every action button is disabled while it is. */
    val busy: Boolean = false,
    val error: AppError? = null,
    /** A short sentence confirming what just happened. */
    val notice: String? = null,
    val assigning: Boolean = false,
    val actualsOpen: Boolean = false,
    val statusChoice: String? = null,
) {
    val current: OrderDto? get() = order.dataOrNull
}

/**
 * One delivery: everything about it, and every action allowed on it.
 *
 * Two rules shape this class.
 *
 * First, the *only* source of truth for what may happen next is
 * [com.articals.logistics.domain.order.OrderStatus], which mirrors the backend's
 * state machine. The screen uses it to disable buttons; the repository re-checks it
 * and can still refuse — a disabled button is a courtesy, not a safeguard.
 *
 * Second, this view model never computes money. The estimate comes from the delivery
 * document, which came from the quote the backend produced, and the final amounts are
 * whatever the person enters and the backend accepts.
 */
class OrderDetailViewModel(
    private val orders: OrdersRepository,
    private val riders: RidersRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(OrderDetailState())
    val state: StateFlow<OrderDetailState> = mutableState.asStateFlow()

    private var tenantId: String? = null
    private var orderId: String? = null
    private var loadedFor: String? = null

    fun load(tenantId: String, orderId: String) {
        this.tenantId = tenantId
        this.orderId = orderId
        if (loadedFor == "$tenantId/$orderId") return
        loadedFor = "$tenantId/$orderId"

        viewModelScope.launch {
            orders.observeOrder(orderId).collect { order ->
                mutableState.update { current ->
                    current.copy(
                        order = order?.let { UiState.Content(it) }
                            ?: UiState.Error(
                                AppError.NotFound(
                                    detail = "order_not_found",
                                    message = ArticalsStrings.Orders.notFound,
                                ),
                            ),
                    )
                }
            }
        }
        viewModelScope.launch {
            orders.observeDeliveryEvents(orderId).collect { events ->
                mutableState.update { it.copy(events = events) }
            }
        }
        viewModelScope.launch {
            riders.observeActiveRiders(tenantId).collect { list ->
                mutableState.update { it.copy(riders = list) }
            }
        }
    }

    /**
     * Assigns or removes a rider.
     *
     * A reason is required whenever an assignment changes, because "why did this
     * delivery get a different rider" is the second question a manager asks.
     */
    fun assign(riderId: String?, reason: String) {
        val tenantId = tenantId ?: return
        val orderId = orderId ?: return
        runWrite(notice = ArticalsStrings.Orders.assigned) {
            orders.assignRider(
                AssignRiderRequest(
                    tenantId = tenantId,
                    orderId = orderId,
                    riderId = riderId,
                    reason = reason.trim().takeIf { it.isNotEmpty() },
                ),
            )
        }
        mutableState.update { it.copy(assigning = false) }
    }

    /**
     * Moves the delivery to [status].
     *
     * [reason] is only needed for a correction — reopening a finished delivery — and
     * the screen asks for it only when the state machine says the move is one.
     */
    fun updateStatus(
        status: String,
        notes: String? = null,
        failureReason: String? = null,
        reason: String? = null,
    ) {
        val tenantId = tenantId ?: return
        val orderId = orderId ?: return
        runWrite(notice = ArticalsStrings.Orders.statusUpdated) {
            orders.updateStatus(
                UpdateDeliveryStatusRequest(
                    tenantId = tenantId,
                    orderId = orderId,
                    status = status,
                    notes = notes?.trim()?.takeIf { it.isNotEmpty() },
                    failureReason = failureReason,
                    reason = reason?.trim()?.takeIf { it.isNotEmpty() },
                ),
            )
        }
        mutableState.update { it.copy(statusChoice = null) }
    }

    /**
     * Records what the delivery finally cost and earned.
     *
     * The values are entered in the company's currency and converted to minor units by
     * the field the user typed in, so nothing in this class does arithmetic on money.
     */
    fun recordActuals(
        finalCustomerChargeMinor: Long,
        actualRiderCostMinor: Long,
        extraCosts: List<AdditionalCostDto>,
        notes: String?,
    ) {
        val tenantId = tenantId ?: return
        val orderId = orderId ?: return
        runWrite(notice = ArticalsStrings.Orders.actualsRecorded) {
            orders.recordActuals(
                RecordActualsRequest(
                    tenantId = tenantId,
                    orderId = orderId,
                    finalCustomerChargeMinor = finalCustomerChargeMinor,
                    actualRiderCostMinor = actualRiderCostMinor,
                    actualAdditionalCosts = extraCosts,
                    notes = notes?.trim()?.takeIf { it.isNotEmpty() },
                ),
            )
        }
        mutableState.update { it.copy(actualsOpen = false) }
    }

    fun setAssigning(open: Boolean) = mutableState.update { it.copy(assigning = open) }

    fun setActualsOpen(open: Boolean) = mutableState.update { it.copy(actualsOpen = open) }

    fun chooseStatus(status: String?) = mutableState.update { it.copy(statusChoice = status) }

    fun clearError() = mutableState.update { it.copy(error = null) }

    fun clearNotice() = mutableState.update { it.copy(notice = null) }

    /**
     * Runs one write and reports exactly what happened.
     *
     * Every action goes through here so that "busy", "failed" and "done" look the same
     * everywhere on the screen, and so a failure can never be swallowed silently.
     */
    private fun runWrite(
        notice: String,
        block: suspend () -> AppResult<*>,
    ) {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(busy = true, error = null, notice = null) }
        viewModelScope.launch {
            val result = block()
            mutableState.update { current ->
                when (result) {
                    is AppResult.Success -> current.copy(busy = false, notice = notice)
                    is AppResult.Failure -> current.copy(busy = false, error = result.error)
                }
            }
        }
    }
}
