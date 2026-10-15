package com.articals.logistics.ui.screen.pricing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.articals.logistics.core.formatMinor
import com.articals.logistics.core.parseAmountToMinorOrNull
import com.articals.logistics.core.parseDistanceToMetresOrNull
import com.articals.logistics.core.parsePercentToBpsOrNull
import com.articals.logistics.data.dto.AdditionalCostDto
import com.articals.logistics.data.dto.AmountWithReasonDto
import com.articals.logistics.data.dto.PriceOverrideDto
import com.articals.logistics.data.dto.QuoteDto
import com.articals.logistics.data.dto.QuoteLineDto
import com.articals.logistics.data.dto.QuoteLocationDto
import com.articals.logistics.data.dto.QuoteRequestDto
import com.articals.logistics.data.dto.QuoteWarningDto
import com.articals.logistics.data.dto.TargetProfitOverrideDto
import com.articals.logistics.domain.order.ItemCategory
import com.articals.logistics.domain.QuoteWarningCodes
import com.articals.logistics.domain.order.ServiceLevel
import com.articals.logistics.ui.component.ArticalsAmountField
import com.articals.logistics.ui.component.ArticalsDropdown
import com.articals.logistics.ui.component.ArticalsTextField
import com.articals.logistics.ui.component.Banner
import com.articals.logistics.ui.component.BannerTone
import com.articals.logistics.ui.component.LabelValueRow
import com.articals.logistics.ui.component.MarginText
import com.articals.logistics.ui.component.MetaChip
import com.articals.logistics.ui.component.SectionCard
import com.articals.logistics.ui.text.ArticalsStrings
import com.articals.logistics.ui.theme.Spacing
import com.articals.logistics.util.Idempotency

/**
 * The form that asks for everything a price depends on.
 *
 * It is deliberately the *only* place the price inputs are collected, because three
 * screens need them: the calculator, "create a delivery" (the same form with a
 * customer attached) and creating a delivery from a saved quote. One form means one
 * definition of what a price is made of.
 *
 * Nothing here computes anything. Every figure that appears on screen was produced by
 * whoever owns the rules — the backend, or the demo simulator standing in for it
 * (ADR-002). The form's job is to carry the inputs and to show the answer.
 */
