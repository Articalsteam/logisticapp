package com.articals.logistics.data.api

import com.articals.logistics.core.AppResult
import com.articals.logistics.core.AuthUser
import com.articals.logistics.core.SessionClaims
import com.articals.logistics.data.dto.MembershipDto
import kotlinx.coroutines.flow.Flow

/**
 * Sign-in, the signed-in user's memberships and the workspace on screen.
 *
 * Implemented twice: against Firebase Auth + Firestore, and in memory for demo
 * mode. Nothing in the UI knows which one it is talking to.
 */
interface SessionRepository {
    fun observeAuthUser(): Flow<AuthUser?>
    fun currentAuthUser(): AuthUser?

    /** Live memberships for [userId]; disabled memberships are delivered too. */
    fun observeMemberships(userId: String): Flow<List<MembershipDto>>

    /** Membership in the workspace currently selected, when it is still active. */
    fun observeActiveMembership(userId: String): Flow<MembershipDto?>

    fun activeTenantId(): String?
    fun setActiveTenant(tenantId: String)
    fun clearActiveTenant()

    /** Re-reads custom claims (platform administrator, elevated). */
    suspend fun refreshClaims(): AppResult<SessionClaims>

    suspend fun signIn(email: String, password: String): AppResult<AuthUser>
    suspend fun register(email: String, password: String, displayName: String): AppResult<AuthUser>
    suspend fun sendPasswordReset(email: String): AppResult<Unit>
    suspend fun reloadUser(): AppResult<Unit>
    suspend fun signOut(): AppResult<Unit>
}
