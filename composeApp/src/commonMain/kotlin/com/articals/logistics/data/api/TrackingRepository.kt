package com.articals.logistics.data.api

import com.articals.logistics.core.AppResult
import com.articals.logistics.data.dto.TrackingResultDto

/**
 * Public delivery tracking — the customer's view, and the same view the in-app
 * tracking screen shows.
 *
 * Both modes answer identically for a reference that does not exist and one that
 * belongs to somebody else.
 */
interface TrackingRepository {
    suspend fun track(reference: String, lastFourPhoneDigits: String? = null): AppResult<TrackingResultDto>
}
