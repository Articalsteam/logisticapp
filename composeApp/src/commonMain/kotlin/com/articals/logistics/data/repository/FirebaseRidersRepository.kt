package com.articals.logistics.data.repository

import com.articals.logistics.data.api.RidersRepository
import com.articals.logistics.core.AppResult
import com.articals.logistics.data.BackendFunctions
import com.articals.logistics.data.FirestorePaths
import com.articals.logistics.data.FunctionsCaller
import com.articals.logistics.data.dto.AcceptRiderInvitationRequest
import com.articals.logistics.data.dto.AcceptRiderInvitationResponse
import com.articals.logistics.data.dto.AckDto
import com.articals.logistics.data.dto.CreateRiderRequest
import com.articals.logistics.data.dto.CreateRiderResponse
import com.articals.logistics.data.dto.RiderDto
import com.articals.logistics.data.dto.RiderInvitationRequest
import com.articals.logistics.data.dto.RiderInvitationResponse
import com.articals.logistics.data.dto.UpdateRiderRequest
import com.articals.logistics.data.observeDocuments
import dev.gitlive.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Riders.
 *
 * A rider record exists before the person has an account. Linking the two is done
 * with a single-use invitation that the rider accepts while signed in, which is
 * what gives their account the right to see their own deliveries and nothing else.
 */
class FirebaseRidersRepository(
    private val firestore: FirebaseFirestore,
    private val caller: FunctionsCaller,
) : RidersRepository {
    override fun observeRiders(tenantId: String, limit: Int): Flow<List<RiderDto>> =
        firestore.collection(FirestorePaths.riders)
            .where { "tenantId" equalTo tenantId }
            .limit(limit)
            .observeDocuments { id, rider -> rider.copy(id = id) }
            // Ordered here rather than in the query on purpose: an equality filter needs
            // no composite index, and client-side ordering keeps every screen working on
            // a fresh project before firestore.indexes.json has finished building.
            .map { riders -> riders.sortedBy { it.name.lowercase() } }

    override fun observeActiveRiders(tenantId: String, limit: Int): Flow<List<RiderDto>> =
        firestore.collection(FirestorePaths.riders)
            .where {
                all(
                    "tenantId" equalTo tenantId,
                    "status" equalTo "active",
                )
            }
            .limit(limit)
            .observeDocuments { id, rider -> rider.copy(id = id) }
            // Ordered here rather than in the query on purpose: an equality filter needs
            // no composite index, and client-side ordering keeps every screen working on
            // a fresh project before firestore.indexes.json has finished building.
            .map { riders -> riders.sortedBy { it.name.lowercase() } }

    override suspend fun create(request: CreateRiderRequest): AppResult<CreateRiderResponse> =
        caller.call(BackendFunctions.createRider, request)

    override suspend fun update(request: UpdateRiderRequest): AppResult<AckDto> =
        caller.call(BackendFunctions.updateRider, request)

    /**
     * Creates a single-use invitation link for a rider.
     *
     * The returned token is shown once and shared by the dispatcher; the backend
     * stores only its hash, so a leaked database cannot be used to join a workspace.
     */
    override suspend fun createInvitation(request: RiderInvitationRequest): AppResult<RiderInvitationResponse> =
        caller.call(BackendFunctions.createRiderInvitation, request)

    override suspend fun acceptInvitation(token: String): AppResult<AcceptRiderInvitationResponse> =
        caller.call(BackendFunctions.acceptRiderInvitation, AcceptRiderInvitationRequest(token))
}
