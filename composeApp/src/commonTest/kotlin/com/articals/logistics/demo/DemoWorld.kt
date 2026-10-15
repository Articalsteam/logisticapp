package com.articals.logistics.demo

/**
 * A fresh demo world for one test.
 *
 * The tests build the demo graph by hand rather than through Koin: same three steps the
 * module takes (controls, store, seed), no containers to tear down, and no latency —
 * a test that sleeps to prove a screen's spinner would be testing the build machine.
 *
 * Every test gets its own world, so one test's delivery is never another's.
 */
class DemoWorld(latencyMillis: Long = 0L) {

    val controls = DemoControls(initialLatencyMillis = latencyMillis)

    val store: DemoStore = DemoStore(controls = controls).also { store ->
        store.controls.commands = store
        DemoSeed.populate(store)
    }

    val session = DemoSessionRepository(store = store)
    val companies = DemoCompanyRepository(store = store)
    val members = DemoMembersRepository(store = store)
    val customers = DemoCustomersRepository(store = store)
    val riders = DemoRidersRepository(store = store)
    val pricing = DemoPricingRepository(store = store)
    val orders = DemoOrdersRepository(store = store)
    val expenses = DemoExpensesRepository(store = store)
    val insights = DemoInsightsRepository(store = store)
    val tracking = DemoTrackingRepository(store = store)
    val billing = DemoBillingRepository(store = store)
    val admin = DemoPlatformAdminRepository(store = store)
    val storage = DemoStorageRepository(store = store)

    val simulator = DemoQuoteSimulator()
}