data class QuoteFormState(
    val pickupAddress: String = "",
    val pickupArea: String = "",
    val pickupContactName: String = "",
    val pickupContactPhone: String = "",
    val destinationAddress: String = "",
    val destinationArea: String = "",
    val destinationContactName: String = "",
    val destinationContactPhone: String = "",
    /** Kilometres as typed. `parseDistanceToMetresOrNull` decides what the text means. */
    val distanceKm: String = "",
    val serviceLevel: String = ServiceLevel.Standard,
    val itemCategory: String? = null,
    val description: String = "",
    val extraCosts: List<ExtraCostField> = emptyList(),
    val riderCostOverride: String = "",
    val riderCostReason: String = "",
    val priceOverride: String = "",
    val priceOverrideReason: String = "",
    val targetMarginPercent: String = "",
) {
    val pickupValid: Boolean get() = pickupAddress.trim().isNotEmpty()

    val destinationValid: Boolean get() = destinationAddress.trim().isNotEmpty()

    /** Enough to ask for a price: both ends of the journey. */
    val canAskForPrice: Boolean get() = pickupValid && destinationValid

    val descriptionValid: Boolean get() = description.trim().length >= 2

    val distanceMetres: Long? get() = parseDistanceToMetresOrNull(distanceKm)

    /** Extra costs, with the ones the person left blank dropped rather than zeroed. */
    fun additionalCosts(): List<AdditionalCostDto> = extraCosts
        .mapNotNull { field ->
            val amount = field.amountMinor() ?: return@mapNotNull null
            val label = field.label.trim().ifEmpty { ArticalsStrings.Pricing.costLabel }
            AdditionalCostDto(id = field.id, label = label, amountMinor = amount)
        }

    /**
     * The request the pricing engine expects.
     *
     * The manual distance is attached to the pickup, which is where the backend reads
     * it from, and it is only sent when the person actually typed one: an absent
     * distance is a fact the engine must see (it answers "a distance is needed"),
     * whereas a zero would look like a journey of no length.
     */
    fun toRequest(
        tenantId: String,
        extraCosts: List<AdditionalCostDto>,
        riderCostOverride: AmountWithReasonDto?,
        priceOverride: PriceOverrideDto?,
        targetProfitOverride: TargetProfitOverrideDto?,
        saveQuote: Boolean,
        orderId: String? = null,
    ): QuoteRequestDto = QuoteRequestDto(
        tenantId = tenantId,
        pickup = QuoteLocationDto(
            address = pickupAddress.trim(),
            area = pickupArea.trim().takeIf { it.isNotEmpty() },
            contactName = pickupContactName.trim().takeIf { it.isNotEmpty() },
            contactPhone = pickupContactPhone.trim().takeIf { it.isNotEmpty() },
            manualDistanceMetres = distanceMetres,
        ),
        destination = QuoteLocationDto(
            address = destinationAddress.trim(),
            area = destinationArea.trim().takeIf { it.isNotEmpty() },
            contactName = destinationContactName.trim().takeIf { it.isNotEmpty() },
            contactPhone = destinationContactPhone.trim().takeIf { it.isNotEmpty() },
        ),
        serviceLevel = serviceLevel,
        additionalCosts = extraCosts,
        riderCostOverride = riderCostOverride,
        customerPriceOverride = priceOverride,
        targetProfitOverride = targetProfitOverride,
        saveQuote = saveQuote,
        orderId = orderId,
    )

    /** The rider-cost override, if the person entered both an amount and a reason. */
    fun riderCostOverrideOrNull(): AmountWithReasonDto? {
        val amount = parseAmountToMinorOrNull(riderCostOverride) ?: return null
        val reason = riderCostReason.trim()
        if (reason.length < 3) return null
        return AmountWithReasonDto(amountMinor = amount, reason = reason)
    }

    fun priceOverrideOrNull(): PriceOverrideDto? {
        val amount = parseAmountToMinorOrNull(priceOverride) ?: return null
        val reason = priceOverrideReason.trim()
        if (reason.length < 3) return null
        return PriceOverrideDto(amountMinor = amount, reason = reason)
    }

    /** A target margin, converted to basis points by the shared parser — never here. */
    fun targetProfitOverrideOrNull(): TargetProfitOverrideDto? {
        val bps = parsePercentToBpsOrNull(targetMarginPercent) ?: return null
        return TargetProfitOverrideDto(marginBps = bps)
    }
}

/** One extra cost being typed. */
data class ExtraCostField(
    val id: String,
    val label: String = "",
    val amount: String = "",
) {
    fun amountMinor(): Long? = parseAmountToMinorOrNull(amount)

    companion object {
        /** A stable-enough id for a line the user added: unique inside this form. */
        fun create(): ExtraCostField = ExtraCostField(id = Idempotency.newKey("cost"))
    }
}

