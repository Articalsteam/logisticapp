package com.articals.logistics.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.NavType
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.articals.logistics.core.AppConfig
import com.articals.logistics.core.Roles
import com.articals.logistics.data.dto.AppNotificationDto
import com.articals.logistics.di.SupportLocator
import com.articals.logistics.ui.AppPreferences
import com.articals.logistics.ui.component.Banner
import com.articals.logistics.ui.component.BannerTone
import com.articals.logistics.ui.component.DemoBadge
import com.articals.logistics.ui.demo.DemoControlsSheet
import com.articals.logistics.ui.screen.auth.RegisterScreen
import com.articals.logistics.ui.screen.auth.ResetPasswordScreen
import com.articals.logistics.ui.screen.auth.SignInScreen
import com.articals.logistics.ui.screen.auth.VerifyEmailScreen
import com.articals.logistics.ui.screen.dashboard.DashboardScreen
import com.articals.logistics.ui.screen.notifications.NotificationsScreen
import com.articals.logistics.ui.screen.onboarding.OnboardingScreen
import com.articals.logistics.ui.screen.orders.OrderDetailScreen
import com.articals.logistics.ui.screen.orders.OrdersScreen
import com.articals.logistics.ui.screen.admin.AdminAuditScreen
import com.articals.logistics.ui.screen.admin.AdminCompaniesScreen
import com.articals.logistics.ui.screen.admin.AdminCompanyScreen
import com.articals.logistics.ui.screen.admin.AdminOverviewScreen
import com.articals.logistics.ui.screen.admin.AdminSettingsScreen
import com.articals.logistics.ui.screen.companysettings.CompanySettingsScreen
import com.articals.logistics.ui.screen.customers.CustomerDetailScreen
import com.articals.logistics.ui.screen.customers.CustomersScreen
import com.articals.logistics.ui.screen.expenses.ExpensesScreen
import com.articals.logistics.ui.screen.pricing.CalculatorScreen
import com.articals.logistics.ui.screen.pricing.QuotesScreen
import com.articals.logistics.ui.screen.pricingsettings.PricingSettingsScreen
import com.articals.logistics.ui.screen.riders.RiderDetailScreen
import com.articals.logistics.ui.screen.riders.RidersScreen
import com.articals.logistics.ui.screen.reports.ReportsScreen
import com.articals.logistics.ui.screen.subscription.SubscriptionScreen
import com.articals.logistics.ui.screen.team.TeamScreen
import com.articals.logistics.ui.screen.tracking.TrackingScreen
import com.articals.logistics.ui.screen.rider.RiderHomeScreen
import com.articals.logistics.ui.screen.rider.RiderJobScreen
import com.articals.logistics.ui.screen.settings.SettingsScreen
import com.articals.logistics.ui.screen.system.LimitedAccessScreen
import com.articals.logistics.ui.screen.system.NoAccessScreen
import com.articals.logistics.ui.screen.system.NotConfiguredScreen
import com.articals.logistics.ui.screen.system.SplashScreen
import com.articals.logistics.ui.screen.workspace.NoCompanyScreen
import com.articals.logistics.ui.screen.workspace.WorkspacesScreen
import com.articals.logistics.ui.text.ArticalsStrings
import com.articals.logistics.ui.theme.Spacing
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.compose.viewmodel.koinViewModel

/**
 * The navigation host: every screen in the app hangs off here.
 *
 * Two ideas do the work.
 *
 * **The session decides where you land.** [RootState.phase] is one value — loading,
 * signed out, needs verification, no company, onboarding, ready — and this host
 * navigates whenever it changes, clearing the stack. That is why a signed-in
 * dispatcher never sees the sign-in form flash, and why somebody who has just created
 * their company cannot press Back into the empty state they came from.
 *
 * **Every route states who may open it.** [Routes.canOpen] is the rule, applied
 * *inside* the graph rather than only on the buttons that lead there: hiding a button
 * is a courtesy, and this is where the app actually refuses.
 *
 * Nothing below knows which mode the app is in beyond a single [AppConfig]. The
 * screens are identical in both, which is the whole point of the seam (ADR-013).
 */
