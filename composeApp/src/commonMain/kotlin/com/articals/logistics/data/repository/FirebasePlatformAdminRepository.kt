package com.articals.logistics.data.repository

import com.articals.logistics.data.api.PlatformAdminRepository
import com.articals.logistics.core.AppResult
import com.articals.logistics.data.BackendFunctions
import com.articals.logistics.data.FunctionsCaller
import com.articals.logistics.data.dto.BillingBypassRequest
import com.articals.logistics.data.dto.BillingBypassResponse
import com.articals.logistics.core.AppError
import com.articals.logistics.data.dto.AuditEntryDto
import com.articals.logistics.data.dto.LedgerEntryDto
import com.articals.logistics.data.dto.ListCompaniesRequest
import com.articals.logistics.data.dto.ListCompaniesResponse
import com.articals.logistics.data.dto.ManageSubscriptionRequest
import com.articals.logistics.data.dto.ManageSubscriptionResponse
import com.articals.logistics.data.dto.PlatformOverviewDto
import com.articals.logistics.data.dto.RecordOfflinePaymentRequest
import com.articals.logistics.data.dto.RecordOfflinePaymentResponse
import com.articals.logistics.data.dto.RevokeBypassRequest
import com.articals.logistics.data.dto.RevokeBypassResponse
import com.articals.logistics.data.dto.UpdatePlatformSettingsRequest
import com.articals.logistics.data.dto.UpdatePlatformSettingsResponse

/**
 * Articals Studio's console.
 *
 * Every operation here is refused by the backend unless the caller carries the
 * platform administrator claim, which the client cannot grant itself. The console
 * is built into the same app (a separate app flavour / scheme) but is unreachable
 * without the claim — the UI check is a convenience, the backend check is the rule.
 *
 * Offline payments are recorded, never collected: the app does not take money, so
 * there is no payment provider anywhere in this code path (ADR-010).
 */
class FirebasePlatformAdminRepository(
    private val caller: FunctionsCaller,
) : PlatformAdminRepository {
    override suspend fun overview(): AppResult<PlatformOverviewDto> =
        caller.call(BackendFunctions.getPlatformOverview, EmptyRequest)

    override suspend fun listCompanies(request: ListCompaniesRequest): AppResult<ListCompaniesResponse> =
        caller.call(BackendFunctions.listCompanies, request)

    override suspend fun updateSettings(request: UpdatePlatformSettingsRequest): AppResult<UpdatePlatformSettingsResponse> =
        caller.call(BackendFunctions.updatePlatformSettings, request)

    /** Records money that arrived outside the app (bank transfer, cash, cheque). */
    override suspend fun recordOfflinePayment(request: RecordOfflinePaymentRequest): AppResult<RecordOfflinePaymentResponse> =
        caller.call(BackendFunctions.recordOfflinePayment, request)

    /** Grants or extends a billing bypass. Long bypasses need the elevated claim. */
    override suspend fun setBillingBypass(request: BillingBypassRequest): AppResult<BillingBypassResponse> =
        caller.call(BackendFunctions.setBillingBypass, request)

    override suspend fun revokeBillingBypass(request: RevokeBypassRequest): AppResult<RevokeBypassResponse> =
        caller.call(BackendFunctions.revokeBillingBypass, request)

    override suspend fun manageSubscription(request: ManageSubscriptionRequest): AppResult<ManageSubscriptionResponse> =

    /**
     * The audit log.
     *
     * `auditLogs` is not client-readable in Firebase mode: the rules keep it
     * server-only so that a compromised owner account cannot read Articals Studio's
     * operational trail. Serving it to the console needs a backend read operation,
     * which is not part of this phase (ADR-014); demo mode shows its own records so
     * the screen can be verified.
     */
    override suspend fun auditLog(tenantId: String?, limit: Int): AppResult<List<AuditEntryDto>> =
        unavailable(
            detail = "audit_log_requires_backend",
            message = "The audit log is served by the platform backend, which this build does not read " +
                "directly. Switch to demo mode to inspect the trail, or use the Firebase console.",
        )

    /** Billing ledger history — server-only in Firebase mode for the same reason. */
    override suspend fun ledger(tenantId: String, limit: Int): AppResult<List<LedgerEntryDto>> =
        unavailable(
            detail = "ledger_requires_backend",
            message = "Payment ledger history is server-only. The company's current billing state and the " +
                "recorded payments are still shown on the billing screen.",
        )

    private fun <T> unavailable(detail: String, message: String): AppResult<T> =
        AppResult.Failure(AppError.Unsupported(detail = detail, message = message))
        caller.call(BackendFunctions.manageSubscription, request)
}

/** The platform overview callable takes no arguments; the backend still expects an object. */
@kotlinx.serialization.Serializable
internal object EmptyRequest
