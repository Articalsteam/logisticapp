package com.articals.logistics.data.repository

import com.articals.logistics.core.AppResult
import com.articals.logistics.data.BackendFunctions
import com.articals.logistics.data.FirestorePaths
import com.articals.logistics.data.FunctionsCaller
import com.articals.logistics.data.dto.AckDto
import com.articals.logistics.data.dto.AssignRiderRequest
import com.articals.logistics.data.dto.AssignRiderResponse
import com.articals.logistics.data.dto.CreateOrderRequest
import com.articals.logistics.data.dto.CreateOrderResponse
import com.articals.logistics.data.dto.DeliveryEventDto
import com.articals.logistics.data.dto.OrderDto
import com.articals.logistics.data.dto.RecordActualsRequest
import com.articals.logistics.data.dto.RecordActualsResponse
import com.articals.logistics.data.dto.UpdateDeliveryStatusRequest
import com.articals.logistics.data.dto.UpdateDeliveryStatusResponse
import com.articals.logistics.data.dto.UpdateOrderRequest
import com.articals.logistics.data.documentFlow
import com.articals.logistics.data.observeDocuments
import dev.gitlive.firebase.firestore.Direction
import dev.gitlive.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.Flow

/**
 * Deliveries.
 *
 * Reads come straight from Firestore (fast, live, and allowed by the rules for the
 * right roles); every change is a backend operation so that the status machine,
 * the rider-ownership check and the financial roll-ups cannot be bypassed.
 */
class OrdersRepository(
    private val firestore: FirebaseFirestore,
    private val caller: FunctionsCaller,
) {
    fun observeOrder(orderId: String): Flow<OrderDto?> =
        firestore.documentFlow<OrderDto>(FirestorePaths.order(orderId))

    fun observeDeliveryEvents(orderId: String): Flow<List<DeliveryEventDto>> =
        firestore.collection(FirestorePaths.orderEvents(orderId))
            .orderBy("createdAt", Direction.DESCENDING)
            .observeDocuments { id, event -> event.copy(id = id) }

    /**
     * The dispatch board: deliveries for one business day.
     *
     * Keyed on the company's business date rather than a device timestamp, so a
     * dispatcher and a rider working past midnight still see the same list.
     */
    fun observeOrdersForDay(tenantId: String, businessDateKey: String, limit: Int = 200): Flow<List<OrderDto>> =
        firestore.collection(FirestorePaths.orders)
            .where {
                all(
                    "tenantId" equalTo tenantId,
                    "businessDate" equalTo businessDateKey,
                )
            }
            .orderBy("createdAt", Direction.DESCENDING)
            .limit(limit)
            .observeDocuments { id, order -> order.copy(id = id) }

    /** One rider's deliveries for a day. Riders can only read their own under the rules. */
    fun observeRiderOrders(tenantId: String, riderId: String, limit: Int = 100): Flow<List<OrderDto>> =
        firestore.collection(FirestorePaths.orders)
            .where {
                all(
                    "tenantId" equalTo tenantId,
                    "assignedRiderId" equalTo riderId,
                )
            }
            .orderBy("createdAt", Direction.DESCENDING)
            .limit(limit)
            .observeDocuments { id, order -> order.copy(id = id) }

    /** Recent deliveries for one customer, used on the customer detail screen. */
    fun observeCustomerOrders(tenantId: String, customerId: String, limit: Int = 50): Flow<List<OrderDto>> =
        firestore.collection(FirestorePaths.orders)
            .where {
                all(
                    "tenantId" equalTo tenantId,
                    "customerId" equalTo customerId,
                )
            }
            .orderBy("createdAt", Direction.DESCENDING)
            .limit(limit)
            .observeDocuments { id, order -> order.copy(id = id) }

    /**
     * Creates a delivery.
     *
     * The backend recomputes the quote before saving, so the price a dispatcher saw
     * is a preview of what the company's rules produce — not a value the app can
     * dictate. [CreateOrderRequest.idempotencyKey] is generated when the form opens.
     */
    suspend fun createOrder(request: CreateOrderRequest): AppResult<CreateOrderResponse> = caller.call(
        BackendFunctions.createOrder,
        request,
        timeoutMillis = FunctionsCaller.QUOTE_TIMEOUT_MILLIS,
    )

    suspend fun updateOrder(request: UpdateOrderRequest): AppResult<AckDto> =
        caller.call(BackendFunctions.updateOrder, request)

    suspend fun assignRider(request: AssignRiderRequest): AppResult<AssignRiderResponse> =
        caller.call(BackendFunctions.assignRider, request)

    suspend fun updateStatus(request: UpdateDeliveryStatusRequest): AppResult<UpdateDeliveryStatusResponse> =
        caller.call(BackendFunctions.updateDeliveryStatus, request)

    /** Records what a delivery actually earned and cost, after it closed. */
    suspend fun recordActuals(request: RecordActualsRequest): AppResult<RecordActualsResponse> =
        caller.call(BackendFunctions.recordDeliveryActuals, request)
}
