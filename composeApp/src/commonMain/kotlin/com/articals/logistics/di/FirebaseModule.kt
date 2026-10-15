package com.articals.logistics.di

import com.articals.logistics.data.BackendFunctions
import com.articals.logistics.data.FunctionsCaller
import com.articals.logistics.data.api.BillingRepository
import com.articals.logistics.data.api.CompanyRepository
import com.articals.logistics.data.api.CustomersRepository
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
import com.articals.logistics.data.repository.FirebaseBillingRepository
import com.articals.logistics.data.repository.FirebaseCompanyRepository
import com.articals.logistics.data.repository.FirebaseCustomersRepository
import com.articals.logistics.data.repository.FirebaseExpensesRepository
import com.articals.logistics.data.repository.FirebaseInsightsRepository
import com.articals.logistics.data.repository.FirebaseMembersRepository
import com.articals.logistics.data.repository.FirebaseOrdersRepository
import com.articals.logistics.data.repository.FirebasePlatformAdminRepository
import com.articals.logistics.data.repository.FirebasePricingRepository
import com.articals.logistics.data.repository.FirebaseRidersRepository
import com.articals.logistics.data.repository.FirebaseSessionRepository
import com.articals.logistics.data.repository.FirebaseStorageRepository
import com.articals.logistics.data.repository.FirebaseTrackingRepository
import com.articals.logistics.data.repository.FirestoreHistoryPlaceSuggestionSource
import com.articals.logistics.data.repository.SilentInAppNotifications
import com.articals.logistics.data.repository.UnavailableProofPhotoCapture
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.FirebaseAuth
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.firestore.FirebaseFirestore
import dev.gitlive.firebase.firestore.firestore
import dev.gitlive.firebase.functions.FirebaseFunctions
import dev.gitlive.firebase.functions.functions
import dev.gitlive.firebase.storage.FirebaseStorage
import dev.gitlive.firebase.storage.storage
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The real graph.
 *
 * Everything is bound to an interface, so no screen or view model can tell whether
 * it is talking to Firebase or to the demo store: switching modes swaps this module
 * for [demoModule] and nothing else changes (ADR-013).
 *
 * The SDK handles are created here rather than at start-up, which means demo mode —
 * where this module is never installed — never initialises Firebase at all.
 */
val firebaseModule: Module = module {

    // --- SDK handles -------------------------------------------------------
    single<FirebaseAuth> { Firebase.auth }
    single<FirebaseFirestore> { Firebase.firestore }
    single<FirebaseStorage> { Firebase.storage }
    single<FirebaseFunctions> { Firebase.functions(BackendFunctions.REGION) }

    // --- Preferences -------------------------------------------------------
    single { createPlatformPreferences() }

    // --- Callable transport ------------------------------------------------
    single { FunctionsCaller(functions = get(), timeoutMillis = FunctionsCaller.DEFAULT_TIMEOUT_MILLIS) }

    // --- Repositories ------------------------------------------------------
    single<SessionRepository> { FirebaseSessionRepository(auth = get(), firestore = get(), preferences = get()) }
    single<CompanyRepository> { FirebaseCompanyRepository(firestore = get(), caller = get()) }
    single<MembersRepository> { FirebaseMembersRepository(firestore = get(), caller = get()) }
    single<CustomersRepository> { FirebaseCustomersRepository(firestore = get(), caller = get()) }
    single<RidersRepository> { FirebaseRidersRepository(firestore = get(), caller = get()) }
    single<PricingRepository> { FirebasePricingRepository(firestore = get(), caller = get()) }
    single<OrdersRepository> { FirebaseOrdersRepository(firestore = get(), caller = get()) }
    single<ExpensesRepository> { FirebaseExpensesRepository(firestore = get(), caller = get()) }
    single<InsightsRepository> { FirebaseInsightsRepository(caller = get()) }
    single<TrackingRepository> { FirebaseTrackingRepository(caller = get()) }
    single<BillingRepository> { FirebaseBillingRepository(firestore = get(), caller = get()) }
    single<PlatformAdminRepository> { FirebasePlatformAdminRepository(caller = get()) }
    single<StorageRepository> { FirebaseStorageRepository(storage = get()) }

    // --- Supporting sources that are real (not simulated) in this mode -----
    // Capture, suggestions and in-app notifications all report honestly instead of
    // pretending: a photo the user cannot take, or a push that was never sent, is
    // worse than a clear "not in this build".
    single<ProofPhotoCapture> { UnavailableProofPhotoCapture() }
    single<PlaceSuggestionSource> { FirestoreHistoryPlaceSuggestionSource(firestore = get()) }
    single<InAppNotifications> { SilentInAppNotifications() }

    // No DiagnosticsControls binding: the demo control panel does not exist here, so
    // the settings screen hides its entry point.
}
