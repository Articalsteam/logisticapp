package com.articals.logistics.ui.screen.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.articals.logistics.core.AppError
import com.articals.logistics.core.AppResult
import com.articals.logistics.core.UiState
import com.articals.logistics.core.currentTimeMillis
import com.articals.logistics.core.endOfBusinessDateMillis
import com.articals.logistics.core.isDateKey
import com.articals.logistics.core.isoInstant
import com.articals.logistics.core.parseAmountToMinorOrNull
import com.articals.logistics.core.shiftDateKey
import com.articals.logistics.core.startOfBusinessDateMillis
import com.articals.logistics.core.todayKey
import com.articals.logistics.data.api.PlatformAdminRepository
import com.articals.logistics.data.dto.AuditEntryDto
import com.articals.logistics.data.dto.BillingBypassRequest
import com.articals.logistics.data.dto.EntitlementPolicyDto
import com.articals.logistics.data.dto.LedgerEntryDto
import com.articals.logistics.data.dto.ListCompaniesRequest
import com.articals.logistics.data.dto.ManageSubscriptionRequest
import com.articals.logistics.data.dto.PlanDto
import com.articals.logistics.data.dto.PlatformCompanyDto
import com.articals.logistics.data.dto.PlatformOverviewDto
import com.articals.logistics.data.dto.RecordOfflinePaymentRequest
import com.articals.logistics.data.dto.RevokeBypassRequest
import com.articals.logistics.data.dto.UpdatePlatformSettingsRequest
import com.articals.logistics.domain.BypassReasons
import com.articals.logistics.domain.EntitlementStates
import com.articals.logistics.domain.OfflinePaymentMethods
import com.articals.logistics.ui.text.ArticalsStrings
import com.articals.logistics.util.Idempotency
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The platform-wide picture. */
data class AdminOverviewState(
    val overview: PlatformOverviewDto? = null,
    val loading: Boolean = true,
    val error: AppError? = null,
)

/**
 * The console's front page.
 *
 * One rule shapes everything here: what an administrator may *do* and what they may
 * *read* are different things. The overview shows counts, expiries and revenue that
 * came from recorded ledger entries; the audit log and the ledger themselves are
 * write-only from the app's point of view — entries are read, never created or edited
 * by hand (ADR-014).
 */
class AdminOverviewViewModel(
    private val admin: PlatformAdminRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(AdminOverviewState())
    val state: StateFlow<AdminOverviewState> = mutableState.asStateFlow()

    fun load() {
        mutableState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val result = admin.overview()) {
                is AppResult.Failure -> mutableState.update {
                    it.copy(loading = false, error = result.error)
                }

                is AppResult.Success -> mutableState.update {
                    it.copy(loading = false, overview = result.value, error = null)
                }
            }
        }
    }
}

data class AdminCompaniesState(
    val search: String = "",
    val status: String? = null,
    val companies: List<PlatformCompanyDto> = emptyList(),
    val loading: Boolean = true,
    val error: AppError? = null,
) {
    val listState: UiState<List<PlatformCompanyDto>>
        get() = when {
            loading -> UiState.Loading
            error != null && companies.isEmpty() -> UiState.Error(error)
            companies.isEmpty() -> UiState.Empty(
                title = ArticalsStrings.Admin.noCompanies,
                message = ArticalsStrings.Admin.noCompaniesBody,
            )

            else -> UiState.Content(companies)
        }
}

/** Every company on the platform, with the two filters that matter. */
class AdminCompaniesViewModel(
    private val admin: PlatformAdminRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(AdminCompaniesState())
    val state: StateFlow<AdminCompaniesState> = mutableState.asStateFlow()

    fun load() {
        mutableState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val request = ListCompaniesRequest(
                search = mutableState.value.search.trim().takeIf { it.isNotEmpty() },
                status = mutableState.value.status,
                limit = 100,
            )
            when (val result = admin.listCompanies(request)) {
                is AppResult.Failure -> mutableState.update {
                    it.copy(loading = false, error = result.error)
                }

                is AppResult.Success -> mutableState.update {
                    it.copy(loading = false, companies = result.value.companies, error = null)
                }
            }
        }
    }

    fun setSearch(search: String) {
        mutableState.update { it.copy(search = search) }
        load()
    }

    fun setStatus(status: String?) {
        mutableState.update { it.copy(status = status) }
        load()
    }
}

