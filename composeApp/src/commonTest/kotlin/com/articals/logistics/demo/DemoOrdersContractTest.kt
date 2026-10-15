package com.articals.logistics.demo

import com.articals.logistics.core.AppResult
import com.articals.logistics.core.Permissions
import com.articals.logistics.data.dto.AssignRiderRequest
import com.articals.logistics.data.dto.CreateOrderRequest
import com.articals.logistics.data.dto.QuoteLocationDto
import com.articals.logistics.data.dto.RecordActualsRequest
import com.articals.logistics.data.dto.UpdateDeliveryStatusRequest
import com.articals.logistics.domain.order.OrderStatus
import com.articals.logistics.data.UploadPayload
import com.articals.logistics.util.Idempotency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * The deliveries repository, against the demo implementation.
 *
 * These are the promises the rest of the app relies on: a created delivery exists
 * immediately, the same idempotency key produces one delivery and not two, a rider can
 * only be assigned by somebody who may assign, a status only moves where the workflow
 * allows it, and the final amounts carry a variance rather than quietly replacing the
 * estimate.
 */
class DemoOrdersContractTest {

    private fun orderRequest(tenantId: String, key: String) = CreateOrderRequest(
        tenantId = tenantId,
        idempotencyKey = key,
        pickup = QuoteLocationDto(address = "Ikeja warehouse", zoneId = "ikeja", area = "Ikeja"),
        destination = QuoteLocationDto(address = "Yaba office", zoneId = "yaba", area = "Yaba"),
        serviceLevel = "standard",
        description = "Two boxes of documents",
    )

    /** Walks a delivery along the workflow, as the dispatcher would. */
    private suspend fun advance(world: DemoWorld, orderId: String, vararg statuses: String) {
        statuses.forEach { status ->
            val result = world.orders.updateStatus(
                UpdateDeliveryStatusRequest(
                    tenantId = DemoSeed.primaryTenantId,
                    orderId = orderId,
                    status = status,
                ),
            )
            assertTrue(result is AppResult.Success, "could not move the delivery to $status: $result")
        }
    }

    private fun signedInOwner(): DemoWorld {
        val world = DemoWorld()
        world.store.signInAs(world.store.accountByEmail("owner@demo.example")!!)
        world.store.setActiveTenant(DemoSeed.primaryTenantId)
        return world
    }

    @Test
    fun `creating a delivery prices it and makes it visible at once`() = runTest {
        val world = signedInOwner()

        val result = world.orders.createOrder(orderRequest(DemoSeed.primaryTenantId, Idempotency.newKey("test")))

        assertTrue(result is AppResult.Success, "create failed: $result")
        val created = assertNotNull(result.getOrNull(), "expected a successful result")
        assertTrue(created.orderId.isNotBlank())
        assertTrue(created.orderNumber.isNotBlank())
        assertTrue(created.trackingReference.isNotBlank())
        // Ikeja → Yaba is 8 400 m at the seeded rates, so the price is the canonical one.
        assertEquals(196_000L, created.customerChargeMinor)

        val stored = world.store.orders.value[created.orderId]
        assertNotNull(stored)
        assertEquals(DemoSeed.primaryTenantId, stored.tenantId)
        assertEquals(OrderStatus.Pending, stored.status)
    }

    @Test
    fun `the same idempotency key creates one delivery, not two`() = runTest {
        val world = signedInOwner()
        val key = Idempotency.newKey("test.retry")

        val first = world.orders.createOrder(orderRequest(DemoSeed.primaryTenantId, key))
        val second = world.orders.createOrder(orderRequest(DemoSeed.primaryTenantId, key))

        val firstId = assertNotNull(first.getOrNull(), "expected a successful result").orderId
        val secondId = assertNotNull(second.getOrNull(), "expected a successful result").orderId
        assertEquals(firstId, secondId, "a retry with the same key produced a second delivery")
    }

    @Test
    fun `a dispatcher may create a delivery but a rider may not`() = runTest {
        val world = DemoWorld()
        world.store.signInAs(world.store.accountByEmail("dispatcher@demo.example")!!)
        world.store.setActiveTenant(DemoSeed.primaryTenantId)

        val asDispatcher = world.orders.createOrder(
            orderRequest(DemoSeed.primaryTenantId, Idempotency.newKey("test.dispatcher")),
        )
        assertTrue(asDispatcher is AppResult.Success)

        world.store.signInAs(world.store.accountByEmail("rider@demo.example")!!)
        world.store.setActiveTenant(DemoSeed.primaryTenantId)
        val asRider = world.orders.createOrder(
            orderRequest(DemoSeed.primaryTenantId, Idempotency.newKey("test.rider")),
        )
        assertTrue(asRider is AppResult.Failure)
        assertEquals("permission_required", (asRider as AppResult.Failure).error.detail)
    }

