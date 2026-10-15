package com.articals.logistics.ui.screen.rider

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.articals.logistics.core.AppError
import com.articals.logistics.core.AppResult
import com.articals.logistics.core.UiState
import com.articals.logistics.core.businessDateKey
import com.articals.logistics.core.currentTimeMillis
import com.articals.logistics.data.api.OrdersRepository
import com.articals.logistics.data.api.ProofPhotoCapture
import com.articals.logistics.data.api.RidersRepository
import com.articals.logistics.data.api.StorageRepository
import com.articals.logistics.data.dto.DeliveryEventDto
import com.articals.logistics.data.dto.OrderDto
import com.articals.logistics.data.dto.RiderDto
import com.articals.logistics.data.dto.UpdateDeliveryStatusRequest
import com.articals.logistics.domain.order.OrderStatus
import com.articals.logistics.ui.text.ArticalsStrings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The rider's list of work.
 *
 * A rider sees their own deliveries and nothing else. That is enforced by the security
 * rules and by the demo's own membership check, never by this file filtering a bigger
 * list: the query itself asks only for assigned work, so there is nothing to leak if
 * this screen is wrong.
 *
 * The rider document is found by user id rather than by a stored rider key, because a
 * rider's login and their rider record are separate things that are joined exactly
 * once — here.
 */
data class RiderHomeState(
    val jobs: UiState<List<OrderDto>> = UiState.Loading,
    val riderId: String? = null,
    val riderName: String? = null,
    val activeCount: Int = 0,
    val doneTodayCount: Int = 0,
    val businessDate: String = "",
)

@OptIn(ExperimentalCoroutinesApi::class)
class RiderHomeViewModel(
    private val orders: OrdersRepository,
    private val riders: RidersRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(RiderHomeState())
    val state: StateFlow<RiderHomeState> = mutableState.asStateFlow()

    private var loadedFor: String? = null

    fun load(tenantId: String, userId: String, timeZoneId: String) {
        if (loadedFor == "$tenantId/$userId") return
        loadedFor = "$tenantId/$userId"

        val today = businessDateKey(currentTimeMillis(), timeZoneId)
        mutableState.update { it.copy(businessDate = today) }

        viewModelScope.launch {
            riders.observeRiders(tenantId)
                .map { all: List<RiderDto> -> all.firstOrNull { it.userId == userId } }
                .distinctUntilChangedBy { rider -> rider?.id }
                .flatMapLatest { rider ->
                    if (rider == null) {
                        flowOf(null)
                    } else {
                        orders.observeRiderOrders(tenantId = tenantId, riderId = rider.id, limit = 100)
                            .map { jobs -> rider to jobs }
                    }
                }
                .collect { pair ->
                    if (pair == null) {
                        mutableState.update {
                            it.copy(
                                riderId = null,
                                riderName = null,
                                jobs = UiState.Empty(
                                    title = ArticalsStrings.Rider.noProfile,
                                    message = ArticalsStrings.Rider.noProfileBody,
                                ),
                            )
                        }
                        return@collect
                    }

                    val (rider, jobs) = pair
                    val relevant = jobs
                        .filter { order -> order.status in ACTIVE_STATUSES || order.businessDate == today }
                        .sortedWith(
                            compareBy(
                                { OrderStatus.sortRank(it.status) },
                                { it.scheduledAt ?: it.createdAt ?: "" },
                            ),
                        )

                    mutableState.update { current ->
                        current.copy(
                            riderId = rider.id,
                            riderName = rider.name,
                            jobs = if (relevant.isEmpty()) {
                                UiState.Empty(
                                    title = ArticalsStrings.Rider.empty,
                                    message = ArticalsStrings.Rider.emptyBody,
                                )
                            } else {
                                UiState.Content(relevant)
                            },
                            activeCount = relevant.count { it.status in ACTIVE_STATUSES },
                            doneTodayCount = relevant.count {
                                it.businessDate == today && it.status == OrderStatus.Delivered
                            },
                        )
                    }
                }
        }
    }

    private companion object {
        val ACTIVE_STATUSES = setOf(OrderStatus.Assigned, OrderStatus.PickedUp, OrderStatus.InTransit)
    }
}

/** The rider's own view of one delivery, and the things a rider may do to it. */
data class RiderJobState(
    val order: UiState<OrderDto> = UiState.Loading,
    val events: List<DeliveryEventDto> = emptyList(),
    val busy: Boolean = false,
    val error: AppError? = null,
    val notice: String? = null,
    val proofPath: String? = null,
    val proofSimulated: Boolean = false,
)

class RiderJobViewModel(
    private val orders: OrdersRepository,
    private val storage: StorageRepository,
    private val photos: ProofPhotoCapture,
) : ViewModel() {

    private val mutableState = MutableStateFlow(RiderJobState())
    val state: StateFlow<RiderJobState> = mutableState.asStateFlow()

    private var tenantId: String? = null
    private var orderId: String? = null
    private var loadedFor: String? = null

    /** True when the photo comes from a bundled image rather than a camera. */
    val photoIsSimulated: Boolean get() = photos.isSimulated

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
    }

    fun clearError() = mutableState.update { it.copy(error = null) }

    fun clearNotice() = mutableState.update { it.copy(notice = null) }

    /** Moves the delivery along the rider's part of the workflow. */
    fun updateStatus(status: String, notes: String?, failureReason: String?) {
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
                ),
            )
        }
    }

    /**
     * Takes the photo and uploads it.
     *
     * In demo mode the camera is simulated and the screen says so. In a Firebase build
     * without the camera module compiled in, [ProofPhotoCapture] reports that honestly
     * instead of pretending a photo exists. Either way the upload itself is the real
     * path, so the storage rules are exercised by the flow.
     */
    fun captureProof() {
        val tenantId = tenantId ?: return
        val orderId = orderId ?: return
        runWrite(notice = ArticalsStrings.Rider.proofUploaded) {
            when (val captured = photos.capture()) {
                is AppResult.Failure -> captured
                is AppResult.Success -> when (val invalid = storage.validateProof(captured.value)) {
                    // The same rules the bucket enforces, checked before the rider
                    // spends their data on an upload that would be rejected.
                    null -> storage.uploadProof(
                        tenantId = tenantId,
                        orderId = orderId,
                        payload = captured.value,
                    ).also { result ->
                        if (result is AppResult.Success) {
                            mutableState.update {
                                it.copy(proofPath = result.value, proofSimulated = photos.isSimulated)
                            }
                        }
                    }

                    else -> AppResult.Failure(invalid)
                }
            }
        }
    }

    /**
     * Reports that something went wrong.
     *
     * The backend has one way for a rider to say "this delivery did not happen": the
     * delivery is failed with a reason. There is deliberately no way to send a note
     * that changes nothing, because a message nobody is obliged to read is worse than
     * no message at all.
     */
    fun reportProblem(failureReason: String, notes: String?) {
        val tenantId = tenantId ?: return
        val orderId = orderId ?: return
        runWrite(notice = ArticalsStrings.Rider.problemReported) {
            orders.updateStatus(
                UpdateDeliveryStatusRequest(
                    tenantId = tenantId,
                    orderId = orderId,
                    status = OrderStatus.Failed,
                    notes = notes?.trim()?.takeIf { it.isNotEmpty() },
                    failureReason = failureReason,
                ),
            )
        }
    }

    private fun runWrite(notice: String, block: suspend () -> AppResult<*>) {
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
