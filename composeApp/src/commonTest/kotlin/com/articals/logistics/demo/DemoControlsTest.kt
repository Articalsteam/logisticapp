package com.articals.logistics.demo

import com.articals.logistics.core.AppError
import com.articals.logistics.core.AppResult
import com.articals.logistics.data.api.DemoNetworkMode
import com.articals.logistics.data.dto.CreateOrderRequest
import com.articals.logistics.data.dto.QuoteLocationDto
import com.articals.logistics.data.dto.QuoteRequestDto
import com.articals.logistics.domain.EntitlementStates
import com.articals.logistics.domain.order.OrderStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * The demo controls panel, which is also the error-lab.
 *
 * Everything a reviewer is asked to try by hand — slow network, offline, a forced
 * failure, an empty company, each entitlement state, a reset — is a repository call
 * away here. If one of these stops working, a screen's empty and error states stop
 * being reachable, and the build silently gets worse at demonstrating itself.
 */
class DemoControlsTest {

    private fun ownerWorld(latencyMillis: Long = 0L): DemoWorld {
        val world = DemoWorld(latencyMillis)
        world.store.signInAs(world.store.accountByEmail("owner@demo.example")!!)
        world.store.setActiveTenant(DemoSeed.primaryTenantId)
        return world
    }

    @Test
    fun `with zero latency a call returns without waiting`() = runTest {
        val world = ownerWorld()
        assertEquals(0L, world.controls.state.value.latencyMillis)

        val result = world.session.refreshClaims()

        assertTrue(result is AppResult.Success)
    }

    @Test
    fun `a slow network triples the wait, which is what makes the spinner visible`() = runTest {
        val world = ownerWorld(latencyMillis = 100L)
        world.controls.setNetwork(DemoNetworkMode.SLOW)

        val call = async { world.session.refreshClaims() }
        // A normal call would have finished by now at 100 ms; the slow one waits 300.
        advanceTimeBy(150L)
        assertFalse(call.isCompleted)

        advanceUntilIdle()
        assertTrue(call.isCompleted)
        assertTrue(call.await() is AppResult.Success)
    }

    @Test
    fun `offline fails every call with the offline error, not with a made-up one`() = runTest {
        val world = ownerWorld()
        world.controls.setNetwork(DemoNetworkMode.OFFLINE)

        val claims = world.session.refreshClaims()
        val quote = world.pricing.requestQuote(
            QuoteRequestDto(
                tenantId = DemoSeed.primaryTenantId,
                pickup = QuoteLocationDto(address = "Ikeja", zoneId = "ikeja"),
                destination = QuoteLocationDto(address = "Yaba", zoneId = "yaba"),
            ),
        )

        assertTrue((claims as? AppResult.Failure)?.error is AppError.Offline)
        assertTrue((quote as? AppResult.Failure)?.error is AppError.Offline)
    }

    @Test
    fun `a forced error is spent on exactly one call`() = runTest {
        val world = ownerWorld()
        world.controls.forceNextError(AppError.Server(detail = "demo_simulated_failure"))

        val first = world.session.refreshClaims()
        val second = world.session.refreshClaims()

        assertTrue(first is AppResult.Failure)
        assertEquals("demo_simulated_failure", (first as AppResult.Failure).error.detail)
        assertTrue(second is AppResult.Success, "the forced error was not cleared after one call")
    }

    @Test
    fun `flaky network gives the same pattern for the same seed, and never a different error`() = runTest {
        val one = ownerWorld()
        val two = ownerWorld()
        one.controls.setNetwork(DemoNetworkMode.FLAKY)
        two.controls.setNetwork(DemoNetworkMode.FLAKY)

        val patternOne = (1..20).map { one.session.refreshClaims() is AppResult.Failure }
        val patternTwo = (1..20).map { two.session.refreshClaims() is AppResult.Failure }

        assertEquals(patternOne, patternTwo, "the same seed produced two different failure patterns")

        // Whatever it does, a flaky failure is a server error the app already knows
        // how to show — never a crash and never an invented error type.
        (1..20).forEach { _ ->
            val result = one.session.refreshClaims()
            if (result is AppResult.Failure) {
                assertEquals("demo_simulated_failure", result.error.detail)
            }
        }
    }

    @Test
    fun `latency and font scale are clamped to what the panel offers`() = runTest {
        val world = ownerWorld()

        world.controls.setLatency(99_000L)
        assertEquals(5_000L, world.controls.state.value.latencyMillis)
        world.controls.setLatency(-10L)
        assertEquals(0L, world.controls.state.value.latencyMillis)

        world.controls.setFontScale(3f)
        assertEquals(1.6f, world.controls.state.value.fontScale)
        world.controls.setFontScale(0.1f)
        assertEquals(0.85f, world.controls.state.value.fontScale)
    }

