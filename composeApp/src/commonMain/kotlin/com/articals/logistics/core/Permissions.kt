package com.articals.logistics.core

/**
 * Role → permission matrix, mirrored from the backend.
 *
 * The backend is authoritative (`functions/src/domain/permissions.ts`); this copy
 * exists so the interface can hide what a role cannot use and so demo mode can
 * refuse an action with the same `permission_denied` error the server would send.
 * Hiding a control is a courtesy, never a control: every repository method that
 * needs a permission still asks the source of truth (the backend, or the demo
 * server) and can fail.
 *
 * Adding a permission is a deliberate act: add it here, grant it to the roles that
 * need it, mirror it in `functions/src/domain/permissions.ts`, and cover it.
 */
object Permissions {
    const val CompanySettingsView = "company.settings.view"
    const val CompanySettingsManage = "company.settings.manage"
    const val StaffView = "staff.view"
    const val StaffManage = "staff.manage"

    const val PricingView = "pricing.view"
    const val PricingManage = "pricing.manage"
    const val QuotesCreate = "quotes.create"
    const val QuotesOverridePrice = "quotes.override_price"

    const val OrdersViewAll = "orders.view_all"
    const val OrdersViewAssigned = "orders.view_assigned"
    const val OrdersCreate = "orders.create"
    const val OrdersUpdate = "orders.update"
    const val OrdersCancel = "orders.cancel"
    const val OrdersAssign = "orders.assign"
    const val OrdersCorrectStatus = "orders.correct_status"
    const val OrdersUpdateStatus = "orders.update_status"
    const val OrdersRecordActuals = "orders.record_actuals"
    const val OrdersRecordProof = "orders.record_proof"

    const val RidersView = "riders.view"
    const val RidersManage = "riders.manage"
    const val CustomersView = "customers.view"
    const val CustomersManage = "customers.manage"
    const val ExpensesView = "expenses.view"
    const val ExpensesManage = "expenses.manage"

    const val ReportsViewOperational = "reports.view_operational"
    const val ReportsViewFinancial = "reports.view_financial"
    const val ReportsExport = "reports.export"

    const val SubscriptionViewStatus = "subscription.view_status"
    const val TrackingShareLink = "tracking.share_link"

    val all: Set<String> = setOf(
        CompanySettingsView, CompanySettingsManage, StaffView, StaffManage,
        PricingView, PricingManage, QuotesCreate, QuotesOverridePrice,
        OrdersViewAll, OrdersViewAssigned, OrdersCreate, OrdersUpdate, OrdersCancel,
        OrdersAssign, OrdersCorrectStatus, OrdersUpdateStatus, OrdersRecordActuals,
        OrdersRecordProof,
        RidersView, RidersManage, CustomersView, CustomersManage, ExpensesView, ExpensesManage,
        ReportsViewOperational, ReportsViewFinancial, ReportsExport,
        SubscriptionViewStatus, TrackingShareLink,
    )

    /** Defaults per role. `manager` deliberately has no staff management or subscription. */
    fun forRole(role: String): Set<String> = when (role) {
        Roles.Owner -> all
        Roles.Manager -> setOf(
            CompanySettingsView, StaffView, PricingView, PricingManage, QuotesCreate,
            OrdersViewAll, OrdersCreate, OrdersUpdate, OrdersCancel, OrdersAssign,
            OrdersUpdateStatus, OrdersCorrectStatus, OrdersRecordActuals, OrdersRecordProof,
            RidersView, RidersManage, CustomersView, CustomersManage, ExpensesView, ExpensesManage,
            ReportsViewOperational, ReportsViewFinancial, ReportsExport, TrackingShareLink,
        )
        Roles.Dispatcher -> setOf(
            CompanySettingsView, PricingView, QuotesCreate,
            OrdersViewAll, OrdersCreate, OrdersUpdate, OrdersAssign,
            OrdersUpdateStatus, OrdersRecordActuals, OrdersRecordProof,
            RidersView, CustomersView, CustomersManage, ExpensesView, ExpensesManage,
            ReportsViewOperational, TrackingShareLink,
        )
        Roles.Rider -> setOf(OrdersViewAssigned, OrdersUpdateStatus, OrdersRecordProof)
        else -> emptySet()
    }

    /**
     * The catalogue, grouped for the staff editor.
     *
     * Order matters: the group order here is the order the screen shows, and inside a
     * group the permissions are in the order they were declared above. Nothing is
     * inferred from the permission string at runtime, so a new permission with an
     * unexpected prefix cannot vanish from the editor.
     */
    fun groups(): List<Pair<String, List<String>>> = listOf(
        "Company" to listOf(CompanySettingsView, CompanySettingsManage),
        "Staff" to listOf(StaffView, StaffManage),
        "Pricing" to listOf(PricingView, PricingManage, QuotesCreate, QuotesOverridePrice),
        "Deliveries" to listOf(
            OrdersViewAll, OrdersViewAssigned, OrdersCreate, OrdersUpdate, OrdersCancel,
            OrdersAssign, OrdersCorrectStatus, OrdersUpdateStatus, OrdersRecordActuals, OrdersRecordProof,
        ),
        "Riders" to listOf(RidersView, RidersManage),
        "Customers" to listOf(CustomersView, CustomersManage),
        "Expenses" to listOf(ExpensesView, ExpensesManage),
        "Reports" to listOf(ReportsViewOperational, ReportsViewFinancial, ReportsExport),
        "Subscription and sharing" to listOf(SubscriptionViewStatus, TrackingShareLink),
    )

    /**
     * Effective permissions for a membership.
     *
     * An owner always keeps everything: revoking permissions from the owner would let
     * a workspace lock itself out of its own administration.
     */
    fun effective(
        role: String,
        granted: List<String> = emptyList(),
        revoked: List<String> = emptyList(),
    ): Set<String> {
        if (role == Roles.Owner) return all
        val permissions = forRole(role).toMutableSet()
        granted.filter { it in all }.forEach { permissions += it }
        revoked.filter { it in all }.forEach { permissions -= it }
        return permissions
    }
}

object Roles {
    const val Owner = "owner"
    const val Manager = "manager"
    const val Dispatcher = "dispatcher"
    const val Rider = "rider"

    val assignable: List<String> = listOf(Owner, Manager, Dispatcher, Rider)

    fun label(role: String): String = when (role) {
        Owner -> "Owner"
        Manager -> "Manager"
        Dispatcher -> "Dispatcher"
        Rider -> "Rider"
        else -> role.replaceFirstChar { it.uppercase() }
    }

    /** Everyone who works in the office rather than on a bike. */
    fun isStaff(role: String): Boolean = role == Owner || role == Manager || role == Dispatcher
}
