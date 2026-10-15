package com.articals.logistics.di

import com.articals.logistics.data.BackendFunctions
import com.articals.logistics.data.FunctionsCaller
import com.articals.logistics.data.createPlatformPreferences
import com.articals.logistics.data.repository.BillingRepository
import com.articals.logistics.data.repository.CompanyRepository
import com.articals.logistics.data.repository.CustomersRepository
import com.articals.logistics.data.repository.ExpensesRepository
import com.articals.logistics.data.repository.InsightsRepository
import com.articals.logistics.data.repository.MembersRepository
import com.articals.logistics.data.repository.OrdersRepository
import com.articals.logistics.data.repository.PlatformAdminRepository
import com.articals.logistics.data.repository.PricingRepository
import com.articals.logistics.data.repository.RidersRepository
import com.articals.logistics.data.repository.SessionRepository
import com.articals.logistics.data.repository.StorageRepository
import com.articals.logistics.data.repository.TrackingRepository
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
 * Dependency graph.
 *
 * Koin is used deliberately instead of an annotation processor: it works on Kotlin
 * Native without code generation, so the shared module keeps a single, identical
 * graph on Android and iOS (ADR-001).
 *
 * Everything is a singleton: the Firebase SDKs are already long-lived, and the
 * repositories are stateless beyond their clients.
 */
val backendModule: Module = module {

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
    single { SessionRepository(auth = get(), firestore = get(), preferences = get()) }
    single { CompanyRepository(firestore = get(), caller = get()) }
    single { MembersRepository(firestore = get(), caller = get()) }
    single { CustomersRepository(firestore = get(), caller = get()) }
    single { RidersRepository(firestore = get(), caller = get()) }
    single { PricingRepository(firestore = get(), caller = get()) }
    single { OrdersRepository(firestore = get(), caller = get()) }
    single { ExpensesRepository(firestore = get(), caller = get()) }
    single { InsightsRepository(caller = get()) }
    single { TrackingRepository(caller = get()) }
    single { BillingRepository(caller = get()) }
    single { PlatformAdminRepository(caller = get()) }
    single { StorageRepository(storage = get()) }
}
