package com.articals.logistics.di

import com.articals.logistics.ui.AppPreferences
import com.articals.logistics.ui.nav.RootViewModel
import com.articals.logistics.ui.screen.admin.AdminAuditViewModel
import com.articals.logistics.ui.screen.admin.AdminCompaniesViewModel
import com.articals.logistics.ui.screen.admin.AdminCompanyViewModel
import com.articals.logistics.ui.screen.admin.AdminOverviewViewModel
import com.articals.logistics.ui.screen.admin.AdminSettingsViewModel
import com.articals.logistics.ui.screen.auth.RegisterViewModel
import com.articals.logistics.ui.screen.auth.ResetPasswordViewModel
import com.articals.logistics.ui.screen.auth.SignInViewModel
import com.articals.logistics.ui.screen.auth.VerifyEmailViewModel
import com.articals.logistics.ui.screen.dashboard.DashboardViewModel
import com.articals.logistics.ui.screen.notifications.NotificationsViewModel
import com.articals.logistics.ui.screen.orders.OrderDetailViewModel
import com.articals.logistics.ui.screen.onboarding.OnboardingViewModel
import com.articals.logistics.ui.screen.companysettings.CompanySettingsViewModel
import com.articals.logistics.ui.screen.customers.CustomerDetailViewModel
import com.articals.logistics.ui.screen.customers.CustomersViewModel
import com.articals.logistics.ui.screen.expenses.ExpensesViewModel
import com.articals.logistics.ui.screen.orders.OrdersViewModel
import com.articals.logistics.ui.screen.pricing.CalculatorViewModel
import com.articals.logistics.ui.screen.pricing.QuotesViewModel
import com.articals.logistics.ui.screen.pricingsettings.PricingSettingsViewModel
import com.articals.logistics.ui.screen.riders.RiderDetailViewModel
import com.articals.logistics.ui.screen.riders.RidersViewModel
import com.articals.logistics.ui.screen.reports.ReportsViewModel
import com.articals.logistics.ui.screen.subscription.SubscriptionViewModel
import com.articals.logistics.ui.screen.team.TeamViewModel
import com.articals.logistics.ui.screen.tracking.TrackingViewModel
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
    viewModel {
        CalculatorViewModel(
            pricing = get(),
            orders = get(),
            customers = get(),
            companies = get(),
        )
    }
    viewModel { QuotesViewModel(pricing = get()) }

    // --- The customer book and the roster ----------------------------------
    viewModel { CustomersViewModel(customers = get(), orders = get()) }
    viewModel { CustomerDetailViewModel(customers = get(), orders = get()) }
    viewModel { RidersViewModel(riders = get()) }
    viewModel { RiderDetailViewModel(riders = get(), orders = get()) }

    // --- Money and rates ---------------------------------------------------
    viewModel { ExpensesViewModel(expenses = get()) }
    viewModel { PricingSettingsViewModel(pricing = get()) }
    viewModel { TrackingViewModel(tracking = get()) }
    viewModel { ReportsViewModel(insights = get()) }

    // --- The subscription and the office's own settings --------------------
    viewModel { SubscriptionViewModel(billing = get()) }
    viewModel { TeamViewModel(members = get()) }
    viewModel { CompanySettingsViewModel(companies = get(), storage = get(), photos = get()) }

    // --- The platform console ----------------------------------------------
    // Bound in both modes; the console is only *reachable* with the platform claim in
    // the token and the administrator flavour compiled in.
    viewModel { AdminOverviewViewModel(admin = get()) }
    viewModel { AdminCompaniesViewModel(admin = get()) }
    viewModel { AdminCompanyViewModel(admin = get()) }
    viewModel { AdminSettingsViewModel(admin = get()) }
    viewModel { AdminAuditViewModel(admin = get()) }

    // --- The rider's own screens -------------------------------------------
    viewModel { RiderHomeViewModel(orders = get(), riders = get()) }
    viewModel { RiderJobViewModel(orders = get(), storage = get(), photos = get()) }
}
