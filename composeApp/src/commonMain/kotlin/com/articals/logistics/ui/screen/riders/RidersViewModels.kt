package com.articals.logistics.ui.screen.riders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.articals.logistics.core.AppError
import com.articals.logistics.core.AppResult
import com.articals.logistics.core.UiState
import com.articals.logistics.data.api.OrdersRepository
import com.articals.logistics.data.api.RidersRepository
import com.articals.logistics.data.dto.CreateRiderRequest
import com.articals.logistics.data.dto.OrderDto
import com.articals.logistics.data.dto.RiderDto
import com.articals.logistics.data.dto.RiderInvitationRequest
import com.articals.logistics.data.dto.RiderInvitationResponse
import com.articals.logistics.data.dto.UpdateRiderRequest
import com.articals.logistics.domain.RiderStatuses
import com.articals.logistics.ui.text.ArticalsStrings
import com.articals.logistics.util.Idempotency
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The rider being added or edited. `id == null` means a new one. */
data class RiderEditor(
    val id: String? = null,
    val name: String = "",
    val phone: String = "",
    val email: String = "",
    val vehicleType: String = "motorcycle",
    val vehiclePlate: String = "",
    val notes: String = "",
    val status: String = RiderStatuses.Active,
) {
    val nameValid: Boolean get() = name.trim().length >= 2

    val phoneValid: Boolean get() = phone.trim().length >= 7

    val valid: Boolean get() = nameValid && phoneValid

    val isNew: Boolean get() = id == null
}

data class RidersState(
    val query: String = "",
    val showInactive: Boolean = false,
    val riders: List<RiderDto> = emptyList(),
    val loading: Boolean = true,
    val error: AppError? = null,
    val notice: String? = null,
    val editor: RiderEditor? = null,
    val saving: Boolean = false,
    val editorError: AppError? = null,
    /** The rider an invitation link was just created for, with the link itself. */
    val invitation: RiderInvitationResponse? = null,
    val invitingFor: RiderDto? = null,
    val inviteEmail: String = "",
    val inviteError: AppError? = null,
) {
    val visible: List<RiderDto>
        get() {
            val needle = query.trim().lowercase()
            return riders
                .filter { (it.status != RiderStatuses.Active) == showInactive }
                .filter {
                    needle.isEmpty() ||
                        it.name.lowercase().contains(needle) ||
                        it.phone?.contains(needle) == true ||
                        it.email?.lowercase()?.contains(needle) == true
                }
        }

    val activeCount: Int get() = riders.count { it.status == RiderStatuses.Active }

    val inactiveCount: Int get() = riders.count { it.status != RiderStatuses.Active }

    val inviteEmailValid: Boolean get() = inviteEmail.trim().contains('@')

    val listState: UiState<List<RiderDto>>
        get() = when {
            loading -> UiState.Loading
            error != null && riders.isEmpty() -> UiState.Error(error)
            visible.isEmpty() -> UiState.Empty(
                title = if (query.isBlank()) {
                    ArticalsStrings.Settings.ridersEmpty
                } else {
                    ArticalsStrings.Riders.noMatches
                },
                message = if (query.isBlank()) {
                    ArticalsStrings.Settings.ridersEmptyBody
                } else {
                    ArticalsStrings.Riders.noMatchesBody
                },
                actionLabel = if (query.isBlank()) ArticalsStrings.Settings.addRider else null,
            )

            else -> UiState.Content(visible)
        }
}

/**
 * Riders: the people who carry the parcels.
 *
 * Two things make a rider real in this system: the record (their name, their phone,
 * their bike) and a login. The record is what a dispatcher assigns work to; the login
 * is what lets the rider see it. The screen keeps those two steps visibly separate —
 * "add a rider" never silently creates an account, and an invitation link is shown
 * with what it is: a link you send, not a message the app sent.
 */
