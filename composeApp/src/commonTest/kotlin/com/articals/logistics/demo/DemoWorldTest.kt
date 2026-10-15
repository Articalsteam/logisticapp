package com.articals.logistics.demo

import com.articals.logistics.core.AppResult
import com.articals.logistics.core.DemoCredentials
import com.articals.logistics.domain.EntitlementStates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * The demo world's own guarantees.
 *
 * These are the claims the app makes about demo mode: the seven accounts exist and can
 * sign in; a rider sees only their own deliveries; two companies see nothing of each
 * other; a dispatcher has no financial reports. Each one is a claim a reviewer will
 * check by hand, so each one is checked here first.
 */
class DemoWorldTest {

    @Test
    fun `every account the sign-in screen offers really signs in`() = runTest {
        val world = DemoWorld()

        DemoCredentials.accounts.forEach { account ->
            val result = world.session.signIn(email = account.email, password = DemoCredentials.password)
            assertTrue(result is AppResult.Success, "sign-in failed for ${account.email}")

            // A company account lands in the role the sign-in card advertises. The
            // platform admin has no company membership at all, which is the point of it.
            val uid = assertNotNull(result.getOrNull(), "expected a successful result").uid
            val memberships = world.store.membershipsOf(uid)
            if (account.role == "platform_admin") {
                assertTrue(memberships.isEmpty(), "the platform admin should not be a member of a company")
            } else {
                assertEquals(account.role, memberships.firstOrNull()?.role, "wrong role for ${account.email}")
            }
        }
    }

    @Test
    fun `a wrong password is refused and does not start a session`() = runTest {
        val world = DemoWorld()

        val result = world.session.signIn(email = "owner@demo.example", password = "not-the-password")

        assertTrue(result is AppResult.Failure)
        assertNull(world.store.currentAuthUser())
    }

    @Test
    fun `the demo world has the two companies the walkthrough promises`() {
        val world = DemoWorld()

        assertEquals("demoexpress", DemoSeed.primaryTenantId)
        assertEquals("kanoswift", DemoSeed.secondaryTenantId)
        assertNotNull(world.store.companies.value[DemoSeed.primaryTenantId])
        assertNotNull(world.store.companies.value[DemoSeed.secondaryTenantId])
    }

    @Test
    fun `the first company has a working day's worth of deliveries`() {
        val world = DemoWorld()

        val orders = world.store.orders.value.values
            .filter { it.tenantId == DemoSeed.primaryTenantId }

        assertTrue(orders.size >= 40, "expected a busy demo company, found ${orders.size} deliveries")
        // Every status the product has, so every state can be seen without waiting.
        val statuses = orders.map { it.status }.toSet()
        assertTrue("delivered" in statuses, "no delivered deliveries in the seed")
        assertTrue("cancelled" in statuses, "no cancelled deliveries in the seed")
        assertTrue("failed" in statuses, "no failed deliveries in the seed")
    }

    @Test
    fun `the demo company starts on a trial`() {
        val world = DemoWorld()

        assertEquals(EntitlementStates.Trialing, world.store.entitlementFor(DemoSeed.primaryTenantId))
    }

    @Test
    fun `no demo account is a real address, and the password is not a secret`() {
        DemoCredentials.accounts.forEach { account ->
            assertTrue(account.email.endsWith("@demo.example"), "${account.email} is not a demo.example address")
        }
        assertEquals("demo1234", DemoCredentials.password)
    }

    @Test
    fun `a rider sees only the deliveries assigned to them`() = runTest {
        val world = DemoWorld()
        world.session.signIn(email = "rider@demo.example", password = "demo1234")
        world.store.setActiveTenant(DemoSeed.primaryTenantId)

        val rider = world.store.riders.value.values.first {
            it.tenantId == DemoSeed.primaryTenantId && it.userId == world.store.currentUid()
        }

        val mine = world.orders.observeRiderOrders(DemoSeed.primaryTenantId, rider.id).first()

        assertTrue(mine.isNotEmpty(), "the demo rider has no work, which would hide the whole rider flow")
        assertTrue(mine.all { it.assignedRiderId == rider.id })
    }

    @Test
    fun `the second rider has a different set of deliveries`() = runTest {
        val world = DemoWorld()
        val riders = world.store.riders.value.values
            .filter { it.tenantId == DemoSeed.primaryTenantId && it.userId != null }
            .sortedBy { it.id }

        val first = world.orders.observeRiderOrders(DemoSeed.primaryTenantId, riders[0].id).first()
        val second = world.orders.observeRiderOrders(DemoSeed.primaryTenantId, riders[1].id).first()

        assertTrue(first.isNotEmpty() && second.isNotEmpty())
        assertTrue(first.map { it.id }.intersect(second.map { it.id }.toSet()).isEmpty())
    }

    @Test
    fun `a query for one company never returns another company's deliveries`() = runTest {
        val world = DemoWorld()

        val primary = world.orders.observeOrdersForDay(
            tenantId = DemoSeed.primaryTenantId,
            businessDateKey = world.store.todayKey(DemoSeed.primaryTenantId),
            limit = 500,
        ).first()
        val secondary = world.orders.observeOrdersForDay(
            tenantId = DemoSeed.secondaryTenantId,
            businessDateKey = world.store.todayKey(DemoSeed.secondaryTenantId),
            limit = 500,
        ).first()

        assertTrue(primary.isNotEmpty(), "the first company has no deliveries today")
        assertTrue(primary.all { it.tenantId == DemoSeed.primaryTenantId })
        assertTrue(secondary.all { it.tenantId == DemoSeed.secondaryTenantId })
        assertTrue(secondary.none { order -> primary.any { it.id == order.id } })
    }

    @Test
    fun `a dispatcher gets no financial block in the dashboard`() = runTest {
        val world = DemoWorld()
        world.session.signIn(email = "dispatcher@demo.example", password = "demo1234")
        world.store.setActiveTenant(DemoSeed.primaryTenantId)

        val metrics = world.insights.dashboard(DemoSeed.primaryTenantId)

        val data = assertNotNull(metrics.getOrNull(), "the dashboard call failed: ${metrics.errorOrNull()}")
        assertNull(data.financials, "a dispatcher must not receive money figures")
    }

    @Test
    fun `the owner gets the financial block the dispatcher is denied`() = runTest {
        val world = DemoWorld()
        world.session.signIn(email = "owner@demo.example", password = "demo1234")
        world.store.setActiveTenant(DemoSeed.primaryTenantId)

        val metrics = world.insights.dashboard(DemoSeed.primaryTenantId)

        val data = assertNotNull(metrics.getOrNull(), "the dashboard call failed: ${metrics.errorOrNull()}")
        assertNotNull(data.financials)
    }

    @Test
    fun `the platform admin claim is on the admin account only`() = runTest {
        val world = DemoWorld()
        world.session.signIn(email = "admin@demo.example", password = "demo1234")

        val claims = assertNotNull(world.session.refreshClaims().getOrNull(), "reading the claims failed")
        assertTrue(claims.isPlatformAdmin)

        world.session.signOut()
        world.session.signIn(email = "owner@demo.example", password = "demo1234")
        val ownerClaims = assertNotNull(world.session.refreshClaims().getOrNull(), "reading the claims failed")
        assertTrue(
            ownerClaims.isPlatformAdmin.not(),
            "a company owner must never carry the platform claim",
        )
    }

    @Test
    fun `the seed is deterministic, so a failure can be reproduced`() {
        val first = DemoWorld()
        val second = DemoWorld()

        assertEquals(
            first.store.orders.value.keys.sorted(),
            second.store.orders.value.keys.sorted(),
        )
    }
}
