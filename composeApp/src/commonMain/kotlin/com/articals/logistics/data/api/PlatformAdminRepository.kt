package com.articals.logistics.data.api

import com.articals.logistics.core.AppResult
import com.articals.logistics.data.dto.AuditEntryDto
import com.articals.logistics.data.dto.BillingBypassRequest
import com.articals.logistics.data.dto.BillingBypassResponse
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
 * Every operation here is refused unless the caller carries the platform
 * administrator claim, which the client cannot grant itself. Offline payments are
 * *recorded*, never collected: there is no payment provider anywhere in this path.
 *
 * [auditLog] and [ledger] read records that are deliberately not client-readable in
 * Firebase mode (the security rules keep them server-only). The Firebase
 * implementation therefore answers with an honest "not available in this build"
 * error instead of pretending, and demo mode shows its own records so the screens
 * can be verified. See ADR-014.
 */
interface PlatformAdminRepository {
    suspend fun overview(): AppResult<PlatformOverviewDto>

    suspend fun listCompanies(request: ListCompaniesRequest): AppResult<ListCompaniesResponse>

    suspend fun updateSettings(request: UpdatePlatformSettingsRequest): AppResult<UpdatePlatformSettingsResponse>

    suspend fun recordOfflinePayment(request: RecordOfflinePaymentRequest): AppResult<RecordOfflinePaymentResponse>

    suspend fun setBillingBypass(request: BillingBypassRequest): AppResult<BillingBypassResponse>

    suspend fun revokeBillingBypass(request: RevokeBypassRequest): AppResult<RevokeBypassResponse>

    suspend fun manageSubscription(request: ManageSubscriptionRequest): AppResult<ManageSubscriptionResponse>

    /** Newest-first entries for the audit log screen. */
    suspend fun auditLog(tenantId: String?, limit: Int = 100): AppResult<List<AuditEntryDto>>

    /** Newest-first billing ledger entries for one company. */
    suspend fun ledger(tenantId: String, limit: Int = 50): AppResult<List<LedgerEntryDto>>
}