/** The inputs themselves, shared by the calculator and the create-delivery screen. */
@Composable
fun QuoteFormFields(
    state: QuoteFormState,
    onChange: (QuoteFormState) -> Unit,
    currency: String,
    serviceAreas: List<String>,
    canOverrideRiderCost: Boolean,
    canOverridePrice: Boolean,
    enabled: Boolean,
) {
    SectionCard(title = ArticalsStrings.Pricing.pickup) {
        ArticalsTextField(
            value = state.pickupAddress,
            onValueChange = { onChange(state.copy(pickupAddress = it)) },
            label = ArticalsStrings.Pricing.address,
            enabled = enabled,
            isError = state.pickupAddress.isNotEmpty() && !state.pickupValid,
        )
        Spacer(Modifier.height(Spacing.sm))
        if (serviceAreas.isEmpty()) {
            ArticalsTextField(
                value = state.pickupArea,
                onValueChange = { onChange(state.copy(pickupArea = it)) },
                label = ArticalsStrings.Pricing.area,
                enabled = enabled,
            )
        } else {
            ArticalsDropdown(
                label = ArticalsStrings.Pricing.area,
                selected = state.pickupArea.takeIf { it.isNotEmpty() },
                options = serviceAreas,
                optionLabel = { it },
                onSelect = { onChange(state.copy(pickupArea = it)) },
                enabled = enabled,
            )
        }
        Spacer(Modifier.height(Spacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            ArticalsTextField(
                value = state.pickupContactName,
                onValueChange = { onChange(state.copy(pickupContactName = it)) },
                label = ArticalsStrings.Pricing.contactName,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
            ArticalsTextField(
                value = state.pickupContactPhone,
                onValueChange = { onChange(state.copy(pickupContactPhone = it)) },
                label = ArticalsStrings.Pricing.contactPhone,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
        }
    }

    Spacer(Modifier.height(Spacing.md))
    SectionCard(title = ArticalsStrings.Pricing.destination) {
        ArticalsTextField(
            value = state.destinationAddress,
            onValueChange = { onChange(state.copy(destinationAddress = it)) },
            label = ArticalsStrings.Pricing.address,
            enabled = enabled,
        )
        Spacer(Modifier.height(Spacing.sm))
        if (serviceAreas.isEmpty()) {
            ArticalsTextField(
                value = state.destinationArea,
                onValueChange = { onChange(state.copy(destinationArea = it)) },
                label = ArticalsStrings.Pricing.area,
                enabled = enabled,
            )
        } else {
            ArticalsDropdown(
                label = ArticalsStrings.Pricing.area,
                selected = state.destinationArea.takeIf { it.isNotEmpty() },
                options = serviceAreas,
                optionLabel = { it },
                onSelect = { onChange(state.copy(destinationArea = it)) },
                enabled = enabled,
            )
        }
        Spacer(Modifier.height(Spacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            ArticalsTextField(
                value = state.destinationContactName,
                onValueChange = { onChange(state.copy(destinationContactName = it)) },
                label = ArticalsStrings.Pricing.contactName,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
            ArticalsTextField(
                value = state.destinationContactPhone,
                onValueChange = { onChange(state.copy(destinationContactPhone = it)) },
                label = ArticalsStrings.Pricing.contactPhone,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
        }
    }

    Spacer(Modifier.height(Spacing.md))
    SectionCard(title = ArticalsStrings.Pricing.distance) {
        ArticalsTextField(
            value = state.distanceKm,
            onValueChange = { onChange(state.copy(distanceKm = it)) },
            label = ArticalsStrings.Pricing.distanceManual,
            enabled = enabled,
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = ArticalsStrings.Pricing.distanceExplained,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(Spacing.md))
    SectionCard(title = ArticalsStrings.Orders.sectionDetails) {
        ArticalsDropdown(
            label = ArticalsStrings.Pricing.serviceLevel,
            selected = state.serviceLevel,
            options = ServiceLevel.all,
            optionLabel = { ServiceLevel.label(it) },
            onSelect = { onChange(state.copy(serviceLevel = it)) },
            enabled = enabled,
        )
        Spacer(Modifier.height(Spacing.sm))
        ArticalsDropdown(
            label = ArticalsStrings.Orders.itemCategory,
            selected = state.itemCategory,
            options = ItemCategory.all,
            optionLabel = { ItemCategory.label(it) },
            onSelect = { onChange(state.copy(itemCategory = it)) },
            enabled = enabled,
        )
        Spacer(Modifier.height(Spacing.sm))
        ArticalsTextField(
            value = state.description,
            onValueChange = { onChange(state.copy(description = it)) },
            label = ArticalsStrings.Orders.description,
            enabled = enabled,
            singleLine = false,
            minLines = 2,
            isError = state.description.isNotEmpty() && !state.descriptionValid,
        )
    }

    Spacer(Modifier.height(Spacing.md))
    SectionCard(title = ArticalsStrings.Pricing.additionalCosts) {
        state.extraCosts.forEach { field ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                ArticalsTextField(
                    value = field.label,
                    onValueChange = { label ->
                        onChange(
                            state.copy(
                                extraCosts = state.extraCosts.map {
                                    if (it.id == field.id) it.copy(label = label) else it
                                },
                            ),
                        )
                    },
                    label = ArticalsStrings.Pricing.costLabel,
                    enabled = enabled,
                    modifier = Modifier.weight(1.2f),
                )
                ArticalsAmountField(
                    value = field.amount,
                    onValueChange = { amount ->
                        onChange(
                            state.copy(
                                extraCosts = state.extraCosts.map {
                                    if (it.id == field.id) it.copy(amount = amount) else it
                                },
                            ),
                        )
                    },
                    label = ArticalsStrings.Pricing.costAmount,
                    currency = currency,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        onChange(state.copy(extraCosts = state.extraCosts.filterNot { it.id == field.id }))
                    },
                    enabled = enabled,
                ) { Text(ArticalsStrings.Common.delete) }
            }
        }
        TextButton(
            onClick = { onChange(state.copy(extraCosts = state.extraCosts + ExtraCostField.create())) },
            enabled = enabled,
        ) { Text(ArticalsStrings.Pricing.addCost) }
    }

    // --- the overrides, which are the two places money can be moved by a person ---
    if (canOverrideRiderCost || canOverridePrice) {
        Spacer(Modifier.height(Spacing.md))
        SectionCard(
            title = ArticalsStrings.Orders.sectionMoney,
            subtitle = ArticalsStrings.Pricing.riderCostExplain,
        ) {
            if (canOverrideRiderCost) {
                ArticalsAmountField(
                    value = state.riderCostOverride,
                    onValueChange = { onChange(state.copy(riderCostOverride = it)) },
                    label = ArticalsStrings.Pricing.riderCostAdjust,
                    currency = currency,
                    enabled = enabled,
                )
                Spacer(Modifier.height(Spacing.sm))
                ArticalsTextField(
                    value = state.riderCostReason,
                    onValueChange = { onChange(state.copy(riderCostReason = it)) },
                    label = ArticalsStrings.Pricing.reason,
                    enabled = enabled,
                    helper = ArticalsStrings.Pricing.reasonRequired,
                    error = if (state.riderCostOverride.isNotEmpty() && state.riderCostReason.trim().length < 3) {
                        ArticalsStrings.Pricing.reasonRequired
                    } else {
                        null
                    },
                )
            }
            if (canOverridePrice) {
                Spacer(Modifier.height(Spacing.md))
                ArticalsAmountField(
                    value = state.priceOverride,
                    onValueChange = { onChange(state.copy(priceOverride = it)) },
                    label = ArticalsStrings.Pricing.overridePrice,
                    currency = currency,
                    enabled = enabled,
                )
                Spacer(Modifier.height(Spacing.sm))
                ArticalsTextField(
                    value = state.priceOverrideReason,
                    onValueChange = { onChange(state.copy(priceOverrideReason = it)) },
                    label = ArticalsStrings.Pricing.reason,
                    enabled = enabled,
                    error = if (state.priceOverride.isNotEmpty() && state.priceOverrideReason.trim().length < 3) {
                        ArticalsStrings.Pricing.reasonRequired
                    } else {
                        null
                    },
                )
                Spacer(Modifier.height(Spacing.sm))
                ArticalsTextField(
                    value = state.targetMarginPercent,
                    onValueChange = { onChange(state.copy(targetMarginPercent = it)) },
                    label = ArticalsStrings.Pricing.targetMargin,
                    enabled = enabled,
                    helper = ArticalsStrings.Settings.targetMarginExplain,
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                )
            }
        }
    } else {
        Spacer(Modifier.height(Spacing.md))
        Banner(text = ArticalsStrings.Pricing.overrideNeedsPermission, tone = BannerTone.Info)
    }
}

/** The price itself: every line the engine produced, in the order it produced them. */
@Composable
fun QuoteBreakdown(quote: QuoteDto, currency: String) {
    SectionCard(title = ArticalsStrings.Pricing.breakdown) {
        quote.lines.forEach { line: QuoteLineDto ->
            LabelValueRow(
                label = line.label,
                value = formatMinor(line.amountMinor, currency),
            )
            line.note?.takeIf { it.isNotBlank() }?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(Spacing.sm))
        quote.rounding?.let { rounding ->
            if (rounding.incrementMinor > 0L) {
                LabelValueRow(
                    label = "${ArticalsStrings.Settings.rounding} (${formatMinor(rounding.incrementMinor, currency)})",
                    value = formatMinor(rounding.beforeMinor - rounding.afterMinor, currency),
                )
            }
        }
        if (quote.appliedMinimumCharge) MetaChip(text = ArticalsStrings.Settings.minimumCharge)
        if (quote.appliedMaximumCharge) MetaChip(text = ArticalsStrings.Settings.maximumCharge)
        if (quote.manualPriceOverrideApplied) MetaChip(text = ArticalsStrings.Pricing.overridePrice)

        Spacer(Modifier.height(Spacing.sm))
        LabelValueRow(
            label = ArticalsStrings.Pricing.customerCharge,
            value = formatMinor(quote.computedCustomerChargeMinor, currency),
            emphasised = true,
        )
        LabelValueRow(
            label = ArticalsStrings.Pricing.riderCost,
            value = formatMinor(quote.estimatedRiderCost.amountMinor, currency),
        )
        LabelValueRow(
            label = ArticalsStrings.Pricing.additionalTotal,
            value = formatMinor(quote.estimatedAdditionalDeliveryCostsMinor, currency),
        )
        LabelValueRow(
            label = ArticalsStrings.Pricing.profit,
            value = formatMinor(quote.estimatedProfitMinor, currency),
            emphasised = true,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = ArticalsStrings.Pricing.margin,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MarginText(bps = quote.estimatedMarginBps)
        }

        quote.distance?.let { distance ->
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = com.articals.logistics.core.formatDistanceMetres(distance.metres) +
                    " · " + distance.source,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!quote.estimatedMarginIsDefined) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = ArticalsStrings.Pricing.zeroCustomerCharge,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** The warnings, each of which has to be read before the price may be used. */
@Composable
fun QuoteWarnings(
    warnings: List<QuoteWarningDto>,
    acknowledged: Set<String>,
    onAcknowledge: (String) -> Unit,
    blocked: Boolean,
) {
    SectionCard(title = ArticalsStrings.Pricing.warnings) {
        if (warnings.isEmpty()) {
            Text(
                text = ArticalsStrings.Pricing.noWarnings,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        if (blocked) {
            Banner(text = ArticalsStrings.Pricing.blocked, tone = BannerTone.Warning)
            Spacer(Modifier.height(Spacing.sm))
        }
        warnings.forEach { warning ->
            val isBlocker = warning.severity == "blocker"
            val isAcknowledged = warning.code in acknowledged
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.sm)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = QuoteWarningCodes.label(warning.code),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (isBlocker) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                    MetaChip(text = warning.severity)
                }
                Text(
                    text = warning.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = { onAcknowledge(warning.code) },
                    enabled = !isAcknowledged,
                ) {
                    Text(
                        if (isAcknowledged) {
                            ArticalsStrings.Common.done
                        } else {
                            ArticalsStrings.Common.confirm
                        },
                    )
                }
            }
        }
    }
}