    @Test
    fun `the entitlement control drives the workspace through every state`() = runTest {
        val world = ownerWorld()

        EntitlementStates.all.forEach { state ->
            world.controls.setEntitlement(state)
            assertEquals(state, world.store.entitlementFor(DemoSeed.primaryTenantId))
        }

        world.controls.setEntitlement(null)
        assertEquals(EntitlementStates.Trialing, world.store.entitlementFor(DemoSeed.primaryTenantId))
    }

    @Test
    fun `a limited workspace still reads, but refuses to write`() = runTest {
        val world = ownerWorld()
        world.controls.setEntitlement(EntitlementStates.Limited)

        val orders = world.orders.observeOrdersForDay(
            tenantId = DemoSeed.primaryTenantId,
            businessDateKey = world.store.todayKey(DemoSeed.primaryTenantId),
            limit = 200,
        )
        assertTrue(orders.first().isNotEmpty(), "a limited company must still be able to read its data")

        val created = world.orders.createOrder(
            CreateOrderRequest(
                tenantId = DemoSeed.primaryTenantId,
                idempotencyKey = "test.limited",
                pickup = QuoteLocationDto(address = "Ikeja warehouse", zoneId = "ikeja"),
                destination = QuoteLocationDto(address = "Yaba office", zoneId = "yaba"),
                serviceLevel = "standard",
                description = "Two boxes of documents",
            ),
        )

        assertTrue(created is AppResult.Failure, "a limited workspace accepted a write")
        val error = (created as AppResult.Failure).error
        assertTrue(error is AppError.EntitlementLimited)
        assertEquals("entitlement_limited", error.detail)
    }

    @Test
    fun `a suspended workspace says so rather than pretending it is limited`() = runTest {
        val world = ownerWorld()
        world.controls.setEntitlement(EntitlementStates.Suspended)

        val created = world.orders.createOrder(
            CreateOrderRequest(
                tenantId = DemoSeed.primaryTenantId,
                idempotencyKey = "test.suspended",
                pickup = QuoteLocationDto(address = "Ikeja warehouse", zoneId = "ikeja"),
                destination = QuoteLocationDto(address = "Yaba office", zoneId = "yaba"),
                serviceLevel = "standard",
                description = "Two boxes of documents",
            ),
        )

        assertEquals("entitlement_suspended", (created as AppResult.Failure).error.detail)
    }

    @Test
    fun `loading an empty company leaves a workspace with nothing in it`() = runTest {
        val world = ownerWorld()

        world.controls.loadEmptyCompany()

        assertTrue(world.controls.state.value.emptyCompanyLoaded)
        assertTrue(
            world.store.orders.value.values.none { it.tenantId == DemoSeed.primaryTenantId },
            "the empty company still has deliveries",
        )
        assertTrue(world.store.customers.value.values.none { it.tenantId == DemoSeed.primaryTenantId })
        assertNotNull(world.store.companies.value[DemoSeed.primaryTenantId], "the company itself must survive")
    }

    @Test
    fun `reset puts the whole demo back, including the entitlement and the sign-in`() = runTest {
        val world = ownerWorld()
        world.controls.setEntitlement(EntitlementStates.Suspended)
        world.controls.setNetwork(DemoNetworkMode.OFFLINE)
        world.controls.loadEmptyCompany()

        world.controls.reset()

        assertEquals(EntitlementStates.Trialing, world.store.entitlementFor(DemoSeed.primaryTenantId))
        assertEquals(DemoNetworkMode.NORMAL, world.controls.state.value.network)
        assertEquals(null, world.controls.state.value.entitlementOverride)
        assertFalse(world.controls.state.value.emptyCompanyLoaded)
        assertTrue(world.store.orders.value.values.any { it.tenantId == DemoSeed.primaryTenantId })
        assertEquals(null, world.store.currentUid(), "reset signs the demo user out")
    }

    @Test
    fun `the seeded deliveries cover the states a reviewer is told to look for`() = runTest {
        val world = ownerWorld()

        val orders = world.store.orders.value.values.filter { it.tenantId == DemoSeed.primaryTenantId }

        assertTrue(orders.any { it.assignedRiderId == null && it.status == OrderStatus.Pending }, "no unassigned delivery")
        assertTrue(orders.any { it.riderCostConfidence == "unconfirmed" }, "no delivery with an unconfirmed rider cost")
        assertTrue(
            orders.any { it.distance?.source == "manual" },
            "no delivery priced from a manually entered distance",
        )
        assertTrue(
            orders.any { it.actualProfitMinor != null && it.actualProfitMinor!! < it.estimatedProfitMinor },
            "no delivery that actually cost more than it was expected to",
        )
        assertTrue(world.store.expenses.value.values.any { it.tenantId == DemoSeed.primaryTenantId })
    }
}
