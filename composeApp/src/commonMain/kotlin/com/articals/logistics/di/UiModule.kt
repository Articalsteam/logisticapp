package com.articals.logistics.di

import com.articals.logistics.ui.AppPreferences
import com.articals.logistics.ui.nav.RootViewModel
import com.articals.logistics.ui.screen.auth.RegisterViewModel
import com.articals.logistics.ui.screen.auth.ResetPasswordViewModel
import com.articals.logistics.ui.screen.auth.SignInViewModel
import com.articals.logistics.ui.screen.auth.VerifyEmailViewModel
import com.articals.logistics.ui.screen.dashboard.DashboardViewModel
import com.articals.logistics.ui.screen.notifications.NotificationsViewModel
import com.articals.logistics.ui.screen.orders.OrderDetailViewModel
import com.articals.logistics.ui.screen.onboarding.OnboardingViewModel
import com.articals.logistics.ui.screen.orders.OrdersViewModel
import com.articals.logistics.ui.screen.rider.RiderHomeViewModel
import com.articals.logistics.ui.screen.rider.RiderJobViewModel
import com.articals.logistics.ui.screen.workspace.CompanySetupViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * The screens' own bindings, installed in *both* modes.
 *
 * This is the one module that does not change when the app switches between the demo
 * world and Firebase: a view model asks the graph for an interface, and which
 * implementation it receives was decided in [Startup]. That is what makes "screens
 * are never edited to change mode" true rather than aspirational (ADR-013).
 *
 * Every argument is written out with its parameter name. `get()` is inferred from the
 * parameter's type, so a screen that asks for something the graph does not provide
 * fails loudly at start-up instead of quietly receiving the wrong repository.
 */
val uiModule: Module = module {

    // --- Device preferences ------------------------------------------------
    // Real in both modes: theme, font scale and the last workspace are device
    // choices, not business data.
    single { AppPreferences(settings = get()) }

    // --- Session and shell -------------------------------------------------
    viewModel { RootViewModel(session = get(), companies = get(), billing = get()) }

    // --- Authentication ----------------------------------------------------
    viewModel { SignInViewModel(session = get()) }
    viewModel { RegisterViewModel(session = get()) }
    viewModel { ResetPasswordViewModel(session = get()) }
    viewModel { VerifyEmailViewModel(session = get()) }

    // --- Workspace and onboarding ------------------------------------------
    viewModel { CompanySetupViewModel(companies = get(), members = get()) }
    viewModel { OnboardingViewModel(companies = get(), pricing = get(), members = get()) }

    // --- Day-to-day --------------------------------------------------------
    viewModel { DashboardViewModel(insights = get(), orders = get()) }
    viewModel { OrdersViewModel(orders = get()) }
    viewModel { OrderDetailViewModel(orders = get(), riders = get()) }
    viewModel { NotificationsViewModel(notifications = get()) }

    // --- The rider's own screens -------------------------------------------
    viewModel { RiderHomeViewModel(orders = get(), riders = get()) }
    viewModel { RiderJobViewModel(orders = get(), storage = get(), photos = get()) }
}