/** One company, and everything an administrator can do to its billing. */
data class AdminCompanyState(
    val tenantId: String = "",
    val company: PlatformCompanyDto? = null,
    val ledger: List<LedgerEntryDto> = emptyList(),
    val audit: List<AuditEntryDto> = emptyList(),
    val loading: Boolean = true,
    val error: AppError? = null,
    val notice: String? = null,
    val busy: Boolean = false,
    val payment: OfflinePaymentDraft? = null,
    val bypass: BypassDraft? = null,
    val revoke: RevokeDraft? = null,
    val subscription: SubscriptionActionDraft? = null,
)

/** Recording an offline payment: money that arrived outside the app. */
data class OfflinePaymentDraft(
    val amount: String = "",
    val method: String = "bank_transfer",
    val reference: String = "",
    val receivedDateKey: String = "",
    val periodStartDateKey: String = "",
    val periodEndDateKey: String = "",
    val reason: String = "",
    val internalNotes: String = "",
) {
    val amountMinor: Long? get() = parseAmountToMinorOrNull(amount)

    val datesValid: Boolean
        get() = isDateKey(receivedDateKey) && isDateKey(periodStartDateKey) &&
            isDateKey(periodEndDateKey) && periodStartDateKey <= periodEndDateKey

    val valid: Boolean
        get() = (amountMinor ?: 0L) > 0L && reference.trim().isNotEmpty() &&
            reason.trim().length >= 3 && datesValid
}

/** Granting access without payment. Always a reason and always an end date. */
data class BypassDraft(
    val reason: String = BypassReasons.all.first(),
    val untilDateKey: String = "",
    val internalNotes: String = "",
    val replacesExisting: Boolean = false,
) {
    val valid: Boolean get() = isDateKey(untilDateKey) && untilDateKey > today
}

/** Revoking a bypass, optionally suspending the account with it. */
data class RevokeDraft(
    val reason: String = "",
    val suspendAccount: Boolean = false,
) {
    val valid: Boolean get() = reason.trim().length >= 3
}

/** A subscription action: activate, suspend, reinstate, extend, change plan, set trial end. */
data class SubscriptionActionDraft(
    val action: String = "extend_period",
    val reason: String = "",
    val planId: String = "",
    val extendDays: String = "30",
    val trialEndDateKey: String = "",
) {
    val needsPlan: Boolean get() = action == "change_plan"

    val needsDays: Boolean get() = action == "extend_period"

    val needsTrialDate: Boolean get() = action == "set_trial_end"

    val valid: Boolean
        get() = reason.trim().length >= 3 &&
            (!needsPlan || planId.isNotBlank()) &&
            (!needsDays || (extendDays.toIntOrNull() ?: 0) in 1..3650) &&
            (!needsTrialDate || isDateKey(trialEndDateKey))
}

/**
 * One company's billing, as an administrator works it.
 *
 * Three things the screen refuses to do, all of which the backend also refuses: record a
 * payment with no external reference, grant access with no end date, and change access
 * with no reason. Every one of those leaves a ledger entry and an audit entry, which is
 * why the two lists are on the same screen as the buttons that write them.
 */