@Composable
fun ArticalsNavigation(
    appPreferences: AppPreferences,
    config: AppConfig,
    modifier: Modifier = Modifier,
    rootViewModel: RootViewModel = koinViewModel(),
) {
    val state by rootViewModel.state.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    var demoPanelOpen by remember { mutableStateOf(false) }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route

    SessionRouting(navController = navController, state = state, config = config)

    val showChrome = route == null || !isBareRoute(route)

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (showChrome) {
                AppTopBar(
                    title = state.companyName ?: ArticalsStrings.Common.appName,
                    subtitle = state.role?.let { Roles.label(it) },
                    isDemo = config.isDemo,
                    onOpenNotifications = { navController.navigate(Routes.Notifications) },
                )
            }
        },
        bottomBar = {
            if (showChrome) {
                ShellBottomBar(
                    navController = navController,
                    role = state.role,
                    currentRoute = route,
                )
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            // A workspace that cannot start new work says so before a save fails, and
            // loses nothing else: reading, exporting and sharing all keep working.
            LimitedAccessNotice(
                entitlement = state.entitlement,
                role = state.role,
                onOpen = { navController.navigate(Routes.LimitedAccess) },
            )
            Box(modifier = Modifier.fillMaxSize()) {
            NavHost(
                navController = navController,
                startDestination = Routes.Splash,
                modifier = Modifier.fillMaxSize(),
            ) {
                // --- session ---------------------------------------------------
                composable(Routes.Splash) { SplashScreen() }

                composable(Routes.NotConfigured) { NotConfiguredScreen(config = config) }

                composable(Routes.SignIn) {
                    SignInScreen(
                        onSignedIn = rootViewModel::refresh,
                        onRegister = { navController.navigate(Routes.Register) },
                        onForgotPassword = { navController.navigate(Routes.ResetPassword) },
                    )
                }

                composable(Routes.Register) {
                    RegisterScreen(
                        onRegistered = { navController.navigate(Routes.VerifyEmail) },
                        onBackToSignIn = { navController.popBackStack() },
                    )
                }

                composable(Routes.ResetPassword) {
                    ResetPasswordScreen(onBackToSignIn = { navController.popBackStack() })
                }

                composable(Routes.VerifyEmail) {
                    VerifyEmailScreen(onVerified = rootViewModel::refresh)
                }

                composable(Routes.NoCompany) {
                    NoCompanyScreen(
                        onCompanyCreated = { tenantId ->
                            rootViewModel.selectWorkspace(tenantId)
                            rootViewModel.refresh()
                        },
                    )
                }

                composable(Routes.Workspaces) {
                    WorkspacesScreen(
                        memberships = state.memberships,
                        activeTenantId = state.activeTenantId,
                        companyNames = state.memberships.associate { membership ->
                            membership.tenantId to (state.companyName ?: membership.tenantId)
                        },
                        onSelect = { tenantId ->
                            rootViewModel.selectWorkspace(tenantId)
                            navController.popBackStack()
                        },
                        onCreateNew = { navController.navigate(Routes.NoCompany) },
                    )
                }

                composable(Routes.Onboarding) { entry ->
                    val tenantId = entry.arguments?.getString(Routes.ArgTenantId).orEmpty()
                    OnboardingScreen(tenantId = tenantId, onFinished = rootViewModel::refresh)
                }

                // --- a working day ---------------------------------------------
                composable(Routes.Dashboard) {
                    Guarded(navController = navController, state = state, route = Routes.Dashboard) {
                        val tenantId = state.activeTenantId
                        if (tenantId == null) {
                            NoAccessScreen(onBack = { navController.popBackStack() })
                        } else {
                            DashboardScreen(
                                tenantId = tenantId,
                                timeZoneId = state.zoneId,
                                currency = state.currency,
                                onOpenOrder = { orderId -> navController.navigate(Routes.order(orderId)) },
                                onOpenOrders = { navController.navigate(Routes.Orders) },
                            )
                        }
                    }
                }

                composable(Routes.Orders) {
                    Guarded(navController = navController, state = state, route = Routes.Orders) {
                        val tenantId = state.activeTenantId
                        if (tenantId == null) {
                            NoAccessScreen(onBack = { navController.popBackStack() })
                        } else {
                            OrdersScreen(
                                tenantId = tenantId,
                                timeZoneId = state.zoneId,
                                currency = state.currency,
                                onOpenOrder = { orderId -> navController.navigate(Routes.order(orderId)) },
                                onNewOrder = { navController.navigate(Routes.calculator()) },
                            )
                        }
                    }
                }

                composable(Routes.OrderDetail) { entry ->
                    Guarded(navController = navController, state = state, route = Routes.OrderDetail) {
                        val tenantId = state.activeTenantId
                        val orderId = entry.arguments?.getString(Routes.ArgOrderId)
                        if (tenantId == null || orderId.isNullOrBlank()) {
                            NoAccessScreen(onBack = { navController.popBackStack() })
                        } else {
                            OrderDetailScreen(
                                tenantId = tenantId,
                                orderId = orderId,
                                currency = state.currency,
                                timeZoneId = state.zoneId,
                                companyName = state.companyName ?: ArticalsStrings.Common.appName,
                                permissions = state.permissions,
                                isDemoMode = config.isDemo,
                                isAssignedRider = false,
                            )
                        }
                    }
                }

                composable(
                    route = Routes.Calculator,
                    arguments = listOf(
                        navArgument(Routes.ArgQuoteId) {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                    ),
                ) { entry ->
                    Guarded(navController = navController, state = state, route = Routes.Calculator) {
                        val tenantId = state.activeTenantId
                        if (tenantId == null) {
                            NoAccessScreen(onBack = { navController.popBackStack() })
                        } else {
                            val quoteId = entry.arguments?.getString(Routes.ArgQuoteId)
                            CalculatorScreen(
                                tenantId = tenantId,
                                currency = state.currency,
                                timeZoneId = state.zoneId,
                                permissions = state.permissions,
                                onOpenOrder = { orderId -> navController.navigate(Routes.order(orderId)) },
                                onOpenQuotes = { navController.navigate(Routes.Quotes) },
                                pendingQuoteId = quoteId,
                            )
                        }
                    }
                }

                composable(Routes.Quotes) {
                    Guarded(navController = navController, state = state, route = Routes.Quotes) {
                        val tenantId = state.activeTenantId
                        if (tenantId == null) {
                            NoAccessScreen(onBack = { navController.popBackStack() })
                        } else {
                            QuotesScreen(
                                tenantId = tenantId,
                                currency = state.currency,
                                timeZoneId = state.zoneId,
                                onReuse = { quoteId -> navController.navigate(Routes.calculator(quoteId)) },
                            )
                        }
                    }
                }

                composable(Routes.Customers) {
                    Guarded(navController = navController, state = state, route = Routes.Customers) {
                        val tenantId = state.activeTenantId
                        if (tenantId == null) {
                            NoAccessScreen(onBack = { navController.popBackStack() })
                        } else {
                            CustomersScreen(
                                tenantId = tenantId,
                                permissions = state.permissions,
                                onOpenCustomer = { customerId ->
                                    navController.navigate(Routes.customer(customerId))
                                },
                            )
                        }
                    }
                }

                composable(Routes.CustomerDetail) { entry ->
                    Guarded(navController = navController, state = state, route = Routes.CustomerDetail) {
                        val tenantId = state.activeTenantId
                        val customerId = entry.arguments?.getString(Routes.ArgCustomerId)
                        if (tenantId == null || customerId.isNullOrBlank()) {
                            NoAccessScreen(onBack = { navController.popBackStack() })
                        } else {
                            CustomerDetailScreen(
                                tenantId = tenantId,
                                customerId = customerId,
                                currency = state.currency,
                                timeZoneId = state.zoneId,
                                onOpenOrder = { orderId -> navController.navigate(Routes.order(orderId)) },
                                onBack = { navController.popBackStack() },
                            )
                        }
                    }
                }

                composable(Routes.Riders) {
                    Guarded(navController = navController, state = state, route = Routes.Riders) {
                        val tenantId = state.activeTenantId
                        if (tenantId == null) {
                            NoAccessScreen(onBack = { navController.popBackStack() })
                        } else {
                            RidersScreen(
                                tenantId = tenantId,
                                permissions = state.permissions,
                                onOpenRider = { riderId -> navController.navigate(Routes.rider(riderId)) },
                            )
                        }
                    }
                }

                composable(Routes.RiderDetail) { entry ->
                    Guarded(navController = navController, state = state, route = Routes.RiderDetail) {
                        val tenantId = state.activeTenantId
                        val riderId = entry.arguments?.getString(Routes.ArgRiderId)
                        if (tenantId == null || riderId.isNullOrBlank()) {
                            NoAccessScreen(onBack = { navController.popBackStack() })
                        } else {
                            RiderDetailScreen(
                                tenantId = tenantId,
                                riderId = riderId,
                                currency = state.currency,
                                onOpenOrder = { orderId -> navController.navigate(Routes.order(orderId)) },
                                onBack = { navController.popBackStack() },
                            )
                        }
                    }
                }

                composable(Routes.Expenses) {
                    Guarded(navController = navController, state = state, route = Routes.Expenses) {
                        val tenantId = state.activeTenantId
                        if (tenantId == null) {
                            NoAccessScreen(onBack = { navController.popBackStack() })
                        } else {
                            ExpensesScreen(
                                tenantId = tenantId,
                                currency = state.currency,
                                zoneId = state.zoneId,
                                permissions = state.permissions,
                            )
                        }
                    }
                }

                composable(Routes.PricingSettings) {
                    Guarded(navController = navController, state = state, route = Routes.PricingSettings) {
                        val tenantId = state.activeTenantId
                        if (tenantId == null) {
                            NoAccessScreen(onBack = { navController.popBackStack() })
                        } else {
                            PricingSettingsScreen(
                                tenantId = tenantId,
                                currency = state.currency,
                                serviceAreas = state.company?.serviceAreas.orEmpty(),
                                permissions = state.permissions,
                            )
                        }
                    }
                }

                composable(Routes.Tracking) {
                    Guarded(navController = navController, state = state, route = Routes.Tracking) {
                        TrackingScreen(timeZoneId = state.zoneId)
                    }
                }

                composable(Routes.Reports) {
                    Guarded(navController = navController, state = state, route = Routes.Reports) {
                        val tenantId = state.activeTenantId
                        if (tenantId == null) {
                            NoAccessScreen(onBack = { navController.popBackStack() })
                        } else {
                            ReportsScreen(
                                tenantId = tenantId,
                                zoneId = state.zoneId,
                                permissions = state.permissions,
                            )
                        }
                    }
                }

                composable(Routes.CompanySettings) {
                    Guarded(navController = navController, state = state, route = Routes.CompanySettings) {
                        val tenantId = state.activeTenantId
                        if (tenantId == null) {
                            NoAccessScreen(onBack = { navController.popBackStack() })
                        } else {
                            CompanySettingsScreen(
                                tenantId = tenantId,
                                permissions = state.permissions,
                            )
                        }
                    }
                }

                composable(Routes.Team) {
                    Guarded(navController = navController, state = state, route = Routes.Team) {
                        val tenantId = state.activeTenantId
                        if (tenantId == null) {
                            NoAccessScreen(onBack = { navController.popBackStack() })
                        } else {
                            TeamScreen(
                                tenantId = tenantId,
                                currentUserId = state.user?.uid,
                                permissions = state.permissions,
                            )
                        }
                    }
                }

                composable(Routes.Subscription) {
                    Guarded(navController = navController, state = state, route = Routes.Subscription) {
                        val tenantId = state.activeTenantId
                        if (tenantId == null) {
                            NoAccessScreen(onBack = { navController.popBackStack() })
                        } else {
                            SubscriptionScreen(
                                tenantId = tenantId,
                                currency = state.currency,
                                timeZoneId = state.zoneId,
                            )
                        }
                    }
                }

                composable(Routes.LimitedAccess) {
                    LimitedAccessScreen(
                        entitlement = state.entitlement,
                        daysRemaining = null,
                        onOpenSubscription = { navController.navigate(Routes.Subscription) },
                        onBack = { navController.popBackStack() },
                    )
                }

                // --- the platform console --------------------------------------
                composable(Routes.AdminOverview) {
                    Guarded(navController = navController, state = state, route = Routes.AdminOverview) {
                        AdminOverviewScreen(
                            timeZoneId = state.zoneId,
                            onOpenCompany = { tenantId -> navController.navigate(Routes.adminCompany(tenantId)) },
                            onOpenCompanies = { navController.navigate(Routes.AdminCompanies) },
                            onOpenSettings = { navController.navigate(Routes.AdminSettings) },
                            onOpenAudit = { navController.navigate(Routes.AdminAudit) },
                        )
                    }
                }

                composable(Routes.AdminCompanies) {
                    Guarded(navController = navController, state = state, route = Routes.AdminCompanies) {
                        AdminCompaniesScreen(
                            onOpenCompany = { tenantId -> navController.navigate(Routes.adminCompany(tenantId)) },
                            onBack = { navController.popBackStack() },
                        )
                    }
                }

                composable(
                    route = Routes.AdminCompanyDetail,
                    arguments = listOf(
                        navArgument(Routes.ArgTenantId) { type = NavType.StringType },
                    ),
                ) { entry ->
                    Guarded(navController = navController, state = state, route = Routes.AdminCompanyDetail) {
                        val tenantId = entry.arguments?.getString(Routes.ArgTenantId)
                        if (tenantId.isNullOrBlank()) {
                            NoAccessScreen(onBack = { navController.popBackStack() })
                        } else {
                            AdminCompanyScreen(
                                tenantId = tenantId,
                                company = null,
                                currency = state.currency,
                                timeZoneId = state.zoneId,
                                onBack = { navController.popBackStack() },
                            )
                        }
                    }
                }

                composable(
                    route = Routes.AdminPayments,
                    arguments = listOf(
                        navArgument(Routes.ArgTenantId) { type = NavType.StringType },
                    ),
                ) { entry ->
                    Guarded(navController = navController, state = state, route = Routes.AdminPayments) {
                        val tenantId = entry.arguments?.getString(Routes.ArgTenantId)
                        if (tenantId.isNullOrBlank()) {
                            NoAccessScreen(onBack = { navController.popBackStack() })
                        } else {
                            AdminCompanyScreen(
                                tenantId = tenantId,
                                company = null,
                                currency = state.currency,
                                timeZoneId = state.zoneId,
                                focusPayments = true,
                                onBack = { navController.popBackStack() },
                            )
                        }
                    }
                }

                composable(Routes.AdminSettings) {
                    Guarded(navController = navController, state = state, route = Routes.AdminSettings) {
                        AdminSettingsScreen(onBack = { navController.popBackStack() })
                    }
                }

                composable(Routes.AdminAudit) {
                    Guarded(navController = navController, state = state, route = Routes.AdminAudit) {
                        AdminAuditScreen(onBack = { navController.popBackStack() })
                    }
                }

                composable(Routes.Settings) {
                    SettingsScreen(
                        signedInAs = state.user?.email,
                        companyName = state.companyName,
                        roleLabel = state.role?.let { Roles.label(it) },
                        entitlement = state.entitlement,
                        canSwitchCompany = state.memberships.size > 1,
                        currency = state.currency,
                        onOpenNotifications = { navController.navigate(Routes.Notifications) },
                        onOpenCustomers = { navController.navigate(Routes.Customers) },
                        onOpenRiders = { navController.navigate(Routes.Riders) },
                        onOpenPricing = { navController.navigate(Routes.PricingSettings) },
                        onOpenExpenses = { navController.navigate(Routes.Expenses) },
                        onOpenTracking = { navController.navigate(Routes.Tracking) },
                        onOpenReports = { navController.navigate(Routes.Reports) },
                        onOpenCompanySettings = { navController.navigate(Routes.CompanySettings) },
                        onOpenTeam = { navController.navigate(Routes.Team) },
                        onOpenSubscription = { navController.navigate(Routes.Subscription) },
                        onOpenConsole = if (state.canOpenConsole) {
                            { navController.navigate(Routes.AdminOverview) }
                        } else {
                            null
                        },
                        onSwitchCompany = { navController.navigate(Routes.Workspaces) },
                        onOpenDemoControls = if (config.allowDiagnostics) {
                            { demoPanelOpen = true }
                        } else {
                            null
                        },
                        onSignOut = {
                            rootViewModel.signOut()
                            navController.navigate(Routes.SignIn) {
                                popUpTo(Routes.Splash) { inclusive = true }
                            }
                        },
                    )
                }

                composable(Routes.Notifications) {
                    NotificationsScreen(timeZoneId = state.zoneId)
                }

                // --- the rider's app -------------------------------------------
                composable(Routes.RiderHome) {
                    Guarded(navController = navController, state = state, route = Routes.RiderHome) {
                        val tenantId = state.activeTenantId
                        val userId = state.user?.uid
                        if (tenantId == null || userId == null) {
                            NoAccessScreen(onBack = { navController.popBackStack() })
                        } else {
                            RiderHomeScreen(
                                tenantId = tenantId,
                                userId = userId,
                                timeZoneId = state.zoneId,
                                onOpenJob = { orderId -> navController.navigate(Routes.riderJob(orderId)) },
                            )
                        }
                    }
                }

                composable(Routes.RiderJob) { entry ->
                    Guarded(navController = navController, state = state, route = Routes.RiderJob) {
                        val tenantId = state.activeTenantId
                        val orderId = entry.arguments?.getString(Routes.ArgOrderId)
                        if (tenantId == null || orderId.isNullOrBlank()) {
                            NoAccessScreen(onBack = { navController.popBackStack() })
                        } else {
                            RiderJobScreen(
                                tenantId = tenantId,
                                orderId = orderId,
                                timeZoneId = state.zoneId,
                                onBack = { navController.popBackStack() },
                            )
                        }
                    }
                }
            }
        }
            }
    }

    if (demoPanelOpen) {
        DemoPanelHost(
            appPreferences = appPreferences,
            onDismiss = { demoPanelOpen = false },
        )
    }
}

