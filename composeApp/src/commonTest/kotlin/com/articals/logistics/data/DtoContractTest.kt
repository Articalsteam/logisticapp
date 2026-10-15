package com.articals.logistics.data

import com.articals.logistics.data.dto.CreateOrderResponse
import com.articals.logistics.data.dto.Envelope
import com.articals.logistics.data.dto.PricingConfigDto
import com.articals.logistics.data.dto.PricingSnapshotDto
import com.articals.logistics.data.dto.QuoteDto
import com.articals.logistics.data.dto.QuoteResponseDto
import com.articals.logistics.data.dto.RoundingDto
import com.articals.logistics.data.dto.UpdatePricingResponse
import com.articals.logistics.domain.EntitlementStates
import com.articals.logistics.domain.order.OrderStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * The wire contract, in the client's own terms.
 *
 * Two rules decide everything here. Money crosses the wire as whole minor units in a
 * number — never as a decimal, never as a formatted string. And every field the
 * backend may omit is nullable or defaulted, because a build released today has to
 * keep working against a backend deployed tomorrow.
 *
 * The shapes themselves are asserted against the real handlers on the backend side
 * (`functions/tests/unit/contract.test.ts`); this test keeps the Kotlin side of that
 * agreement from drifting.
 */
class DtoContractTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val envelope = """
        {
          "ok": true,
          "replayed": false,
          "data": {
            "orderId": "order-1",
            "orderNumber": "DEL-2026-0007",
            "trackingReference": "ART-7K2M9Q",
            "status": "pending",
            "customerChargeMinor": 196000,
            "estimatedRiderCostMinor": 50000,
            "estimatedProfitMinor": 146000,
            "estimatedMarginBps": 7449,
            "distanceSource": "zone_based",
            "warnings": [
              { "code": "manual_distance", "severity": "warning", "message": "The distance was entered by hand." }
            ]
          }
        }
    """.trimIndent()

    @Test
    fun `the callable envelope decodes into the response payload`() {
        val decoded = json.decodeFromString(Envelope.serializer(CreateOrderResponse.serializer()), envelope)

        assertTrue(decoded.ok)
        assertEquals(false, decoded.replayed)
        assertEquals("order-1", decoded.data.orderId)
        assertEquals(196_000L, decoded.data.customerChargeMinor)
        assertEquals(7_449, decoded.data.estimatedMarginBps)
        assertEquals("zone_based", decoded.data.distanceSource)
        assertEquals(1, decoded.data.warnings.size)
        assertEquals("manual_distance", decoded.data.warnings.first().code)
    }

    @Test
    fun `a response that omits optional fields still decodes, with the defaults`() {
        val minimal = """{"ok":true,"data":{"orderId":"order-2","status":"confirmed"}}"""

        val decoded = json.decodeFromString(Envelope.serializer(CreateOrderResponse.serializer()), minimal)

        assertEquals("order-2", decoded.data.orderId)
        assertEquals(0L, decoded.data.customerChargeMinor)
        assertNull(decoded.data.trackingUrl)
        assertEquals(0, decoded.data.warnings.size)
        assertNull(decoded.replayed)
    }

    @Test
    fun `money is a number of minor units on the wire, never a decimal`() {
        val config = PricingConfigDto(
            id = "config-1",
            tenantId = "tenant-1",
            name = "Standard rates",
            version = 3,
            baseChargeMinor = 70_000L,
            perKmRateMinor = 15_000L,
            minimumChargeMinor = 80_000L,
        )

        val encoded = json.encodeToString(PricingConfigDto.serializer(), config)
        val root = json.parseToJsonElement(encoded).jsonObject

        assertEquals(70_000L, root.getValue("baseChargeMinor").jsonPrimitive.long)
        assertEquals(15_000L, root.getValue("perKmRateMinor").jsonPrimitive.long)
        // Naira appear nowhere: no field carries 700, 150 or a "700.00" string.
        assertTrue(!encoded.contains("700.00"))
        assertTrue(!encoded.contains("\"baseChargeMinor\":700,"))
        assertEquals(3, root.getValue("version").jsonPrimitive.long)
        // The whole rate card survives a round trip, so a saved draft is not lossy.
        assertEquals(config, json.decodeFromString(PricingConfigDto.serializer(), encoded))
    }

    @Test
    fun `an unknown method or status is carried as a string, not rejected`() {
        // The backend may add a pricing method or a status before this build is
        // retired. Decoding must not throw: the screens show an unfamiliar value with
        // a readable label rather than a crash.
        val payload = """
            {"ok":true,"data":{"configId":"c1","version":4,"method":"per_parcel_locker"}}
        """.trimIndent()

        val decoded = json.decodeFromString(Envelope.serializer(UpdatePricingResponse.serializer()), payload)

        assertEquals("per_parcel_locker", decoded.data.method)
        assertEquals(4, decoded.data.version)
    }

    @Test
    fun `a quote round-trips with its lines, rounding and snapshot intact`() {
        val quote = QuoteResponseDto(
            status = "ok",
            quoteId = "quote-9",
            routingProvider = "demo",
            quote = QuoteDto(
                currency = "NGN",
                method = "base_plus_distance",
                computedCustomerChargeMinor = 180_000L,
                recommendedCustomerChargeMinor = 200_000L,
                linesTotalBeforeRounding = 180_000L,
                rounding = RoundingDto(incrementMinor = 50_000L, beforeMinor = 180_000L, afterMinor = 200_000L),
                estimatedProfitMinor = 150_000L,
                estimatedMarginBps = 7_500,
                pricingSnapshot = PricingSnapshotDto(
                    pricingConfigId = "config-1",
                    pricingConfigVersion = 3,
                ),
            ),
        )

        val decoded = json.decodeFromString(QuoteResponseDto.serializer(), json.encodeToString(QuoteResponseDto.serializer(), quote))

        assertEquals(quote.quote?.recommendedCustomerChargeMinor, decoded.quote?.recommendedCustomerChargeMinor)
        assertEquals(50_000L, decoded.quote?.rounding?.incrementMinor)
        assertEquals(3, decoded.quote?.pricingSnapshot?.pricingConfigVersion)
        assertEquals(quote, decoded)
    }

    @Test
    fun `vocabulary the client switches on is documented in one place`() {
        // These lists are what the interface renders and what the backend validates
        // against; a value added on one side and not the other is a support call.
        assertEquals(8, OrderStatus.all.size)
        assertTrue(OrderStatus.Pending in OrderStatus.all)
        assertTrue(OrderStatus.Cancelled in OrderStatus.all)
        assertEquals(3, OrderStatus.terminal.size)
        assertEquals(7, EntitlementStates.all.size)
        assertTrue(EntitlementStates.Suspended in EntitlementStates.all)
        assertTrue(EntitlementStates.canWrite(EntitlementStates.Trialing))
        assertTrue(!EntitlementStates.canWrite(EntitlementStates.Limited))
        assertTrue(!EntitlementStates.canWrite(EntitlementStates.Suspended))
    }
}
