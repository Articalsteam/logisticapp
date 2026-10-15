package com.articals.logistics.data

/**
 * Names of the trusted backend operations.
 *
 * Every write in this application goes through one of these callables (ADR-004):
 * the app never writes business documents directly, so the rules can stay simple
 * and the pricing, status and billing rules can never be bypassed by a modified
 * client.
 */
object BackendFunctions {
    /**
     * Region every callable is deployed to (spec §22). The app pins it so a callable
     * cannot be accidentally served from another region, and so the latency of a
     * dispatch action is the same for every dispatcher.
     */
    const val REGION = "europe-west1"


    // Onboarding, company and staff
    const val createCompany = "createCompany"
    const val updateCompanySettings = "updateCompanySettings"
    const val inviteCompanyMember = "inviteCompanyMember"
    const val acceptInvitation = "acceptInvitation"
    const val updateMemberRole = "updateMemberRole"
    const val setMemberStatus = "setMemberStatus"

    // Directory
    const val createCustomer = "createCustomer"
    const val updateCustomer = "updateCustomer"
    const val createRider = "createRider"
    const val updateRider = "updateRider"
    const val createRiderInvitation = "createRiderInvitation"
    const val acceptRiderInvitation = "acceptRiderInvitation"

    // Pricing and deliveries
    const val updateCompanyPricing = "updateCompanyPricing"
    const val calculateDeliveryQuote = "calculateDeliveryQuote"
    const val createOrder = "createOrder"
    const val updateOrder = "updateOrder"
    const val assignRider = "assignRider"
    const val updateDeliveryStatus = "updateDeliveryStatus"
    const val recordDeliveryActuals = "recordDeliveryActuals"

    // Money
    const val createExpense = "createExpense"
    const val deleteExpense = "deleteExpense"

    // Insights
    const val getCompanyDashboardMetrics = "getCompanyDashboardMetrics"
    const val rebuildDailyRollups = "rebuildDailyRollups"

    // Public tracking
    const val getPublicTracking = "getPublicTracking"

    // Billing (own workspace)
    const val getMySubscription = "getMySubscription"
    const val registerDeviceToken = "registerDeviceToken"

    // Platform administration
    const val recordOfflinePayment = "recordOfflinePayment"
    const val setBillingBypass = "setBillingBypass"
    const val revokeBillingBypass = "revokeBillingBypass"
    const val manageSubscription = "manageSubscription"
    const val listCompanies = "listCompanies"
    const val getPlatformOverview = "getPlatformOverview"
    const val updatePlatformSettings = "updatePlatformSettings"
}