    @Test
    fun `assigning a rider moves the delivery to assigned and records an event`() = runTest {
        val world = signedInOwner()
        val created = assertNotNull(
            world.orders.createOrder(orderRequest(DemoSeed.primaryTenantId, Idempotency.newKey("test.assign"))).getOrNull(),
            "creating a delivery failed",
        )

        val rider = world.store.riders.value.values.first { it.tenantId == DemoSeed.primaryTenantId }

        val assigned = world.orders.assignRider(
            AssignRiderRequest(
                tenantId = DemoSeed.primaryTenantId,
                orderId = created.orderId,
                riderId = rider.id,
            ),
        )

        assertTrue(assigned is AppResult.Success, "assign failed: $assigned")
        val order = assertNotNull(world.store.orders.value[created.orderId])
        assertEquals(rider.id, order.assignedRiderId)
        assertEquals(OrderStatus.Assigned, order.status)

        val events = world.orders.observeDeliveryEvents(created.orderId).first()
        assertTrue(events.any { it.status == OrderStatus.Assigned })
    }

    @Test
    fun `a delivery cannot jump straight from pending to delivered`() = runTest {
        val world = signedInOwner()
        val created = assertNotNull(
            world.orders.createOrder(orderRequest(DemoSeed.primaryTenantId, Idempotency.newKey("test.jump"))).getOrNull(),
            "creating a delivery failed",
        )

        val result = world.orders.updateStatus(
            UpdateDeliveryStatusRequest(
                tenantId = DemoSeed.primaryTenantId,
                orderId = created.orderId,
                status = OrderStatus.Delivered,
            ),
        )

        assertTrue(result is AppResult.Failure, "an illegal transition was allowed")
        assertEquals(OrderStatus.Pending, world.store.orders.value[created.orderId]?.status)
    }

    @Test
    fun `the allowed next statuses come back with every successful move`() = runTest {
        val world = signedInOwner()
        val created = assertNotNull(
            world.orders.createOrder(orderRequest(DemoSeed.primaryTenantId, Idempotency.newKey("test.next"))).getOrNull(),
            "creating a delivery failed",
        )

        val confirmed = world.orders.updateStatus(
            UpdateDeliveryStatusRequest(
                tenantId = DemoSeed.primaryTenantId,
                orderId = created.orderId,
                status = OrderStatus.Confirmed,
            ),
        )

        assertTrue(confirmed is AppResult.Success, "confirm failed: $confirmed")
        val response = assertNotNull(confirmed.getOrNull(), "expected a successful result")
        assertEquals(OrderStatus.Confirmed, response.status)
        assertEquals(OrderStatus.Pending, response.previousStatus)
        assertTrue(response.allowedNextStatuses.isNotEmpty())
        assertTrue(OrderStatus.Delivered !in response.allowedNextStatuses)
    }

    @Test
    fun `the final amounts can only be recorded once the delivery is closed`() = runTest {
        val world = signedInOwner()
        val created = assertNotNull(
            world.orders.createOrder(orderRequest(DemoSeed.primaryTenantId, Idempotency.newKey("test.too-early"))).getOrNull(),
            "creating a delivery failed",
        )

        val tooEarly = world.orders.recordActuals(
            RecordActualsRequest(
                tenantId = DemoSeed.primaryTenantId,
                orderId = created.orderId,
                finalCustomerChargeMinor = created.customerChargeMinor,
                actualRiderCostMinor = created.estimatedRiderCostMinor,
                actualAdditionalCosts = emptyList(),
            ),
        )

        assertTrue(tooEarly is AppResult.Failure, "final amounts were accepted on a pending delivery")
        assertEquals("order.not_closed", (tooEarly as AppResult.Failure).error.detail)
    }

