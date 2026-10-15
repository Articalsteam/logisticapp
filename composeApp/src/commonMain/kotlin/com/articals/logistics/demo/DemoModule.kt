package com.articals.logistics.demo

import com.articals.logistics.data.api.BillingRepository
import com.articals.logistics.data.api.CompanyRepository
import com.articals.logistics.data.api.CustomersRepository
import com.articals.logistics.data.api.DiagnosticsControls
import com.articals.logistics.data.api.ExpensesRepository
import com.articals.logistics.data.api.InAppNotifications
import com.articals.logistics.data.api.InsightsRepository
import com.articals.logistics.data.api.MembersRepository
import com.articals.logistics.data.api.OrdersRepository
import com.articals.logistics.data.api.PlaceSuggestionSource
import com.articals.logistics.data.api.PlatformAdminRepository
import com.articals.logistics.data.api.PricingRepository
import com.articals.logistics.data.api.ProofPhotoCapture
import com.articals.logistics.data.api.RidersRepository
import com.articals.logistics.data.api.SessionRepository
import com.articals.logistics.data.api.StorageRepository
import com.articals.logistics.data.api.TrackingRepository
import com.articals.logistics.data.createPlatformPreferences
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** Qualifier for the demo latency, so a test can build the graph with no waiting. */
const val DEMO_LATENCY_QUALIFIER = "demo.latency"

/**
 * The demo graph.
 *
 * The mirror image of `firebaseModule`: the same fifteen interfaces, the same
 * lifetimes, different data. Only one of the two is ever installed (see
 * `di/Startup.kt`), so a Firebase build cannot reach demo code and a demo build
 * cannot initialise Firebase — the module that creates the SDK handles is simply
 * absent.
 *
 * Everything here is in-memory. Nothing on this path opens a socket, reads a config
 * file or asks for a permission, which is what makes the app runnable with no
 * Firebase project at all.
 */
fun demoModuleWith(latencyMillis: Long): Module = module {

    // --- The simulated world ----------------------------------------------
    // One store, one clock, one seeded world. Screens share it, so a delivery
    // created on one screen appears immediately on another.
    single(qualifier = named(DEMO_LATENCY_QUALIFIER)) { latencyMillis }

    single { DemoControls(initialLatencyMillis = get(qualifier = named(DEMO_LATENCY_QUALIFIER))) }

    single {
        val store = DemoStore(controls = get())
        // The control panel and the store reference each other: the panel needs to
        // command the store, the store needs the panel's latency and error injection.
        store.controls.commands = store
        DemoSeed.populate(store)
        store
    }

    single<DiagnosticsControls> { get<DemoControls>() }

    // --- Preferences -------------------------------------------------------
    // Real, not simulated: theme, font scale and "seen the tour" are device
    // preferences in both modes, and none of them touch Firebase.
    single { createPlatformPreferences() }

    // --- Repositories ------------------------------------------------------
    single<SessionRepository> { DemoSessionRepository(store = get()) }
    single<CompanyRepository> { DemoCompanyRepository(store = get()) }
    single<MembersRepository> { DemoMembersRepository(store = get()) }
    single<CustomersRepository> { DemoCustomersRepository(store = get()) }
    single<RidersRepository> { DemoRidersRepository(store = get()) }
    single<PricingRepository> { DemoPricingRepository(store = get()) }
    single<OrdersRepository> { DemoOrdersRepository(store = get()) }
    single<ExpensesRepository> { DemoExpensesRepository(store = get()) }
    single<InsightsRepository> { DemoInsightsRepository(store = get()) }
    single<TrackingRepository> { DemoTrackingRepository(store = get()) }
    single<BillingRepository> { DemoBillingRepository(store = get()) }
    single<PlatformAdminRepository> { DemoPlatformAdminRepository(store = get()) }
    single<StorageRepository> { DemoStorageRepository(store = get()) }

    // --- Supporting sources, all honestly simulated ------------------------
    single<ProofPhotoCapture> { DemoProofPhotoCapture() }
    single<PlaceSuggestionSource> { DemoPlaceSuggestionSource() }
    single<InAppNotifications> { DemoInAppNotifications(store = get()) }
}

/**
 * The standard demo graph.
 *
 * Kept as a value because `di/Startup.kt` picks between this and `firebaseModule` by
 * mode alone. Tests that need determinism use [demoModuleWith] with a latency of
 * zero, so no test ever waits on a simulated network.
 */
val demoModule: Module = demoModuleWith(DemoControls.DEFAULT_LATENCY_MILLIS)