class AdminCompanyViewModel(
    private val admin: PlatformAdminRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(AdminCompanyState())
    val state: StateFlow<AdminCompanyState> = mutableState.asStateFlow()

    /** The company being administered, so the drafts can be validated on the server's terms. */
    private var paymentKey: String = Idempotency.newKey("admin.offline_payment")
    private var bypassKey: String = Idempotency.newKey("admin.bypass")

    fun load(
        tenantId: String,
        company: PlatformCompanyDto? = null,
        zoneId: String = "UTC",
    ) {
        if (mutableState.value.tenantId == tenantId && company == null) return
        mutableState.update { it.copy(tenantId = tenantId, company = company ?: it.company) }
        reload(tenantId, zoneId)
    }

    fun reload(tenantId: String, zoneId: String) {
        mutableState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val ledgerResult = admin.ledger(tenantId, limit = 50)
            val auditResult = admin.auditLog(tenantId, limit = 50)
            val companies = admin.listCompanies(ListCompaniesRequest(limit = 200))

            val company = when (companies) {
                is AppResult.Success -> companies.value.companies.firstOrNull { it.tenantId == tenantId }
                is AppResult.Failure -> null
            }

            mutableState.update { current ->
                current.copy(
                    loading = false,
                    company = company ?: current.company,
                    ledger = (ledgerResult as? AppResult.Success)?.value.orEmpty(),
                    audit = (auditResult as? AppResult.Success)?.value.orEmpty(),
                    error = when {
                        companies is AppResult.Failure -> companies.error
                        ledgerResult is AppResult.Failure -> ledgerResult.error
                        auditResult is AppResult.Failure -> auditResult.error
                        else -> null
                    },
                )
            }
        }
        today = todayKey(zoneId)
    }

    fun clearNotice() = mutableState.update { it.copy(notice = null) }

    fun dismissError() = mutableState.update { it.copy(error = null) }

    // --- offline payments ---------------------------------------------------

    fun openPayment(zoneId: String) = mutableState.update {
        val today = todayKey(zoneId)
        it.copy(
            payment = OfflinePaymentDraft(
                receivedDateKey = today,
                periodStartDateKey = today,
                periodEndDateKey = shiftDateKey(today, 30, zoneId),
            ),
        )
    }

    fun updatePayment(draft: OfflinePaymentDraft) = mutableState.update { it.copy(payment = draft) }

    fun closePayment() = mutableState.update { it.copy(payment = null) }

    fun recordPayment(zoneId: String, currency: String) {
        val current = mutableState.value
        val draft = current.payment ?: return
        if (!draft.valid) {
            mutableState.update { it.copy(error = ArticalsStrings.Admin.paymentIncomplete()) }
            return
        }

        mutableState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val request = RecordOfflinePaymentRequest(
                tenantId = current.tenantId,
                idempotencyKey = paymentKey,
                amountMinor = draft.amountMinor ?: 0L,
                currency = currency,
                method = draft.method,
                externalReference = draft.reference.trim(),
                receivedAt = isoInstant(startOfBusinessDateMillis(draft.receivedDateKey, zoneId)),
                periodStart = isoInstant(startOfBusinessDateMillis(draft.periodStartDateKey, zoneId)),
                periodEnd = isoInstant(endOfBusinessDateMillis(draft.periodEndDateKey, zoneId)),
                reason = draft.reason.trim(),
                internalNotes = draft.internalNotes.trim().takeIf { it.isNotEmpty() },
            )
            when (val result = admin.recordOfflinePayment(request)) {
                is AppResult.Failure -> mutableState.update {
                    it.copy(busy = false, error = result.error)
                }

                is AppResult.Success -> {
                    paymentKey = Idempotency.newKey("admin.offline_payment")
                    mutableState.update {
                        it.copy(
                            busy = false,
                            payment = null,
                            notice = ArticalsStrings.Admin.paymentRecordedWith(result.value.entitlement),
                        )
                    }
                    reload(current.tenantId, zoneId)
                }
            }
        }
    }

    // --- bypasses -----------------------------------------------------------

    fun openBypass(zoneId: String) = mutableState.update {
        it.copy(
            bypass = BypassDraft(
                untilDateKey = shiftDateKey(todayKey(zoneId), 30, zoneId),
            ),
        )
    }

    fun updateBypass(draft: BypassDraft) = mutableState.update { it.copy(bypass = draft) }

    fun closeBypass() = mutableState.update { it.copy(bypass = null) }

    fun grantBypass(zoneId: String) {
        val current = mutableState.value
        val draft = current.bypass ?: return
        if (!draft.valid) {
            mutableState.update { it.copy(error = ArticalsStrings.Admin.bypassIncomplete()) }
            return
        }

        mutableState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val request = BillingBypassRequest(
                tenantId = current.tenantId,
                idempotencyKey = bypassKey,
                reason = draft.reason,
                until = isoInstant(endOfBusinessDateMillis(draft.untilDateKey, zoneId)),
                internalNotes = draft.internalNotes.trim().takeIf { it.isNotEmpty() },
                replacesExisting = draft.replacesExisting,
            )
            when (val result = admin.setBillingBypass(request)) {
                is AppResult.Failure -> mutableState.update { it.copy(busy = false, error = result.error) }
                is AppResult.Success -> {
                    bypassKey = Idempotency.newKey("admin.bypass")
                    mutableState.update {
                        it.copy(
                            busy = false,
                            bypass = null,
                            notice = ArticalsStrings.Admin.bypassGrantedWith(result.value.durationDays),
                        )
                    }
                    reload(current.tenantId, zoneId)
                }
            }
        }
    }

    fun askRevoke() = mutableState.update { it.copy(revoke = RevokeDraft()) }

    fun updateRevoke(draft: RevokeDraft) = mutableState.update { it.copy(revoke = draft) }

    fun cancelRevoke() = mutableState.update { it.copy(revoke = null) }

    fun revokeBypass(zoneId: String) {
        val current = mutableState.value
        val draft = current.revoke ?: return
        if (!draft.valid) {
            mutableState.update { it.copy(error = ArticalsStrings.Admin.revokeReasonRequired()) }
            return
        }

        mutableState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val request = RevokeBypassRequest(
                tenantId = current.tenantId,
                reason = draft.reason.trim(),
                suspendAccount = draft.suspendAccount,
            )
            when (val result = admin.revokeBillingBypass(request)) {
                is AppResult.Failure -> mutableState.update { it.copy(busy = false, error = result.error) }
                is AppResult.Success -> mutableState.update {
                    it.copy(
                        busy = false,
                        revoke = null,
                        notice = ArticalsStrings.Admin.bypassRevokedWith(result.value.entitlement),
                    )
                }
            }
            reload(current.tenantId, zoneId)
        }
    }

    // --- subscription actions ----------------------------------------------

    fun openSubscription() = mutableState.update { it.copy(subscription = SubscriptionActionDraft()) }

    fun updateSubscription(draft: SubscriptionActionDraft) = mutableState.update {
        it.copy(subscription = draft)
    }

    fun closeSubscription() = mutableState.update { it.copy(subscription = null) }

    fun applySubscription(zoneId: String) {
        val current = mutableState.value
        val draft = current.subscription ?: return
        if (!draft.valid) {
            mutableState.update { it.copy(error = ArticalsStrings.Admin.actionIncomplete()) }
            return
        }

        mutableState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val request = ManageSubscriptionRequest(
                tenantId = current.tenantId,
                action = draft.action,
                reason = draft.reason.trim(),
                planId = draft.planId.takeIf { draft.needsPlan && it.isNotBlank() },
                extendDays = draft.extendDays.toIntOrNull().takeIf { draft.needsDays },
                trialEndsAt = draft.trialEndDateKey
                    .takeIf { draft.needsTrialDate && isDateKey(it) }
                    ?.let { isoInstant(endOfBusinessDateMillis(it, zoneId)) },
            )
            when (val result = admin.manageSubscription(request)) {
                is AppResult.Failure -> mutableState.update { it.copy(busy = false, error = result.error) }
                is AppResult.Success -> mutableState.update {
                    it.copy(
                        busy = false,
                        subscription = null,
                        notice = ArticalsStrings.Admin.subscriptionApplied(result.value.entitlement),
                    )
                }
            }
            reload(current.tenantId, zoneId)
        }
    }

    private var today: String = "1970-01-01"

    /** The company's own dates, for validating the bypass end date against today. */
    fun setToday(zoneId: String) {
        today = todayKey(zoneId)
    }

    fun nowMillis(): Long = currentTimeMillis()
}

