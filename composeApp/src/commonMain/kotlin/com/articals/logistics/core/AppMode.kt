package com.articals.logistics.core

/**
 * Where the app gets its data.
 *
 * The two modes are complete alternatives, not layers: a build runs in exactly one
 * of them and no screen, view model or repository interface knows which. Switching
 * is a build/configuration value (ADR-013).
 */
enum class AppMode {
    /** In-memory demo data. No Firebase initialisation, no network, no config files. */
    DEMO,

    /** Real backend: Firebase Auth, Firestore, Storage, Functions, Messaging. */
    FIREBASE;

    val isDemo: Boolean get() = this == DEMO
    val isFirebase: Boolean get() = this == FIREBASE

    companion object {
        /** Parses the value that arrives from a Gradle property, Info.plist or a test. */
        fun fromConfig(value: String?): AppMode = when (value?.trim()?.lowercase()) {
            "firebase", "prod", "production", "live" -> FIREBASE
            else -> DEMO
        }
    }
}

/**
 * Immutable configuration resolved once, before the dependency graph is built.
 *
 * Carried in the graph so any screen can explain itself ("DEMO" badge, the
 * not-configured screen) without reaching for global state.
 */
data class AppConfig(
    val mode: AppMode,
    /** True when the platform found Firebase configuration (google-services.json / plist). */
    val firebaseConfigured: Boolean,
    /** Where the demo controls panel may be opened. Never true in a release build. */
    val allowDiagnostics: Boolean,
    /**
     * Build flavour. The platform console is compiled into the admin flavour only,
     * and the backend refuses every administrative call without the claim.
     */
    val adminFlavor: Boolean,
) {
    val isDemo: Boolean get() = mode.isDemo

    /**
     * True when the app can run: demo always can, Firebase needs its configuration
     * files. The alternative is a crash on first SDK call, which is never acceptable.
     */
    val isRunnable: Boolean get() = mode.isDemo || firebaseConfigured

    companion object {
        /** Default used by previews and tests. */
        val Demo = AppConfig(AppMode.DEMO, firebaseConfigured = false, allowDiagnostics = true, adminFlavor = true)

        /**
         * Builds the configuration from what the platform shell knows.
         *
         * Each entry point reads its own build system (Android's `BuildConfig`, iOS's
         * `Info.plist` / xcconfig) and passes the result here, so the rules below are
         * written once and cannot drift between the two apps.
         *
         * The rule worth stating: a Firebase build without configuration is *not*
         * runnable, and it is never quietly turned into a demo build.
         */
        fun fromParts(
            mode: String?,
            firebaseConfigured: Boolean,
            allowDiagnostics: Boolean,
            adminFlavor: Boolean,
        ): AppConfig {
            val resolved = AppMode.fromConfig(mode)
            return AppConfig(
                mode = resolved,
                firebaseConfigured = if (resolved.isFirebase) firebaseConfigured else false,
                allowDiagnostics = allowDiagnostics && resolved.isDemo,
                adminFlavor = adminFlavor,
            )
        }
    }
}

/** Names of the demo accounts and the shared demo password, in one place. */
object DemoCredentials {
    const val password = "demo1234"

    const val ownerEmail = "owner@demo.example"
    const val managerEmail = "manager@demo.example"
    const val dispatcherEmail = "dispatcher@demo.example"
    const val riderEmail = "rider@demo.example"
    const val secondRiderEmail = "rider2@demo.example"
    const val adminEmail = "admin@demo.example"
    const val secondOwnerEmail = "owner2@demo.example"

    /** The roster shown as one-tap buttons on the sign-in screen. */
    val accounts: List<DemoAccount> = listOf(
        DemoAccount("Company owner", ownerEmail, "Sees everything: money, staff, settings", Roles.Owner),
        DemoAccount("Manager", managerEmail, "Reports and pricing, no staff admin", Roles.Manager),
        DemoAccount("Dispatcher", dispatcherEmail, "Orders and assignment, no financial reports", Roles.Dispatcher),
        DemoAccount("Rider", riderEmail, "Only their own assigned deliveries", Roles.Rider),
        DemoAccount("Second rider", secondRiderEmail, "Proves riders cannot see each other's jobs", Roles.Rider),
        DemoAccount("Second company owner", secondOwnerEmail, "Sees none of the first company's data", Roles.Owner),
        DemoAccount("Platform admin", adminEmail, "Platform console (admin build)", "platform_admin"),
    )
}

data class DemoAccount(
    val title: String,
    val email: String,
    val description: String,
    val role: String,
)
