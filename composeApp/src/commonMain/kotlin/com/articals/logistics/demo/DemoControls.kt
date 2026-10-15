package com.articals.logistics.demo

import com.articals.logistics.core.AppError
import com.articals.logistics.data.api.DemoNetworkMode
import com.articals.logistics.data.api.DiagnosticsControls
import com.articals.logistics.data.api.DiagnosticsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Commands the control panel sends to the demo data layer.
 *
 * Implemented by [DemoStore]; kept as an interface so the panel does not need a
 * reference to the store and the store does not need to know about the panel.
 */
interface DemoDataCommands {
    fun loadEmptyCompany()
    fun setEntitlementOverride(entitlement: String?)
    fun reset()
}

/**
 * The simulator behind the demo: latency, network conditions and forced errors.
 *
 * Every demo repository call goes through [simulateCall], which is why "switch
 * simulated network to offline" makes every screen behave the way it would on a
 * train — including the retry affordances and the offline copy.
 *
 * Latency is configurable (and zero in tests) so that loading skeletons can be seen
 * on purpose rather than by luck.
 */
class DemoControls(
    initialLatencyMillis: Long = DEFAULT_LATENCY_MILLIS,
    private val random: DemoRandom = DemoRandom(7L),
) : DiagnosticsControls {

    private val mutableState = MutableStateFlow(DiagnosticsState(latencyMillis = initialLatencyMillis))
    override val state: StateFlow<DiagnosticsState> = mutableState.asStateFlow()

    /** Set by [DemoStore] once it exists, to break the construction cycle. */
    internal var commands: DemoDataCommands? = null

    override fun setNetwork(mode: DemoNetworkMode) {
        mutableState.update { it.copy(network = mode) }
    }

    override fun forceNextError(error: AppError?) {
        mutableState.update { it.copy(forcedError = error) }
    }

    override fun loadEmptyCompany() {
        commands?.loadEmptyCompany()
        mutableState.update { it.copy(emptyCompanyLoaded = true, dataVersion = it.dataVersion + 1) }
    }

    override fun setEntitlement(entitlement: String?) {
        commands?.setEntitlementOverride(entitlement)
        mutableState.update { it.copy(entitlementOverride = entitlement, dataVersion = it.dataVersion + 1) }
    }

    override fun reset() {
        commands?.reset()
        mutableState.update {
            it.copy(
                emptyCompanyLoaded = false,
                forcedError = null,
                network = DemoNetworkMode.NORMAL,
                // The store drops its entitlement override too, so leaving this behind
                // would show a state chip for a company that no longer has it.
                entitlementOverride = null,
                dataVersion = it.dataVersion + 1,
            )
        }
    }

    override fun setFontScale(scale: Float) {
        mutableState.update { it.copy(fontScale = scale.coerceIn(0.85f, 1.6f)) }
    }

    override fun setDarkMode(enabled: Boolean?) {
        mutableState.update { it.copy(darkMode = enabled) }
    }

    fun setLatency(millis: Long) {
        mutableState.update { it.copy(latencyMillis = millis.coerceIn(0L, 5_000L)) }
    }

    /**
     * Applies the simulated network conditions to a call.
     *
     * A forced error is consumed by exactly one call: that is what makes "force an
     * error on the next call" usable while clicking through a flow.
     */
    suspend fun simulateCall() {
        val snapshot = mutableState.value
        val latency = when (snapshot.network) {
            DemoNetworkMode.NORMAL -> snapshot.latencyMillis
            DemoNetworkMode.SLOW -> snapshot.latencyMillis * 3
            DemoNetworkMode.OFFLINE -> 250L
            DemoNetworkMode.FLAKY -> snapshot.latencyMillis
        }
        if (latency > 0) delay(latency)

        snapshot.forcedError?.let { forced ->
            mutableState.update { it.copy(forcedError = null) }
            throw DemoFailure(forced)
        }

        when (snapshot.network) {
            DemoNetworkMode.OFFLINE -> throw DemoFailure(AppError.Offline(cause = null))
            DemoNetworkMode.FLAKY ->
                if (random.chance(25)) throw DemoFailure(AppError.Server(detail = "demo_simulated_failure"))
            else -> Unit
        }
    }

    /** Progress ticks for the simulated upload, honest about being a simulation. */
    fun progressTicks(steps: Int = 12): List<Double> = (1..steps).map { it.toDouble() / steps.toDouble() }

    companion object {
        const val DEFAULT_LATENCY_MILLIS: Long = 450L
    }
}