/**
 * Sends the user where their session says they belong.
 *
 * It navigates only when the destination actually changes, so a state update in the
 * middle of a working day — an assignment arriving, say — never yanks somebody back to
 * the dashboard. The stack is cleared on these transitions because none of them is a
 * step anybody should be able to walk back through: not back into the sign-in form,
 * not back into the wizard, not back into the empty company.
 */
@Composable
private fun SessionRouting(
    navController: NavHostController,
    state: RootState,
    config: AppConfig,
) {
    val target = destinationFor(state, config)
    val current = navController.currentBackStackEntry?.destination?.route

    LaunchedEffect(target, current) {
        if (target == null || current == target) return@LaunchedEffect
        // A screen that is already the right place (a detail view inside a tab) is left
        // alone: only the session's own destinations move the user.
        if (current != null && !isSessionRoute(current) && isSessionRoute(target)) return@LaunchedEffect
        navController.navigate(target) {
            popUpTo(Routes.Splash) { inclusive = true }
            launchSingleTop = true
        }
    }
}

/** The one route a session state belongs to, or null when nothing should move. */
private fun destinationFor(state: RootState, config: AppConfig): String? = when {
    !config.isRunnable -> Routes.NotConfigured
    state.phase == SessionPhase.Loading -> Routes.Splash
    state.phase == SessionPhase.SignedOut -> Routes.SignIn
    state.phase == SessionPhase.NeedsVerification -> Routes.VerifyEmail
    state.phase == SessionPhase.NoCompany -> Routes.NoCompany
    state.phase == SessionPhase.Onboarding ->
        state.activeTenantId?.let { Routes.onboarding(it) } ?: Routes.NoCompany

    state.phase == SessionPhase.Ready ->
        if (state.role == Roles.Rider) Routes.RiderHome else Routes.Dashboard

    else -> null
}

