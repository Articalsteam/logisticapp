package com.articals.logistics.ui.screen.pricingsettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.articals.logistics.core.Permissions
import com.articals.logistics.data.dto.PricingConfigDto
import com.articals.logistics.domain.PricingMethods
import com.articals.logistics.domain.RiderCostMethods
import com.articals.logistics.domain.RoundingModes
import com.articals.logistics.domain.order.ServiceLevel
import com.articals.logistics.ui.component.ArticalsAmountField
import com.articals.logistics.ui.component.ArticalsDropdown
import com.articals.logistics.ui.component.ArticalsTextField
import com.articals.logistics.ui.component.Banner
import com.articals.logistics.ui.component.BannerTone
import com.articals.logistics.ui.component.ChoiceChips
import com.articals.logistics.ui.component.ErrorState
import com.articals.logistics.ui.component.MetaChip
import com.articals.logistics.ui.component.SectionCard
import com.articals.logistics.ui.component.SkeletonList
import com.articals.logistics.ui.demo.DemoStrip
import com.articals.logistics.ui.text.ArticalsStrings
import com.articals.logistics.ui.theme.Spacing
import org.koin.compose.viewmodel.koinViewModel

/**
 * The rate card.
 *
 * One scrolling form in the order a price is built: the method, the charges, the
 * rounding, the bands, the areas, the surcharges, what the rider is paid, and the
 * target. Each section is a part of the pricing engine's own breakdown, so what is set
 * here is what the calculator will show line by line.
 *
 * The version counter is the important thing on this screen: saving does not change the
 * prices of deliveries that already exist (ADR-002).
 */
@Composable
fun PricingSettingsScreen(
    tenantId: String,
    currency: String,
    serviceAreas: List<String>,
    permissions: Set<String>,
    modifier: Modifier = Modifier,
    viewModel: PricingSettingsViewModel = koinViewModel(),
) {
    LaunchedEffect(tenantId) { viewModel.load(tenantId) }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val canManage = Permissions.PricingManage in permissions

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.screen),
    ) {
        DemoStrip()

        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = ArticalsStrings.PricingSettings.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = ArticalsStrings.PricingSettings.subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.loading) {
            Spacer(Modifier.height(Spacing.md))
            SkeletonList(rows = 4)
            return@Column
        }

        if (state.config == null) {
            Spacer(Modifier.height(Spacing.md))
            ErrorState(
                error = state.error
                    ?: com.articals.logistics.core.AppError.NotFound(
                        message = ArticalsStrings.PricingSettings.missing,
                    ),
                onRetry = { viewModel.load(tenantId) },
            )
            return@Column
        }

        state.notice?.let {
            Spacer(Modifier.height(Spacing.md))
            Banner(
                text = it,
                tone = BannerTone.Success,
                action = { TextButton(onClick = viewModel::clearNotice) { Text(ArticalsStrings.Common.close) } },
            )
        }

        if (!canManage) {
            Spacer(Modifier.height(Spacing.md))
            Banner(text = ArticalsStrings.Pricing.overrideNeedsPermission, tone = BannerTone.Info)
        }

        val config = state.config ?: return@Column
        val draft = state.draft ?: return@Column
        val enabled = canManage && !state.saving

        Spacer(Modifier.height(Spacing.md))
        RatesIdentity(
            config = config,
            draft = draft,
            enabled = enabled,
            onChange = viewModel::updateDraft,
        )

        Spacer(Modifier.height(Spacing.md))
        MethodSection(draft = draft, enabled = enabled, currency = currency, onChange = viewModel::updateDraft)

        Spacer(Modifier.height(Spacing.md))
        ChargesSection(draft = draft, enabled = enabled, currency = currency, onChange = viewModel::updateDraft)

        Spacer(Modifier.height(Spacing.md))
        BandsSection(draft = draft, enabled = enabled, currency = currency, onChange = viewModel::updateDraft)

        if (draft.method == PricingMethods.ZoneBased ||
            draft.riderCost.method == RiderCostMethods.ZoneBased
        ) {
            Spacer(Modifier.height(Spacing.md))
            ZonesSection(
                draft = draft,
                enabled = enabled,
                currency = currency,
                serviceAreas = serviceAreas,
                onChange = viewModel::updateDraft,
            )
        }

        Spacer(Modifier.height(Spacing.md))
        SurchargesSection(draft = draft, enabled = enabled, currency = currency, onChange = viewModel::updateDraft)

        Spacer(Modifier.height(Spacing.md))
        RiderCostSection(draft = draft, enabled = enabled, currency = currency, onChange = viewModel::updateDraft)

        Spacer(Modifier.height(Spacing.md))
        TargetProfitSection(draft = draft, enabled = enabled, currency = currency, onChange = viewModel::updateDraft)

        if (state.problems.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.md))
            SectionCard(title = ArticalsStrings.PricingSettings.problemsTitle) {
                state.problems.forEach { problem ->
                    Text(
                        text = "• $problem",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        state.error?.let {
            Spacer(Modifier.height(Spacing.md))
            Banner(text = it.userMessage, tone = BannerTone.Danger)
        }

        Spacer(Modifier.height(Spacing.md))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = viewModel::save,
                enabled = state.canSave && canManage,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    if (state.saving) {
                        ArticalsStrings.Common.saving
                    } else {
                        ArticalsStrings.Settings.saveRates
                    },
                )
            }
            if (state.dirty) {
                TextButton(onClick = viewModel::discardChanges, enabled = !state.saving) {
                    Text(ArticalsStrings.PricingSettings.discard)
                }
            }
        }

        if (state.dirty) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = ArticalsStrings.PricingSettings.unsaved,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = ArticalsStrings.PricingSettings.versioningNote,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.xxl))
    }
}

