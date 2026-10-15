package com.articals.logistics.ui.nav

import com.articals.logistics.core.Roles

/**
 * Every destination in the app, as a string route.
 *
 * Routes are spelled out rather than built from a sealed class with a serialiser:
 * navigation-compose multiplies pattern-matching risk by platform, and a string
 * constant is trivially greppable when checking "is this screen reachable?".
 *
 * The [role] rule sits next to the route on purpose: the question "who can open
 * this?" is answered in one file, and [canOpen] is enforced at the navigation
 * graph — not only by hiding a button, because a hidden button is not a safeguard.
 */
object Routes {
    // --- session ----------------------------------------------------------
    const val Splash = "splash"
    const val NotConfigured = "not-configured"
    const val SignIn = "sign-in"
    const val Register = "register"
    const val ResetPassword = "reset-password"
    const val VerifyEmail = "verify-email"
    const val Workspaces = "workspaces"
    const val Onboarding = "onboarding/{tenantId}"
    const val NoCompany = "no-company"

    // --- the company app --------------------------------------------------
    const val Dashboard = "dashboard"
    const val Orders = "orders"
    const val OrderDetail = "orders/{orderId}"
    const val NewOrder = "orders/new"
    const val Calculator = "calculator?quoteId={quoteId}"
    const val Quotes = "quotes"
    const val Customers = "customers"
    const val CustomerDetail = "customers/{customerId}"
    const val Riders = "riders"
    const val RiderDetail = "riders/{riderId}"
    const val Expenses = "expenses"
    const val Reports = "reports"
    const val Settings = "settings"
    const val CompanySettings = "settings/company"
    const val PricingSettings = "settings/pricing"
    const val Team = "settings/team"
    const val Subscription = "settings/subscription"
    const val Notifications = "notifications"
    const val Tracking = "tracking"
    const val AdoptInvitation = "invitation"

    // --- the rider app ----------------------------------------------------
    const val RiderHome = "rider"
    const val RiderJob = "rider/{orderId}"
    const val RiderProblem = "rider/{orderId}/problem"

    // --- the platform console (admin flavour only) ------------------------
    const val AdminOverview = "admin"
    const val AdminCompanies = "admin/companies"
    const val AdminCompanyDetail = "admin/companies/{tenantId}"
    const val AdminPayments = "admin/companies/{tenantId}/payments"
    const val AdminSettings = "admin/settings"
    const val AdminAudit = "admin/audit"

    // --- builders ---------------------------------------------------------
    fun order(orderId: String) = "orders/$orderId"

    fun customer(customerId: String) = "customers/$customerId"

    fun rider(riderId: String) = "riders/$riderId"

    fun riderJob(orderId: String) = "rider/$orderId"

    fun riderProblem(orderId: String) = "rider/$orderId/problem"

    fun onboarding(tenantId: String) = "onboarding/$tenantId"

    /** The calculator, optionally opening with a saved quote already filled in. */
    fun calculator(quoteId: String? = null) =
        if (quoteId == null) "calculator" else "calculator?quoteId=$quoteId"

    fun adminCompany(tenantId: String) = "admin/companies/$tenantId"

    fun adminPayments(tenantId: String) = "admin/companies/$tenantId/payments"

    /** The argument names used by the graph, so builders and patterns cannot drift. */
    const val ArgQuoteId = "quoteId"
    const val ArgCustomerId = "customerId"
    const val ArgRiderId = "riderId"
    const val ArgOrderId = "orderId"
    const val ArgTenantId = "tenantId"

    /**
     * Who may open a route.
     *
     * The backend is the real authority — every callable re-checks the caller's
     * membership and permission — so this exists to keep a rider from *loading* a
     * screen that would only ever show her permission errors. It is a UX rule, and it
     * is deliberately conservative: a route that is not listed is treated as
     * staff-only.
     */
    fun canOpen(route: String, role: String?, hasPlatformAdminClaim: Boolean): Boolean {
        val base = route.substringBefore('?').substringBefore("/{")
        return when (base) {
            // Anyone signed in, whatever their role.
            Dashboard, Orders, OrderDetail, NewOrder, Calculator, Quotes,
            Customers, CustomerDetail, Riders, RiderDetail, Expenses, Reports, Settings, CompanySettings,
            PricingSettings, Team, Subscription, Notifications, Onboarding,
            Workspaces, NoCompany, Tracking, AdoptInvitation,
            -> role != null && role != Roles.Rider

            // Riders only.
            RiderHome, RiderJob, RiderProblem -> role == Roles.Rider

            // The platform console requires the claim, which only Articals Studio staff
            // carry, and the flavour that compiles it in.
            AdminOverview, AdminCompanies, AdminCompanyDetail, AdminPayments, AdminSettings, AdminAudit,
            -> hasPlatformAdminClaim

            // Unauthenticated.
            Splash, NotConfigured, SignIn, Register, ResetPassword, VerifyEmail -> true

            else -> false
        }
    }

    /** True when this route is part of the platform console. */
    fun isAdminRoute(route: String): Boolean = route.startsWith("admin")
}
