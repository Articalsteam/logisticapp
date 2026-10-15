package com.articals.logistics.data.repository

import com.articals.logistics.data.api.SessionRepository
import com.articals.logistics.core.AppResult
import com.articals.logistics.core.AuthUser
import com.articals.logistics.core.SessionClaims
import com.articals.logistics.core.appRunCatching
import com.articals.logistics.data.FirestorePaths
import com.articals.logistics.data.dto.MembershipDto
import com.articals.logistics.data.observeDocuments
import com.russhwolf.settings.Settings
import dev.gitlive.firebase.auth.FirebaseAuth
import dev.gitlive.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Sign-in, the signed-in user's memberships, and which workspace is on screen.
 *
 * A user may belong to more than one company; the chosen workspace is remembered
 * locally and used by every other repository. Membership documents are readable by
 * their own user under the security rules, so this needs no backend call.
 */
class FirebaseSessionRepository(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val preferences: Settings,
) : SessionRepository {
    /** Emits the signed-in user, or null after sign-out. */
    override fun observeAuthUser(): Flow<AuthUser?> = auth.authStateChanged.map { user ->
        user?.let {
            AuthUser(
                uid = it.uid,
                email = it.email,
                displayName = it.displayName,
                phoneNumber = it.phoneNumber,
                isEmailVerified = it.isEmailVerified,
            )
        }
    }

    override fun currentAuthUser(): AuthUser? = auth.currentUser?.let {
        AuthUser(
            uid = it.uid,
            email = it.email,
            displayName = it.displayName,
            phoneNumber = it.phoneNumber,
            isEmailVerified = it.isEmailVerified,
        )
    }

    /** Live memberships for [userId]; disabled memberships are still delivered so the UI can explain. */
    override fun observeMemberships(userId: String): Flow<List<MembershipDto>> =
        firestore.collection(FirestorePaths.memberships)
            .where { "userId" equalTo userId }
            .observeDocuments { id, membership -> membership.copy(id = id) }

    /** Membership in the workspace currently on screen, if the user is still a member. */
    override fun observeActiveMembership(userId: String): Flow<MembershipDto?> =
        observeMemberships(userId).map { memberships ->
            val activeTenantId = activeTenantId() ?: return@map null
            memberships.firstOrNull { it.tenantId == activeTenantId && it.status == "active" }
        }

    override fun activeTenantId(): String? = preferences.getStringOrNull(KEY_ACTIVE_TENANT)

    override fun setActiveTenant(tenantId: String) {
        preferences.putString(KEY_ACTIVE_TENANT, tenantId)
    }

    override fun clearActiveTenant() {
        preferences.remove(KEY_ACTIVE_TENANT)
    }

    /**
     * Reads the custom claims that mark a platform administrator.
     *
     * Claims can only be trusted after a refresh, which is why this is a call and
     * not a property: the console re-checks them when it opens.
     */
    override suspend fun refreshClaims(): AppResult<SessionClaims> = appRunCatching {
        val user = auth.currentUser ?: return@appRunCatching SessionClaims()
        val token = user.getIdTokenResult(forceRefresh = true)
        SessionClaims(
            isPlatformAdmin = token.claims["platformAdmin"] == true,
            isPlatformAdminElevated = token.claims["platformAdminElevated"] == true,
        )
    }

    override suspend fun signIn(email: String, password: String): AppResult<AuthUser> = appRunCatching {
        val result = auth.signInWithEmailAndPassword(email.trim(), password)
        result.user?.let {
            AuthUser(it.uid, it.email, it.displayName, it.phoneNumber, it.isEmailVerified)
        } ?: throw IllegalStateException("signInWithEmailAndPassword returned no user")
    }

    /** Creates the account. Company membership is created afterwards by `createCompany`. */
    override suspend fun register(email: String, password: String, displayName: String): AppResult<AuthUser> = appRunCatching {
        val result = auth.createUserWithEmailAndPassword(email.trim(), password)
        val user = result.user ?: throw IllegalStateException("createUserWithEmailAndPassword returned no user")
        if (displayName.isNotBlank()) {
            user.updateProfile(displayName = displayName.trim())
        }
        user.sendEmailVerification()
        AuthUser(user.uid, user.email, user.displayName ?: displayName, user.phoneNumber, user.isEmailVerified)
    }

    override suspend fun sendPasswordReset(email: String): AppResult<Unit> = appRunCatching {
        auth.sendPasswordResetEmail(email.trim())
    }

    override suspend fun signOut(): AppResult<Unit> = appRunCatching {
        auth.signOut()
        clearActiveTenant()
    }

    override suspend fun reloadUser(): AppResult<Unit> = appRunCatching {
        auth.currentUser?.reload()
    }

    private companion object {
        const val KEY_ACTIVE_TENANT = "active_tenant_id"
    }
}
