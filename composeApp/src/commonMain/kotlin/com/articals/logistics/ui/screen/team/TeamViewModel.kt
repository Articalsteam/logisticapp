package com.articals.logistics.ui.screen.team

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.articals.logistics.core.AppError
import com.articals.logistics.core.AppResult
import com.articals.logistics.core.Permissions
import com.articals.logistics.core.Roles
import com.articals.logistics.core.UiState
import com.articals.logistics.data.api.MembersRepository
import com.articals.logistics.data.dto.InviteMemberRequest
import com.articals.logistics.data.dto.MembershipDto
import com.articals.logistics.data.dto.SetMemberStatusRequest
import com.articals.logistics.data.dto.UpdateMemberRoleRequest
import com.articals.logistics.ui.text.ArticalsStrings
import com.articals.logistics.util.Idempotency
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How one permission is set for one person: their role's default, or forced on or off. */
enum class PermissionChoice { Default, Allow, Deny }

/** The invitation being composed. */
data class InviteDraft(
    val email: String = "",
    val displayName: String = "",
    val role: String = Roles.Dispatcher,
) {
    val emailValid: Boolean get() = email.trim().contains('@') && email.trim().length >= 5
}

/** The member editor: role, permission overrides, and why. */
data class MemberDraft(
    val userId: String,
    val name: String,
    val email: String?,
    val currentRole: String,
    val role: String,
    val overrides: Map<String, PermissionChoice> = emptyMap(),
    val reason: String = "",
) {
    val changed: Boolean
        get() = role != currentRole || overrides.any { it.value != PermissionChoice.Default }

    val reasonValid: Boolean get() = !changed || reason.trim().length >= 3
}

data class TeamState(
    val members: List<MembershipDto> = emptyList(),
    val loading: Boolean = true,
    val error: AppError? = null,
    val notice: String? = null,
    val saving: Boolean = false,
    val invite: InviteDraft? = null,
    val inviteLink: String? = null,
    val editor: MemberDraft? = null,
    val disabling: MembershipDto? = null,
    val disableReason: String = "",
) {
    val ownerCount: Int get() = members.count { it.role == Roles.Owner && it.status == "active" }

    val listState: UiState<List<MembershipDto>>
        get() = when {
            loading -> UiState.Loading
            error != null && members.isEmpty() -> UiState.Error(error)
            members.isEmpty() -> UiState.Empty(
                title = ArticalsStrings.Team.empty,
                message = ArticalsStrings.Team.emptyBody,
            )

            else -> UiState.Content(members)
        }
}

/**
 * Staff and what they may do.
 *
 * Two ideas, kept apart on purpose:
 *
 *  * a **role** is a starting point (owner, manager, dispatcher, rider);
 *  * a **permission override** is a deliberate exception to it, in either direction.
 *
 * The owner always keeps everything — revoking permissions from the owner would let a
 * workspace lock itself out — so the screen says that instead of offering the choice.
 * Changing anybody's access needs a reason, because it is the kind of change somebody
 * will ask about later.
 */
