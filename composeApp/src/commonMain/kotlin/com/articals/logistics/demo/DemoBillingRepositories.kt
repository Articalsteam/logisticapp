package com.articals.logistics.demo

import com.articals.logistics.core.AppError
import com.articals.logistics.core.AppResult
import com.articals.logistics.core.isoInstant
import com.articals.logistics.core.shiftDateKey
import com.articals.logistics.data.api.BillingRepository
import com.articals.logistics.data.dto.PlanDto
import com.articals.logistics.data.dto.RegisterDeviceTokenResponse
import com.articals.logistics.data.dto.SubscriptionViewDto

/**
 * Subscriptions in demo mode.
 *
 * The company only ever *reads* its subscription: nothing here can change a plan,
 * take a payment or open a checkout. Payments are recorded by a platform
 * administrator on the admin side (see [DemoPlatformAdminRepository]), exactly as
 * in production, and demo mode has no store integration by design — the app never
 * sells subscriptions (ADR-009).
 */
class DemoBillingRepository(
    private val store: DemoStore,
) : BillingRepository {

    override suspend fun mySubscription(tenantId: String): AppResult<SubscriptionViewDto> = store.demoCall {
        store.requireMembership(tenantId)
        store.subscriptionView(tenantId)
    }

    override suspend fun plans(): AppResult<List<PlanDto>> = store.demoCall {
        store.plans.value.filter { it.isActive }
    }

    override suspend fun registerDeviceToken(
        token: String,
        platform: String,
        appVersion: String?,
    ): AppResult<RegisterDeviceTokenResponse> = store.demoCall {
        val user = store.requireUser()
        requireValid(token.isNotBlank(), "token", "Missing device token.", "notifications.invalid_token")
        requireValid(
            platform in listOf("android", "ios"),
            "platform",
            "Unknown device platform.",
            "notifications.invalid_platform",
        )
        // Demo mode has no push provider, so nothing is registered with anybody. The
        // call succeeds honestly — the count reported is the number of tokens this
        // demo account would have, which is what the settings screen displays.
        store.deviceTokens.value = store.deviceTokens.value
            .filterNot { it.first == token } + (token to platform)
        store.audit(
            action = "notifications.token_registered",
            tenantId = null,
            resourceType = "user",
            resourceId = user.uid,
            metadata = mapOf("platform" to platform, "appVersion" to (appVersion ?: "unknown"), "simulated" to "true"),
        )
        RegisterDeviceTokenResponse(registered = true, tokenCount = store.deviceTokens.value.size)
    }
}

/**
 * The platform administrator console in demo mode.
 *
 * Demo mode is the only place the console has data to show, because [DemoStore]
 * plays the part of the server: the audit log and the ledger live in memory here.
 * In Firebase mode those two surfaces are not client-readable at all (ADR-014) and
 * the repository returns [AppError.Unsupported] rather than pretending otherwise.
 *
 * Everything that changes money or access is admin-only, needs a reason, is
 * idempotent, and writes both an audit entry and a ledger entry — the same
 * invariants the backend enforces.
 */
