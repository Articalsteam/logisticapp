package com.articals.logistics.data.repository

import com.articals.logistics.core.AppResult
import com.articals.logistics.data.BackendFunctions
import com.articals.logistics.data.FirestorePaths
import com.articals.logistics.data.FunctionsCaller
import com.articals.logistics.data.dto.AcceptInvitationRequest
import com.articals.logistics.data.dto.AcceptInvitationResponse
import com.articals.logistics.data.dto.InviteMemberRequest
import com.articals.logistics.data.dto.InviteMemberResponse
import com.articals.logistics.data.dto.MembershipDto
import com.articals.logistics.data.dto.SetMemberStatusRequest
import com.articals.logistics.data.dto.SetMemberStatusResponse
import com.articals.logistics.data.dto.UpdateMemberRoleRequest
import com.articals.logistics.data.dto.UpdateMemberRoleResponse
import com.articals.logistics.data.observeDocuments
import dev.gitlive.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.Flow

/**
 * Staff membership.
 *
 * Role and permission changes are backend operations: the client can request them
 * but never performs them, so a modified app cannot promote itself. Owners are the
 * only role that can change another member's role, and no one can change their own.
 */
class MembersRepository(
    private val firestore: FirebaseFirestore,
    private val caller: FunctionsCaller,
) {
    fun observeMembers(tenantId: String): Flow<List<MembershipDto>> =
        firestore.collection(FirestorePaths.memberships)
            .where { "tenantId" equalTo tenantId }
            .observeDocuments { id, membership -> membership.copy(id = id) }

    suspend fun invite(request: InviteMemberRequest): AppResult<InviteMemberResponse> =
        caller.call(BackendFunctions.inviteCompanyMember, request)

    /** Accepts an invitation for the signed-in user; matched on their verified email. */
    suspend fun acceptInvitation(invitationId: String, displayName: String?): AppResult<AcceptInvitationResponse> =
        caller.call(BackendFunctions.acceptInvitation, AcceptInvitationRequest(invitationId, displayName))

    suspend fun updateRole(request: UpdateMemberRoleRequest): AppResult<UpdateMemberRoleResponse> =
        caller.call(BackendFunctions.updateMemberRole, request)

    suspend fun setStatus(request: SetMemberStatusRequest): AppResult<SetMemberStatusResponse> =
        caller.call(BackendFunctions.setMemberStatus, request)
}
