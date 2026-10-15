package com.articals.logistics.demo

import com.articals.logistics.core.AppError
import com.articals.logistics.core.AppResult
import com.articals.logistics.core.Permissions
import com.articals.logistics.data.api.PricingRepository
import com.articals.logistics.data.dto.PricingConfigDto
import com.articals.logistics.data.dto.QuoteRequestDto
import com.articals.logistics.data.dto.QuoteResponseDto
import com.articals.logistics.data.dto.SavedQuoteDto
import com.articals.logistics.data.dto.UpdatePricingResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Pricing in demo mode.
 *
 * The quote itself comes from [DemoQuoteSimulator] — the single, documented place
 * where demo code computes money (ADR-013). Everything around it behaves like the
 * backend: the caller needs `quotes.create`, a price override needs
 * `quotes.override_price`, saving rules needs `pricing.manage`, and every saved
 * quote is written to the history with an audit entry.
 */
class DemoPricingRepository(
    private val store: DemoStore,
    private val simulator: DemoQuoteSimulator = DemoQuoteSimulator(),
) : PricingRepository {

    override suspend fun requestQuote(request: QuoteRequestDto): AppResult<QuoteResponseDto> = store.demoCall {
        store.requireMembership(request.tenantId, Permissions.QuotesCreate)
        val config = activeConfig(request.tenantId)
        if (request.customerPriceOverride != null) {
            store.requirePermission(request.tenantId, Permissions.QuotesOverridePrice)
        }

        val response = simulator.quote(config, request, store.isoNow())

        if (request.saveQuote == true && response.quote != null) {
            val quote = response.quote
            val id = store.newId("quote")
            store.quotes.value = store.quotes.value + SavedQuoteDto(
                id = id,
                orderId = request.orderId,
                createdBy = store.currentUid(),
                createdAt = store.isoNow(),
                pickup = request.pickup,
                destination = request.destination,
                serviceLevel = request.serviceLevel,
                distance = quote.distance,
                recommendedCustomerChargeMinor = quote.recommendedCustomerChargeMinor,
                estimatedRiderCost = quote.estimatedRiderCost,
                estimatedAdditionalDeliveryCostsMinor = quote.estimatedAdditionalDeliveryCostsMinor,
                estimatedProfitMinor = quote.estimatedProfitMinor,
                estimatedMarginBps = quote.estimatedMarginBps,
                warnings = quote.warnings,
            )
            store.audit(
                action = "quote.saved",
                tenantId = request.tenantId,
                resourceType = "quote",
                resourceId = id,
                metadata = mapOf(
                    "recommendedCustomerChargeMinor" to quote.recommendedCustomerChargeMinor.toString(),
                    "pricingConfigVersion" to (quote.pricingSnapshot.pricingConfigVersion ?: 0).toString(),
                ),
            )
            response.copy(quoteId = id)
        } else {
            response
        }
    }

    override suspend fun savePricingRules(config: PricingConfigDto): AppResult<UpdatePricingResponse> =
        store.demoCall {
            store.requireWrite(config.tenantId, Permissions.PricingManage)
            requireValid(
                config.name.trim().length in 2..60,
                "name",
                "Give this rate card a name.",
                "pricing.invalid_name",
            )
            requireValid(
                config.method in com.articals.logistics.domain.PricingMethods.all,
                "method",
                "Choose how this company prices deliveries.",
                "pricing.invalid_method",
            )
            requireValid(
                config.minimumChargeMinor >= 0 && config.baseChargeMinor >= 0 && config.perKmRateMinor >= 0,
                "baseChargeMinor",
                "Charges cannot be negative.",
                "pricing.negative_amount",
            )
            requireValid(
                config.roundingIncrementMinor in 0..100_000,
                "roundingIncrementMinor",
                "Rounding must be between 0 and ₦1,000.",
                "pricing.invalid_rounding",
            )
            config.targetProfitMarginBps?.let {
                requireValid(it < 10_000, "targetProfitMarginBps", "A margin must be below 100%.", "pricing.margin_too_high")
            }
            config.maximumChargeMinor?.let { maximum ->
                requireValid(
                    maximum >= config.minimumChargeMinor,
                    "maximumChargeMinor",
                    "The maximum charge cannot be below the minimum.",
                    "pricing.maximum_below_minimum",
                )
            }
            config.zoneRules.forEach { rule ->
                requireValid(
                    rule.fromZoneId.isNotBlank() && rule.toZoneId.isNotBlank(),
                    "zoneRules",
                    "Every zone rule needs both a pickup and a destination zone.",
                    "pricing.invalid_zone_rule",
                )
            }

            val existing = store.pricingConfigs.value[config.id]
            val nextVersion = (existing?.version ?: 0) + 1
            val saved = config.copy(
                id = config.id.ifBlank { store.newId("pricingConfig") },
                version = nextVersion,
                isActive = true,
            )
            store.pricingConfigs.value = store.pricingConfigs.value + (saved.id to saved)

            // The company points at the active configuration, exactly as in production.
            store.companies.value[saved.tenantId]?.let { company ->
                store.companies.value = store.companies.value + (
                    saved.tenantId to company.copy(
                        activePricingConfigId = saved.id,
                        updatedAt = store.isoNow(),
                    )
                    )
            }

            store.audit(
                action = "pricing.updated",
                tenantId = saved.tenantId,
                resourceType = "pricingConfig",
                resourceId = saved.id,
                metadata = mapOf(
                    "version" to nextVersion.toString(),
                    "method" to saved.method,
                    "previousVersion" to (existing?.version ?: 0).toString(),
                ),
            )
            UpdatePricingResponse(configId = saved.id, version = nextVersion, method = saved.method)
        }

    override fun observeActivePricingConfig(configId: String): Flow<PricingConfigDto?> =
        store.pricingConfigs.map { configs -> configs[configId] }

    override fun observeActivePricingConfigId(tenantId: String): Flow<String?> =
        store.companies.map { companies -> companies[tenantId]?.activePricingConfigId }

    override fun observeSavedQuotes(tenantId: String, limit: Int): Flow<List<SavedQuoteDto>> =
        combine(store.quotes, store.orders) { quotes, orders ->
            val tenantOrderIds = orders.values.filter { it.tenantId == tenantId }.map { it.id }.toSet()
            quotes
                .filter { quote -> quote.orderId == null || quote.orderId in tenantOrderIds }
                .sortedByDescending { it.createdAt ?: "" }
                .take(limit)
        }

    private fun activeConfig(tenantId: String): PricingConfigDto {
        val company = store.companies.value[tenantId]
            ?: throw DemoFailure(
                AppError.NotFound(detail = "company_missing", message = "That company workspace was not found."),
            )
        val configId = company.activePricingConfigId
        if (configId.isNullOrBlank()) {
            throw DemoFailure(
                AppError.Rejected(
                    detail = "pricing_config_missing",
                    message = "This company has no pricing configuration yet. Set one up in pricing settings.",
                ),
            )
        }
        return store.pricingConfigs.value[configId]
            ?: throw DemoFailure(
                AppError.Rejected(
                    detail = "pricing_config_missing",
                    message = "This company's pricing configuration could not be found. Set it up again in pricing settings.",
                ),
            )
    }
}