class TeamViewModel(
    private val members: MembersRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(TeamState())
    val state: StateFlow<TeamState> = mutableState.asStateFlow()

    private var tenantId: String? = null
    private var inviteKey: String = Idempotency.newKey("member.invite")

    fun load(tenantId: String) {
        if (this.tenantId == tenantId) return
        this.tenantId = tenantId
        mutableState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            members.observeMembers(tenantId).collect { list ->
                mutableState.update { it.copy(members = list, loading = false, error = null) }
            }
        }
    }

    fun dismissError() = mutableState.update { it.copy(error = null) }

    fun clearNotice() = mutableState.update { it.copy(notice = null) }

    // --- inviting -----------------------------------------------------------

    fun openInvite() = mutableState.update {
        it.copy(invite = InviteDraft(), inviteLink = null)
    }

    fun updateInvite(draft: InviteDraft) = mutableState.update { it.copy(invite = draft) }

    fun closeInvite() = mutableState.update { it.copy(invite = null, inviteLink = null) }

    fun sendInvite() {
        val tenantId = tenantId ?: return
        val draft = mutableState.value.invite ?: return
        if (!draft.emailValid) {
            mutableState.update { it.copy(error = ArticalsStrings.Team.inviteEmailInvalid()) }
            return
        }

        mutableState.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val request = InviteMemberRequest(
                tenantId = tenantId,
                idempotencyKey = inviteKey,
                email = draft.email.trim(),
                role = draft.role,
                displayName = draft.displayName.trim().takeIf { it.isNotEmpty() },
            )
            when (val result = members.invite(request)) {
                is AppResult.Failure -> mutableState.update { it.copy(saving = false, error = result.error) }
                is AppResult.Success -> {
                    inviteKey = Idempotency.newKey("member.invite")
                    mutableState.update {
                        it.copy(
                            saving = false,
                            invite = null,
                            notice = ArticalsStrings.Team.inviteSent(result.value.email, result.value.role),
                        )
                    }
                }
            }
        }
    }

    // --- editing a member ---------------------------------------------------

    fun openEditor(member: MembershipDto) = mutableState.update {
        it.copy(
            editor = MemberDraft(
                userId = member.userId,
                name = member.displayName ?: member.email ?: member.userId,
                email = member.email,
                currentRole = member.role,
                role = member.role,
                overrides = overrideMapOf(member),
            ),
        )
    }

    fun updateEditor(draft: MemberDraft) = mutableState.update { it.copy(editor = draft) }

    fun closeEditor() = mutableState.update { it.copy(editor = null) }

    fun saveEditor() {
        val tenantId = tenantId ?: return
        val draft = mutableState.value.editor ?: return
        if (!draft.reasonValid) {
            mutableState.update { it.copy(error = ArticalsStrings.Team.reasonRequired()) }
            return
        }

        mutableState.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val request = UpdateMemberRoleRequest(
                tenantId = tenantId,
                userId = draft.userId,
                role = draft.role,
                grantedPermissions = draft.overrides
                    .filterValues { it == PermissionChoice.Allow }
                    .keys
                    .toList(),
                revokedPermissions = draft.overrides
                    .filterValues { it == PermissionChoice.Deny }
                    .keys
                    .toList(),
                reason = draft.reason.trim().takeIf { it.isNotEmpty() },
            )
            when (val result = members.updateRole(request)) {
                is AppResult.Failure -> mutableState.update { it.copy(saving = false, error = result.error) }
                is AppResult.Success -> mutableState.update {
                    it.copy(
                        saving = false,
                        editor = null,
                        notice = ArticalsStrings.Team.memberSaved(result.value.role),
                    )
                }
            }
        }
    }

    private fun overrideMapOf(member: MembershipDto): Map<String, PermissionChoice> {
        val map = mutableMapOf<String, PermissionChoice>()
        member.grantedPermissions.forEach { map[it] = PermissionChoice.Allow }
        member.revokedPermissions.forEach { map[it] = PermissionChoice.Deny }
        return map
    }

    // --- disabling ----------------------------------------------------------

    fun askDisable(member: MembershipDto) = mutableState.update {
        it.copy(disabling = member, disableReason = "")
    }

    fun setDisableReason(reason: String) = mutableState.update { it.copy(disableReason = reason) }

    fun cancelDisable() = mutableState.update { it.copy(disabling = null, disableReason = "") }

    fun confirmDisable() {
        val tenantId = tenantId ?: return
        val member = mutableState.value.disabling ?: return
        val reason = mutableState.value.disableReason.trim()
        if (reason.length < 3) {
            mutableState.update { it.copy(error = ArticalsStrings.Team.reasonRequired()) }
            return
        }
        if (member.role == Roles.Owner && mutableState.value.ownerCount <= 1) {
            mutableState.update { it.copy(error = ArticalsStrings.Team.lastOwner()) }
            return
        }

        mutableState.update { it.copy(saving = true, disabling = null) }
        viewModelScope.launch {
            val request = SetMemberStatusRequest(
                tenantId = tenantId,
                userId = member.userId,
                status = "disabled",
                reason = reason,
            )
            when (val result = members.setStatus(request)) {
                is AppResult.Failure -> mutableState.update { it.copy(saving = false, error = result.error) }
                is AppResult.Success -> mutableState.update {
                    it.copy(saving = false, notice = ArticalsStrings.Team.memberDisabled, disableReason = "")
                }
            }
        }
    }

    /** Turns a disabled member back on. Reactivating is not a demotion, so no reason. */
    fun enable(member: MembershipDto) {
        val tenantId = tenantId ?: return
        viewModelScope.launch {
            val request = SetMemberStatusRequest(tenantId = tenantId, userId = member.userId, status = "active")
            when (val result = members.setStatus(request)) {
                is AppResult.Failure -> mutableState.update { it.copy(error = result.error) }
                is AppResult.Success -> mutableState.update { it.copy(notice = ArticalsStrings.Team.memberEnabled) }
            }
        }
    }

    /** The permission catalogue, grouped the way the backend groups it. */
    fun permissionGroups(): List<Pair<String, List<String>>> = Permissions.groups()
}
