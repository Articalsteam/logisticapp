package com.articals.logistics.data.repository

import com.articals.logistics.core.AppResult
import com.articals.logistics.data.BackendFunctions
import com.articals.logistics.data.FunctionsCaller
import com.articals.logistics.data.dto.BillingBypassRequest
import com.articals.logistics.data.dto.BillingBypassResponse
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
class PlatformAdminRepository(
    private val caller: FunctionsCaller,
) {
    suspend fun overview(): AppResult<PlatformOverviewDto> =
        caller.call(BackendFunctions.getPlatformOverview, EmptyRequest)

    suspend fun listCompanies(request: ListCompaniesRequest): AppResult<ListCompaniesResponse> =
        caller.call(BackendFunctions.listCompanies, request)

    suspend fun updateSettings(request: UpdatePlatformSettingsRequest): AppResult<UpdatePlatformSettingsResponse> =
        caller.call(BackendFunctions.updatePlatformSettings, request)

    /** Records money that arrived outside the app (bank transfer, cash, cheque). */
    suspend fun recordOfflinePayment(request: RecordOfflinePaymentRequest): AppResult<RecordOfflinePaymentResponse> =
        caller.call(BackendFunctions.recordOfflinePayment, request)

    /** Grants or extends a billing bypass. Long bypasses need the elevated claim. */
    suspend fun setBillingBypass(request: BillingBypassRequest): AppResult<BillingBypassResponse> =
        caller.call(BackendFunctions.setBillingBypass, request)

    suspend fun revokeBillingBypass(request: RevokeBypassRequest): AppResult<RevokeBypassResponse> =
        caller.call(BackendFunctions.revokeBillingBypass, request)

    suspend fun manageSubscription(request: ManageSubscriptionRequest): AppResult<ManageSubscriptionResponse> =
        caller.call(BackendFunctions.manageSubscription, request)
}

/** The platform overview callable takes no arguments; the backend still expects an object. */
@kotlinx.serialization.Serializable
internal object EmptyRequest
