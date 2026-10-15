package com.articals.logistics.core

/**
 * Who is signed in, as far as the app needs to know.
 *
 * Deliberately not the SDK's user object: screens and view models stay free of
 * Firebase types, which keeps them testable and keeps the platform-specific code
 * in one place.
 */
data class AuthUser(
    val uid: String,
    val email: String?,
    val displayName: String?,
    val phoneNumber: String?,
    val isEmailVerified: Boolean,
) {
    /** Best-effort label for headers and avatars. */
    val label: String
        get() = displayName?.takeIf { it.isNotBlank() } ?: email?.substringBefore('@') ?: "Account"
}

/**
 * Custom claims that raise a user above ordinary membership.
 *
 * Only Articals Studio staff carry these; they are granted server-side from an
 * allow-list of bootstrap addresses and are never accepted from the client.
 */
data class SessionClaims(
    /** May see the platform console, record offline payments and manage billing. */
    val isPlatformAdmin: Boolean = false,
    /**
     * A higher level used for unusually long billing bypasses and for granting the
     * administrator claim itself. Absent from ordinary administrators on purpose.
     */
    val isPlatformAdminElevated: Boolean = false,
)