@Composable
private fun RatesIdentity(
    config: PricingConfigDto,
    draft: RatesDraft,
    enabled: Boolean,
    onChange: (RatesDraft) -> Unit,
) {
    SectionCard(title = ArticalsStrings.PricingSettings.title) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${ArticalsStrings.PricingSettings.versionLabel} ${config.version}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(Spacing.xs))
            MetaChip(text = config.currency)
        }
        Spacer(Modifier.height(Spacing.sm))
        ArticalsTextField(
            value = draft.name,
            onValueChange = { onChange(draft.copy(name = it)) },
            label = ArticalsStrings.PricingSettings.name,
            enabled = enabled,
        )
    }
}

@Composable
private fun MethodSection(
    draft: RatesDraft,
    enabled: Boolean,
    currency: String,
    onChange: (RatesDraft) -> Unit,
) {
    SectionCard(
        title = ArticalsStrings.PricingSettings.methodTitle,
        subtitle = PricingMethods.help(draft.method),
    ) {
        ChoiceChips(
            options = PricingMethods.all,
            selected = draft.method,
            optionLabel = { PricingMethods.label(it) },
            onSelect = { onChange(draft.copy(method = it)) },
        )
        Text(
            text = ArticalsStrings.Settings.flatRateHint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (draft.method == PricingMethods.ZoneBased) {
            Spacer(Modifier.height(Spacing.xs))
            ChoiceChips(
                options = listOf(true, false),
                selected = draft.fallbackToDistanceWhenZoneMissing,
                optionLabel = {
                    if (it) {
                        ArticalsStrings.PricingSettings.useDistanceInstead
                    } else {
                        ArticalsStrings.PricingSettings.refuseInstead
                    }
                },
                onSelect = { onChange(draft.copy(fallbackToDistanceWhenZoneMissing = it)) },
                label = ArticalsStrings.PricingSettings.zoneMissingFallback,
            )
        }
    }
}

@Composable
private fun ChargesSection(
    draft: RatesDraft,
    enabled: Boolean,
    currency: String,
    onChange: (RatesDraft) -> Unit,
) {
    val needsPerKm = draft.method == PricingMethods.BasePlusDistance || draft.method == PricingMethods.Hybrid
    SectionCard(title = ArticalsStrings.Settings.pricing) {
        ArticalsAmountField(
            value = draft.baseCharge,
            onValueChange = { onChange(draft.copy(baseCharge = it)) },
            label = ArticalsStrings.Settings.baseCharge,
            currency = currency,
            enabled = enabled,
        )
        if (needsPerKm) {
            Spacer(Modifier.height(Spacing.sm))
            ArticalsAmountField(
                value = draft.perKmRate,
                onValueChange = { onChange(draft.copy(perKmRate = it)) },
                label = ArticalsStrings.Settings.perKm,
                currency = currency,
                enabled = enabled,
            )
        }
        Spacer(Modifier.height(Spacing.sm))
        ArticalsAmountField(
            value = draft.minimumCharge,
            onValueChange = { onChange(draft.copy(minimumCharge = it)) },
            label = ArticalsStrings.Settings.minimumCharge,
            currency = currency,
            enabled = enabled,
        )
        Spacer(Modifier.height(Spacing.sm))
        ArticalsAmountField(
            value = draft.maximumCharge,
            onValueChange = { onChange(draft.copy(maximumCharge = it)) },
            label = ArticalsStrings.Settings.maximumCharge,
            currency = currency,
            enabled = enabled,
        )

        Spacer(Modifier.height(Spacing.md))
        ChoiceChips(
            options = RoundingModes.all,
            selected = draft.roundingMode,
            optionLabel = { RoundingModes.label(it) },
            onSelect = { onChange(draft.copy(roundingMode = it)) },
            label = ArticalsStrings.Settings.rounding,
        )
        Spacer(Modifier.height(Spacing.sm))
        ArticalsAmountField(
            value = draft.roundingIncrement,
            onValueChange = { onChange(draft.copy(roundingIncrement = it)) },
            label = ArticalsStrings.Settings.roundingIncrement,
            currency = currency,
            enabled = enabled,
            helper = ArticalsStrings.Settings.roundingNone,
        )
    }
}

@Composable
private fun BandsSection(
    draft: RatesDraft,
    enabled: Boolean,
    currency: String,
    onChange: (RatesDraft) -> Unit,
) {
    SectionCard(
        title = ArticalsStrings.Settings.bands,
        subtitle = if (draft.method == PricingMethods.DistanceBands) {
            ArticalsStrings.PricingSettings.bandsRequired
        } else {
            null
        },
    ) {
        draft.bands.forEachIndexed { index, band ->
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.sm)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    ArticalsTextField(
                        value = if (band.openEnded) "" else band.upToKm,
                        onValueChange = { value ->
                            val bands = draft.bands.toMutableList()
                            bands[index] = band.copy(upToKm = value)
                            onChange(draft.copy(bands = bands))
                        },
                        label = ArticalsStrings.Settings.bandUpTo,
                        enabled = enabled && !band.openEnded,
                        placeholder = if (band.openEnded) ArticalsStrings.Settings.bandOpen else null,
                        modifier = Modifier.weight(1f),
                    )
                    ArticalsAmountField(
                        value = band.charge,
                        onValueChange = { value ->
                            val bands = draft.bands.toMutableList()
                            bands[index] = band.copy(charge = value)
                            onChange(draft.copy(bands = bands))
                        },
                        label = ArticalsStrings.Settings.bandCharge,
                        currency = currency,
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            val bands = draft.bands.toMutableList()
                            bands[index] = band.copy(openEnded = !band.openEnded, upToKm = "")
                            onChange(draft.copy(bands = bands))
                        },
                        enabled = enabled,
                    ) {
                        Text(
                            if (band.openEnded) {
                                ArticalsStrings.Common.clear
                            } else {
                                ArticalsStrings.PricingSettings.openEndedBand
                            },
                        )
                    }
                    TextButton(
                        onClick = { onChange(draft.copy(bands = draft.bands.filterIndexed { i, _ -> i != index })) },
                        enabled = enabled,
                    ) { Text(ArticalsStrings.Common.delete) }
                }
            }
        }
        TextButton(
            onClick = { onChange(draft.copy(bands = draft.bands + BandField())) },
            enabled = enabled,
        ) { Text(ArticalsStrings.Settings.addBand) }
    }
}

