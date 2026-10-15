package com.articals.logistics.demo

import com.articals.logistics.core.AppError
import com.articals.logistics.core.AppResult
import com.articals.logistics.core.AuthUser
import com.articals.logistics.core.Permissions
import com.articals.logistics.core.SessionClaims
import com.articals.logistics.data.api.SessionRepository
import com.articals.logistics.data.dto.MembershipDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Sign-in against the demo store.
 *
 * The behaviour mirrors Firebase Auth closely enough to verify the screens:
 * wrong credentials fail with a message that names the demo password (a real
 * deployment never could), registration leaves the address unverified, and
 * [reloadUser] plays the part of clicking the verification link.
 */
class DemoSessionRepository(
    private val store: DemoStore,
) : SessionRepository {

    override fun observeAuthUser(): Flow<AuthUser?> =
        combine(store.sessionUid, store.accounts) { uid, accounts ->
            uid?.let { id -> accounts.firstOrNull { it.uid == id } }?.let(store::authUser)
        }

    override fun currentAuthUser(): AuthUser? = store.currentAuthUser()

    override fun observeMemberships(userId: String): Flow<List<MembershipDto>> =
        store.memberships.map { memberships -> memberships.filter { it.userId == userId } }

    override fun observeActiveMembership(userId: String): Flow<MembershipDto?> =
        combine(store.memberships, store.activeTenant) { memberships, tenantId ->
            memberships.firstOrNull {
                it.userId == userId && it.tenantId == tenantId && it.status == "active"
            }
        }

    override fun activeTenantId(): String? = store.activeTenant.value

    override fun setActiveTenant(tenantId: String) = store.setActiveTenant(tenantId)

    override fun clearActiveTenant() = store.clearActiveTenant()

    override suspend fun refreshClaims(): AppResult<SessionClaims> = store.demoCall {
        val user = store.currentUser()
        SessionClaims(
            isPlatformAdmin = user?.isPlatformAdmin == true,
            isPlatformAdminElevated = user?.isPlatformAdminElevated == true,
        )
    }

    override suspend fun signIn(email: String, password: String): AppResult<AuthUser> = store.demoCall {
        val user = store.signIn(email, password)
        store.authUser(user)
    }

    override suspend fun register(email: String, password: String, displayName: String): AppResult<AuthUser> =
        store.demoCall {
            val cleanEmail = email.trim().lowercase()
            if (!cleanEmail.isDemoEmail()) {
                throw DemoFailure(
                    AppError.InvalidInput(
                        detail = "auth.invalid_email",
                        message = "Enter a valid email address.",
                        fields = mapOf("email" to "Enter a valid email address"),
                    ),
                )
            }
            if (password.length < 8) {
                throw DemoFailure(
                    AppError.InvalidInput(
                        detail = "auth.weak_password",
                        message = "Use at least 8 characters.",
                        fields = mapOf("password" to "Use at least 8 characters"),
                    ),
                )
            }
            if (store.accountByEmail(cleanEmail) != null) {
                throw DemoFailure(
                    AppError.Rejected(
                        detail = "auth.email_in_use",
                        message = "An account already exists for that email address. Sign in instead.",
                    ),
                )
            }
            val record = DemoUserRecord(
                uid = store.newId("user"),
                email = cleanEmail,
                password = password,
                displayName = displayName.trim().ifBlank { cleanEmail.substringBefore('@') },
                emailVerified = false,
            )
            store.addAccount(record)
            store.signInAs(record)
            store.audit("user.registered", tenantId = null, resourceType = "user", resourceId = record.uid)
            store.authUser(record)
        }

    override suspend fun sendPasswordReset(email: String): AppResult<Unit> = store.demoCall {
        // A real deployment answers the same way whether or not the address exists, so
        // that nobody can use the form to discover who has an account.
        store.audit(
            action = "user.password_reset_requested",
            tenantId = null,
            resourceType = "user",
            resourceId = store.accountByEmail(email)?.uid ?: "unknown",
            metadata = mapOf("email" to email.trim().lowercase()),
        )
    }

    /**
     * Stands in for the verification link.
     *
     * In demo mode there is no inbox, so the verification screen tells the user that
     * tapping "I have verified" marks the address verified — honestly labelled as a
     * simulation rather than pretending an email was sent.
     */
    override suspend fun reloadUser(): AppResult<Unit> = store.demoCall {
        val user = store.requireUser()
        if (!user.emailVerified) {
            store.accounts.value = store.accounts.value.map {
                if (it.uid == user.uid) it.copy(emailVerified = true) else it
            }
            store.audit("user.email_verified", null, "user", user.uid)
        }
    }

    override suspend fun signOut(): AppResult<Unit> = store.demoCall {
        store.signOut()
    }
}

/** Shared validation used by the demo repositories, matching the backend's rules. */
internal fun String.isDemoEmail(): Boolean {
    val value = trim()
    if (value.length < 5 || value.length > 254) return false
    val at = value.indexOf('@')
    if (at <= 0 || at != value.lastIndexOf('@')) return false
    val domain = value.substring(at + 1)
    return domain.contains('.') && !domain.startsWith('.') && !domain.endsWith('.') && !value.contains(' ')
}

internal fun String.isDemoPhone(): Boolean {
    val digits = filter { it.isDigit() }
    return digits.length in 7..15
}

internal fun requireValid(condition: Boolean, field: String, message: String, detail: String) {
    if (!condition) {
        throw DemoFailure(
            AppError.InvalidInput(detail = detail, message = message, fields = mapOf(field to message)),
        )
    }
}

/** The permission check a demo repository performs before a write. */
internal fun DemoStore.requirePermission(tenantId: String, permission: String): MembershipDto {
    val membership = requireMembership(tenantId)
    val effective = Permissions.effective(
        role = membership.role,
        granted = membership.grantedPermissions,
        revoked = membership.revokedPermissions,
    )
    if (permission !in effective) {
        throw DemoFailure(AppError.PermissionDenied(detail = "permission_required"))
    }
    return membership
}
