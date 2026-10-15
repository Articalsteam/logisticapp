package com.articals.logistics.demo

import com.articals.logistics.data.dto.AdditionalCostDto
import com.articals.logistics.data.dto.AmountWithReasonDto
import com.articals.logistics.data.dto.DistanceBandDto
import com.articals.logistics.data.dto.PricingConfigDto
import com.articals.logistics.data.dto.QuoteLocationDto
import com.articals.logistics.data.dto.QuoteRequestDto
import com.articals.logistics.data.dto.RiderCostConfigDto
import com.articals.logistics.domain.PricingMethods
import com.articals.logistics.domain.RiderCostMethods
import com.articals.logistics.domain.RoundingModes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The demo simulator, checked against the published pricing behaviour.
 *
 * The canonical example is the backend's own worked example — ₦700 base, ₦150 per
 * kilometre, ₦500 for the rider over 8 400 m — and the numbers below are the same ones
 * `functions/src/pricing/engine.test.ts` asserts. If the two ever disagree, a person
 * sees one price in the demo and another in production, which is the worst kind of bug
 * this product can have; so the vectors are pinned here as well.
 *
 * Written, not run: see `docs/COMPILE_CHECKLIST.md`.
 */
class DemoQuoteSimulatorTest {

    private val simulator = DemoQuoteSimulator()

    private fun config(method: String = PricingMethods.BasePlusDistance) = PricingConfigDto(
        id = "test-config",
        tenantId = "test-tenant",
        name = "Test rates",
        currency = "NGN",
        method = method,
        baseChargeMinor = 70_000,
        perKmRateMinor = 15_000,
        distanceBands = listOf(
            DistanceBandDto(upToMetres = 5_000, chargeMinor = 100_000, label = "Up to 5 km"),
            DistanceBandDto(upToMetres = 10_000, chargeMinor = 180_000, label = "5 to 10 km"),
            DistanceBandDto(upToMetres = null, chargeMinor = 300_000, label = "Above 10 km"),
        ),
        minimumChargeMinor = 0,
        roundingIncrementMinor = 0,
        roundingMode = RoundingModes.Nearest,
        riderCost = RiderCostConfigDto(method = RiderCostMethods.Fixed, defaultFeeMinor = 50_000),
        targetProfitMarginBps = null,
    )

    /** Ikeja → Yaba is 8 400 m in the demo distance table. */
    private fun request(
        extraCosts: List<AdditionalCostDto> = emptyList(),
        riderCostOverride: AmountWithReasonDto? = null,
        manualDistanceMetres: Long? = null,
    ) = QuoteRequestDto(
        tenantId = "test-tenant",
        pickup = QuoteLocationDto(address = "Ikeja", zoneId = "ikeja", manualDistanceMetres = manualDistanceMetres),
        destination = QuoteLocationDto(address = "Yaba", zoneId = "yaba"),
        additionalCosts = extraCosts,
        riderCostOverride = riderCostOverride,
    )

    @Test
    fun `the canonical example prices 700 plus 150 per km with a 500 rider cost`() {
        val response = simulator.quote(config(), request(), nowIso = "2026-03-14T09:00:00Z")

        val quote = assertNotNull(response.quote, response.warnings.joinToString { it.code })
        assertEquals("ok", response.status)
        assertEquals(8_400L, quote.distance?.metres)

        // 700 + (150/km × 8.4 km) = 1 960; the rider costs 500; the profit is the
        // difference, and the margin is that profit as basis points of the charge.
        assertEquals(196_000L, quote.computedCustomerChargeMinor)
        assertEquals(196_000L, quote.recommendedCustomerChargeMinor)
        assertEquals(50_000L, quote.estimatedRiderCost.amountMinor)
        assertEquals(146_000L, quote.estimatedProfitMinor)
        assertEquals(7_449, quote.estimatedMarginBps)
    }

    @Test
    fun `a rider cost override changes the rider's cost and the profit, not the price`() {
        val plain = simulator.quote(config(), request(), nowIso = "2026-03-14T09:00:00Z")
        val overridden = simulator.quote(
            config = config(),
            request = request(
                riderCostOverride = AmountWithReasonDto(amountMinor = 80_000, reason = "Long carry to the gate"),
            ),
            nowIso = "2026-03-14T09:00:00Z",
        )

        assertEquals(
            plain.quote?.computedCustomerChargeMinor,
            overridden.quote?.computedCustomerChargeMinor,
        )
        assertEquals(80_000L, overridden.quote?.estimatedRiderCost?.amountMinor)
        assertEquals(
            (plain.quote?.estimatedProfitMinor ?: 0L) - 30_000L,
            overridden.quote?.estimatedProfitMinor,
        )
    }

    @Test
    fun `an extra cost is a cost of the delivery and comes off the profit`() {
        val response = simulator.quote(
            config = config(),
            request = request(extraCosts = listOf(AdditionalCostDto(id = "wait", label = "Waiting", amountMinor = 20_000))),
            nowIso = "2026-03-14T09:00:00Z",
        )

        val quote = assertNotNull(response.quote)
        assertEquals(20_000L, quote.estimatedAdditionalDeliveryCostsMinor)
        assertEquals(70_000L, quote.estimatedTotalDeliveryCostMinor)
        assertEquals(196_000L, quote.computedCustomerChargeMinor)
        assertEquals(126_000L, quote.estimatedProfitMinor)
    }

