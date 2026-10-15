package com.articals.logistics.demo

import com.articals.logistics.data.dto.AdditionalCostDto
import com.articals.logistics.data.dto.DistanceBandDto
import com.articals.logistics.data.dto.PricingConfigDto
import com.articals.logistics.data.dto.PricingSnapshotDto
import com.articals.logistics.data.dto.QuoteDistanceDto
import com.articals.logistics.data.dto.QuoteDto
import com.articals.logistics.data.dto.QuoteLineDto
import com.articals.logistics.data.dto.QuoteRequestDto
import com.articals.logistics.data.dto.QuoteResponseDto
import com.articals.logistics.data.dto.QuoteWarningDto
import com.articals.logistics.data.dto.RiderCostEstimateDto
import com.articals.logistics.data.dto.RoundingDto
import com.articals.logistics.data.dto.TargetProfitDto
import com.articals.logistics.data.dto.ZoneRuleDto

/**
 * The demo's price simulator.
 *
 * **This is the one place where the client computes money, and it is an exception
 * recorded as ADR-013.** In production the pricing engine lives only in the backend
 * (ADR-002), and nothing here is reachable from Firebase mode: the demo module
 * installs this class as the `PricingRepository`, and the Firebase module installs
 * the real callable instead.
 *
 * It exists because demo mode has to run with no network and no Firebase project at
 * all, and a pricing calculator whose numbers never change would verify nothing. It
 * is a re-implementation of the published behaviour — verified against the canonical
 * example and the contract file's warning vocabulary — not the source of truth.
 *
 * What it deliberately does NOT do: invent a distance. If the two locations have no
 * known distance and none was entered, it returns `needs_input` with the
 * review-blocking `distance_required` warning, exactly like the backend, so the
 * manual-distance path is exercised rather than hidden.
 */
