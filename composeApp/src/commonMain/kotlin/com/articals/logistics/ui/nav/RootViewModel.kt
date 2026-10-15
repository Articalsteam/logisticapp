package com.articals.logistics.ui.nav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.articals.logistics.core.AppResult
import com.articals.logistics.core.AuthUser
import com.articals.logistics.core.Permissions
import com.articals.logistics.core.SessionClaims
import com.articals.logistics.data.api.BillingRepository
import com.articals.logistics.data.api.CompanyRepository
import com.articals.logistics.data.api.SessionRepository
import com.articals.logistics.data.dto.CompanyDto
import com.articals.logistics.data.dto.MembershipDto
import com.articals.logistics.di.SupportLocator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Where the user is in the journey from "app opened" to "working". */
enum class SessionPhase {
    /** Still finding out; show the splash, never the sign-in form. */
    Loading,

    /** Nobody signed in. */
    SignedOut,

    /** Signed in, but the email address has not been verified yet. */
    NeedsVerification,

    /** Signed in, verified, and not part of any company yet. */
    NoCompany,

    /** Signed in with a company, but its onboarding wizard is unfinished. */
    Onboarding,

    /** Working. */
    Ready,
}

/**
 * Everything a screen needs to know about who is signed in and where they work.
 *
 * This is deliberately one view model at the root of the app rather than a
 * "current user" singleton: the workspace, the role and the effective permissions all
 * come from the same place, and a screen that needs them asks for this object.
 */
