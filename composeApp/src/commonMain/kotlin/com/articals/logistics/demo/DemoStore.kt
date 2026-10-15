package com.articals.logistics.demo

import com.articals.logistics.core.AppError
import com.articals.logistics.core.AuthUser
import com.articals.logistics.core.Permissions
import com.articals.logistics.core.businessDateKey
import com.articals.logistics.core.currentTimeMillis
import com.articals.logistics.core.isoInstant
import com.articals.logistics.core.parseIsoInstantOrNull
import com.articals.logistics.domain.EntitlementStates
import com.articals.logistics.data.dto.AppNotificationDto
import com.articals.logistics.data.dto.AuditEntryDto
import com.articals.logistics.data.dto.BypassViewDto
import com.articals.logistics.data.dto.CompanyDto
import com.articals.logistics.data.dto.CustomerDto
import com.articals.logistics.data.dto.DeliveryEventDto
import com.articals.logistics.data.dto.ExpenseDto
import com.articals.logistics.data.dto.LedgerEntryDto
import com.articals.logistics.data.dto.MembershipDto
import com.articals.logistics.data.dto.OfflinePaymentViewDto
import com.articals.logistics.data.dto.OrderDto
import com.articals.logistics.data.dto.PricingConfigDto
import com.articals.logistics.data.dto.RiderDto
import com.articals.logistics.data.dto.SavedQuoteDto
import com.articals.logistics.data.dto.SubscriptionViewDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A demo account: an email, a shared demo password, and the roles it can play. */
data class DemoUserRecord(
    val uid: String,
    val email: String,
    val password: String,
    val displayName: String,
    val phoneNumber: String? = null,
    val emailVerified: Boolean = true,
    val isPlatformAdmin: Boolean = false,
    val isPlatformAdminElevated: Boolean = false,
    val disabled: Boolean = false,
)

/** The subscription record demo mode stores, before entitlement is derived. */
data class DemoSubscriptionRecord(
    val tenantId: String,
    val planId: String = "starter",
    val billingMode: String = "trial",
    val status: String = "trialing",
    val trialEndsAt: String? = null,
    val currentPeriodStart: String? = null,
    val currentPeriodEnd: String? = null,
    val bypass: DemoBypass? = null,
    val lastOfflinePayment: OfflinePaymentViewDto? = null,
    val suspended: Boolean = false,
    val suspensionReason: String? = null,
    val paidPeriodsRecorded: Int = 0,
    /** Admin-only: never shown to the company, mirroring `subscriptions/{id}/private/billing`. */
    val lastPaymentReference: String? = null,
    val lastPaymentNotes: String? = null,
    val lastPaymentEvidencePath: String? = null,
    val lastPaymentRecordedBy: String? = null,
)

data class DemoBypass(
    val reason: String,
    val until: String,
    val grantedBy: String,
    val grantedAt: String,
    /** Admin-only notes; never shown to the company. */
    val notes: String? = null,
)

data class DemoInvitation(
    val id: String,
    val tenantId: String,
    val email: String,
    val role: String,
    val displayName: String?,
    val grantedPermissions: List<String>,
    val revokedPermissions: List<String>,
    val expiresAt: String,
    val acceptedBy: String? = null,
)

data class DemoRiderInvitation(
    val id: String,
    val tenantId: String,
    val riderId: String,
    val email: String,
    val token: String,
    val expiresAt: String,
    val acceptedBy: String? = null,
)

/**
 * The demo "server".
 *
 * One in-memory store, shared by every demo repository, so a change made on one
 * screen is visible everywhere instantly: create a delivery and it appears in the
 * list, on the rider's home and on the tracking page.
 *
 * It also plays the part the backend plays in production, which is why the
 * authority checks below exist at all:
 *
 *  * membership and permissions are resolved from the membership document, exactly
 *    like `functions/src/shared/auth.ts`;
 *  * writes are refused when the workspace is limited or suspended;
 *  * status changes are validated against the transition table;
 *  * every administrative action writes an audit entry and a ledger entry.
 *
 * Without this, demo mode would happily let a dispatcher do things the real backend
 * would refuse, and the interface would be verified against behaviour that does not
 * exist.
 */
