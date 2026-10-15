package com.articals.logistics.ui.screen.pricingsettings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.articals.logistics.core.AppError
import com.articals.logistics.core.AppResult
import com.articals.logistics.core.bpsToEditablePercent
import com.articals.logistics.core.distanceToEditableKilometres
import com.articals.logistics.core.minorToMajorString
import com.articals.logistics.core.parseAmountToMinorOrNull
import com.articals.logistics.core.parseDistanceToMetresOrNull
import com.articals.logistics.core.parsePercentToBpsOrNull
import com.articals.logistics.data.api.PricingRepository
import com.articals.logistics.data.dto.DistanceBandDto
import com.articals.logistics.data.dto.PricingConfigDto
import com.articals.logistics.data.dto.RiderCostConfigDto
import com.articals.logistics.data.dto.SurchargeDto
import com.articals.logistics.data.dto.ZoneRuleDto
import com.articals.logistics.domain.PricingMethods
import com.articals.logistics.domain.RiderCostMethods
import com.articals.logistics.domain.RoundingModes
import com.articals.logistics.ui.text.ArticalsStrings
import com.articals.logistics.util.Idempotency
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One distance band, as typed. An open-ended band has no upper limit. */
data class BandField(
    val upToKm: String = "",
    val charge: String = "",
    val label: String = "",
    val openEnded: Boolean = false,
) {
    fun toDtoOrNull(): DistanceBandDto? {
        val charge = parseAmountToMinorOrNull(charge) ?: return null
        val metres = if (openEnded) null else parseDistanceToMetresOrNull(upToKm) ?: return null
        return DistanceBandDto(
            upToMetres = metres,
            chargeMinor = charge,
            label = label.trim().takeIf { it.isNotEmpty() },
        )
    }

    companion object {
        fun of(band: DistanceBandDto) = BandField(
            upToKm = distanceToEditableKilometres(band.upToMetres),
            charge = minorToMajorString(band.chargeMinor),
            label = band.label.orEmpty(),
            openEnded = band.upToMetres == null,
        )

        fun openEnded() = BandField(openEnded = true)
    }
}

/** One area-to-area price. */
data class ZoneRuleField(
    val fromZoneId: String = "",
    val toZoneId: String = "",
    val charge: String = "",
    val adjustment: String = "",
) {
    fun toDtoOrNull(): ZoneRuleDto? {
        val charge = parseAmountToMinorOrNull(charge) ?: return null
        if (fromZoneId.isBlank() || toZoneId.isBlank()) return null
        return ZoneRuleDto(
            fromZoneId = fromZoneId,
            toZoneId = toZoneId,
            chargeMinor = charge,
            adjustmentMinor = parseAmountToMinorOrNull(adjustment),
        )
    }

    companion object {
        fun of(rule: ZoneRuleDto) = ZoneRuleField(
            fromZoneId = rule.fromZoneId,
            toZoneId = rule.toZoneId,
            charge = minorToMajorString(rule.chargeMinor),
            adjustment = rule.adjustmentMinor?.let { minorToMajorString(it) }.orEmpty(),
        )
    }
}

/** One surcharge: a fixed amount or a percentage, optionally only on some service levels. */
data class SurchargeField(
    val id: String = "",
    val label: String = "",
    val kind: String = "flat",
    val amount: String = "",
    val percent: String = "",
    val appliesTo: List<String> = emptyList(),
) {
    fun toDtoOrNull(): SurchargeDto? {
        if (label.isBlank()) return null
        val isFlat = kind == "flat"
        val amountMinor = if (isFlat) parseAmountToMinorOrNull(amount) ?: return null else null
        val bps = if (isFlat) null else parsePercentToBpsOrNull(percent) ?: return null
        return SurchargeDto(
            id = id.ifBlank { Idempotency.newKey("surcharge") },
            label = label.trim(),
            kind = kind,
            amountMinor = amountMinor,
            percentageBps = bps,
            appliesToServiceLevels = appliesTo,
        )
    }

    companion object {
        fun of(surcharge: SurchargeDto) = SurchargeField(
            id = surcharge.id,
            label = surcharge.label,
            kind = surcharge.kind,
            amount = surcharge.amountMinor?.let { minorToMajorString(it) }.orEmpty(),
            percent = surcharge.percentageBps?.let { bpsToEditablePercent(it) }.orEmpty(),
            appliesTo = surcharge.appliesToServiceLevels,
        )

        fun create() = SurchargeField(id = Idempotency.newKey("surcharge"))
    }
}