data class RootState(
    val phase: SessionPhase = SessionPhase.Loading,
    val user: AuthUser? = null,
    val memberships: List<MembershipDto> = emptyList(),
    val activeTenantId: String? = null,
    val activeMembership: MembershipDto? = null,
    val company: CompanyDto? = null,
    val entitlement: String? = null,
    val claims: SessionClaims = SessionClaims(),
) {
    val role: String? get() = activeMembership?.role

    /**
     * True when the signed-in account carries the platform-admin claim.
     *
     * It comes from the token, never from a membership document: a company owner
     * cannot promote themselves, and only Articals Studio's allow-list can grant it.
     */
    val isPlatformAdmin: Boolean get() = claims.isPlatformAdmin

    /** True when this person may administer the platform. */
    val canOpenConsole: Boolean get() = isPlatformAdmin && SupportLocator.config().adminFlavor

    val companyName: String? get() = company?.name

    val currency: String get() = company?.currency ?: com.articals.logistics.core.DEFAULT_CURRENCY

    /**
     * The company's time zone.
     *
     * Business dates are decided in the company's zone, never the phone's: a delivery
     * completed at 00:30 in Lagos belongs to the day the office thinks it does, and a
     * manager flying to London must not see the figures move because of it.
     */
    val zoneId: String get() = company?.timezone ?: com.articals.logistics.core.DEFAULT_TIME_ZONE

    /** Effective permissions for the active membership, role defaults plus overrides. */
    val permissions: Set<String>
        get() = activeMembership?.let {
            Permissions.effective(it.role, it.grantedPermissions, it.revokedPermissions)
        } ?: emptySet()

    fun can(permission: String): Boolean = permission in permissions

    /** A person may belong to several companies; this is the one they are working in. */
    val otherMemberships: List<MembershipDto>
        get() = memberships.filter { it.tenantId != activeTenantId }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RootViewModel(
    private val session: SessionRepository,
    private val companies: CompanyRepository,
    private val billing: BillingRepository,
) : ViewModel() {

    private val claims = MutableStateFlow(SessionClaims())

    /**
     * A real device restores its session asynchronously, so the first emission of
     * "nobody is signed in" cannot be trusted for a moment or two. Showing the
     * sign-in form at launch and then jumping to the dashboard would look like a bug;
     * the splash covers exactly that window. Demo mode has nothing to restore, so it
     * does not wait at all.
     */
    private val settleMillis: Long = if (SupportLocator.config().mode.isDemo) 0L else AUTH_SETTLE_MILLIS
    private val settled = MutableStateFlow(settleMillis == 0L)

    private val authUser = session.observeAuthUser()

    private val memberships = authUser.flatMapLatest { user ->
        if (user == null) flow<List<MembershipDto>> { emit(emptyList()) } else session.observeMemberships(user.uid)
    }

    private val activeMembership = authUser.flatMapLatest { user ->
        if (user == null) flow<MembershipDto?> { emit(null) } else session.observeActiveMembership(user.uid)
    }

    private val activeTenantId: StateFlow<String?> =
        combine(memberships, activeMembership) { all, active ->
            // If the stored workspace is gone (removed, or never chosen), fall back to
            // the first membership rather than leaving the user stranded on an empty app.
            active?.tenantId ?: all.firstOrNull()?.tenantId
        }.stateIn(viewModelScope, SharingStarted.Eagerly, session.activeTenantId())

    private val company = activeTenantId.flatMapLatest { tenantId ->
        if (tenantId == null) flow<CompanyDto?> { emit(null) } else companies.observeCompany(tenantId)
    }

    private val entitlement = activeTenantId.flatMapLatest { tenantId ->
        if (tenantId == null) {
            flow<String?> { emit(null) }
        } else {
            flow<String?> {
                val result = billing.mySubscription(tenantId)
                emit((result as? AppResult.Success)?.value?.entitlement)
            }
        }
    }

    val state: StateFlow<RootState> = combine(
        combine(authUser, settled) { user, isSettled -> user to isSettled },
        memberships,
        combine(activeTenantId, activeMembership) { tenantId, membership -> tenantId to membership },
        combine(company, entitlement) { loaded, entitlementState -> loaded to entitlementState },
        claims,
    ) { (user, isSettled), allMemberships, (tenantId, membership), (loadedCompany, entitlementState), currentClaims ->
        RootState(
            phase = phaseOf(
                user = user,
                settled = isSettled,
                memberships = allMemberships,
                membership = membership,
                company = loadedCompany,
            ),
            user = user,
            memberships = allMemberships,
            activeTenantId = tenantId,
            activeMembership = membership,
            company = loadedCompany,
            entitlement = entitlementState,
            claims = currentClaims,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RootState())

    init {
        viewModelScope.launch {
            if (settleMillis > 0) {
                delay(settleMillis)
                settled.value = true
            }
        }
        viewModelScope.launch {
            session.refreshClaims().let { result ->
                if (result is AppResult.Success) claims.value = result.value
            }
        }
        // Keep the stored workspace in step with what is actually available.
        viewModelScope.launch {
            combine(memberships, activeMembership) { all, active -> all to active }
                .collect { (all, active) ->
                    val current = session.activeTenantId()
                    if (active != null && current != active.tenantId) {
                        session.setActiveTenant(active.tenantId)
                    } else if (active == null && all.isNotEmpty()) {
                        session.setActiveTenant(all.first().tenantId)
                    }
                }
        }
    }

    /** Switches the workspace this device is working in. */
    fun selectWorkspace(tenantId: String) {
        session.setActiveTenant(tenantId)
    }

    fun signOut(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            session.signOut()
            claims.value = SessionClaims()
            onDone()
        }
    }

    /** Called after the verification check, so the wizard can appear without a restart. */
    fun refresh() {
        viewModelScope.launch {
            session.reloadUser()
            session.refreshClaims().let { result ->
                if (result is AppResult.Success) claims.value = result.value
            }
        }
    }

    private fun phaseOf(
        user: AuthUser?,
        settled: Boolean,
        memberships: List<MembershipDto>,
        membership: MembershipDto?,
        company: CompanyDto?,
    ): SessionPhase = when {
        user == null -> if (settled) SessionPhase.SignedOut else SessionPhase.Loading
        !user.isEmailVerified -> SessionPhase.NeedsVerification
        memberships.none { it.status == "active" } -> SessionPhase.NoCompany
        membership == null -> SessionPhase.Loading
        // A company that has not finished onboarding shows its wizard once, before the
        // dashboard: the wizard is what creates the first rate card and invites the team.
        company != null && company.onboarding?.isComplete != true -> SessionPhase.Onboarding
        else -> SessionPhase.Ready
    }

    private companion object {
        const val AUTH_SETTLE_MILLIS = 700L
    }
}
