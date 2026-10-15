package com.articals.logistics.data.repository

import com.articals.logistics.core.AppResult
import com.articals.logistics.data.BackendFunctions
import com.articals.logistics.data.FunctionsCaller
import com.articals.logistics.data.dto.RegisterDeviceTokenRequest
import com.articals.logistics.data.dto.RegisterDeviceTokenResponse
import com.articals.logistics.data.dto.SubscriptionRequest
import com.articals.logistics.data.dto.SubscriptionViewDto

/**
 * The company's own subscription as its owners may see it: state, dates, plan and
 * a plain-language billing notice.
 *
 * Internal reasons, notes, evidence and ledger entries are not part of this
 * response at all — they are readable only by platform administrators, so a
 * compromised owner account cannot read Articals Studio's internal records.
 */
class BillingRepository(
    private val caller: FunctionsCaller,
) {
    suspend fun mySubscription(tenantId: String): AppResult<SubscriptionViewDto> =
        caller.call(BackendFunctions.getMySubscription, SubscriptionRequest(tenantId))

    /**
     * Registers this device for operational notifications.
     *
     * Failures are reported honestly (the backend marks them rather than pretending)
     * and never block the user: notification permission is optional.
     */
    suspend fun registerDeviceToken(token: String, platform: String, appVersion: String?): AppResult<RegisterDeviceTokenResponse> =
        caller.call(
            BackendFunctions.registerDeviceToken,
            RegisterDeviceTokenRequest(token = token, platform = platform, appVersion = appVersion),
        )
}