/** What the company pays the rider, as typed. */
data class RiderCostFields(
    val method: String = RiderCostMethods.Fixed,
    val perKmRate: String = "",
    val defaultFee: String = "",
    val historicalAverage: String = "",
    val minimumCost: String = "",
    val bands: List<BandField> = emptyList(),
    val zoneRules: List<ZoneRuleField> = emptyList(),
) {
    fun toDto(): RiderCostConfigDto = RiderCostConfigDto(
        method = method,
        perKmRateMinor = parseAmountToMinorOrNull(perKmRate),
        bands = bands.mapNotNull { it.toDtoOrNull() },
        zoneRules = zoneRules.mapNotNull { it.toDtoOrNull() },
        defaultFeeMinor = parseAmountToMinorOrNull(defaultFee),
        historicalAverageMinor = parseAmountToMinorOrNull(historicalAverage),
        historicalSampleSize = null,
        minimumCostMinor = parseAmountToMinorOrNull(minimumCost),
    )

    companion object {
        fun of(config: RiderCostConfigDto) = RiderCostFields(
            method = config.method,
            perKmRate = config.perKmRateMinor?.let { minorToMajorString(it) }.orEmpty(),
            defaultFee = config.defaultFeeMinor?.let { minorToMajorString(it) }.orEmpty(),
            historicalAverage = config.historicalAverageMinor?.let { minorToMajorString(it) }.orEmpty(),
            minimumCost = config.minimumCostMinor?.let { minorToMajorString(it) }.orEmpty(),
            bands = config.bands.map { BandField.of(it) },
            zoneRules = config.zoneRules.map { ZoneRuleField.of(it) },
        )
    }
}

/**
 * The rates, as typed.
 *
 * Text in, records out: every money field is a string until it is parsed once by
 * `parseAmountToMinorOrNull`, and nothing here adds two prices together. The form's job
 * is to collect what the company wants to charge; deciding what a delivery costs is the
 * pricing engine's job (ADR-002).
 */
data class RatesDraft(
    val name: String = "Standard pricing",
    val method: String = PricingMethods.BasePlusDistance,
    val baseCharge: String = "",
    val perKmRate: String = "",
    val minimumCharge: String = "",
    val maximumCharge: String = "",
    val roundingMode: String = RoundingModes.Nearest,
    val roundingIncrement: String = "",
    val fallbackToDistanceWhenZoneMissing: Boolean = true,
    val bands: List<BandField> = emptyList(),
    val zoneRules: List<ZoneRuleField> = emptyList(),
    val surcharges: List<SurchargeField> = emptyList(),
    val riderCost: RiderCostFields = RiderCostFields(),
    val targetProfitAmount: String = "",
    val targetProfitMarginPercent: String = "",
) {
    fun toConfig(existing: PricingConfigDto): PricingConfigDto = existing.copy(
        name = name.trim().ifEmpty { existing.name },
        method = method,
        baseChargeMinor = parseAmountToMinorOrNull(baseCharge) ?: 0L,
        perKmRateMinor = parseAmountToMinorOrNull(perKmRate) ?: 0L,
        distanceBands = bands.mapNotNull { it.toDtoOrNull() },
        zoneRules = zoneRules.mapNotNull { it.toDtoOrNull() },
        fallbackToDistanceWhenZoneMissing = fallbackToDistanceWhenZoneMissing,
        minimumChargeMinor = parseAmountToMinorOrNull(minimumCharge) ?: 0L,
        maximumChargeMinor = parseAmountToMinorOrNull(maximumCharge),
        roundingIncrementMinor = parseAmountToMinorOrNull(roundingIncrement) ?: 0L,
        roundingMode = roundingMode,
        surcharges = surcharges.mapNotNull { it.toDtoOrNull() },
        riderCost = riderCost.toDto(),
        targetProfitAmountMinor = parseAmountToMinorOrNull(targetProfitAmount),
        targetProfitMarginBps = parsePercentToBpsOrNull(targetProfitMarginPercent),
    )

    companion object {
        fun of(config: PricingConfigDto) = RatesDraft(
            name = config.name,
            method = config.method,
            baseCharge = minorToMajorString(config.baseChargeMinor),
            perKmRate = minorToMajorString(config.perKmRateMinor),
            minimumCharge = minorToMajorString(config.minimumChargeMinor),
            maximumCharge = config.maximumChargeMinor?.let { minorToMajorString(it) }.orEmpty(),
            roundingMode = config.roundingMode,
            roundingIncrement = if (config.roundingIncrementMinor == 0L) {
                ""
            } else {
                minorToMajorString(config.roundingIncrementMinor)
            },
            fallbackToDistanceWhenZoneMissing = config.fallbackToDistanceWhenZoneMissing,
            bands = config.distanceBands.map { BandField.of(it) },
            zoneRules = config.zoneRules.map { ZoneRuleField.of(it) },
            surcharges = config.surcharges.map { SurchargeField.of(it) },
            riderCost = RiderCostFields.of(config.riderCost),
            targetProfitAmount = config.targetProfitAmountMinor?.let { minorToMajorString(it) }.orEmpty(),
            targetProfitMarginPercent = config.targetProfitMarginBps?.let { bpsToEditablePercent(it) }.orEmpty(),
        )
    }
}