class RidersViewModel(
    private val riders: RidersRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(RidersState())
    val state: StateFlow<RidersState> = mutableState.asStateFlow()

    private var tenantId: String? = null
    private var addKey: String = Idempotency.newKey("rider.create")

    fun load(tenantId: String) {
        if (this.tenantId == tenantId) return
        this.tenantId = tenantId
        mutableState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            riders.observeRiders(tenantId).collect { list ->
                mutableState.update { it.copy(riders = list, loading = false, error = null) }
            }
        }
    }

    fun setQuery(query: String) = mutableState.update { it.copy(query = query) }

    fun setShowInactive(show: Boolean) = mutableState.update { it.copy(showInactive = show) }

    fun dismissError() = mutableState.update { it.copy(error = null) }

    fun clearNotice() = mutableState.update { it.copy(notice = null) }

    // --- the editor ---------------------------------------------------------

    fun openEditor(rider: RiderDto? = null) = mutableState.update {
        it.copy(
            editor = if (rider == null) {
                RiderEditor()
            } else {
                RiderEditor(
                    id = rider.id,
                    name = rider.name,
                    phone = rider.phone.orEmpty(),
                    email = rider.email.orEmpty(),
                    vehicleType = rider.vehicleType.orEmpty().ifEmpty { "motorcycle" },
                    vehiclePlate = rider.vehiclePlate.orEmpty(),
                    notes = rider.notes.orEmpty(),
                    status = rider.status,
                )
            },
            editorError = null,
        )
    }

    fun updateEditor(editor: RiderEditor) = mutableState.update { it.copy(editor = editor) }

    fun closeEditor() = mutableState.update { it.copy(editor = null, editorError = null) }

    fun saveEditor() {
        val tenantId = tenantId ?: return
        val editor = mutableState.value.editor ?: return
        if (!editor.valid) {
            mutableState.update { it.copy(editorError = ArticalsStrings.Riders.incomplete()) }
            return
        }

        mutableState.update { it.copy(saving = true, editorError = null) }
        viewModelScope.launch {
            val result = if (editor.isNew) {
                riders.create(
                    CreateRiderRequest(
                        tenantId = tenantId,
                        idempotencyKey = addKey,
                        name = editor.name.trim(),
                        phone = editor.phone.trim(),
                        email = editor.email.trim().takeIf { it.isNotEmpty() },
                        vehicleType = editor.vehicleType,
                        internalNotes = editor.notes.trim().takeIf { it.isNotEmpty() },
                    ),
                )
            } else {
                riders.update(
                    UpdateRiderRequest(
                        tenantId = tenantId,
                        riderId = editor.id.orEmpty(),
                        name = editor.name.trim(),
                        phone = editor.phone.trim(),
                        email = editor.email.trim().takeIf { it.isNotEmpty() },
                        vehicleType = editor.vehicleType,
                        status = editor.status,
                        internalNotes = editor.notes.trim().takeIf { it.isNotEmpty() },
                    ),
                )
            }

            when (result) {
                is AppResult.Failure -> mutableState.update {
                    it.copy(saving = false, editorError = result.error)
                }

                is AppResult.Success -> {
                    if (editor.isNew) addKey = Idempotency.newKey("rider.create")
                    mutableState.update {
                        it.copy(
                            saving = false,
                            editor = null,
                            notice = if (editor.isNew) {
                                ArticalsStrings.Riders.added
                            } else {
                                ArticalsStrings.Riders.saved
                            },
                        )
                    }
                }
            }
        }
    }

    /** Quick status change from the list: suspend or reactivate without the editor. */
    fun setStatus(rider: RiderDto, status: String) {
        val tenantId = tenantId ?: return
        viewModelScope.launch {
            val request = UpdateRiderRequest(
                tenantId = tenantId,
                riderId = rider.id,
                status = status,
            )
            when (val result = riders.update(request)) {
                is AppResult.Failure -> mutableState.update { it.copy(error = result.error) }
                is AppResult.Success -> mutableState.update {
                    it.copy(notice = ArticalsStrings.Riders.statusChanged(rider.name))
                }
            }
        }
    }

    // --- login invitations --------------------------------------------------

    fun openInvite(rider: RiderDto) = mutableState.update {
        it.copy(
            invitingFor = rider,
            inviteEmail = rider.email.orEmpty(),
            inviteError = null,
            invitation = null,
        )
    }

    fun setInviteEmail(email: String) = mutableState.update { it.copy(inviteEmail = email) }

    fun closeInvite() = mutableState.update {
        it.copy(invitingFor = null, invitation = null, inviteEmail = "", inviteError = null)
    }

    /**
     * Creates the invitation link.
     *
     * The link is shown so it can be sent by whatever channel the company actually
     * uses. Nothing here claims a message was delivered — sending is the person's job.
     */
    fun sendInvitation() {
        val tenantId = tenantId ?: return
        val rider = mutableState.value.invitingFor ?: return
        val email = mutableState.value.inviteEmail.trim()
        if (!mutableState.value.inviteEmailValid) {
            mutableState.update { it.copy(inviteError = ArticalsStrings.Riders.inviteEmailInvalid()) }
            return
        }

        mutableState.update { it.copy(saving = true, inviteError = null) }
        viewModelScope.launch {
            val request = RiderInvitationRequest(tenantId = tenantId, riderId = rider.id, email = email)
            when (val result = riders.createInvitation(request)) {
                is AppResult.Failure -> mutableState.update {
                    it.copy(saving = false, inviteError = result.error)
                }

                is AppResult.Success -> mutableState.update {
                    it.copy(saving = false, invitation = result.value, notice = ArticalsStrings.Riders.inviteReady)
                }
            }
        }
    }
}

/** One rider, and the deliveries they have been given. */
data class RiderDetailState(
    val rider: RiderDto? = null,
    val orders: List<OrderDto> = emptyList(),
    val loading: Boolean = true,
    val error: AppError? = null,
) {
    val delivered: Int get() = orders.count { it.status == "delivered" }

    val failed: Int get() = orders.count { it.status == "failed" }

    val open: Int get() = orders.count { it.status in setOf("assigned", "picked_up", "in_transit") }

    val earnedMinor: Long get() = orders.mapNotNull { it.actualRiderCostMinor ?: it.estimatedRiderCostMinor }.sum()
}

/**
 * A rider's record and their deliveries.
 *
 * `deliveriesCompleted` and `deliveriesFailed` live on the rider document and are the
 * backend's running totals; the lists here are the live orders, so the two can differ
 * while a delivery is in flight. The screen shows both rather than picking a winner.
 */
class RiderDetailViewModel(
    private val riders: RidersRepository,
    private val orders: OrdersRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(RiderDetailState())
    val state: StateFlow<RiderDetailState> = mutableState.asStateFlow()

    private var riderId: String? = null

    fun load(tenantId: String, riderId: String) {
        if (this.riderId == riderId) return
        this.riderId = riderId
        viewModelScope.launch {
            riders.observeRiders(tenantId).collect { list ->
                mutableState.update {
                    it.copy(rider = list.firstOrNull { rider -> rider.id == riderId }, loading = false)
                }
            }
        }
        viewModelScope.launch {
            orders.observeRiderOrders(tenantId, riderId).collect { list ->
                mutableState.update { it.copy(orders = list, loading = false) }
            }
        }
    }
}