/** True when a route is decided by the session rather than chosen by the user. */
private fun isSessionRoute(route: String): Boolean {
    val base = route.substringBefore("/{")
    return base == Routes.Splash ||
        base == Routes.NotConfigured ||
        base == Routes.SignIn ||
        base == Routes.Register ||
        base == Routes.ResetPassword ||
        base == Routes.VerifyEmail ||
        base == Routes.NoCompany ||
        base == Routes.Workspaces ||
        base == Routes.Onboarding
}

/** Screens that own the whole window: no top bar, no tabs. */
private fun isBareRoute(route: String): Boolean = isSessionRoute(route)

/**
 * Access control at the graph.
 *
 * The rule lives in [Routes.canOpen]; this is the enforcement point, so a screen a
 * role may not use renders an explanation instead of data it should not have.
 */
@Composable
/**
 * The banner that appears when the company's access no longer covers new work.
 *
 * It is deliberately quiet and never blocking: a limited workspace is still a workspace.
 */
@Composable
private fun LimitedAccessNotice(
    entitlement: String?,
    role: String?,
    onOpen: () -> Unit,
) {
    if (role == null || role == Roles.Rider) return
    if (com.articals.logistics.domain.EntitlementStates.canWrite(entitlement.orEmpty())) return

    Banner(
        text = com.articals.logistics.domain.EntitlementStates.notice(entitlement.orEmpty(), null)
            ?: ArticalsStrings.Subscription.readOnlyBody,
        tone = BannerTone.Warning,
        modifier = Modifier.padding(horizontal = Spacing.screen),
        action = { TextButton(onClick = onOpen) { Text(ArticalsStrings.Subscription.title) } },
    )
}

