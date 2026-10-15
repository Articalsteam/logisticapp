package com.articals.logistics.data.api

import com.articals.logistics.core.AppResult
import com.articals.logistics.data.dto.AcceptRiderInvitationResponse
import com.articals.logistics.data.dto.AckDto
import com.articals.logistics.data.dto.CreateRiderRequest
import com.articals.logistics.data.dto.CreateRiderResponse
import com.articals.logistics.data.dto.RiderDto
import com.articals.logistics.data.dto.RiderInvitationRequest
import com.articals.logistics.data.dto.RiderInvitationResponse
import com.articals.logistics.data.dto.UpdateRiderRequest
import kotlinx.coroutines.flow.Flow

/**
 * Riders.
 *
 * A rider record exists before the person has an account; accepting an invitation
 * links the two, which is what gives their account the right to see their own
 * deliveries and nothing else.
 */
interface RidersRepository {
    fun observeRiders(tenantId: String, limit: Int = 200): Flow<List<RiderDto>>

    fun observeActiveRiders(tenantId: String, limit: Int = 200): Flow<List<RiderDto>>

    suspend fun create(request: CreateRiderRequest): AppResult<CreateRiderResponse>

    suspend fun update(request: UpdateRiderRequest): AppResult<AckDto>

    suspend fun createInvitation(request: RiderInvitationRequest): AppResult<RiderInvitationResponse>

    suspend fun acceptInvitation(token: String): AppResult<AcceptRiderInvitationResponse>
}
