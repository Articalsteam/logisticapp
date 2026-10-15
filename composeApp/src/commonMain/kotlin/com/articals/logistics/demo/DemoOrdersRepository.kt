package com.articals.logistics.demo

import com.articals.logistics.core.AppError
import com.articals.logistics.core.AppResult
import com.articals.logistics.core.Permissions
import com.articals.logistics.core.Roles
import com.articals.logistics.core.businessDateKey
import com.articals.logistics.core.isoInstant
import com.articals.logistics.domain.order.OrderStatus
import com.articals.logistics.data.api.OrdersRepository
import com.articals.logistics.data.dto.AckDto
import com.articals.logistics.data.dto.AssignRiderRequest
import com.articals.logistics.data.dto.AssignRiderResponse
import com.articals.logistics.data.dto.CreateOrderRequest
import com.articals.logistics.data.dto.CreateOrderResponse
import com.articals.logistics.data.dto.CustomerDto
import com.articals.logistics.data.dto.CustomerSnapshotDto
import com.articals.logistics.data.dto.DeliveryEventDto
import com.articals.logistics.data.dto.OrderDto
import com.articals.logistics.data.dto.OrderLocationDto
import com.articals.logistics.data.dto.QuoteLocationDto
import com.articals.logistics.data.dto.QuoteRequestDto
import com.articals.logistics.data.dto.RecordActualsRequest
import com.articals.logistics.data.dto.RecordActualsResponse
import com.articals.logistics.data.dto.RiderSnapshotDto
import com.articals.logistics.data.dto.UpdateDeliveryStatusRequest
import com.articals.logistics.data.dto.UpdateDeliveryStatusResponse
import com.articals.logistics.data.dto.UpdateOrderRequest
import com.articals.logistics.data.dto.SavedQuoteDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Deliveries in demo mode.
 *
 * This class plays the part of the backend's `orders`, `orderLifecycle` and
 * `tracking` handlers together, and it is where most of the demo's value is: create a
 * delivery here and it appears in the dispatch list, on the rider's home, in the
 * customer's history and on the tracking screen, with the same status history and
 * the same money the real system would record.
 *
 * The rules it enforces are copied from the backend on purpose — the status machine,
 * rider ownership, "corrections need a reason", "a failed delivery needs a reason",
 * idempotent creation — because a demo that lets a dispatcher do what production
 * would refuse verifies nothing.
 */