class DemoQuoteSimulator(
    private val distanceTable: DemoDistanceTable = DemoDistanceTable,
) {
    fun quote(
        config: PricingConfigDto,
        request: QuoteRequestDto,
        nowIso: String,
    ): QuoteResponseDto {
        val warnings = mutableListOf<QuoteWarningDto>()

        val distance = resolveDistance(request, warnings)
        if (distance == null) {
            return QuoteResponseDto(
                status = "needs_input",
                quote = null,
                warnings = warnings,
                quoteId = null,
                distanceFallbackReason = null,
                routingProvider = "demo",
            )
        }

        val metres = distance.metres
        val additionalCosts = request.additionalCosts
        val duplicateIds = additionalCosts.groupBy { it.id }.filterValues { it.size > 1 }.keys
        if (duplicateIds.isNotEmpty()) {
            warnings += warning(
                "duplicate_additional_cost",
                "warning",
                "The same extra cost was added twice (${duplicateIds.joinToString()}).",
            )
        }

        // --- method lines ---------------------------------------------------
        val lines = mutableListOf<QuoteLineDto>()
        var linesTotal = 0L
        var distanceFallbackReason: String? = null

        when (config.method) {
            "flat_rate" -> {
                lines += QuoteLineDto(
                    code = "base",
                    label = "Flat delivery charge",
                    amountMinor = config.baseChargeMinor,
                )
                linesTotal += config.baseChargeMinor
            }

            "distance_bands" -> {
                val band = matchingBand(config.distanceBands, metres)
                if (band == null) {
                    warnings += warning(
                        "no_matching_band",
                        "blocker",
                        "No distance band covers ${formatKm(metres)} km. Add a band that does, or price this delivery by hand.",
                    )
                    return needsInput(warnings)
                }
                lines += QuoteLineDto(
                    code = "band",
                    label = band.label ?: "Distance band up to ${formatKm(band.upToMetres ?: metres)} km",
                    amountMinor = band.chargeMinor,
                )
                linesTotal += band.chargeMinor
            }

            "zone_to_zone" -> {
                val rule = matchingZoneRule(config.zoneRules, request.pickup.zoneId, request.destination.zoneId)
                if (rule == null) {
                    if (config.fallbackToDistanceWhenZoneMissing) {
                        warnings += warning(
                            "zone_rule_missing_fallback",
                            "warning",
                            "There is no charge for this zone pair, so the distance rate was used instead.",
                        )
                        distanceFallbackReason = "zone_rule_missing"
                        val amount = config.baseChargeMinor + scaleMinor(config.perKmRateMinor, metres, 1_000)
                        lines += QuoteLineDto(
                            code = "zone_fallback_distance",
                            label = "Base charge + distance (zone rule missing)",
                            amountMinor = amount,
                        )
                        linesTotal += amount
                    } else {
                        warnings += warning(
                            "zone_rule_missing",
                            "blocker",
                            "There is no charge for this zone pair, and this company prices by zone only.",
                        )
                        return needsInput(warnings)
                    }
                } else {
                    lines += QuoteLineDto(
                        code = "zone_pair",
                        label = "Zone pair charge",
                        amountMinor = rule.chargeMinor,
                    )
                    linesTotal += rule.chargeMinor
                    if (rule.adjustmentMinor == null) {
                        warnings += warning(
                            "zone_adjustment_missing",
                            "info",
                            "No adjustment is configured for this zone pair.",
                        )
                    } else if (rule.adjustmentMinor != 0L) {
                        lines += QuoteLineDto(
                            code = "zone_adjustment",
                            label = "Zone adjustment",
                            amountMinor = rule.adjustmentMinor,
                        )
                        linesTotal += rule.adjustmentMinor
                    }
                }
            }

            "hybrid" -> {
                lines += QuoteLineDto(code = "base", label = "Base charge", amountMinor = config.baseChargeMinor)
                linesTotal += config.baseChargeMinor
                val distanceAmount = scaleMinor(config.perKmRateMinor, metres, 1_000)
                lines += QuoteLineDto(
                    code = "distance",
                    label = "Distance (${formatKm(metres)} km)",
                    amountMinor = distanceAmount,
                )
                linesTotal += distanceAmount
                matchingBand(config.distanceBands, metres)?.let { band ->
                    lines += QuoteLineDto(
                        code = "band",
                        label = band.label ?: "Distance band",
                        amountMinor = band.chargeMinor,
                    )
                    linesTotal += band.chargeMinor
                }
            }

            else -> {
                // base_plus_distance, and the safe default for an unknown method.
                lines += QuoteLineDto(code = "base", label = "Base charge", amountMinor = config.baseChargeMinor)
                linesTotal += config.baseChargeMinor
                val distanceAmount = scaleMinor(config.perKmRateMinor, metres, 1_000)
                lines += QuoteLineDto(
                    code = "distance",
                    label = "Distance (${formatKm(metres)} km)",
                    amountMinor = distanceAmount,
                )
                linesTotal += distanceAmount
            }
        }

        // --- surcharges -----------------------------------------------------
        for (surcharge in config.surcharges) {
            val appliesToLevels = surcharge.appliesToServiceLevels
            if (appliesToLevels.isNotEmpty() && request.serviceLevel !in appliesToLevels) continue
            val amount = when (surcharge.kind) {
                "percentage" -> scaleMinor(linesTotal, (surcharge.percentageBps ?: 0).toLong(), 10_000)
                else -> surcharge.amountMinor ?: 0L
            }
            if (amount == 0L) continue
            lines += QuoteLineDto(code = surcharge.codeKey(), label = surcharge.label, amountMinor = amount)
            linesTotal += amount
        }

        val computedCharge = linesTotal

        // --- rider cost and delivery extras ---------------------------------
        val riderCost = estimateRiderCost(config, request, metres, computedCharge, warnings)
        val additionalDeliveryCosts = additionalCosts
            .filter { it.kind != "operating" }
            .sumOf { it.amountMinor }
        val totalDeliveryCost = riderCost.amountMinor + additionalDeliveryCosts

        // --- target profit back-solve --------------------------------------
        // The target is solved against the real cost, so "make at least ₦X on this
        // delivery" produces a price rather than a wish.
        val target = resolveTarget(config, request, totalDeliveryCost)
        var recommended = computedCharge
        target.requiredChargeMinor?.let { required ->
            if (required > recommended) recommended = required
        }

        // --- manual override ------------------------------------------------
        var manualOverrideApplied = false
        var minimumWaived = false
        request.customerPriceOverride?.let { override ->
            recommended = override.amountMinor
            manualOverrideApplied = true
            warnings += warning(
                "manual_price_override",
                "warning",
                "The price was set by hand: ${override.reason}",
            )
            if (override.amountMinor < config.minimumChargeMinor && override.allowBelowMinimum == true) {
                minimumWaived = true
            }
        }

        // --- minimum / maximum ---------------------------------------------
        var appliedMinimum = false
        var appliedMaximum = false
        if (!minimumWaived && recommended < config.minimumChargeMinor) {
            recommended = config.minimumChargeMinor
            appliedMinimum = true
            warnings += warning("minimum_charge_applied", "info", "The company's minimum charge was applied.")
        }
        if (minimumWaived) {
            warnings += warning("minimum_charge_waived", "info", "The minimum charge was waived for this price.")
        }
        val maximum = config.maximumChargeMinor
        if (maximum != null && recommended > maximum) {
            recommended = maximum
            appliedMaximum = true
            warnings += warning("maximum_charge_applied", "info", "The company's maximum charge was applied.")
        }

        // --- rounding (once, at the end) ------------------------------------
        val roundingBefore = recommended
        var rounding: RoundingDto? = null
        if (config.roundingIncrementMinor > 0 && recommended % config.roundingIncrementMinor != 0L) {
            val rounded = roundToIncrement(recommended, config.roundingIncrementMinor, config.roundingMode)
            rounding = RoundingDto(
                incrementMinor = config.roundingIncrementMinor,
                beforeMinor = roundingBefore,
                afterMinor = rounded,
            )
            recommended = rounded
            warnings += warning(
                "rounded",
                "info",
                "Rounded to the nearest ${formatMinorPlain(config.roundingIncrementMinor)}.",
            )
        }

        // --- profit and margin ---------------------------------------------
        val profit = recommended - totalDeliveryCost
        val marginDefined = recommended > 0
        val marginBps = if (marginDefined) divideBps(profit, recommended) else 0

        if (recommended == 0L) {
            warnings += warning("zero_customer_charge", "warning", "This delivery would be free of charge.")
        }
        if (profit < 0) {
            warnings += warning(
                "price_below_cost",
                "warning",
                "The charge is below what this delivery costs (rider and extras).",
            )
        }

        val targetStatus = target.evaluate(profit, recommended)
        if (!targetStatus.satisfied) {
            warnings += warning(
                "below_target_profit",
                "warning",
                "This delivery is ${formatMinorPlain(targetStatus.shortfallMinor)} below the target the company set.",
            )
        }

        val requiresReview = warnings.any { it.severity == "blocker" } ||
            warnings.any { it.code in REVIEW_REQUIRED_CODES }

        val quote = QuoteDto(
            currency = config.currency,
            method = config.method,
            engineVersion = ENGINE_VERSION,
            lines = lines,
            distance = distance,
            distanceKm = metres.toDouble() / 1_000.0,
            computedCustomerChargeMinor = computedCharge,
            recommendedCustomerChargeMinor = recommended,
            linesTotalBeforeRounding = linesTotal,
            appliedMinimumCharge = appliedMinimum,
            appliedMaximumCharge = appliedMaximum,
            rounding = rounding,
            manualPriceOverrideApplied = manualOverrideApplied,
            estimatedRiderCost = riderCost,
            estimatedAdditionalDeliveryCostsMinor = additionalDeliveryCosts,
            estimatedTotalDeliveryCostMinor = totalDeliveryCost,
            estimatedProfitMinor = profit,
            estimatedMarginBps = marginBps,
            estimatedMarginIsDefined = marginDefined,
            targetProfit = targetStatus,
            warnings = warnings.toList(),
            requiresReviewBeforeAccepting = requiresReview,
            pricingSnapshot = PricingSnapshotDto(
                engineVersion = ENGINE_VERSION,
                pricingConfigId = config.id,
                pricingConfigVersion = config.version,
                pricingConfigName = config.name,
                currency = config.currency,
                capturedAt = nowIso,
            ),
            computedAt = nowIso,
        )

        return QuoteResponseDto(
            status = if (requiresReview) "needs_review" else "ok",
            quote = quote,
            warnings = warnings.toList(),
            quoteId = null,
            distanceFallbackReason = distanceFallbackReason,
            routingProvider = "demo",
        )
    }

    // -----------------------------------------------------------------------
    // Distance
    // -----------------------------------------------------------------------

    private fun resolveDistance(request: QuoteRequestDto, warnings: MutableList<QuoteWarningDto>): QuoteDistanceDto? {
        val manual = request.pickup.manualDistanceMetres ?: request.destination.manualDistanceMetres
        if (manual != null && manual > 0) {
            warnings += warning(
                "manual_distance",
                "warning",
                "The distance was entered by hand (${formatKm(manual)} km), which decides the price. " +
                    "Check it before accepting.",
            )
            return QuoteDistanceDto(
                metres = manual,
                source = "manual",
                provider = "demo",
                summary = "Distance entered by hand",
                durationSeconds = null,
                calculatedAt = null,
            )
        }

        val table = distanceTable.lookup(request.pickup, request.destination)
        if (table != null) {
            return QuoteDistanceDto(
                metres = table.metres,
                source = "zone_based",
                provider = "demo",
                summary = table.summary,
                durationSeconds = table.durationSeconds,
                calculatedAt = null,
            )
        }

        warnings += warning(
            "distance_required",
            "blocker",
            "No distance is known for these two addresses. Enter the distance to see a price.",
        )
        return null
    }

    // -----------------------------------------------------------------------
    // Rider cost
    // -----------------------------------------------------------------------

    private fun estimateRiderCost(
        config: PricingConfigDto,
        request: QuoteRequestDto,
        metres: Long,
        charge: Long,
        warnings: MutableList<QuoteWarningDto>,
    ): RiderCostEstimateDto {
        request.riderCostOverride?.let { override ->
            return RiderCostEstimateDto(
                amountMinor = override.amountMinor,
                method = "manual",
                confidence = "confirmed",
                note = override.reason,
            )
        }

        val riderCost = config.riderCost
        return when (riderCost.method) {
            "distance_rate" -> RiderCostEstimateDto(
                amountMinor = scaleMinor(riderCost.perKmRateMinor ?: 0L, metres, 1_000),
                method = "distance_rate",
                confidence = "estimated",
            )

            "distance_bands" -> {
                val band = matchingBand(riderCost.bands, metres)
                if (band != null) {
                    RiderCostEstimateDto(band.chargeMinor, "distance_bands", "estimated")
                } else {
                    unconfirmed(riderCost.method, riderCost.defaultFeeMinor ?: riderCost.historicalAverageMinor ?: 0L, warnings)
                }
            }

            "zone_based" -> {
                val rule = matchingZoneRule(riderCost.zoneRules, request.pickup.zoneId, request.destination.zoneId)
                if (rule != null) {
                    RiderCostEstimateDto(
                        amountMinor = rule.chargeMinor + (rule.adjustmentMinor ?: 0L),
                        method = "zone_based",
                        confidence = "estimated",
                    )
                } else {
                    unconfirmed(riderCost.method, riderCost.defaultFeeMinor ?: 0L, warnings)
                }
            }

            "historical_average" -> unconfirmed(
                method = riderCost.method,
                amount = riderCost.historicalAverageMinor ?: riderCost.defaultFeeMinor ?: 0L,
                warnings = warnings,
                note = riderCost.historicalSampleSize?.let { "Average of the last $it payments" },
            )

            "manual" -> unconfirmed(riderCost.method, 0L, warnings, "Entered by the dispatcher for each delivery")

            else -> {
                val amount = riderCost.defaultFeeMinor ?: 0L
                if (amount == 0L) unconfirmed(riderCost.method, 0L, warnings)
                else RiderCostEstimateDto(amount, "fixed", "estimated")
            }
        }
    }

    private fun unconfirmed(
        method: String,
        amount: Long,
        warnings: MutableList<QuoteWarningDto>,
        note: String? = null,
    ): RiderCostEstimateDto {
        warnings += warning(
            "rider_cost_unconfirmed",
            "warning",
            "What this delivery pays the rider is not confirmed. Review it before accepting.",
        )
        return RiderCostEstimateDto(
            amountMinor = amount,
            method = method,
            confidence = "unconfirmed",
            note = note,
        )
    }

    // -----------------------------------------------------------------------
    // Target profit
    // -----------------------------------------------------------------------

    private class TargetResolution(
        val kind: String,
        val value: Long,
        val requiredChargeMinor: Long?,
    ) {
        fun evaluate(profit: Long, charge: Long): TargetProfitDto {
            if (kind == "none") return TargetProfitDto()
            val satisfied = when (kind) {
                "amount" -> profit >= value
                else -> charge > 0 && divideBps(profit, charge) >= value
            }
            val shortfall = when {
                satisfied -> 0L
                kind == "amount" -> value - profit
                else -> requiredChargeFor(value, charge, profit) - charge
            }
            return TargetProfitDto(
                kind = kind,
                value = value,
                satisfied = satisfied,
                shortfallMinor = if (shortfall < 0) 0L else shortfall,
            )
        }
    }

    private fun resolveTarget(
        config: PricingConfigDto,
        request: QuoteRequestDto,
        costMinor: Long,
    ): TargetResolution {
        val override = request.targetProfitOverride
        val amount = override?.amountMinor ?: config.targetProfitAmountMinor
        val margin = override?.marginBps?.toLong() ?: config.targetProfitMarginBps?.toLong()
        // Margin wins when both are present, matching the engine's documented order.
        return when {
            margin != null && margin in 0..9_999 -> TargetResolution(
                kind = "margin",
                value = margin,
                requiredChargeMinor = requiredChargeForMargin(costMinor, margin),
            )
            amount != null && amount > 0 -> TargetResolution(
                kind = "amount",
                value = amount,
                requiredChargeMinor = costMinor + amount,
            )
            else -> TargetResolution("none", 0L, null)
        }
    }

    // -----------------------------------------------------------------------
    // Small numeric helpers (integer only — never a Float for money)
    // -----------------------------------------------------------------------

    private fun matchingBand(bands: List<DistanceBandDto>, metres: Long): DistanceBandDto? =
        bands.filter { it.upToMetres == null || metres <= it.upToMetres }.minByOrNull { it.upToMetres ?: Long.MAX_VALUE }

    private fun matchingZoneRule(rules: List<ZoneRuleDto>, from: String?, to: String?): ZoneRuleDto? {
        if (from == null || to == null) return null
        return rules.firstOrNull { it.fromZoneId == from && it.toZoneId == to }
    }

    private fun roundToIncrement(value: Long, increment: Long, mode: String): Long {
        if (increment <= 0) return value
        val remainder = value % increment
        if (remainder == 0L) return value
        val base = value - remainder
        val next = base + increment
        return when (mode) {
            "up" -> next
            "down" -> base
            else -> if (remainder * 2 >= increment) next else base
        }
    }

    private fun needsInput(warnings: List<QuoteWarningDto>) = QuoteResponseDto(
        status = "needs_input",
        quote = null,
        warnings = warnings,
        quoteId = null,
        distanceFallbackReason = null,
        routingProvider = "demo",
    )

    private fun warning(code: String, severity: String, message: String) =
        QuoteWarningDto(code = code, severity = severity, message = message)

    private fun AdditionalCostDto.codeKey(): String = "extra_$id"

    private fun com.articals.logistics.data.dto.SurchargeDto.codeKey(): String = "surcharge_$id"

    private fun formatKm(metres: Long): String = (metres / 100).let { hundredths ->
        "${hundredths / 10}.${hundredths % 10}"
    }

    private fun formatMinorPlain(amountMinor: Long): String =
        "${amountMinor / 100}.${(amountMinor % 100).toString().padStart(2, '0')}"

    companion object {
        const val ENGINE_VERSION = "demo-simulator-1.0"

        /** Warning codes the backend treats as requiring a human acknowledgement. */
        val REVIEW_REQUIRED_CODES: Set<String> = com.articals.logistics.domain.QuoteWarningCodes.reviewRequired

        /** `value * numerator / denominator`, rounded half up. Integer only. */
        fun scaleMinor(value: Long, numerator: Long, denominator: Long): Long {
            if (denominator == 0L) return 0L
            val product = value * numerator
            val half = denominator / 2
            return if (product >= 0) (product + half) / denominator else -((-product + half) / denominator)
        }

        /** Basis points, rounded half up, for a profit/charge pair. */
        fun divideBps(part: Long, whole: Long): Int {
            if (whole == 0L) return 0
            val scaled = scaleMinor(part, 10_000L, whole)
            return scaled.coerceIn(-100_000L, 100_000L).toInt()
        }

        /** The charge that would achieve [marginBps] on the given cost. */
        fun requiredChargeForMargin(costMinor: Long, marginBps: Long): Long {
            if (marginBps >= 10_000L) return costMinor
            val numerator = costMinor * 10_000L
            val denominator = 10_000L - marginBps
            return (numerator + denominator - 1) / denominator
        }

        private fun requiredChargeFor(marginBps: Long, charge: Long, profit: Long): Long {
            val cost = charge - profit
            return requiredChargeForMargin(cost, marginBps)
        }
    }
}