class DemoPlatformAdminRepository(
    private val store: DemoStore,
) : com.articals.logistics.data.api.PlatformAdminRepository {

    override suspend fun overview(): AppResult<com.articals.logistics.data.dto.PlatformOverviewDto> =
        store.demoCall {
            store.requireElevatedAdmin()
            val now = store.now()
            val companies = store.companies.value.values
            val entitles = companies.map { it.id to store.entitlementFor(it.id) }

            fun count(state: String) = entitles.count { it.second == state }

            val upcoming = entitles
                .mapNotNull { (tenantId, entitlement) ->
                    val until = store.entitlementUntil(tenantId) ?: return@mapNotNull null
                    val days = ((until - now) / DemoStore.MILLIS_PER_DAY).toInt()
                    if (days !in 0..14) return@mapNotNull null
                    com.articals.logistics.data.dto.UpcomingExpiryDto(
                        tenantId = tenantId,
                        name = store.companies.value[tenantId]?.name ?: tenantId,
                        entitlement = entitlement,
                        daysRemaining = days,
                    )
                }
                .sortedBy { it.daysRemaining }

            val ledgers = store.ledgers.value.values.flatten()
            com.articals.logistics.data.dto.PlatformOverviewDto(
                companies = com.articals.logistics.data.dto.PlatformOverviewCompaniesDto(
                    total = companies.size,
                    trialing = count(com.articals.logistics.domain.EntitlementStates.Trialing),
                    activePaid = count(com.articals.logistics.domain.EntitlementStates.ActivePaid),
                    activeOffline = count(com.articals.logistics.domain.EntitlementStates.ActiveOffline),
                    activeBypass = count(com.articals.logistics.domain.EntitlementStates.ActiveBypass),
                    grace = count(com.articals.logistics.domain.EntitlementStates.Grace),
                    limited = count(com.articals.logistics.domain.EntitlementStates.Limited),
                    suspended = count(com.articals.logistics.domain.EntitlementStates.Suspended),
                    onboardingIncomplete = companies.count { it.onboarding?.isComplete != true },
                ),
                upcomingExpiries = upcoming,
                revenue = com.articals.logistics.data.dto.PlatformOverviewRevenueDto(
                    // Kobo, and only ever what was actually recorded: an offline payment
                    // recorded by an administrator, or a completed automated period.
                    automated = ledgers
                        .filter { it.type == "subscription_activated" }
                        .sumOf { it.amountMinor }
                        .toInt(),
                    offline = ledgers
                        .filter { it.type == "payment_recorded_offline" }
                        .sumOf { it.amountMinor }
                        .toInt(),
                    nonRevenue = ledgers
                        .filter { it.type == "bypass_granted" || it.type == "bypass_extended" }
                        .count(),
                ),
                activeBypasses = store.subscriptions.value.values
                    .mapNotNull { record ->
                        val bypass = record.bypass ?: return@mapNotNull null
                        if (bypass.until <= store.isoNow()) return@mapNotNull null
                        com.articals.logistics.data.dto.ActiveBypassDto(
                            tenantId = record.tenantId,
                            until = bypass.until,
                            reason = bypass.reason,
                        )
                    },
                generatedAt = store.isoNow(),
            )
        }

    override suspend fun listCompanies(
        request: com.articals.logistics.data.dto.ListCompaniesRequest,
    ): AppResult<com.articals.logistics.data.dto.ListCompaniesResponse> = store.demoCall {
        store.requireElevatedAdmin()
        val needle = request.search?.trim()?.lowercase().orEmpty()
        val companies = store.companies.value.values
            .filter { company ->
                needle.isEmpty() ||
                    company.name.lowercase().contains(needle) ||
                    company.id.lowercase().contains(needle) ||
                    company.email?.lowercase()?.contains(needle) == true
            }
            .map { company ->
                val record = store.subscriptions.value[company.id]
                val entitlement = store.entitlementFor(company.id)
                com.articals.logistics.data.dto.PlatformCompanyDto(
                    tenantId = company.id,
                    name = company.name,
                    slug = company.slug,
                    status = company.status,
                    planId = record?.planId ?: "none",
                    entitlement = entitlement,
                    entitlementEffectiveUntil = store.entitlementUntil(company.id)?.let(::isoInstant),
                    billingMode = record?.billingMode ?: "trial",
                    trialEndsAt = record?.trialEndsAt,
                    currentPeriodEnd = record?.currentPeriodEnd,
                    bypassActive = record?.bypass?.let { it.until > store.isoNow() } == true,
                    bypassUntil = record?.bypass?.until,
                    onboardingComplete = company.onboarding?.isComplete == true,
                    createdAt = company.createdAt,
                    createdBy = company.createdBy,
                )
            }
            .filter { item -> request.status == null || item.entitlement == request.status || item.status == request.status }
            .sortedBy { it.name.lowercase() }
            .let { list -> request.limit?.let(list::take) ?: list }

        com.articals.logistics.data.dto.ListCompaniesResponse(companies = companies)
    }

    override suspend fun updateSettings(
        request: com.articals.logistics.data.dto.UpdatePlatformSettingsRequest,
    ): AppResult<com.articals.logistics.data.dto.UpdatePlatformSettingsResponse> = store.demoCall {
        store.requireElevatedAdmin()
        val updated = mutableListOf<String>()

        request.plans?.let { plans ->
            plans.forEach { plan ->
                requireValid(plan.id.isNotBlank(), "plans", "Every plan needs an id.", "admin.invalid_plan")
                requireValid(
                    plan.priceMinor >= 0 && plan.periodDays in 1..730 && plan.trialDays in 0..365,
                    "plans",
                    "Check the plan's price, period and trial length.",
                    "admin.invalid_plan",
                )
            }
            store.plans.value = plans
            updated += "plans"
        }
        request.entitlementPolicy?.let { policy ->
            requireValid(
                policy.trialDays in 0..365 && policy.graceDays in 0..90 &&
                    policy.bypassMaxDays in 1..365 && policy.standardPeriodDays in 1..730,
                "entitlementPolicy",
                "Check the entitlement policy values.",
                "admin.invalid_policy",
            )
            store.entitlementPolicy.value = policy
            updated += "entitlementPolicy"
        }

        store.audit(
            action = "admin.platform_settings_updated",
            tenantId = null,
            resourceType = "platformConfig",
            resourceId = "settings",
            metadata = mapOf("updated" to updated.joinToString(",")),
        )
        com.articals.logistics.data.dto.UpdatePlatformSettingsResponse(updated = updated)
    }

    override suspend fun recordOfflinePayment(
        request: com.articals.logistics.data.dto.RecordOfflinePaymentRequest,
    ): AppResult<com.articals.logistics.data.dto.RecordOfflinePaymentResponse> = store.demoCall {
        val admin = store.requireElevatedAdmin()
        requireValid(
            request.amountMinor > 0,
            "amountMinor",
            "Enter the amount that was actually received.",
            "subscription.invalid_amount",
        )
        requireValid(
            request.method in com.articals.logistics.domain.OfflinePaymentMethods.all,
            "method",
            "Choose how the payment was received.",
            "subscription.invalid_method",
        )
        requireValid(
            request.externalReference.trim().length >= 3,
            "externalReference",
            "Enter the bank, POS or transfer reference so this can be checked later.",
            "subscription.reference_required",
        )
        requireValid(
            request.periodEnd > request.periodStart,
            "periodEnd",
            "The covered period must end after it starts.",
            "subscription.period_invalid",
        )
        requireValid(
            request.reason.trim().length >= 3,
            "reason",
            "Say why this payment is being recorded.",
            "subscription.reason_required",
        )
        val company = store.companies.value[request.tenantId]
            ?: throw DemoFailure(AppError.NotFound(detail = "company_missing", message = "That company workspace was not found."))

        store.idempotent("recordOfflinePayment:${request.tenantId}:${request.idempotencyKey}") {
            val existing = store.subscriptions.value[request.tenantId] ?: DemoSubscriptionRecord(tenantId = request.tenantId)
            val ledgerId = store.newId("ledger")
            store.subscriptions.value = store.subscriptions.value + (
                request.tenantId to existing.copy(
                    planId = request.planId ?: existing.planId,
                    status = "active",
                    billingMode = "offline",
                    currentPeriodStart = request.periodStart,
                    currentPeriodEnd = request.periodEnd,
                    suspended = false,
                    paidPeriodsRecorded = existing.paidPeriodsRecorded + 1,
                    // The company sees the amount, dates and method. The reference and the
                    // internal notes stay in the ledger, which only administrators read.
                    lastOfflinePayment = com.articals.logistics.data.dto.OfflinePaymentViewDto(
                        amountMinor = request.amountMinor,
                        currency = request.currency,
                        method = request.method,
                        receivedAt = request.receivedAt,
                        periodStart = request.periodStart,
                        periodEnd = request.periodEnd,
                    ),
                    lastPaymentReference = request.externalReference,
                    lastPaymentNotes = request.internalNotes,
                    lastPaymentEvidencePath = request.evidencePath,
                    lastPaymentRecordedBy = admin.uid,
                )
                )

            store.appendLedger(
                request.tenantId,
                com.articals.logistics.data.dto.LedgerEntryDto(
                    id = ledgerId,
                    type = "payment_recorded_offline",
                    amountMinor = request.amountMinor,
                    currency = request.currency,
                    recordedBy = admin.uid,
                    reason = request.reason,
                    externalReference = request.externalReference,
                    periodStart = request.periodStart,
                    periodEnd = request.periodEnd,
                    createdAt = store.isoNow(),
                ),
            )

            store.audit(
                action = "subscription.offline_payment_recorded",
                tenantId = request.tenantId,
                resourceType = "subscription",
                resourceId = request.tenantId,
                metadata = mapOf(
                    "amountMinor" to request.amountMinor.toString(),
                    "currency" to request.currency,
                    "method" to request.method,
                    "reference" to request.externalReference,
                    "periodStart" to request.periodStart,
                    "periodEnd" to request.periodEnd,
                    "reason" to request.reason,
                    "ledgerEntryId" to ledgerId,
                    "entitlement" to store.entitlementFor(request.tenantId),
                ),
            )
            store.publishNotification(
                title = "Subscription updated",
                body = "Articals Studio recorded a payment for ${company.name}. " +
                    "This is a simulated notification (demo mode).",
            )

            com.articals.logistics.data.dto.RecordOfflinePaymentResponse(
                tenantId = request.tenantId,
                ledgerEntryId = ledgerId,
                entitlement = store.entitlementFor(request.tenantId),
                entitlementEffectiveUntil = store.entitlementUntil(request.tenantId)?.let(::isoInstant),
                periodEnd = request.periodEnd,
            )
        }
    }

    override suspend fun setBillingBypass(
        request: com.articals.logistics.data.dto.BillingBypassRequest,
    ): AppResult<com.articals.logistics.data.dto.BillingBypassResponse> = store.demoCall {
        val admin = store.requireElevatedAdmin()
        requireValid(
            request.reason in com.articals.logistics.domain.BypassReasons.all,
            "reason",
            "Choose a documented reason for the bypass.",
            "subscription.invalid_bypass_reason",
        )
        requireValid(
            request.until.isoInstantAfter(store.isoNow()),
            "until",
            "A bypass must end in the future.",
            "subscription.bypass_in_past",
        )
        val company = store.companies.value[request.tenantId]
            ?: throw DemoFailure(AppError.NotFound(detail = "company_missing", message = "That company workspace was not found."))

        store.idempotent("setBillingBypass:${request.tenantId}:${request.idempotencyKey}") {
            val nowMillis = store.now()
            val untilMillis = parseOrThrow(request.until, "until")
            val durationDays = ((untilMillis - nowMillis) / DemoStore.MILLIS_PER_DAY).toInt()
            val policy = store.entitlementPolicy.value
            if (durationDays > policy.bypassMaxDays && !admin.isPlatformAdminElevated) {
                throw DemoFailure(
                    AppError.PermissionDenied(
                        detail = "subscription.bypass_too_long",
                        message = "A bypass longer than ${policy.bypassMaxDays} days needs an elevated platform permission.",
                    ),
                )
            }

            val existing = store.subscriptions.value[request.tenantId] ?: DemoSubscriptionRecord(tenantId = request.tenantId)
            val replacingActive = existing.bypass?.let { it.until > store.isoNow() } == true
            if (replacingActive && request.replacesExisting != true) {
                throw DemoFailure(
                    AppError.AlreadyExists(
                        detail = "subscription.bypass_already_active",
                        message = "This company already has a bypass until " +
                            "${existing.bypass?.until}. Confirm that you are replacing it.",
                    ),
                )
            }

            store.subscriptions.value = store.subscriptions.value + (
                request.tenantId to existing.copy(
                    billingMode = "bypass",
                    suspended = false,
                    bypass = DemoBypass(
                        reason = request.reason,
                        until = request.until,
                        grantedBy = admin.uid,
                        grantedAt = store.isoNow(),
                        notes = request.internalNotes,
                    ),
                )
                )

            val ledgerId = store.newId("ledger")
            store.appendLedger(
                request.tenantId,
                com.articals.logistics.data.dto.LedgerEntryDto(
                    id = ledgerId,
                    type = if (replacingActive) "bypass_extended" else "bypass_granted",
                    recordedBy = admin.uid,
                    reason = request.reason,
                    periodEnd = request.until,
                    createdAt = store.isoNow(),
                ),
            )
            store.audit(
                action = if (replacingActive) "subscription.bypass_extended" else "subscription.bypass_granted",
                tenantId = request.tenantId,
                resourceType = "subscription",
                resourceId = request.tenantId,
                metadata = mapOf(
                    "reason" to request.reason,
                    "until" to request.until,
                    "durationDays" to durationDays.toString(),
                    "ledgerEntryId" to ledgerId,
                ),
            )
            store.publishNotification(
                title = "Access granted",
                body = "${company.name} can keep working until ${request.until}. " +
                    "This is a simulated notification (demo mode).",
            )

            com.articals.logistics.data.dto.BillingBypassResponse(
                tenantId = request.tenantId,
                ledgerEntryId = ledgerId,
                entitlement = store.entitlementFor(request.tenantId),
                activeUntil = request.until,
                durationDays = durationDays,
            )
        }
    }

    override suspend fun revokeBillingBypass(
        request: com.articals.logistics.data.dto.RevokeBypassRequest,
    ): AppResult<com.articals.logistics.data.dto.RevokeBypassResponse> = store.demoCall {
        val admin = store.requireElevatedAdmin()
        requireValid(
            request.reason.trim().length >= 3,
            "reason",
            "Say why the bypass is being revoked.",
            "subscription.reason_required",
        )
        val existing = store.subscriptions.value[request.tenantId]
            ?: throw DemoFailure(AppError.NotFound(detail = "subscription_missing", message = "That company has no subscription record."))
        val bypass = existing.bypass
            ?: throw DemoFailure(
                AppError.Rejected(
                    detail = "subscription.no_active_bypass",
                    message = "There is no active bypass to revoke.",
                ),
            )

        store.subscriptions.value = store.subscriptions.value + (
            request.tenantId to existing.copy(
                bypass = null,
                suspended = request.suspendAccount == true,
                suspensionReason = if (request.suspendAccount == true) request.reason else existing.suspensionReason,
            )
            )

        val ledgerId = store.newId("ledger")
        store.appendLedger(
            request.tenantId,
            com.articals.logistics.data.dto.LedgerEntryDto(
                id = ledgerId,
                type = "bypass_revoked",
                recordedBy = admin.uid,
                reason = request.reason,
                periodEnd = bypass.until,
                createdAt = store.isoNow(),
            ),
        )
        store.audit(
            action = "subscription.bypass_revoked",
            tenantId = request.tenantId,
            resourceType = "subscription",
            resourceId = request.tenantId,
            metadata = mapOf(
                "reason" to request.reason,
                "previousUntil" to bypass.until,
                "suspendedAccount" to (request.suspendAccount == true).toString(),
                "ledgerEntryId" to ledgerId,
            ),
        )
        com.articals.logistics.data.dto.RevokeBypassResponse(
            tenantId = request.tenantId,
            entitlement = store.entitlementFor(request.tenantId),
        )
    }

    override suspend fun manageSubscription(
        request: com.articals.logistics.data.dto.ManageSubscriptionRequest,
    ): AppResult<com.articals.logistics.data.dto.ManageSubscriptionResponse> = store.demoCall {
        val admin = store.requireElevatedAdmin()
        requireValid(
            request.action in MANAGE_ACTIONS,
            "action",
            "That subscription action is not supported.",
            "subscription.unknown_action",
        )
        requireValid(
            request.reason.trim().length >= 3,
            "reason",
            "Say why this change is being made. It goes into the audit log.",
            "subscription.reason_required",
        )
        val company = store.companies.value[request.tenantId]
            ?: throw DemoFailure(AppError.NotFound(detail = "company_missing", message = "That company workspace was not found."))
        val existing = store.subscriptions.value[request.tenantId] ?: DemoSubscriptionRecord(tenantId = request.tenantId)
        val policy = store.entitlementPolicy.value
        val nowIso = store.isoNow()
        var ledgerType = "subscription_activated"

        val updated = when (request.action) {
            "activate" -> {
                ledgerType = "subscription_activated"
                existing.copy(
                    planId = request.planId ?: existing.planId,
                    status = "active",
                    billingMode = "automated",
                    currentPeriodStart = nowIso,
                    currentPeriodEnd = isoInstant(store.now() + policy.standardPeriodDays * DemoStore.MILLIS_PER_DAY),
                    suspended = false,
                )
            }

            "suspend" -> {
                ledgerType = "subscription_suspended"
                existing.copy(status = "suspended", suspended = true, suspensionReason = request.reason)
            }

            "reinstate" -> {
                ledgerType = "subscription_reinstated"
                existing.copy(status = "active", suspended = false, suspensionReason = null)
            }

            "extend_period" -> {
                val days = request.extendDays ?: throw DemoFailure(
                    AppError.InvalidInput(
                        detail = "subscription.extend_days_required",
                        message = "How many days should the period be extended by?",
                        fields = mapOf("extendDays" to "Enter a number of days"),
                    ),
                )
                requireValid(days in 1..730, "extendDays", "Enter between 1 and 730 days.", "subscription.invalid_extend")
                ledgerType = "period_extended"
                val currentEnd = existing.currentPeriodEnd
                val baseMillis = currentEnd?.let(::parseOrNullIso)?.takeIf { it > store.now() } ?: store.now()
                existing.copy(
                    status = "active",
                    currentPeriodStart = existing.currentPeriodStart ?: nowIso,
                    currentPeriodEnd = isoInstant(baseMillis + days * DemoStore.MILLIS_PER_DAY),
                )
            }

            "change_plan" -> {
                val planId = request.planId ?: throw DemoFailure(
                    AppError.InvalidInput(
                        detail = "subscription.plan_required",
                        message = "Choose a plan.",
                        fields = mapOf("planId" to "Choose a plan"),
                    ),
                )
                requireValid(
                    store.plans.value.any { it.id == planId },
                    "planId",
                    "That plan is not in the platform catalogue.",
                    "subscription.unknown_plan",
                )
                ledgerType = "plan_changed"
                existing.copy(planId = planId)
            }

            else -> {
                existing.copy(
                    status = "trialing",
                    billingMode = "trial",
                    trialEndsAt = request.trialEndsAt,
                    suspended = false,
                )
            }
        }

        store.subscriptions.value = store.subscriptions.value + (request.tenantId to updated)
        val ledgerId = store.newId("ledger")
        store.appendLedger(
            request.tenantId,
            com.articals.logistics.data.dto.LedgerEntryDto(
                id = ledgerId,
                type = ledgerType,
                recordedBy = admin.uid,
                reason = request.reason,
                periodStart = updated.currentPeriodStart,
                periodEnd = updated.currentPeriodEnd ?: updated.trialEndsAt,
                createdAt = nowIso,
            ),
        )
        store.audit(
            action = "subscription.managed",
            tenantId = request.tenantId,
            resourceType = "subscription",
            resourceId = request.tenantId,
            metadata = mapOf(
                "action" to request.action,
                "reason" to request.reason,
                "entitlement" to store.entitlementFor(request.tenantId),
                "ledgerEntryId" to ledgerId,
            ),
        )
        store.publishNotification(
            title = "Subscription updated",
            body = "${company.name}: ${request.action.replace('_', ' ')}; entitlement is now " +
                "${store.entitlementFor(request.tenantId)}. This is a simulated notification (demo mode).",
        )
        com.articals.logistics.data.dto.ManageSubscriptionResponse(
            tenantId = request.tenantId,
            status = updated.status,
            entitlement = store.entitlementFor(request.tenantId),
            entitlementEffectiveUntil = store.entitlementUntil(request.tenantId)?.let(::isoInstant),
        )
    }

    /**
     * The audit log.
     *
     * Available in demo mode because the demo store *is* the server. In Firebase mode
     * this returns [AppError.Unsupported]: `auditLogs` is not client-readable by
     * design (ADR-014), so the console shows the limitation instead of an empty list
     * that would look like "nothing ever happened".
     */
    override suspend fun auditLog(
        tenantId: String?,
        limit: Int,
    ): AppResult<List<com.articals.logistics.data.dto.AuditEntryDto>> = store.demoCall {
        store.requireElevatedAdmin()
        store.audits.value
            .filter { tenantId == null || it.tenantId == tenantId }
            .sortedByDescending { it.timestamp ?: "" }
            .take(limit)
    }

    /** The offline-payment ledger; see [auditLog] for why this only exists in demo mode. */
    override suspend fun ledger(
        tenantId: String,
        limit: Int,
    ): AppResult<List<com.articals.logistics.data.dto.LedgerEntryDto>> = store.demoCall {
        store.requireElevatedAdmin()
        store.companies.value[tenantId]
            ?: throw DemoFailure(AppError.NotFound(detail = "company_missing", message = "That company workspace was not found."))
        store.ledgers.value[tenantId].orEmpty().sortedByDescending { it.createdAt ?: "" }.take(limit)
    }

    private fun parseOrThrow(value: String, field: String): Long =
        parseOrNullIso(value)?.takeIf { it > 0 } ?: throw DemoFailure(
            AppError.InvalidInput(
                detail = "invalid_instant",
                message = "Enter a valid date and time.",
                fields = mapOf(field to "Enter a valid date and time"),
            ),
        )

    private fun String.isoInstantAfter(other: String): Boolean {
        val a = parseOrNullIso(this) ?: return false
        val b = parseOrNullIso(other) ?: return false
        return a > b
    }

    private fun parseOrNullIso(value: String): Long? =
        com.articals.logistics.core.parseIsoInstantOrNull(value)

    private companion object {
        val MANAGE_ACTIONS = setOf(
            "activate",
            "suspend",
            "reinstate",
            "extend_period",
            "change_plan",
            "set_trial_end",
        )
    }
}

/** A date key one day before [dateKey], in the given company time zone. */
internal fun DemoStore.previousDateKey(dateKey: String, tenantId: String): String =
    shiftDateKey(dateKey, -1, timeZoneOf(tenantId))