class DemoOrdersRepository(
    private val store: DemoStore,
    private val simulator: DemoQuoteSimulator = DemoQuoteSimulator(),
) : OrdersRepository {

    override fun observeOrder(orderId: String): Flow<OrderDto?> =
        store.orders.map { orders -> orders[orderId] }

    override fun observeDeliveryEvents(orderId: String): Flow<List<DeliveryEventDto>> =
        store.events.map { events -> events[orderId].orEmpty().sortedByDescending { it.createdAt ?: "" } }

    override fun observeOrdersForDay(tenantId: String, businessDateKey: String, limit: Int): Flow<List<OrderDto>> =
        store.orders.map { orders ->
            orders.values
                .filter { it.tenantId == tenantId && it.businessDate == businessDateKey }
                .sortedByDescending { it.createdAt ?: "" }
                .take(limit)
        }

    override fun observeRiderOrders(tenantId: String, riderId: String, limit: Int): Flow<List<OrderDto>> =
        store.orders.map { orders ->
            orders.values
                .filter { it.tenantId == tenantId && it.assignedRiderId == riderId }
                .sortedByDescending { it.createdAt ?: "" }
                .take(limit)
        }

    override fun observeCustomerOrders(tenantId: String, customerId: String, limit: Int): Flow<List<OrderDto>> =
        store.orders.map { orders ->
            orders.values
                .filter { it.tenantId == tenantId && it.customerId == customerId }
                .sortedByDescending { it.createdAt ?: "" }
                .take(limit)
        }

    // -----------------------------------------------------------------------
    // Creating a delivery
    // -----------------------------------------------------------------------

    override suspend fun createOrder(request: CreateOrderRequest): AppResult<CreateOrderResponse> =
        store.demoCall {
            val membership = store.requireWrite(request.tenantId, Permissions.OrdersCreate)
            requireValid(request.description.trim().length >= 2, "description", "Describe what is being delivered.", "order.invalid_description")
            requireValid(request.pickup.address.trim().length >= 3, "pickup.address", "Enter the pickup address.", "order.invalid_pickup")
            requireValid(request.destination.address.trim().length >= 3, "destination.address", "Enter the destination address.", "order.invalid_destination")

            val acknowledged = request.acknowledgedWarningCodes.orEmpty().toSet()

            store.idempotent("createOrder:${request.tenantId}:${request.idempotencyKey}") {
                val config = activeConfig(request.tenantId)
                val now = store.now()

                // The price is recomputed here, never taken from the app: a quote the
                // dispatcher saw a minute ago may already be out of date if the rate card
                // changed in between.
                val quoteResponse = simulator.quote(
                    config = config,
                    request = QuoteRequestDto(
                        tenantId = request.tenantId,
                        pickup = request.pickup,
                        destination = request.destination,
                        serviceLevel = request.serviceLevel,
                        deliveryType = request.deliveryType,
                        additionalCosts = request.additionalCosts.orEmpty(),
                        riderCostOverride = request.riderCostOverride,
                        customerPriceOverride = request.customerPriceOverride,
                        targetProfitOverride = request.targetProfitOverride,
                    ),
                    nowIso = store.isoNow(),
                )

                if (request.customerPriceOverride != null) {
                    store.requirePermission(request.tenantId, Permissions.QuotesOverridePrice)
                }

                val quote = quoteResponse.quote
                if (quote == null) {
                    val blocker = quoteResponse.warnings.firstOrNull { it.severity == "blocker" }
                    throw DemoFailure(
                        if (blocker?.code == "zone_rule_missing" || blocker?.code == "no_matching_band") {
                            AppError.Rejected(detail = blocker.code, message = blocker.message)
                        } else {
                            AppError.InvalidInput(
                                detail = "order.distance_required",
                                message = "Enter the delivery distance before creating the order.",
                                fields = mapOf("pickup.manualDistanceMetres" to "Enter the distance in kilometres"),
                            )
                        },
                    )
                }

                val unacknowledged = quote.warnings.filter {
                    (it.severity == "blocker" || it.code in DemoQuoteSimulator.REVIEW_REQUIRED_CODES) &&
                        it.code !in acknowledged
                }
                if (unacknowledged.isNotEmpty()) {
                    throw DemoFailure(
                        AppError.Rejected(
                            detail = "order.warnings_not_acknowledged",
                            message = unacknowledged.first().message,
                        ),
                    )
                }

                // --- customer -------------------------------------------------
                val customerSnapshot: CustomerSnapshotDto
                val customerId: String?
                when {
                    request.customerId != null -> {
                        val customer = store.customers.value[request.customerId]
                        if (customer == null || customer.tenantId != request.tenantId) {
                            throw DemoFailure(
                                AppError.NotFound(
                                    detail = "customer_missing",
                                    message = "That customer was not found.",
                                ),
                            )
                        }
                        customerId = customer.id
                        customerSnapshot = CustomerSnapshotDto(customer.name, customer.phone, customer.customerType)
                    }

                    request.newCustomer != null -> {
                        val newCustomer = request.newCustomer
                        requireValid(newCustomer.name.trim().length >= 2, "newCustomer.name", "Enter the customer's name.", "customer.invalid_name")
                        requireValid(newCustomer.phone.isDemoPhone(), "newCustomer.phone", "Enter a reachable phone number.", "customer.invalid_phone")
                        val id = store.newId("customer")
                        val record = CustomerDto(
                            id = id,
                            tenantId = request.tenantId,
                            name = newCustomer.name.trim(),
                            customerType = newCustomer.customerType ?: "individual",
                            phone = newCustomer.phone.trim(),
                            email = newCustomer.email?.trim(),
                            status = "active",
                            createdAt = store.isoNow(),
                            updatedAt = store.isoNow(),
                        )
                        store.customers.value = store.customers.value + (id to record)
                        customerId = id
                        customerSnapshot = CustomerSnapshotDto(record.name, record.phone, record.customerType)
                    }

                    request.customerSnapshot != null -> {
                        customerId = null
                        customerSnapshot = CustomerSnapshotDto(
                            name = request.customerSnapshot.name,
                            phone = request.customerSnapshot.phone,
                            customerType = request.customerSnapshot.customerType,
                        )
                    }

                    else -> throw DemoFailure(
                        AppError.InvalidInput(
                            detail = "order.customer_required",
                            message = "Choose a customer, add a new one, or enter a name and phone number for this delivery.",
                            fields = mapOf("customerId" to "Choose or add a customer"),
                        ),
                    )
                }

                // --- rider (optional at creation) ----------------------------
                var assignedRiderId: String? = null
                var riderSnapshot: RiderSnapshotDto? = null
                request.assignedRiderId?.let { riderId ->
                    val rider = loadAssignableRider(request.tenantId, riderId)
                    assignedRiderId = rider.id
                    riderSnapshot = RiderSnapshotDto(rider.name, rider.phone, rider.id)
                }

                val businessDate = businessDateKey(now, store.timeZoneOf(request.tenantId))
                val sequence = store.orders.value.values.count { it.tenantId == request.tenantId } + 1
                val orderNumber = DemoIds.orderNumber(
                    prefix = store.companies.value[request.tenantId]?.orderNumberPrefix ?: "ORD",
                    dateKey = businessDate,
                    sequence = sequence,
                )
                val orderId = store.newId("order")
                val trackingReference = DemoIds.trackingReference(DemoRandom(now + sequence))
                val initialStatus = if (assignedRiderId != null) OrderStatus.Assigned else OrderStatus.Pending
                val additionalCosts = request.additionalCosts.orEmpty()

                val order = OrderDto(
                    id = orderId,
                    tenantId = request.tenantId,
                    orderNumber = orderNumber,
                    trackingReference = trackingReference,
                    status = initialStatus,
                    businessDate = businessDate,
                    customerId = customerId,
                    customerSnapshot = customerSnapshot,
                    pickup = request.pickup.toOrderLocation(),
                    destination = request.destination.toOrderLocation(),
                    deliveryType = request.deliveryType,
                    itemCategory = request.itemCategory,
                    description = request.description.trim(),
                    serviceLevel = request.serviceLevel,
                    assignedRiderId = assignedRiderId,
                    riderSnapshot = riderSnapshot,
                    distance = quote.distance,
                    pricingSnapshot = quote.pricingSnapshot,
                    customerChargeMinor = quote.recommendedCustomerChargeMinor,
                    estimatedCustomerChargeMinor = quote.computedCustomerChargeMinor,
                    estimatedRiderCostMinor = quote.estimatedRiderCost.amountMinor,
                    riderCostConfidence = quote.estimatedRiderCost.confidence,
                    estimatedAdditionalCostsMinor = quote.estimatedAdditionalDeliveryCostsMinor,
                    estimatedProfitMinor = quote.estimatedProfitMinor,
                    estimatedMarginBps = quote.estimatedMarginBps,
                    additionalCosts = additionalCosts,
                    acknowledgedWarnings = acknowledged.toList(),
                    internalNotes = request.internalNotes,
                    scheduledAt = request.scheduledAt,
                    currency = quote.currency,
                    createdBy = membership.userId,
                    createdAt = store.isoNow(),
                    updatedAt = store.isoNow(),
                )

                store.orders.value = store.orders.value + (orderId to order)
                store.appendEvent(
                    orderId = orderId,
                    status = initialStatus,
                    actorUserId = membership.userId,
                    notes = if (assignedRiderId != null) "Order created and assigned" else "Order created",
                    metadata = mapOf("distanceSource" to (quote.distance?.source ?: "unknown")),
                )
                customerId?.let { id ->
                    store.customers.value[id]?.let { customer ->
                        store.customers.value = store.customers.value + (
                            id to customer.copy(
                                totalOrders = customer.totalOrders + 1,
                                updatedAt = store.isoNow(),
                            )
                            )
                    }
                }

                // The saved quote becomes part of the order's trail.
                store.quotes.value = store.quotes.value + SavedQuoteDto(
                    id = store.newId("quote"),
                    orderId = orderId,
                    createdBy = membership.userId,
                    createdAt = store.isoNow(),
                    pickup = request.pickup,
                    destination = request.destination,
                    serviceLevel = request.serviceLevel,
                    distance = quote.distance,
                    recommendedCustomerChargeMinor = quote.recommendedCustomerChargeMinor,
                    estimatedRiderCost = quote.estimatedRiderCost,
                    estimatedAdditionalDeliveryCostsMinor = quote.estimatedAdditionalDeliveryCostsMinor,
                    estimatedProfitMinor = quote.estimatedProfitMinor,
                    estimatedMarginBps = quote.estimatedMarginBps,
                    warnings = quote.warnings,
                )

                store.audit(
                    action = "order.created",
                    tenantId = request.tenantId,
                    resourceType = "order",
                    resourceId = orderId,
                    metadata = mapOf(
                        "orderNumber" to orderNumber,
                        "customerChargeMinor" to quote.recommendedCustomerChargeMinor.toString(),
                        "estimatedProfitMinor" to quote.estimatedProfitMinor.toString(),
                        "warningsAcknowledged" to acknowledged.joinToString(","),
                    ),
                )

                if (assignedRiderId != null) {
                    notifyRiderAssignment(order)
                }

                CreateOrderResponse(
                    orderId = orderId,
                    orderNumber = orderNumber,
                    trackingReference = trackingReference,
                    status = initialStatus,
                    trackingUrl = trackingUrl(trackingReference),
                    requiresVerification = store.companies.value[request.tenantId]?.publicTracking?.requireVerification == true,
                    customerChargeMinor = quote.recommendedCustomerChargeMinor,
                    estimatedRiderCostMinor = quote.estimatedRiderCost.amountMinor,
                    estimatedProfitMinor = quote.estimatedProfitMinor,
                    estimatedMarginBps = quote.estimatedMarginBps,
                    warnings = quote.warnings,
                    quoteId = null,
                    distanceSource = quote.distance?.source,
                )
            }
        }

    // -----------------------------------------------------------------------
    // Editing, assignment and status
    // -----------------------------------------------------------------------

    override suspend fun updateOrder(request: UpdateOrderRequest): AppResult<AckDto> = store.demoCall {
        store.requireWrite(request.tenantId, Permissions.OrdersUpdate)
        val order = loadOrder(request.tenantId, request.orderId)
        if (order.status in OrderStatus.terminal) {
            throw DemoFailure(
                AppError.Rejected(
                    detail = "order.closed",
                    message = "This delivery is already ${OrderStatus.label(order.status).lowercase()} and cannot be edited.",
                ),
            )
        }
        request.description?.let {
            requireValid(it.trim().length >= 2, "description", "Describe what is being delivered.", "order.invalid_description")
        }

        // Only descriptive fields are editable here. Price, distance and status have
        // their own operations, each with its own permission.
        val fields = mutableListOf<String>()
        if (request.description != null) fields += "description"
        if (request.itemCategory != null) fields += "itemCategory"
        if (request.deliveryType != null) fields += "deliveryType"
        if (request.serviceLevel != null) fields += "serviceLevel"
        if (request.pickup != null) fields += "pickup.address"
        if (request.destination != null) fields += "destination.address"
        if (request.additionalCosts != null) fields += "additionalCosts"
        if (request.internalNotes != null) fields += "internalNotes"
        if (request.scheduledAt != null) fields += "scheduledAt"

        val destinationChanged = request.destination != null &&
            request.destination.address != order.destination.address

        val updated = order.copy(
            description = request.description?.trim() ?: order.description,
            itemCategory = request.itemCategory ?: order.itemCategory,
            deliveryType = request.deliveryType ?: order.deliveryType,
            serviceLevel = request.serviceLevel ?: order.serviceLevel,
            pickup = request.pickup?.toOrderLocation() ?: order.pickup,
            destination = request.destination?.toOrderLocation() ?: order.destination,
            additionalCosts = request.additionalCosts ?: order.additionalCosts,
            internalNotes = request.internalNotes ?: order.internalNotes,
            scheduledAt = request.scheduledAt ?: order.scheduledAt,
            // The addresses decide the price, so moving the destination makes the saved
            // distance stale — the dispatch screen flags exactly this.
            acknowledgedWarnings = if (destinationChanged) {
                (order.acknowledgedWarnings + "distance_stale").distinct()
            } else {
                order.acknowledgedWarnings
            },
            updatedAt = store.isoNow(),
        )

        store.orders.value = store.orders.value + (order.id to updated)
        store.audit(
            action = "order.updated",
            tenantId = request.tenantId,
            resourceType = "order",
            resourceId = order.id,
            metadata = mapOf("fields" to fields.distinct().joinToString(",")),
        )
        AckDto(tenantId = request.tenantId, orderId = order.id, fields = fields.distinct())
    }

    override suspend fun assignRider(request: AssignRiderRequest): AppResult<AssignRiderResponse> = store.demoCall {
        val membership = store.requireWrite(request.tenantId, Permissions.OrdersAssign)
        val order = loadOrder(request.tenantId, request.orderId)

        if (order.status in OrderStatus.terminal) {
            throw DemoFailure(
                AppError.Rejected(
                    detail = "order.closed",
                    message = "A finished delivery cannot be reassigned.",
                ),
            )
        }

        val newStatus: String
        val riderId: String?
        if (request.riderId == null) {
            if (order.status != OrderStatus.Assigned) {
                throw DemoFailure(
                    AppError.Rejected(
                        detail = "order.cannot_unassign",
                        message = "Only a delivery that is merely assigned can be unassigned. " +
                            "Once the rider has picked it up, correct the status instead.",
                    ),
                )
            }
            riderId = null
            newStatus = OrderStatus.Pending
            store.orders.value = store.orders.value + (
                order.id to order.copy(
                    assignedRiderId = null,
                    riderSnapshot = null,
                    status = newStatus,
                    updatedAt = store.isoNow(),
                )
                )
        } else {
            val rider = loadAssignableRider(request.tenantId, request.riderId)
            riderId = rider.id
            newStatus = when (order.status) {
                OrderStatus.Pending, OrderStatus.Confirmed -> OrderStatus.Assigned
                else -> order.status
            }
            store.orders.value = store.orders.value + (
                order.id to order.copy(
                    assignedRiderId = rider.id,
                    riderSnapshot = RiderSnapshotDto(rider.name, rider.phone, rider.id),
                    status = newStatus,
                    updatedAt = store.isoNow(),
                )
                )
            notifyRiderAssignment(order.copy(assignedRiderId = rider.id, riderSnapshot = RiderSnapshotDto(rider.name, rider.phone, rider.id)))
        }

        store.appendEvent(
            orderId = order.id,
            status = newStatus,
            actorUserId = membership.userId,
            notes = if (riderId == null) {
                "Unassigned${request.reason?.let { ": $it" } ?: ""}"
            } else {
                "Assigned to ${store.riders.value[riderId]?.name ?: "a rider"}"
            },
            metadata = mapOf("riderId" to (riderId ?: "none")),
        )
        store.audit(
            action = if (riderId == null) "order.unassigned" else "order.assigned",
            tenantId = request.tenantId,
            resourceType = "order",
            resourceId = order.id,
            reason = request.reason,
            metadata = mapOf("riderId" to (riderId ?: "none"), "status" to newStatus),
        )
        AssignRiderResponse(orderId = order.id, riderId = riderId, status = newStatus)
    }

    override suspend fun updateStatus(request: UpdateDeliveryStatusRequest): AppResult<UpdateDeliveryStatusResponse> =
        store.demoCall {
            val actor = store.requireUser()
            val order = loadOrder(request.tenantId, request.orderId)

            val membership = store.requireMembership(request.tenantId)
            val riderActing = membership.role == Roles.Rider
            val isAssignedRider = riderActing && store.isAssignedRider(request.tenantId, order, actor.uid)

            // A rider may only move the delivery that is assigned to them. The backend
            // refuses this with the same code and the same message; without the check a
            // rider could move a colleague's delivery, which is exactly the hole this
            // demo exists to make visible.
            if (riderActing && !isAssignedRider) {
                throw DemoFailure(
                    AppError.PermissionDenied(
                        detail = "order.not_assigned_to_rider",
                        message = "This delivery is not assigned to you, so you cannot update it.",
                    ),
                )
            }

            // A staff member needs orders.update_status; a rider acting on their own
            // delivery needs it too, and either way the workspace must allow writes.
            if (isAssignedRider) {
                store.requireWrite(request.tenantId, Permissions.OrdersViewAssigned)
            } else {
                store.requireWrite(request.tenantId, Permissions.OrdersUpdateStatus)
            }
            val canCorrect = !riderActing && Permissions.OrdersCorrectStatus in Permissions.effective(
                role = membership.role,
                granted = membership.grantedPermissions,
                revoked = membership.revokedPermissions,
            )

            val check = OrderStatus.canTransition(
                from = order.status,
                to = request.status,
                isAssignedRider = isAssignedRider,
                canCorrectStatus = canCorrect,
            )
            if (!check.allowed) {
                val error = when (check.kind) {
                    "correction" -> AppError.PermissionDenied(detail = check.reason, message = check.message)
                    else -> AppError.Rejected(detail = check.reason, message = check.message)
                }
                throw DemoFailure(error)
            }

            if (check.kind == "correction") {
                requireValid(
                    !request.reason.isNullOrBlank() && request.reason.trim().length >= 3,
                    "reason",
                    "A correction needs a written reason. It is recorded in the audit log.",
                    "order.correction_reason_required",
                )
            }
            if (request.status == OrderStatus.Failed && request.failureReason == null && request.notes.isNullOrBlank()) {
                throw DemoFailure(
                    AppError.InvalidInput(
                        detail = "order.failure_reason_required",
                        message = "Say why the delivery failed.",
                        fields = mapOf("failureReason" to "Choose a reason or add a note"),
                    ),
                )
            }

            val now = store.now()
            val updated = order.copy(
                status = request.status,
                completedAt = if (request.status == OrderStatus.Delivered) store.isoNow() else order.completedAt,
                cancelledAt = if (request.status == OrderStatus.Cancelled) store.isoNow() else order.cancelledAt,
                failedAt = if (request.status == OrderStatus.Failed) store.isoNow() else order.failedAt,
                failureReason = if (request.status == OrderStatus.Failed) request.failureReason else order.failureReason,
                updatedAt = store.isoNow(),
            )
            store.orders.value = store.orders.value + (order.id to updated)

            // Correcting a finished delivery reverses what it contributed, exactly as the
            // backend reverses roll-ups on a correction.
            if (order.status in OrderStatus.terminal && updated.status !in OrderStatus.terminal) {
                store.riders.value[order.assignedRiderId]?.let { rider ->
                    if (order.status == OrderStatus.Delivered) {
                        store.riders.value = store.riders.value + (
                            rider.id to rider.copy(deliveriesCompleted = (rider.deliveriesCompleted - 1).coerceAtLeast(0))
                            )
                    }
                    if (order.status == OrderStatus.Failed) {
                        store.riders.value = store.riders.value + (
                            rider.id to rider.copy(deliveriesFailed = (rider.deliveriesFailed - 1).coerceAtLeast(0))
                            )
                    }
                }
            } else if (updated.status in OrderStatus.terminal) {
                store.riders.value[order.assignedRiderId]?.let { rider ->
                    when (updated.status) {
                        OrderStatus.Delivered -> store.riders.value = store.riders.value + (
                            rider.id to rider.copy(deliveriesCompleted = rider.deliveriesCompleted + 1)
                            )
                        OrderStatus.Failed -> store.riders.value = store.riders.value + (
                            rider.id to rider.copy(deliveriesFailed = rider.deliveriesFailed + 1)
                            )
                        else -> Unit
                    }
                }
            }

            store.appendEvent(
                orderId = order.id,
                status = request.status,
                actorUserId = actor.uid,
                notes = request.notes ?: correctionNote(check.kind, request.reason),
                metadata = buildMap {
                    put("previousStatus", order.status)
                    request.failureReason?.let { put("failureReason", it) }
                    if (check.kind == "correction") put("correction", "true")
                },
            )
            store.audit(
                action = if (check.kind == "correction") "order.status_corrected" else "order.status_changed",
                tenantId = request.tenantId,
                resourceType = "order",
                resourceId = order.id,
                reason = request.reason,
                metadata = mapOf("from" to order.status, "to" to request.status, "actor" to if (isAssignedRider) "rider" else "staff"),
            )

            // The customer's tracking page and the owner's dashboard both read live data,
            // so nothing needs pushing here — but the company hears about a failure.
            if (request.status == OrderStatus.Failed) {
                store.publishNotification(
                    title = "Delivery ${order.orderNumber} failed",
                    body = com.articals.logistics.domain.order.FailureReason
                        .label(request.failureReason ?: "other"),
                )
            }

            UpdateDeliveryStatusResponse(
                orderId = order.id,
                status = request.status,
                previousStatus = order.status,
                eventId = store.events.value[order.id]?.lastOrNull()?.id,
                allowedNextStatuses = OrderStatus.allowedNext(
                    from = request.status,
                    isAssignedRider = isAssignedRider,
                    canCorrectStatus = canCorrect,
                ),
            )
        }

    override suspend fun recordActuals(request: RecordActualsRequest): AppResult<RecordActualsResponse> =
        store.demoCall {
            store.requireWrite(request.tenantId, Permissions.OrdersRecordActuals)
            val order = loadOrder(request.tenantId, request.orderId)
            if (order.status !in OrderStatus.terminal) {
                throw DemoFailure(
                    AppError.Rejected(
                        detail = "order.not_closed",
                        message = "Record the final amounts once the delivery is delivered, failed or cancelled.",
                    ),
                )
            }
            requireValid(
                request.finalCustomerChargeMinor >= 0 && request.actualRiderCostMinor >= 0,
                "finalCustomerChargeMinor",
                "Amounts cannot be negative.",
                "order.invalid_amounts",
            )

            val knownIds = order.additionalCosts.map { it.id }.toSet()
            request.actualAdditionalCosts.forEach { cost ->
                requireValid(
                    cost.id.isNotBlank() && cost.amountMinor >= 0,
                    "actualAdditionalCosts",
                    "Every extra cost needs a label and an amount.",
                    "order.invalid_additional_cost",
                )
                if (cost.id in knownIds) {
                    throw DemoFailure(
                        AppError.Rejected(
                            detail = "order.duplicate_cost",
                            message = "\"${cost.label}\" was already recorded on this delivery.",
                        ),
                    )
                }
            }

            val additionalTotal = request.actualAdditionalCosts.sumOf { it.amountMinor }
            val profit = request.finalCustomerChargeMinor - request.actualRiderCostMinor - additionalTotal
            val updated = order.copy(
                actualCustomerChargeMinor = request.finalCustomerChargeMinor,
                actualRiderCostMinor = request.actualRiderCostMinor,
                actualAdditionalCostsMinor = additionalTotal,
                actualProfitMinor = profit,
                actualsRecordedAt = store.isoNow(),
                additionalCosts = order.additionalCosts + request.actualAdditionalCosts,
                updatedAt = store.isoNow(),
            )
            store.orders.value = store.orders.value + (order.id to updated)

            store.audit(
                action = "order.actuals_recorded",
                tenantId = request.tenantId,
                resourceType = "order",
                resourceId = order.id,
                metadata = mapOf(
                    "actualProfitMinor" to profit.toString(),
                    "estimatedProfitMinor" to order.estimatedProfitMinor.toString(),
                    "varianceMinor" to (profit - order.estimatedProfitMinor).toString(),
                ),
            )

            RecordActualsResponse(
                orderId = order.id,
                actualCustomerChargeMinor = request.finalCustomerChargeMinor,
                actualRiderCostMinor = request.actualRiderCostMinor,
                actualAdditionalCostsMinor = additionalTotal,
                actualProfitMinor = profit,
                estimatedProfitMinor = order.estimatedProfitMinor,
                varianceMinor = profit - order.estimatedProfitMinor,
            )
        }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun loadOrder(tenantId: String, orderId: String): OrderDto {
        val order = store.orders.value[orderId] ?: throw DemoFailure(
            AppError.NotFound(detail = "order_missing", message = "That delivery no longer exists."),
        )
        // A foreign order answers exactly like a missing one, so the interface cannot be
        // used to discover other companies' deliveries.
        if (order.tenantId != tenantId) {
            throw DemoFailure(AppError.NotFound(detail = "order_missing", message = "That delivery no longer exists."))
        }
        return order
    }

    private fun loadAssignableRider(tenantId: String, riderId: String) = run {
        val rider = store.riders.value[riderId] ?: throw DemoFailure(
            AppError.NotFound(detail = "rider_missing", message = "That rider was not found."),
        )
        if (rider.tenantId != tenantId) {
            throw DemoFailure(AppError.NotFound(detail = "rider_missing", message = "That rider was not found."))
        }
        if (rider.status != "active") {
            throw DemoFailure(
                AppError.Rejected(
                    detail = "rider_not_active",
                    message = "That rider is not active and cannot be assigned deliveries.",
                ),
            )
        }
        rider
    }

    private fun activeConfig(tenantId: String) = run {
        val company = store.companies.value[tenantId] ?: throw DemoFailure(
            AppError.NotFound(detail = "company_missing", message = "That company workspace was not found."),
        )
        val configId = company.activePricingConfigId
        store.pricingConfigs.value[configId] ?: throw DemoFailure(
            AppError.Rejected(
                detail = "pricing_config_missing",
                message = "Set up this company's pricing rules before creating deliveries.",
            ),
        )
    }

    private fun notifyRiderAssignment(order: OrderDto) {
        val riderId = order.assignedRiderId ?: return
        val riderUser = store.riders.value[riderId]?.userId ?: return
        if (riderUser == store.currentUid()) return
        store.publishNotification(
            title = "New delivery assigned",
            body = "${order.orderNumber}: ${order.pickup.area ?: order.pickup.address} to " +
                "${order.destination.area ?: order.destination.address}. " +
                "This is a simulated notification (demo mode).",
        )
    }

    private fun correctionNote(kind: String, reason: String?): String? =
        if (kind == "correction") "Corrected: ${reason ?: "no reason given"}" else null

    private fun trackingUrl(reference: String): String =
        "https://demo.example/track.html?ref=$reference"

    private fun QuoteLocationDto.toOrderLocation() = OrderLocationDto(
        address = address,
        zoneId = zoneId,
        area = area,
        latitude = latitude,
        longitude = longitude,
        contactName = contactName,
        contactPhone = contactPhone,
        notes = notes,
    )
}