@Composable
private fun ZonesSection(
    draft: RatesDraft,
    enabled: Boolean,
    currency: String,
    serviceAreas: List<String>,
    onChange: (RatesDraft) -> Unit,
) {
    SectionCard(title = ArticalsStrings.Settings.zoneRules) {
        draft.zoneRules.forEachIndexed { index, rule ->
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.sm)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    ArticalsDropdown(
                        label = ArticalsStrings.Settings.zoneFrom,
                        selected = rule.fromZoneId.takeIf { it.isNotBlank() },
                        options = serviceAreas,
                        optionLabel = { it },
                        onSelect = { zone ->
                            val rules = draft.zoneRules.toMutableList()
                            rules[index] = rule.copy(fromZoneId = zone)
                            onChange(draft.copy(zoneRules = rules))
                        },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    )
                    ArticalsDropdown(
                        label = ArticalsStrings.Settings.zoneTo,
                        selected = rule.toZoneId.takeIf { it.isNotBlank() },
                        options = serviceAreas,
                        optionLabel = { it },
                        onSelect = { zone ->
                            val rules = draft.zoneRules.toMutableList()
                            rules[index] = rule.copy(toZoneId = zone)
                            onChange(draft.copy(zoneRules = rules))
                        },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    ArticalsAmountField(
                        value = rule.charge,
                        onValueChange = { value ->
                            val rules = draft.zoneRules.toMutableList()
                            rules[index] = rule.copy(charge = value)
                            onChange(draft.copy(zoneRules = rules))
                        },
                        label = ArticalsStrings.Settings.bandCharge,
                        currency = currency,
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    )
                    ArticalsAmountField(
                        value = rule.adjustment,
                        onValueChange = { value ->
                            val rules = draft.zoneRules.toMutableList()
                            rules[index] = rule.copy(adjustment = value)
                            onChange(draft.copy(zoneRules = rules))
                        },
                        label = ArticalsStrings.Common.optional,
                        currency = currency,
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    )
                }
                TextButton(
                    onClick = {
                        onChange(draft.copy(zoneRules = draft.zoneRules.filterIndexed { i, _ -> i != index }))
                    },
                    enabled = enabled,
                ) { Text(ArticalsStrings.Common.delete) }
            }
        }
        TextButton(
            onClick = {
                onChange(
                    draft.copy(
                        zoneRules = draft.zoneRules + ZoneRuleField(
                            fromZoneId = serviceAreas.firstOrNull().orEmpty(),
                            toZoneId = serviceAreas.firstOrNull().orEmpty(),
                        ),
                    ),
                )
            },
            enabled = enabled && serviceAreas.isNotEmpty(),
        ) { Text(ArticalsStrings.PricingSettings.addArea) }

        if (serviceAreas.isEmpty()) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = ArticalsStrings.PricingSettings.serviceLevelsHint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SurchargesSection(
    draft: RatesDraft,
    enabled: Boolean,
    currency: String,
    onChange: (RatesDraft) -> Unit,
) {
    SectionCard(title = ArticalsStrings.Settings.surcharges) {
        draft.surcharges.forEachIndexed { index, surcharge ->
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.sm)) {
                ArticalsTextField(
                    value = surcharge.label,
                    onValueChange = { value ->
                        val list = draft.surcharges.toMutableList()
                        list[index] = surcharge.copy(label = value)
                        onChange(draft.copy(surcharges = list))
                    },
                    label = ArticalsStrings.Settings.surchargeLabel,
                    enabled = enabled,
                )
                Spacer(Modifier.height(Spacing.xs))
                ChoiceChips(
                    options = listOf("flat", "percentage"),
                    selected = surcharge.kind,
                    optionLabel = {
                        if (it == "flat") {
                            ArticalsStrings.Settings.surchargeAmount
                        } else {
                            ArticalsStrings.Settings.surchargePercent
                        }
                    },
                    onSelect = { kind ->
                        val list = draft.surcharges.toMutableList()
                        list[index] = surcharge.copy(kind = kind)
                        onChange(draft.copy(surcharges = list))
                    },
                    label = ArticalsStrings.Settings.succhargeKind,
                )
                Spacer(Modifier.height(Spacing.xs))
                if (surcharge.kind == "flat") {
                    ArticalsAmountField(
                        value = surcharge.amount,
                        onValueChange = { value ->
                            val list = draft.surcharges.toMutableList()
                            list[index] = surcharge.copy(amount = value)
                            onChange(draft.copy(surcharges = list))
                        },
                        label = ArticalsStrings.Settings.surchargeAmount,
                        currency = currency,
                        enabled = enabled,
                    )
                } else {
                    ArticalsTextField(
                        value = surcharge.percent,
                        onValueChange = { value ->
                            val list = draft.surcharges.toMutableList()
                            list[index] = surcharge.copy(percent = value)
                            onChange(draft.copy(surcharges = list))
                        },
                        label = ArticalsStrings.Settings.surchargePercent,
                        enabled = enabled,
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                    )
                }
                Spacer(Modifier.height(Spacing.xs))
                ChoiceChips(
                    options = ServiceLevel.all,
                    selected = surcharge.appliesTo.firstOrNull(),
                    optionLabel = { ServiceLevel.label(it) },
                    onSelect = { level ->
                        val list = draft.surcharges.toMutableList()
                        list[index] = surcharge.copy(
                            appliesTo = if (surcharge.appliesTo.contains(level)) emptyList() else listOf(level),
                        )
                        onChange(draft.copy(surcharges = list))
                    },
                    label = ArticalsStrings.Settings.appliesTo,
                )
                TextButton(
                    onClick = {
                        onChange(draft.copy(surcharges = draft.surcharges.filterIndexed { i, _ -> i != index }))
                    },
                    enabled = enabled,
                ) { Text(ArticalsStrings.Common.delete) }
            }
        }
        TextButton(
            onClick = { onChange(draft.copy(surcharges = draft.surcharges + SurchargeField.create())) },
            enabled = enabled,
        ) { Text(ArticalsStrings.Settings.addSurcharge) }
    }
}