    @Test
    fun `a distance-band method uses the band the distance falls in`() {
        val response = simulator.quote(
            config = config(method = PricingMethods.DistanceBands),
            request = request(),
            nowIso = "2026-03-14T09:00:00Z",
        )

        val quote = assertNotNull(response.quote, response.warnings.joinToString { it.code })
        assertEquals(180_000L, quote.computedCustomerChargeMinor)
        assertTrue(quote.lines.any { it.amountMinor == 180_000L })
    }

    @Test
    fun `an open-ended band is what covers everything beyond the last bound`() {
        val response = simulator.quote(
            config = config(method = PricingMethods.DistanceBands),
            request = request(manualDistanceMetres = 42_000L),
            nowIso = "2026-03-14T09:00:00Z",
        )

        val quote = assertNotNull(response.quote, response.warnings.joinToString { it.code })
        assertEquals(300_000L, quote.computedCustomerChargeMinor)
    }

    @Test
    fun `with no distance and no manual entry the answer is needs_input, never an invented distance`() {
        val response = simulator.quote(
            config = config(),
            request = QuoteRequestDto(
                tenantId = "test-tenant",
                pickup = QuoteLocationDto(address = "Somewhere unlisted"),
                destination = QuoteLocationDto(address = "Somewhere else unlisted"),
            ),
            nowIso = "2026-03-14T09:00:00Z",
        )

        assertEquals("needs_input", response.status)
        assertNull(response.quote)
        assertTrue(
            response.warnings.any { it.code == "distance_required" },
            "expected a distance_required warning, got ${response.warnings.map { it.code }}",
        )
    }

    @Test
    fun `a manually entered distance is priced with a warning that says so`() {
        val response = simulator.quote(
            config = config(),
            request = QuoteRequestDto(
                tenantId = "test-tenant",
                pickup = QuoteLocationDto(address = "Warehouse", manualDistanceMetres = 12_000L),
                destination = QuoteLocationDto(address = "Client site"),
            ),
            nowIso = "2026-03-14T09:00:00Z",
        )

        val quote = assertNotNull(response.quote)
        assertEquals(12_000L, quote.distance?.metres)
        assertEquals("manual", quote.distance?.source)
        assertTrue(response.warnings.any { it.code == "manual_distance" })
    }

    @Test
    fun `the minimum charge lifts a cheap delivery and says it did`() {
        val response = simulator.quote(
            config = config().copy(minimumChargeMinor = 250_000L),
            request = request(manualDistanceMetres = 1_000L),
            nowIso = "2026-03-14T09:00:00Z",
        )

        val quote = assertNotNull(response.quote)
        assertEquals(250_000L, quote.recommendedCustomerChargeMinor)
        assertTrue(quote.appliedMinimumCharge)
        assertTrue(response.warnings.any { it.code == "minimum_charge_applied" })
    }

    @Test
    fun `the maximum charge caps an expensive delivery and says it did`() {
        val response = simulator.quote(
            config = config().copy(maximumChargeMinor = 150_000L),
            request = request(),
            nowIso = "2026-03-14T09:00:00Z",
        )

        val quote = assertNotNull(response.quote)
        assertEquals(150_000L, quote.recommendedCustomerChargeMinor)
        assertTrue(quote.appliedMaximumCharge)
        assertTrue(response.warnings.any { it.code == "maximum_charge_applied" })
    }

    @Test
    fun `rounding happens once, after the minimum and the maximum`() {
        val response = simulator.quote(
            config = config().copy(
                method = PricingMethods.DistanceBands,
                roundingIncrementMinor = 50_000L,
                roundingMode = RoundingModes.Nearest,
            ),
            request = request(),
            nowIso = "2026-03-14T09:00:00Z",
        )

        val quote = assertNotNull(response.quote)
        val rounding = assertNotNull(quote.rounding, "a rounding increment was set but no rounding was reported")
        assertEquals(50_000L, rounding.incrementMinor)
        assertEquals(180_000L, rounding.beforeMinor)
        assertEquals(200_000L, rounding.afterMinor)
        // The line total is what the rate card produced; the recommended charge is
        // what the customer is quoted, and rounding is the difference between them.
        assertEquals(180_000L, quote.computedCustomerChargeMinor)
        assertEquals(200_000L, quote.recommendedCustomerChargeMinor)
    }

    @Test
    fun `a duplicate extra cost is warned about rather than added twice`() {
        val response = simulator.quote(
            config = config(),
            request = request(
                extraCosts = listOf(
                    AdditionalCostDto(id = "toll", label = "Toll", amountMinor = 5_000),
                    AdditionalCostDto(id = "toll", label = "Toll", amountMinor = 5_000),
                ),
            ),
            nowIso = "2026-03-14T09:00:00Z",
        )

        assertTrue(response.warnings.any { it.code == "duplicate_additional_cost" })
    }

    @Test
    fun `the pricing snapshot names the version the price came from`() {
        val response = simulator.quote(config().copy(version = 7), request(), nowIso = "2026-03-14T09:00:00Z")

        val quote = assertNotNull(response.quote)
        assertEquals("test-config", quote.pricingSnapshot.pricingConfigId)
        assertEquals(7, quote.pricingSnapshot.pricingConfigVersion)
        assertEquals("2026-03-14T09:00:00Z", quote.computedAt)
    }

    @Test
    fun `every line adds up to the total before rounding`() {
        val response = simulator.quote(config(), request(), nowIso = "2026-03-14T09:00:00Z")

        val quote = assertNotNull(response.quote)
        assertEquals(quote.lines.sumOf { it.amountMinor }, quote.linesTotalBeforeRounding)
    }
}