@Composable
private fun Guarded(
    navController: NavHostController,
    state: RootState,
    route: String,
    content: @Composable () -> Unit,
) {
    if (Routes.canOpen(route, state.role, state.isPlatformAdmin)) {
        content()
    } else {
        NoAccessScreen(onBack = { navController.popBackStack() })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppTopBar(
    title: String,
    subtitle: String?,
    isDemo: Boolean,
    onOpenNotifications: () -> Unit,
) {
    val flow = remember {
        SupportLocator.notificationsOrNull()?.notifications ?: MutableStateFlow(emptyList<AppNotificationDto>())
    }
    val notices by flow.collectAsStateWithLifecycle()
    val unread = notices.count { !it.read }

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        title = {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        actions = {
            if (isDemo) DemoBadge()
            IconButton(onClick = onOpenNotifications) {
                BadgedBox(badge = { if (unread > 0) Badge { Text(unread.toString()) } }) {
                    Icon(
                        imageVector = Icons.Default.Notifications,
                        contentDescription = ArticalsStrings.Settings.notifications,
                    )
                }
            }
        },
    )
}

/**
 * The tabs, which depend on who is holding the phone.
 *
 * A rider gets their deliveries and nothing else — every other tab would land on a
 * refusal, and a tab that refuses is worse than no tab. Staff get the day, the
 * deliveries, and everything else.
 */
@Composable
private fun ShellBottomBar(
    navController: NavHostController,
    role: String?,
    currentRoute: String?,
) {
    val tabs = if (role == Roles.Rider) {
        listOf(Tab(Routes.RiderHome, ArticalsStrings.Shell.tabMyJobs, Icons.Default.LocalShipping))
    } else {
        listOf(
            Tab(Routes.Dashboard, ArticalsStrings.Shell.tabToday, Icons.Default.Today),
            Tab(Routes.Orders, ArticalsStrings.Shell.tabOrders, Icons.Default.LocalShipping),
            Tab(Routes.calculator(), ArticalsStrings.Shell.tabPrice, Icons.Default.Calculate),
            Tab(Routes.Settings, ArticalsStrings.Shell.tabMore, Icons.Default.MoreHoriz),
        )
    }

    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        tabs.forEach { tab ->
            val selected = currentRoute
                ?.substringBefore('?')
                ?.substringBefore("/{") == tab.route
            NavigationBarItem(
                selected = selected,
                onClick = {
                    if (!selected) {
                        navController.navigate(tab.route) {
                            popUpTo(Routes.Dashboard) { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                },
                icon = { Icon(imageVector = tab.icon, contentDescription = null) },
                label = { Text(tab.label) },
            )
        }
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

/**
 * The demo panel.
 *
 * Appearance is written to the *real* device preferences, so what the panel does is
 * what a preference would do — and the panel's own state comes from those preferences
 * rather than from a second copy of them.
 */
@Composable
private fun DemoPanelHost(
    appPreferences: AppPreferences,
    onDismiss: () -> Unit,
) {
    val controls = SupportLocator.diagnosticsOrNull() ?: return
    val panelState by controls.state.collectAsStateWithLifecycle()
    val fontScale by appPreferences.fontScale.collectAsStateWithLifecycle()
    val darkMode by appPreferences.darkMode.collectAsStateWithLifecycle()

    val state = panelState.copy(fontScale = fontScale, darkMode = darkMode)

    DemoControlsSheet(
        state = state,
        onNetworkChange = controls::setNetwork,
        onLatencyChange = controls::setLatency,
        onErrorChange = controls::forceNextError,
        onEntitlementChange = controls::setEntitlement,
        onLoadEmptyCompany = controls::loadEmptyCompany,
        onReset = controls::reset,
        onDarkModeChange = { enabled ->
            appPreferences.setDarkMode(enabled)
            controls.setDarkMode(enabled)
        },
        onFontScaleChange = { scale ->
            appPreferences.setFontScale(scale)
            controls.setFontScale(scale)
        },
        onDismiss = onDismiss,
    )
}