@Composable
private fun RiderCostSection(
    draft: RatesDraft,
    enabled: Boolean,
    currency: String,
    onChange: (RatesDraft) -> Unit,
) {
    val rider = draft.riderCost
    SectionCard(
        title = ArticalsStrings.Settings.riderCost,
        subtitle = ArticalsStrings.PricingSettings.riderCostMethod,
    ) {
        ArticalsDropdown(
            label = ArticalsStrings.Settings.riderCostMethod,
            selected = rider.method,
            options = RiderCostMethods.all,
            optionLabel = { RiderCostMethods.label(it) },
            onSelect = { onChange(draft.copy(riderCost = rider.copy(method = it))) },
            enabled = enabled,
        )

        when (rider.method) {
            RiderCostMethods.DistanceRate -> {
                Spacer(Modifier.height(Spacing.sm))
                ArticalsAmountField(
                    value = rider.perKmRate,
                    onValueChange = { onChange(draft.copy(riderCost = rider.copy(perKmRate = it))) },
                    label = ArticalsStrings.Settings.riderCostPerKm,
                    currency = currency,
                    enabled = enabled,
                )
            }

            RiderCostMethods.DistanceBands -> {
                Spacer(Modifier.height(Spacing.sm))
                rider.bands.forEachIndexed { index, band ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        ArticalsTextField(
                            value = if (band.openEnded) "" else band.upToKm,
                            onValueChange = { value ->
                                val bands = rider.bands.toMutableList()
                                bands[index] = band.copy(upToKm = value)
                                onChange(draft.copy(riderCost = rider.copy(bands = bands)))
                            },
                            label = ArticalsStrings.Settings.bandUpTo,
                            enabled = enabled && !band.openEnded,
                            placeholder = if (band.openEnded) ArticalsStrings.Settings.bandOpen else null,
                            modifier = Modifier.weight(1f),
                        )
                        ArticalsAmountField(
                            value = band.charge,
                            onValueChange = { value ->
                                val bands = rider.bands.toMutableList()
                                bands[index] = band.copy(charge = value)
                                onChange(draft.copy(riderCost = rider.copy(bands = bands)))
                            },
                            label = ArticalsStrings.Settings.bandCharge,
                            currency = currency,
                            enabled = enabled,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = {
                                val bands = rider.bands.toMutableList()
                                bands[index] = band.copy(openEnded = !band.openEnded, upToKm = "")
                                onChange(draft.copy(riderCost = rider.copy(bands = bands)))
                            },
                            enabled = enabled,
                        ) {
                            Text(
                                if (band.openEnded) {
                                    ArticalsStrings.Common.clear
                                } else {
                                    ArticalsStrings.PricingSettings.openEndedBand
                                },
                            )
                        }
                        TextButton(
                            onClick = {
                                onChange(
                                    draft.copy(
                                        riderCost = rider.copy(
                                            bands = rider.bands.filterIndexed { i, _ -> i != index },
                                        ),
                                    ),
                                )
                            },
                            enabled = enabled,
                        ) { Text(ArticalsStrings.Common.delete) }
                    }
                }
                TextButton(
                    onClick = {
                        onChange(draft.copy(riderCost = rider.copy(bands = rider.bands + BandField())))
                    },
                    enabled = enabled,
                ) { Text(ArticalsStrings.Settings.addBand) }
            }

            RiderCostMethods.Fixed -> {
                Spacer(Modifier.height(Spacing.sm))
                ArticalsAmountField(
                    value = rider.defaultFee,
                    onValueChange = { onChange(draft.copy(riderCost = rider.copy(defaultFee = it))) },
                    label = ArticalsStrings.Settings.riderFixedFee,
                    currency = currency,
                    enabled = enabled,
                )
            }

            RiderCostMethods.HistoricalAverage -> {
                Spacer(Modifier.height(Spacing.sm))
                ArticalsAmountField(
                    value = rider.historicalAverage,
                    onValueChange = { onChange(draft.copy(riderCost = rider.copy(historicalAverage = it))) },
                    label = ArticalsStrings.PricingSettings.riderHistoricalAverage,
                    currency = currency,
                    enabled = enabled,
                    helper = ArticalsStrings.Settings.riderCostAverage,
                )
            }

            RiderCostMethods.ZoneBased -> {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = ArticalsStrings.PricingSettings.riderZonesRequired,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            RiderCostMethods.Manual -> {
                Spacer(Modifier.height(Spacing.xs))
                Banner(text = ArticalsStrings.Pricing.riderCostExplain, tone = BannerTone.Info)
            }
        }

        Spacer(Modifier.height(Spacing.sm))
        ArticalsAmountField(
            value = rider.minimumCost,
            onValueChange = { onChange(draft.copy(riderCost = rider.copy(minimumCost = it))) },
            label = ArticalsStrings.PricingSettings.riderMinimumCost,
            currency = currency,
            enabled = enabled,
            helper = ArticalsStrings.Common.optional,
        )
    }
}

@Composable
private fun TargetProfitSection(
    draft: RatesDraft,
    enabled: Boolean,
    currency: String,
    onChange: (RatesDraft) -> Unit,
) {
    SectionCard(
        title = ArticalsStrings.PricingSettings.targetProfit,
        subtitle = ArticalsStrings.PricingSettings.targetProfitExplain,
    ) {
        ArticalsAmountField(
            value = draft.targetProfitAmount,
            onValueChange = { onChange(draft.copy(targetProfitAmount = it)) },
            label = ArticalsStrings.Settings.targetMargin,
            currency = currency,
            enabled = enabled,
            helper = ArticalsStrings.Common.optional,
        )
        Spacer(Modifier.height(Spacing.sm))
        ArticalsTextField(
            value = draft.targetProfitMarginPercent,
            onValueChange = { onChange(draft.copy(targetProfitMarginPercent = it)) },
            label = ArticalsStrings.Settings.targetMargin,
            enabled = enabled,
            helper = ArticalsStrings.Settings.targetMarginExplain,
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
        )
    }
}
