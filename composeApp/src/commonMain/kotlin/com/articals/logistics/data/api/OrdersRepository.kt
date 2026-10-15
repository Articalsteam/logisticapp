package com.articals.logistics.data.api

import com.articals.logistics.core.AppResult
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
import kotlinx.coroutines.flow.Flow

/**
 * Deliveries.
 *
 * Every mutation goes through the authority, never through the app: the status
 * machine, the rider-ownership rule, the "corrections need a reason" rule and the
 * financial roll-ups are all enforced where the data lives.
 */
interface OrdersRepository {
    fun observeOrder(orderId: String): Flow<OrderDto?>

    fun observeDeliveryEvents(orderId: String): Flow<List<DeliveryEventDto>>

    fun observeOrdersForDay(tenantId: String, businessDateKey: String, limit: Int = 200): Flow<List<OrderDto>>

    fun observeRiderOrders(tenantId: String, riderId: String, limit: Int = 100): Flow<List<OrderDto>>

    fun observeCustomerOrders(tenantId: String, customerId: String, limit: Int = 50): Flow<List<OrderDto>>

    suspend fun createOrder(request: CreateOrderRequest): AppResult<CreateOrderResponse>

    suspend fun updateOrder(request: UpdateOrderRequest): AppResult<AckDto>

    suspend fun assignRider(request: AssignRiderRequest): AppResult<AssignRiderResponse>

    suspend fun updateStatus(request: UpdateDeliveryStatusRequest): AppResult<UpdateDeliveryStatusResponse>

    suspend fun recordActuals(request: RecordActualsRequest): AppResult<RecordActualsResponse>
}