    @Test
    fun `recording the final amounts reports the variance against the estimate`() = runTest {
        val world = signedInOwner()
        val created = assertNotNull(
            world.orders.createOrder(orderRequest(DemoSeed.primaryTenantId, Idempotency.newKey("test.actuals"))).getOrNull(),
            "creating a delivery failed",
        )
        advance(world, created.orderId, OrderStatus.Confirmed, OrderStatus.Assigned, OrderStatus.PickedUp, OrderStatus.InTransit, OrderStatus.Delivered)

        val result = world.orders.recordActuals(
            RecordActualsRequest(
                tenantId = DemoSeed.primaryTenantId,
                orderId = created.orderId,
                finalCustomerChargeMinor = created.customerChargeMinor,
                actualRiderCostMinor = created.estimatedRiderCostMinor + 20_000L,
                actualAdditionalCosts = emptyList(),
            ),
        )

        assertTrue(result is AppResult.Success, "record actuals failed: $result")
        val response = assertNotNull(result.getOrNull(), "expected a successful result")
        assertEquals(created.estimatedProfitMinor - 20_000L, response.actualProfitMinor)
        assertEquals(-20_000L, response.varianceMinor)

        val stored = assertNotNull(world.store.orders.value[created.orderId])
        assertEquals(created.estimatedRiderCostMinor + 20_000L, stored.actualRiderCostMinor)
        assertNotNull(stored.actualsRecordedAt)
    }

    @Test
    fun `a rider may only move the delivery assigned to them`() = runTest {
        val world = DemoWorld()
        val owner = world.store.accountByEmail("owner@demo.example")!!
        val firstRiderUser = world.store.accountByEmail("rider@demo.example")!!
        world.store.signInAs(owner)
        world.store.setActiveTenant(DemoSeed.primaryTenantId)

        val created = assertNotNull(
            world.orders.createOrder(orderRequest(DemoSeed.primaryTenantId, Idempotency.newKey("test.other-rider"))).getOrNull(),
            "creating a delivery failed",
        )
        val assignedRider = world.store.riders.value.values.first {
            it.tenantId == DemoSeed.primaryTenantId && it.userId == firstRiderUser.uid
        }
        val assigned = world.orders.assignRider(
            AssignRiderRequest(
                tenantId = DemoSeed.primaryTenantId,
                orderId = created.orderId,
                riderId = assignedRider.id,
            ),
        )
        assertTrue(assigned is AppResult.Success, "assigning failed: $assigned")

        // A rider who is not on this delivery is refused, with the backend's own code.
        world.store.signInAs(world.store.accountByEmail("rider2@demo.example")!!)
        world.store.setActiveTenant(DemoSeed.primaryTenantId)
        val byStranger = world.orders.updateStatus(
            UpdateDeliveryStatusRequest(
                tenantId = DemoSeed.primaryTenantId,
                orderId = created.orderId,
                status = OrderStatus.PickedUp,
            ),
        )
        assertTrue(byStranger is AppResult.Failure, "a rider moved a delivery that is not theirs")
        assertEquals("order.not_assigned_to_rider", (byStranger as AppResult.Failure).error.detail)
        assertEquals(OrderStatus.Assigned, world.store.orders.value[created.orderId]?.status)

        // The rider it belongs to can, which is what proves the refusal was about
        // ownership rather than about riders being unable to do anything at all.
        world.store.signInAs(firstRiderUser)
        world.store.setActiveTenant(DemoSeed.primaryTenantId)
        val byOwner = world.orders.updateStatus(
            UpdateDeliveryStatusRequest(
                tenantId = DemoSeed.primaryTenantId,
                orderId = created.orderId,
                status = OrderStatus.PickedUp,
            ),
        )
        assertTrue(byOwner is AppResult.Success, "the assigned rider was refused their own delivery: $byOwner")
    }

