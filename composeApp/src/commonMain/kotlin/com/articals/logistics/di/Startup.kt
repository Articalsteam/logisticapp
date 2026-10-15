package com.articals.logistics.di

import com.articals.logistics.data.FirebaseErrorMapper
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.Module

/**
 * Start-up shared by both platforms.
 *
 * Two things must happen before any screen can show real data:
 *
 *  1. the error mapper is installed, so a failed callable becomes a message the
 *     user can act on rather than a raw SDK exception;
 *  2. the dependency graph is started.
 *
 * The platform entry point calls this from `Application.onCreate` (Android) and
 * from the Swift `AppDelegate`/`App` initialiser (iOS), after Firebase itself has
 * been configured — on iOS that means after `FirebaseApp.configure()`.
 */
fun startArticalsLogistics(vararg extraModules: Module): KoinApplication {
    FirebaseErrorMapper.install()
    return startKoin {
        modules(backendModule, *extraModules)
    }
}
