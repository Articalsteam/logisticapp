package com.articals.logistics.data.repository

import com.articals.logistics.data.api.BillingRepository
import com.articals.logistics.core.AppResult
import com.articals.logistics.core.appRunCatching
import com.articals.logistics.data.BackendFunctions
import dev.gitlive.firebase.firestore.FirebaseFirestore
import com.articals.logistics.data.FunctionsCaller
import com.articals.logistics.data.readDocument
import com.articals.logistics.data.dto.RegisterDeviceTokenRequest
import com.articals.logistics.data.dto.PlanDto
import com.articals.logistics.data.dto.PlatformPlansDoc
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
class FirebaseBillingRepository(
    private val firestore: FirebaseFirestore,
    private val caller: FunctionsCaller,
) : BillingRepository {
    override suspend fun mySubscription(tenantId: String): AppResult<SubscriptionViewDto> =
        caller.call(BackendFunctions.getMySubscription, SubscriptionRequest(tenantId))

    /**
     * The plan catalogue.
     *
     * The rules let any signed-in user read `platformConfig/plans`, so this is a
     * direct read: a plan name or price is never hardcoded in a screen.
     */
    override suspend fun plans(): AppResult<List<PlanDto>> = appRunCatching {
        val document: PlatformPlansDoc? = firestore.readDocument(FirestorePaths.platformPlans)
        document?.plans.orEmpty()
    }

    /**
     * Registers this device for operational notifications.
     *
     * Failures are reported honestly (the backend marks them rather than pretending)
     * and never block the user: notification permission is optional.
     */
    override suspend fun registerDeviceToken(token: String, platform: String, appVersion: String?): AppResult<RegisterDeviceTokenResponse> =
        caller.call(
            BackendFunctions.registerDeviceToken,
            RegisterDeviceTokenRequest(token = token, platform = platform, appVersion = appVersion),
        )
}