    @Test
    fun `proof of delivery follows the storage rules, not merely the permission`() = runTest {
        val world = DemoWorld()
        val firstRiderUser = world.store.accountByEmail("rider@demo.example")!!
        world.store.signInAs(world.store.accountByEmail("owner@demo.example")!!)
        world.store.setActiveTenant(DemoSeed.primaryTenantId)

        val created = assertNotNull(
            world.orders.createOrder(orderRequest(DemoSeed.primaryTenantId, Idempotency.newKey("test.proof"))).getOrNull(),
            "creating a delivery failed",
        )
        world.orders.assignRider(
            AssignRiderRequest(
                tenantId = DemoSeed.primaryTenantId,
                orderId = created.orderId,
                riderId = world.store.riders.value.values.first { it.userId == firstRiderUser.uid }.id,
            ),
        )

        val photo = UploadPayload(bytes = ByteArray(64) { 7 }, contentType = "image/jpeg", fileName = "porch.jpg")

        // Staff may attach evidence.
        assertNotNull(
            world.storage.uploadProof(DemoSeed.primaryTenantId, created.orderId, photo).getOrNull(),
            "a dispatcher could not attach proof",
        )

        // A rider who is not on this delivery may not, whatever her permissions say.
        world.store.signInAs(world.store.accountByEmail("rider2@demo.example")!!)
        world.store.setActiveTenant(DemoSeed.primaryTenantId)
        val stranger = world.storage.uploadProof(DemoSeed.primaryTenantId, created.orderId, photo)
        assertEquals("order.not_assigned_to_rider", (stranger as AppResult.Failure).error.detail)

        // The rider it belongs to may.
        world.store.signInAs(firstRiderUser)
        world.store.setActiveTenant(DemoSeed.primaryTenantId)
        assertNotNull(
            world.storage.uploadProof(DemoSeed.primaryTenantId, created.orderId, photo).getOrNull(),
            "the assigned rider could not attach proof",
        )

        // And the file rules are the bucket's rules: images only, 5 MB maximum.
        assertNotNull(world.storage.validateProof(UploadPayload(ByteArray(8), "application/pdf", "scan.pdf")))
        assertNotNull(world.storage.validateProof(UploadPayload(ByteArray(0), "image/png", "empty.png")))
        assertNotNull(
            world.storage.validateProof(UploadPayload(ByteArray(6 * 1024 * 1024), "image/jpeg", "huge.jpg")),
            "a 6 MB photo passed the 5 MB rule",
        )
        assertNull(world.storage.validateProof(photo))
    }

    @Test
    fun `the other company's owner can neither see nor move this delivery`() = runTest {
        val world = signedInOwner()
        val created = assertNotNull(
            world.orders.createOrder(orderRequest(DemoSeed.primaryTenantId, Idempotency.newKey("test.tenant"))).getOrNull(),
            "creating a delivery failed",
        )

        world.store.signOut()
        world.store.signInAs(world.store.accountByEmail("owner2@demo.example")!!)
        world.store.setActiveTenant(DemoSeed.secondaryTenantId)

        // Asking for the second company's day returns the second company's deliveries.
        val theirs = world.orders.observeOrdersForDay(
            tenantId = DemoSeed.secondaryTenantId,
            businessDateKey = world.store.todayKey(DemoSeed.secondaryTenantId),
            limit = 200,
        ).first()
        assertTrue(theirs.none { it.id == created.orderId })

        // And naming the first company gets a refusal, not a silent success.
        val move = world.orders.updateStatus(
            UpdateDeliveryStatusRequest(
                tenantId = DemoSeed.primaryTenantId,
                orderId = created.orderId,
                status = OrderStatus.Confirmed,
            ),
        )
        assertTrue(move is AppResult.Failure, "an outsider was allowed to change another company's delivery")
        assertEquals(OrderStatus.Pending, world.store.orders.value[created.orderId]?.status)
    }

    @Test
    fun `recording actuals needs the permission the dispatcher has and the rider does not`() = runTest {
        val world = DemoWorld()

        fun effectiveFor(email: String): Set<String> {
            world.store.signOut()
            world.store.signInAs(world.store.accountByEmail(email)!!)
            world.store.setActiveTenant(DemoSeed.primaryTenantId)
            val membership = world.store.requireMembership(DemoSeed.primaryTenantId)
            return Permissions.effective(membership.role, membership.grantedPermissions, membership.revokedPermissions)
        }

        assertTrue(Permissions.OrdersRecordActuals !in effectiveFor("rider@demo.example"))
        assertTrue(Permissions.OrdersRecordActuals in effectiveFor("dispatcher@demo.example"))
        assertTrue(Permissions.OrdersRecordActuals in effectiveFor("manager@demo.example"))
        // But a dispatcher still cannot manage the rate card, and a rider cannot even
        // see what the delivery earned.
        assertTrue(Permissions.PricingManage !in effectiveFor("dispatcher@demo.example"))
        assertTrue(Permissions.ReportsViewFinancial !in effectiveFor("rider@demo.example"))
    }
}
