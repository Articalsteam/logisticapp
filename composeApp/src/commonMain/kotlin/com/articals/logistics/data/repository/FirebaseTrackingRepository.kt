package com.articals.logistics.data.repository

import com.articals.logistics.data.api.TrackingRepository
import com.articals.logistics.core.AppResult
import com.articals.logistics.data.BackendFunctions
import com.articals.logistics.data.FunctionsCaller
import com.articals.logistics.data.dto.TrackingRequest
import com.articals.logistics.data.dto.TrackingResultDto
import com.articals.logistics.data.dto.TrackingVerificationDto

/**
 * Public delivery tracking.
 *
 * The customer's view of a delivery is served by a backend projection that
 * physically contains only approved fields: no addresses, no rider identity, no
 * prices, no internal notes. Asking for a reference that does not exist, or that
 * belongs to another company, produces the same "not found" answer so the endpoint
 * cannot be used to probe for live deliveries.
 */
class FirebaseTrackingRepository(
    private val caller: FunctionsCaller,
) : TrackingRepository {
    /** [lastFourPhoneDigits] is only needed when the company switched verification on. */
    override suspend fun track(reference: String, lastFourPhoneDigits: String?): AppResult<TrackingResultDto> =
        caller.call(
            BackendFunctions.getPublicTracking,
            TrackingRequest(
                reference = reference.trim().uppercase(),
                verification = lastFourPhoneDigits
                    ?.filter { it.isDigit() }
                    ?.take(4)
                    ?.takeIf { it.length == 4 }
                    ?.let { TrackingVerificationDto(it) },
            ),
        )
}