data class AdminSettingsState(
    val plans: List<PlanDto> = emptyList(),
    val policy: EntitlementPolicyDto = EntitlementPolicyDto(),
    val loading: Boolean = true,
    val saving: Boolean = false,
    val error: AppError? = null,
    val notice: String? = null,
    val dirty: Boolean = false,
)

/**
 * The platform's own settings: the plan catalogue and the entitlement policy.
 *
 * Editing this changes what every company sees, so the saves are explicit and the
 * screen shows what each number means — the trial length, the grace days, the longest
 * bypass, the standard period.
 */
class AdminSettingsViewModel(
    private val admin: PlatformAdminRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(AdminSettingsState())
    val state: StateFlow<AdminSettingsState> = mutableState.asStateFlow()

    fun load() {
        mutableState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val companies = admin.listCompanies(ListCompaniesRequest(limit = 1))
            if (companies is AppResult.Failure) {
                mutableState.update { it.copy(loading = false, error = companies.error) }
            } else {
                mutableState.update { it.copy(loading = false) }
            }
        }
    }

    fun setPlans(plans: List<PlanDto>) = mutableState.update { it.copy(plans = plans, dirty = true) }

    fun setPolicy(policy: EntitlementPolicyDto) = mutableState.update {
        it.copy(policy = policy, dirty = true)
    }

    fun clearNotice() = mutableState.update { it.copy(notice = null) }

    fun save() {
        mutableState.update { it.copy(saving = true, error = null, notice = null) }
        viewModelScope.launch {
            val request = UpdatePlatformSettingsRequest(
                plans = mutableState.value.plans.takeIf { it.isNotEmpty() },
                entitlementPolicy = mutableState.value.policy,
            )
            when (val result = admin.updateSettings(request)) {
                is AppResult.Failure -> mutableState.update { it.copy(saving = false, error = result.error) }
                is AppResult.Success -> mutableState.update {
                    it.copy(
                        saving = false,
                        dirty = false,
                        notice = ArticalsStrings.Admin.settingsSaved(result.value.updated),
                    )
                }
            }
        }
    }
}

