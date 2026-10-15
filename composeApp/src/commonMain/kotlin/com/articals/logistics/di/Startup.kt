package com.articals.logistics.di

import com.articals.logistics.core.AppConfig
import com.articals.logistics.data.FirebaseErrorMapper
import com.articals.logistics.demo.DemoErrorMapper
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Start-up shared by both platforms.
 *
 * Three things must happen before any screen can show data:
 *
 *  1. the error mapper for the chosen mode is installed, so a failed call becomes a
 *     message the user can act on rather than a raw SDK exception;
 *  2. exactly one graph is installed — Firebase or demo, never both;
 *  3. the configuration is bound, so screens can say "DEMO" or point at
 *     `docs/SETUP.md` without reaching for global state.
 *
 * Demo mode never touches Firebase: [firebaseModule] is the only place the SDK
 * handles are created, and it is simply not installed.
 */
fun startArticalsLogistics(config: AppConfig, vararg extraModules: Module): KoinApplication {
    if (config.mode.isDemo) {
        DemoErrorMapper.install()
    } else {
        FirebaseErrorMapper.install()
    }

    val modeModule = if (config.mode.isDemo) demoModule else firebaseModule
    val configurationModule = module { single { config } }

    return startKoin {
        modules(modeModule, configurationModule, *extraModules)
    }
}

/**
 * Everything the demo needs, in one module.
 *
 * Kept here next to [firebaseModule] so the two graphs are obviously symmetrical:
 * same interface, same binding names, different data source.
 */
val demoModule: Module get() = com.articals.logistics.demo.demoModule