data class PricingSettingsState(
    val config: PricingConfigDto? = null,
    val draft: RatesDraft? = null,
    val loading: Boolean = true,
    val error: AppError? = null,
    val notice: String? = null,
    val saving: Boolean = false,
    /** What stops this from being saved, in the words the company will read. */
    val problems: List<String> = emptyList(),
    /** True once the form differs from the saved rates. */
    val dirty: Boolean = false,
) {
    val canSave: Boolean get() = draft != null && dirty && problems.isEmpty() && !saving

    val version: Int get() = config?.version ?: 1
}

/**
 * The company's rates.
 *
 * This screen edits the *whole* rate card and saves it as a new version. Three rules
 * shape it, all of them the backend's:
 *
 *  * the version is handed back by the server, never chosen here — an older delivery
 *    keeps the price it was quoted at, so rates are history, not a setting;
 *  * the form refuses exactly what the server refuses (a band set without an upper
 *    limit, two bands ending at the same distance, a zone method with no zone prices,
 *    a rider costing method with nothing to cost with), so the error arrives before the
 *    save rather than after it;
 *  * nothing is priced here. Saving rates changes what *future* quotes will say.
 */
class PricingSettingsViewModel(
    private val pricing: PricingRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(PricingSettingsState())
    val state: StateFlow<PricingSettingsState> = mutableState.asStateFlow()

    private var configId: String? = null

    fun load(tenantId: String) {
        if (configId != null) return
        viewModelScope.launch {
            pricing.observeActivePricingConfigId(tenantId).collect { id ->
                if (id == null) {
                    mutableState.update {
                        it.copy(
                            loading = false,
                            error = AppError.NotFound(
                                detail = "pricing_config_missing",
                                message = ArticalsStrings.PricingSettings.missing,
                            ),
                        )
                    }
                    return@collect
                }
                if (configId == id) return@collect
                configId = id
                observe(configId.orEmpty())
            }
        }
    }

    private fun observe(configId: String) {
        viewModelScope.launch {
            pricing.observeActivePricingConfig(configId).collect { config ->
                mutableState.update { current ->
                    if (config == null) {
                        current.copy(
                            loading = false,
                            error = AppError.NotFound(
                                detail = "pricing_config_missing",
                                message = ArticalsStrings.PricingSettings.missing,
                            ),
                        )
                    } else {
                        // A live update replaces the draft only when there is nothing
                        // unsaved: nobody should lose typing to somebody else's save.
                        current.copy(
                            config = config,
                            draft = if (current.dirty) current.draft else RatesDraft.of(config),
                            loading = false,
                            error = null,
                            problems = current.draft
                                ?.takeIf { current.dirty }
                                ?.let { problemsIn(it) }
                                .orEmpty(),
                        )
                    }
                }
            }
        }
    }

    fun updateDraft(draft: RatesDraft) = mutableState.update {
        it.copy(
            draft = draft,
            dirty = true,
            notice = null,
            problems = problemsIn(draft),
        )
    }

    fun discardChanges() = mutableState.update {
        val config = it.config ?: return@update it
        it.copy(draft = RatesDraft.of(config), dirty = false, problems = emptyList())
    }

    fun clearNotice() = mutableState.update { it.copy(notice = null) }

    fun save() {
        val config = mutableState.value.config ?: return
        val draft = mutableState.value.draft ?: return
        val problems = problemsIn(draft)
        if (problems.isNotEmpty()) {
            mutableState.update { it.copy(problems = problems) }
            return
        }

        mutableState.update { it.copy(saving = true, error = null, notice = null) }
        viewModelScope.launch {
            when (val result = pricing.savePricingRules(draft.toConfig(config))) {
                is AppResult.Failure -> mutableState.update {
                    it.copy(saving = false, error = result.error)
                }

                is AppResult.Success -> mutableState.update {
                    it.copy(
                        saving = false,
                        dirty = false,
                        notice = ArticalsStrings.PricingSettings.savedWithVersion(result.value.version),
                    )
                }
            }
        }
    }

    /**
     * Everything the server would refuse, checked here in the same order it checks.
     *
     * It is duplicated on purpose: the server's refusal is the truth, and this copy
     * exists so the person sees it while the form is still open.
     */
    private fun problemsIn(draft: RatesDraft): List<String> {
        val problems = mutableListOf<String>()

        if (draft.name.trim().isEmpty()) problems += ArticalsStrings.PricingSettings.nameRequired

        when (draft.method) {
            PricingMethods.DistanceBands ->
                if (draft.bands.isEmpty()) problems += ArticalsStrings.PricingSettings.bandsRequired

            PricingMethods.ZoneBased ->
                if (draft.zoneRules.isEmpty()) problems += ArticalsStrings.PricingSettings.zoneRulesRequired
        }
        if (draft.method == PricingMethods.BasePlusDistance || draft.method == PricingMethods.Hybrid) {
            if ((parseAmountToMinorOrNull(draft.perKmRate) ?: 0L) == 0L) {
                problems += ArticalsStrings.PricingSettings.perKmRequired
            }
        }

        problems += bandProblems(draft.bands, ArticalsStrings.PricingSettings.bandsLabel)

        val minimum = parseAmountToMinorOrNull(draft.minimumCharge) ?: 0L
        val maximum = parseAmountToMinorOrNull(draft.maximumCharge)
        if (maximum != null && maximum < minimum) problems += ArticalsStrings.PricingSettings.maximumBelowMinimum

        val increment = parseAmountToMinorOrNull(draft.roundingIncrement) ?: 0L
        if (increment > 100_000L) problems += ArticalsStrings.PricingSettings.roundingTooLarge

        val marginBps = parsePercentToBpsOrNull(draft.targetProfitMarginPercent)
        if (marginBps != null && marginBps >= 10_000) problems += ArticalsStrings.PricingSettings.marginTooHigh

        problems += riderCostProblems(draft.riderCost)
        problems += surchargeProblems(draft.surcharges)

        return problems
    }

    private fun bandProblems(bands: List<BandField>, label: String): List<String> {
        val problems = mutableListOf<String>()
        val openEnded = bands.count { it.openEnded }
        if (openEnded > 1) problems += ArticalsStrings.PricingSettings.multipleCatchAll(label)

        val bounds = bands.filterNot { it.openEnded }
            .mapNotNull { parseDistanceToMetresOrNull(it.upToKm) }
        if (bounds.size != bounds.distinct().size) {
            problems += ArticalsStrings.PricingSettings.duplicateBounds(label)
        }
        if (bands.any { it.toDtoOrNull() == null }) {
            problems += ArticalsStrings.PricingSettings.bandIncomplete(label)
        }
        return problems
    }

    private fun riderCostProblems(rider: RiderCostFields): List<String> {
        val problems = mutableListOf<String>()
        when (rider.method) {
            RiderCostMethods.DistanceRate ->
                if (parseAmountToMinorOrNull(rider.perKmRate) == null) {
                    problems += ArticalsStrings.PricingSettings.riderRateRequired
                }

            RiderCostMethods.DistanceBands ->
                if (rider.bands.isEmpty()) {
                    problems += ArticalsStrings.PricingSettings.riderBandsRequired
                } else {
                    problems += bandProblems(rider.bands, ArticalsStrings.PricingSettings.riderBandsLabel)
                }

            RiderCostMethods.ZoneBased ->
                if (rider.zoneRules.isEmpty()) {
                    problems += ArticalsStrings.PricingSettings.riderZonesRequired
                }

            RiderCostMethods.Fixed ->
                if (parseAmountToMinorOrNull(rider.defaultFee) == null) {
                    problems += ArticalsStrings.PricingSettings.riderFixedRequired
                }

            RiderCostMethods.HistoricalAverage ->
                if (parseAmountToMinorOrNull(rider.historicalAverage) == null) {
                    problems += ArticalsStrings.PricingSettings.riderHistoryRequired
                }
        }
        return problems
    }

    private fun surchargeProblems(surcharges: List<SurchargeField>): List<String> {
        val problems = mutableListOf<String>()
        surcharges.forEach { surcharge ->
            if (surcharge.label.isBlank()) {
                problems += ArticalsStrings.PricingSettings.surchargeLabelRequired
                return@forEach
            }
            if (surcharge.kind == "flat") {
                if ((parseAmountToMinorOrNull(surcharge.amount) ?: 0L) <= 0L) {
                    problems += ArticalsStrings.PricingSettings.surchargeAmountRequired(surcharge.label)
                }
            } else {
                if ((parsePercentToBpsOrNull(surcharge.percent) ?: 0) <= 0) {
                    problems += ArticalsStrings.PricingSettings.surchargePercentRequired(surcharge.label)
                }
            }
        }
        return problems
    }
}
