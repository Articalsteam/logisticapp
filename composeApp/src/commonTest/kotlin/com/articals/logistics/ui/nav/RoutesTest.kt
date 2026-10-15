package com.articals.logistics.ui.nav

import com.articals.logistics.core.Roles
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Who can reach which screen.
 *
 * The backend is the authority — every callable re-checks membership and permission —
 * but a screen that can be opened by the wrong role is a screen that shows her a wall
 * of permission errors, and the console must not be reachable at all without the
 * platform claim. These are the acceptance criteria's own examples, as assertions.
 */
class RoutesTest {

    @Test
    fun `a rider reaches the rider app and nothing else`() {
        assertTrue(Routes.canOpen(Routes.RiderHome, Roles.Rider, hasPlatformAdminClaim = false))
        assertTrue(Routes.canOpen(Routes.riderJob("order-1"), Roles.Rider, hasPlatformAdminClaim = false))
        assertTrue(Routes.canOpen(Routes.riderProblem("order-1"), Roles.Rider, hasPlatformAdminClaim = false))

        listOf(Routes.Dashboard, Routes.Orders, Routes.Customers, Routes.Riders, Routes.Expenses, Routes.Reports)
            .forEach { route ->
                assertTrue(
                    !Routes.canOpen(route, Roles.Rider, hasPlatformAdminClaim = false),
                    "a rider could open $route",
                )
            }
    }

    @Test
    fun `staff roles reach the company app and not the rider app`() {
        listOf(Roles.Owner, Roles.Manager, Roles.Dispatcher).forEach { role ->
            assertTrue(Routes.canOpen(Routes.Dashboard, role, hasPlatformAdminClaim = false))
            assertTrue(Routes.canOpen(Routes.Orders, role, hasPlatformAdminClaim = false))
            assertTrue(Routes.canOpen(Routes.Settings, role, hasPlatformAdminClaim = false))
            assertTrue(!Routes.canOpen(Routes.RiderHome, role, hasPlatformAdminClaim = false))
        }
    }

    @Test
    fun `no signed-in role is nobody`() {
        listOf(Routes.Dashboard, Routes.Orders, Routes.Reports, Routes.Subscription, Routes.LimitedAccess)
            .forEach { route ->
                assertTrue(!Routes.canOpen(route, role = null, hasPlatformAdminClaim = false), "$route opened with no role")
                assertTrue(!Routes.canOpen(route, role = null, hasPlatformAdminClaim = true), "$route opened with no role")
            }
    }

    @Test
    fun `the console needs the platform claim, not merely a company role`() {
        listOf(Routes.AdminOverview, Routes.AdminCompanies, Routes.adminCompany("tenant-1"), Routes.AdminAudit)
            .forEach { route ->
                assertTrue(!Routes.canOpen(route, Roles.Owner, hasPlatformAdminClaim = false), "$route opened for an owner")
                assertTrue(Routes.canOpen(route, Roles.Owner, hasPlatformAdminClaim = true), "$route stayed shut for staff")
            }
    }

    @Test
    fun `a route pattern and the route built from it agree`() {
        // The graph registers patterns; the app navigates with built routes. If these
        // drift, a tap silently goes nowhere.
        assertTrue(Routes.canOpen(Routes.order("order-1"), Roles.Owner, hasPlatformAdminClaim = false))
        assertTrue(Routes.canOpen(Routes.adminCompany("tenant-1"), Roles.Owner, hasPlatformAdminClaim = true))
        assertTrue(Routes.canOpen(Routes.onboarding("tenant-1"), Roles.Owner, hasPlatformAdminClaim = false))
        assertTrue(Routes.canOpen(Routes.calculator(), Roles.Dispatcher, hasPlatformAdminClaim = false))
        assertTrue(Routes.canOpen(Routes.calculator("quote-1"), Roles.Dispatcher, hasPlatformAdminClaim = false))
    }

    @Test
    fun `the sign-in screens are open to a signed-out app, and an unknown route is not`() {
        listOf(Routes.Splash, Routes.SignIn, Routes.Register, Routes.ResetPassword, Routes.VerifyEmail)
            .forEach { route -> assertTrue(Routes.canOpen(route, role = null, hasPlatformAdminClaim = false)) }

        assertTrue(!Routes.canOpen("something/nobody/registered", Roles.Owner, hasPlatformAdminClaim = true))
        assertTrue(Routes.isAdminRoute(Routes.AdminOverview))
        assertTrue(!Routes.isAdminRoute(Routes.Dashboard))
    }
}