data class AdminAuditState(
    val entries: List<AuditEntryDto> = emptyList(),
    val tenantId: String? = null,
    val tenantFilter: String = "",
    val loading: Boolean = true,
    val error: AppError? = null,
)

/**
 * The audit log.
 *
 * It is append-only: this screen can read it and cannot change it, which is the whole
 * point of an audit log (ADR-014).
 */
class AdminAuditViewModel(
    private val admin: PlatformAdminRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(AdminAuditState())
    val state: StateFlow<AdminAuditState> = mutableState.asStateFlow()

    fun load() = fetch(mutableState.value.tenantFilter.trim().takeIf { it.isNotEmpty() })

    fun setFilter(value: String) = mutableState.update { it.copy(tenantFilter = value) }

    fun applyFilter() = fetch(mutableState.value.tenantFilter.trim().takeIf { it.isNotEmpty() })

    fun clearFilter() {
        mutableState.update { it.copy(tenantFilter = "") }
        fetch(null)
    }

    private fun fetch(tenantId: String?) {
        mutableState.update { it.copy(loading = true, error = null, tenantId = tenantId) }
        viewModelScope.launch {
            when (val result = admin.auditLog(tenantId, limit = 100)) {
                is AppResult.Failure -> mutableState.update {
                    it.copy(loading = false, error = result.error)
                }

                is AppResult.Success -> mutableState.update {
                    it.copy(loading = false, entries = result.value, error = null)
                }
            }
        }
    }
}

/** Shown when the console is opened without the claim. It should not be reachable. */
object AdminAccess {
    val deniedTitle: String get() = ArticalsStrings.Admin.noAdminAccess
    val deniedBody: String get() = ArticalsStrings.Admin.noAdminAccessBody

    /** Every entitlement state, so a filter row can offer all of them. */
    fun entitlementFilters(): List<String> = EntitlementStates.all

    /** Payment methods the platform records offline. */
    fun paymentMethods(): List<String> = OfflinePaymentMethods.all

    /** The bypass reasons the backend accepts. */
    fun bypassReasons(): List<String> = BypassReasons.all
}
