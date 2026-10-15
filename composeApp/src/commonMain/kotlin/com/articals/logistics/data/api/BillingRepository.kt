package com.articals.logistics.data.api

import com.articals.logistics.core.AppResult
import com.articals.logistics.data.dto.PlanDto
import com.articals.logistics.data.dto.RegisterDeviceTokenResponse
import com.articals.logistics.data.dto.SubscriptionViewDto

/**
 * The company's own subscription, as its owners may see it.
 *
 * Read-only by design: the app never sells a subscription and never takes a payment
 * (ADR-009). Internal reasons, notes, evidence and ledger entries are not part of
 * this interface at all.
 */
interface BillingRepository {
    suspend fun mySubscription(tenantId: String): AppResult<SubscriptionViewDto>

    /** The plan catalogue, so a screen never hardcodes plan names or prices. */
    suspend fun plans(): AppResult<List<PlanDto>>

    suspend fun registerDeviceToken(token: String, platform: String, appVersion: String?): AppResult<RegisterDeviceTokenResponse>
}
