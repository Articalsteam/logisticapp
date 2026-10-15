package com.articals.logistics.data.api

import com.articals.logistics.core.AppResult
import com.articals.logistics.data.dto.AcceptInvitationResponse
import com.articals.logistics.data.dto.InviteMemberRequest
import com.articals.logistics.data.dto.InviteMemberResponse
import com.articals.logistics.data.dto.MembershipDto
import com.articals.logistics.data.dto.SetMemberStatusRequest
import com.articals.logistics.data.dto.SetMemberStatusResponse
import com.articals.logistics.data.dto.UpdateMemberRoleRequest
import com.articals.logistics.data.dto.UpdateMemberRoleResponse
import kotlinx.coroutines.flow.Flow

/**
 * Staff membership.
 *
 * Role changes are always requested from the authority (backend or demo server),
 * which enforces "only an owner may change a role" and "nobody changes their own".
 */
interface MembersRepository {
    fun observeMembers(tenantId: String): Flow<List<MembershipDto>>

    suspend fun invite(request: InviteMemberRequest): AppResult<InviteMemberResponse>

    suspend fun acceptInvitation(invitationId: String, displayName: String?): AppResult<AcceptInvitationResponse>

    suspend fun updateRole(request: UpdateMemberRoleRequest): AppResult<UpdateMemberRoleResponse>

    suspend fun setStatus(request: SetMemberStatusRequest): AppResult<SetMemberStatusResponse>
}