class DemoStore(
    val controls: DemoControls,
    private val random: DemoRandom = DemoRandom(SEED),
    private val clock: () -> Long = { currentTimeMillis() },
) : DemoDataCommands {

    // --- identity ----------------------------------------------------------
    private val accountsFlow = MutableStateFlow<List<DemoUserRecord>>(emptyList())
    val accounts: StateFlow<List<DemoUserRecord>> = accountsFlow.asStateFlow()

    private val sessionUidFlow = MutableStateFlow<String?>(null)
    val sessionUid: StateFlow<String?> = sessionUidFlow.asStateFlow()

    private val activeTenantFlow = MutableStateFlow<String?>(null)
    val activeTenant: StateFlow<String?> = activeTenantFlow.asStateFlow()

    // --- company data ------------------------------------------------------
    val companies = MutableStateFlow<Map<String, CompanyDto>>(emptyMap())
    val memberships = MutableStateFlow<List<MembershipDto>>(emptyList())
    val pricingConfigs = MutableStateFlow<Map<String, PricingConfigDto>>(emptyMap())
    val quotes = MutableStateFlow<List<SavedQuoteDto>>(emptyList())
    val orders = MutableStateFlow<Map<String, OrderDto>>(emptyMap())
    val events = MutableStateFlow<Map<String, List<DeliveryEventDto>>>(emptyMap())
    val riders = MutableStateFlow<Map<String, RiderDto>>(emptyMap())
    val customers = MutableStateFlow<Map<String, CustomerDto>>(emptyMap())
    val expenses = MutableStateFlow<Map<String, ExpenseDto>>(emptyMap())
    val subscriptions = MutableStateFlow<Map<String, DemoSubscriptionRecord>>(emptyMap())
    val plans = MutableStateFlow(DemoSeed.defaultPlans)
    val entitlementPolicy = MutableStateFlow(DemoSeed.defaultEntitlementPolicy)
    /** Device tokens the app would have registered; demo mode never contacts a push provider. */
    val deviceTokens = MutableStateFlow<List<Pair<String, String>>>(emptyList())

    // --- platform records --------------------------------------------------
    val audits = MutableStateFlow<List<AuditEntryDto>>(emptyList())
    val ledgers = MutableStateFlow<Map<String, List<LedgerEntryDto>>>(emptyMap())
    val notifications = MutableStateFlow<List<AppNotificationDto>>(emptyList())
    val invitations = MutableStateFlow<List<DemoInvitation>>(emptyList())
    val riderInvitations = MutableStateFlow<List<DemoRiderInvitation>>(emptyList())

    /** Set by the control panel, so every entitlement state can be inspected. */
    var entitlementOverride: String? = null
        private set

    private val counters = mutableMapOf<String, Int>()

    /**
     * Idempotency: the same key returns the same answer, exactly like the backend's
     * `withIdempotency`. Retrying "create company" after a dropped response must not
     * produce two companies.
     */
    private val idempotentResults = mutableMapOf<String, Any>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> idempotent(key: String, produce: () -> T): T {
        (idempotentResults[key] as? T)?.let { return it }
        val value = produce()
        idempotentResults[key] = value
        return value
    }

    init {
        controls.commands = this
        DemoSeed.populate(this)
    }

    // -----------------------------------------------------------------------
    // Clock, ids, time zone
    // -----------------------------------------------------------------------

    fun now(): Long = clock()

    fun isoNow(): String = isoInstant(now())

    fun newId(prefix: String): String {
        val next = (counters[prefix] ?: 0) + 1
        counters[prefix] = next
        return "$prefix-demo-${next.toString().padStart(5, '0')}"
    }

    /** The company's time zone decides what "today" means, exactly as in production. */
    fun timeZoneOf(tenantId: String): String = companies.value[tenantId]?.timezone ?: "Africa/Lagos"

    fun todayKey(tenantId: String): String = businessDateKey(now(), timeZoneOf(tenantId))

    fun dateKeyFor(tenantId: String, epochMillis: Long): String = businessDateKey(epochMillis, timeZoneOf(tenantId))

    // -----------------------------------------------------------------------
    // Identity
    // -----------------------------------------------------------------------

    fun currentUid(): String? = sessionUidFlow.value

    fun currentUser(): DemoUserRecord? = sessionUidFlow.value?.let { uid ->
        accountsFlow.value.firstOrNull { it.uid == uid }
    }

    fun authUser(user: DemoUserRecord): AuthUser = AuthUser(
        uid = user.uid,
        email = user.email,
        displayName = user.displayName,
        phoneNumber = user.phoneNumber,
        isEmailVerified = user.emailVerified,
    )

    fun currentAuthUser(): AuthUser? = currentUser()?.let(::authUser)

    fun addAccount(record: DemoUserRecord) {
        accountsFlow.update { it + record }
    }

    fun accountByEmail(email: String): DemoUserRecord? =
        accountsFlow.value.firstOrNull { it.email.equals(email.trim(), ignoreCase = true) }

    fun signIn(email: String, password: String): DemoUserRecord {
        val user = accountByEmail(email)
        if (user == null || user.password != password) {
            throw DemoFailure(
                AppError.InvalidInput(
                    detail = "auth.invalid_credentials",
                    message = "That email address and password do not match. In demo mode the password is " +
                        "\"${DemoCredentials.password}\".",
                ),
            )
        }
        if (user.disabled) {
            throw DemoFailure(
                AppError.Rejected(
                    detail = "auth.user_disabled",
                    message = "This demo account has been disabled by an administrator.",
                ),
            )
        }
        signInAs(user)
        return user
    }

    fun signInAs(user: DemoUserRecord) {
        sessionUidFlow.value = user.uid
        if (activeTenantFlow.value == null) {
            activeTenantFlow.value = memberships.value
                .firstOrNull { it.userId == user.uid && it.status == "active" }
                ?.tenantId
        }
    }

    fun signOut() {
        sessionUidFlow.value = null
        activeTenantFlow.value = null
    }

    fun setActiveTenant(tenantId: String) {
        activeTenantFlow.value = tenantId
    }

    fun clearActiveTenant() {
        activeTenantFlow.value = null
    }

    /** Every membership the signed-in user holds, active or not. */
    fun membershipsOf(userId: String): List<MembershipDto> =
        memberships.value.filter { it.userId == userId }

    fun membershipFor(tenantId: String, userId: String): MembershipDto? =
        memberships.value.firstOrNull { it.tenantId == tenantId && it.userId == userId }

    // -----------------------------------------------------------------------
    // Authority — the demo's copy of the backend's guards
    // -----------------------------------------------------------------------

    fun requireUser(): DemoUserRecord = currentUser() ?: throw DemoFailure(
        AppError.Unauthenticated(detail = "no_auth_context"),
    )

    fun requireMembership(tenantId: String, permission: String? = null): MembershipDto {
        val user = requireUser()
        val membership = membershipFor(tenantId, user.uid)
            ?: throw DemoFailure(AppError.NotAMember())
        if (membership.status != "active") {
            throw DemoFailure(AppError.PermissionDenied(
                detail = "membership_inactive",
                message = "Your access to this workspace has been disabled. Contact the company owner.",
            ))
        }
        if (permission != null) {
            val effective = Permissions.effective(
                role = membership.role,
                granted = membership.grantedPermissions,
                revoked = membership.revokedPermissions,
            )
            if (permission !in effective) {
                throw DemoFailure(AppError.PermissionDenied(
                    detail = "permission_required",
                    message = "Your role in this company does not allow that action.",
                ))
            }
        }
        return membership
    }

    /** A write: membership first, then the workspace's entitlement. */
    fun requireWrite(tenantId: String, permission: String? = null): MembershipDto {
        val membership = requireMembership(tenantId, permission)
        val entitlement = entitlementFor(tenantId)
        if (!EntitlementStates.canWrite(entitlement)) {
            throw DemoFailure(
                if (entitlement == EntitlementStates.Suspended) {
                    AppError.EntitlementLimited(
                        detail = "entitlement_suspended",
                        message = "This company account is suspended. Contact Articals Studio support.",
                    )
                } else {
                    AppError.EntitlementLimited(
                        detail = "entitlement_limited",
                        message = "This workspace is read-only until billing is settled. " +
                            "You can still view and export your data.",
                    )
                },
            )
        }
        return membership
    }

    fun requirePlatformAdmin(): DemoUserRecord {
        val user = requireUser()
        if (!user.isPlatformAdmin) {
            throw DemoFailure(AppError.PermissionDenied(
                detail = "platform_admin_required",
                message = "That is a platform administration action.",
            ))
        }
        return user
    }

    fun requireElevatedAdmin(): DemoUserRecord {
        val user = requirePlatformAdmin()
        if (!user.isPlatformAdminElevated) {
            throw DemoFailure(AppError.PermissionDenied(
                detail = "platform_admin_elevated_required",
                message = "Bypasses longer than 90 days need an elevated administrator.",
            ))
        }
        return user
    }

    fun isAssignedRider(tenantId: String, order: OrderDto, userId: String): Boolean {
        val membership = membershipFor(tenantId, userId) ?: return false
        if (membership.role != "rider") return false
        val riderId = riders.value.values.firstOrNull { it.userId == userId }?.id ?: return false
        return order.assignedRiderId == riderId
    }

    // -----------------------------------------------------------------------
    // Entitlement — derived, never stored as a boolean
    // -----------------------------------------------------------------------

    /**
     * Mirrors `computeEntitlement`: suspension beats everything, then an active
     * bypass, then a recorded paid period, then the trial, then grace, then limited.
     */
    fun entitlementFor(tenantId: String): String {
        entitlementOverride?.let { return it }
        val record = subscriptions.value[tenantId] ?: return EntitlementStates.Trialing
        val now = now()

        if (record.suspended || record.status == "suspended") return EntitlementStates.Suspended

        val bypassUntil = record.bypass?.until?.let { parseIsoInstantOrNull(it) }
        if (bypassUntil != null && bypassUntil > now) return EntitlementStates.ActiveBypass

        val periodEnd = record.currentPeriodEnd?.let { parseIsoInstantOrNull(it) }
        if (periodEnd != null && periodEnd > now) {
            return if (record.billingMode == "automated") EntitlementStates.ActivePaid else EntitlementStates.ActiveOffline
        }

        val trialEnd = record.trialEndsAt?.let { parseIsoInstantOrNull(it) }
        if (trialEnd != null && trialEnd > now) return EntitlementStates.Trialing

        val lastDeadline = maxOf(trialEnd ?: 0L, periodEnd ?: 0L)
        if (lastDeadline > 0L && now <= lastDeadline + GRACE_DAYS * MILLIS_PER_DAY) return EntitlementStates.Grace
        return EntitlementStates.Limited
    }

    fun subscriptionView(tenantId: String): SubscriptionViewDto {
        val record = subscriptions.value[tenantId] ?: DemoSubscriptionRecord(tenantId = tenantId)
        val plan = plans.value.firstOrNull { it.id == record.planId } ?: DemoSeed.defaultPlans.first()
        val entitlement = entitlementFor(tenantId)
        val daysRemaining = daysRemaining(record, entitlement)
        return SubscriptionViewDto(
            planId = plan.id,
            planName = plan.name,
            planPriceMinor = plan.priceMinor,
            planCurrency = plan.currency,
            status = entitlementStatus(entitlement, record),
            billingMode = record.billingMode,
            entitlement = entitlement,
            entitlementEffectiveUntil = effectiveUntil(record),
            entitlementLabel = EntitlementStates.label(entitlement),
            trialEndsAt = record.trialEndsAt,
            currentPeriodStart = record.currentPeriodStart,
            currentPeriodEnd = record.currentPeriodEnd,
            bypass = record.bypass?.let { BypassViewDto(active = true, until = it.until, reason = it.reason) },
            lastOfflinePayment = record.lastOfflinePayment,
            daysRemaining = daysRemaining,
            writesAllowed = EntitlementStates.canWrite(entitlement),
            billingNotice = EntitlementStates.notice(entitlement, daysRemaining),
        )
    }

    private fun effectiveUntil(record: DemoSubscriptionRecord): String? = when {
        record.bypass?.until != null && (parseIsoInstantOrNull(record.bypass.until) ?: 0L) > now() -> record.bypass.until
        record.currentPeriodEnd != null -> record.currentPeriodEnd
        else -> record.trialEndsAt
    }

    private fun entitlementStatus(entitlement: String, record: DemoSubscriptionRecord): String = when (entitlement) {
        EntitlementStates.Suspended -> "suspended"
        EntitlementStates.Limited -> "expired"
        EntitlementStates.Trialing -> "trialing"
        else -> "active"
    }

    /**
     * The instant the current entitlement stops being effective.
     *
     * Same rule as the backend: whichever of the trial end, the paid period end or the
     * bypass end is latest, and null when there is nothing to expire.
     */
    fun entitlementUntil(tenantId: String): Long? {
        val record = subscriptions.value[tenantId] ?: return null
        val candidates = listOfNotNull(
            record.trialEndsAt?.let(::parseIsoInstantOrNull),
            record.currentPeriodEnd?.let(::parseIsoInstantOrNull),
            record.bypass?.until?.let(::parseIsoInstantOrNull),
        )
        return candidates.maxOrNull()
    }

    private fun daysRemaining(record: DemoSubscriptionRecord, entitlement: String): Int? {
        val target = when (entitlement) {
            EntitlementStates.Trialing -> record.trialEndsAt
            EntitlementStates.ActiveBypass -> record.bypass?.until
            else -> record.currentPeriodEnd
        } ?: return null
        val end = parseIsoInstantOrNull(target) ?: return null
        val days = ((end - now()) / MILLIS_PER_DAY).toInt()
        return if (days < 0) 0 else days
    }

    // -----------------------------------------------------------------------
    // Records the UI reads
    // -----------------------------------------------------------------------

    fun audit(action: String, tenantId: String?, resourceType: String?, resourceId: String?, reason: String? = null,
              metadata: Map<String, String> = emptyMap(), actorUserId: String? = null) {
        val actor = actorUserId ?: sessionUidFlow.value ?: "system"
        val role = memberships.value.firstOrNull { it.userId == actor && it.tenantId == tenantId }?.role
            ?: if (currentUser()?.isPlatformAdmin == true) "platform_admin" else null
        audits.update { list ->
            listOf(
                AuditEntryDto(
                    id = newId("audit"),
                    tenantId = tenantId,
                    actorUserId = actor,
                    actorRole = role,
                    action = action,
                    resourceType = resourceType,
                    resourceId = resourceId,
                    reason = reason,
                    metadata = metadata,
                    timestamp = isoNow(),
                ),
            ) + list
        }
    }

    fun appendLedger(tenantId: String, entry: LedgerEntryDto) {
        ledgers.update { current ->
            current + (tenantId to (listOf(entry) + current[tenantId].orEmpty()))
        }
    }

    fun publishNotification(title: String, body: String, simulated: Boolean = true) {
        notifications.update { list ->
            listOf(
                AppNotificationDto(
                    id = newId("notification"),
                    title = title,
                    body = body,
                    at = isoNow(),
                    simulated = simulated,
                ),
            ) + list
        }
    }

    // -----------------------------------------------------------------------
    // Order helpers used by several repositories
    // -----------------------------------------------------------------------

    /** Removes one notification from the list (the panel and the bell both use this). */
    fun dismissNotification(id: String) {
        notifications.value = notifications.value.filterNot { it.id == id }
    }

    fun orderById(orderId: String): OrderDto = orders.value[orderId] ?: throw DemoFailure(
        AppError.NotFound(detail = "order_missing", message = "That delivery no longer exists."),
    )

    fun appendEvent(orderId: String, status: String, actorUserId: String, notes: String? = null, metadata: Map<String, String> = emptyMap()): String {
        val id = newId("event")
        val event = DeliveryEventDto(
            id = id,
            status = status,
            actorUserId = actorUserId,
            notes = notes,
            createdAt = isoNow(),
            metadata = metadata,
        )
        events.update { current ->
            current + (orderId to (current[orderId].orEmpty() + event))
        }
        return id
    }

    fun updateOrder(orderId: String, transform: (OrderDto) -> OrderDto) {
        orders.update { current ->
            val existing = current[orderId]?.transform(::copyWithUpdatedAt) ?: return@update current
            current + (orderId to existing)
        }
    }

    private fun copyWithUpdatedAt(order: OrderDto): OrderDto = order.copy(updatedAt = isoNow())

    // -----------------------------------------------------------------------
    // Control panel commands
    // -----------------------------------------------------------------------

    override fun loadEmptyCompany() {
        // Keeps the workspace and its owner, drops everything operational: the state a
        // brand-new company is in, so the getting-started screens can be verified.
        val tenantId = DemoSeed.primaryTenantId
        orders.update { current -> current.filterValues { it.tenantId != tenantId } }
        customers.update { current -> current.filterValues { it.tenantId != tenantId } }
        riders.update { current -> current.filterValues { it.tenantId != tenantId } }
        expenses.update { current -> current.filterValues { it.tenantId != tenantId } }
        events.update { map -> map.filterKeys { orderId -> orders.value.containsKey(orderId) } }
        quotes.update { current -> current.filter { quote -> !quote.id.startsWith("$tenantId-") } }
        memberships.update { current -> current.filter { it.tenantId != tenantId || it.role == "owner" } }
        companies.update { current ->
            current + (tenantId to (current[tenantId]?.copy(
                onboarding = current[tenantId]?.onboarding?.copy(isComplete = false),
                serviceAreas = emptyList(),
            ) ?: return@update current))
        }
        invitations.update { emptyList() }
        riderInvitations.update { emptyList() }
        audit("company.reset_to_empty", tenantId, "company", tenantId, reason = "Demo control: load an empty company")
    }

    override fun setEntitlementOverride(entitlement: String?) {
        entitlementOverride = entitlement?.takeIf { it in EntitlementStates.all }
        audit(
            action = "demo.entitlement_override",
            tenantId = DemoSeed.primaryTenantId,
            resourceType = "subscription",
            resourceId = DemoSeed.primaryTenantId,
            reason = "Demo control: entitlement set to ${entitlement ?: "derived"}",
        )
    }

    override fun reset() {
        counters.clear()
        sessionUidFlow.value = null
        activeTenantFlow.value = null
        entitlementOverride = null
        accountsFlow.value = emptyList()
        companies.value = emptyMap()
        memberships.value = emptyList()
        pricingConfigs.value = emptyMap()
        quotes.value = emptyList()
        orders.value = emptyMap()
        events.value = emptyMap()
        riders.value = emptyMap()
        customers.value = emptyMap()
        expenses.value = emptyMap()
        subscriptions.value = emptyMap()
        audits.value = emptyList()
        ledgers.value = emptyMap()
        notifications.value = emptyList()
        invitations.value = emptyList()
        riderInvitations.value = emptyList()
        plans.value = DemoSeed.defaultPlans
        idempotentResults.clear()
        DemoSeed.populate(this)
    }

    companion object {
        const val SEED: Long = 20261014L
        const val GRACE_DAYS: Int = 7
        const val MILLIS_PER_DAY: Long = 86_400_000L
    }
}
