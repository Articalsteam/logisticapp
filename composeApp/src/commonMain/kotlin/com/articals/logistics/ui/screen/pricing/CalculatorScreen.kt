package com.articals.logistics.ui.screen.pricing

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.OutlinedButton
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
import com.articals.logistics.core.currentTimeMillis
import com.articals.logistics.core.formatMinor
import com.articals.logistics.core.parseIsoInstantOrNull
import com.articals.logistics.core.relativeTime
import com.articals.logistics.data.dto.QuoteDto
import com.articals.logistics.data.dto.SavedQuoteDto
import com.articals.logistics.ui.component.ArticalsDropdown
import com.articals.logistics.ui.component.ArticalsTextField
import com.articals.logistics.ui.component.Banner
import com.articals.logistics.ui.component.BannerTone
import com.articals.logistics.ui.component.EmptyState
import com.articals.logistics.ui.component.LoadingState
import com.articals.logistics.ui.component.SectionCard
import com.articals.logistics.ui.demo.DemoStrip
import com.articals.logistics.ui.text.ArticalsStrings
import com.articals.logistics.ui.theme.Spacing
import org.koin.compose.viewmodel.koinViewModel

/**
 * Price a delivery, and then do something with the price.
 *
 * The screen is one column in the order a dispatcher works: where it goes, how far,
 * what it is, what it costs, what to charge, and only then the buttons. The price
 * appears underneath the inputs it came from, so nobody has to remember what was on
 * screen when they asked for it.
 *
 * The two override fields are shown only when the role holds `pricing.manage`; when it
 * does not, the screen says so in one line rather than showing fields that would be
 * refused.
 */
@Composable
fun CalculatorScreen(
    tenantId: String,
    currency: String,
    timeZoneId: String,
    permissions: Set<String>,
    onOpenOrder: (String) -> Unit,
    onOpenQuotes: () -> Unit,
    /** A saved quote this screen was opened with, from the quote history. */
    pendingQuoteId: String? = null,
    modifier: Modifier = Modifier,
    viewModel: CalculatorViewModel = koinViewModel(),
) {
    LaunchedEffect(tenantId) { viewModel.load(tenantId) }

    LaunchedEffect(pendingQuoteId) {
        pendingQuoteId?.let(viewModel::reuseQuoteById)
    }

    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.createdOrderId) {
        state.createdOrderId?.let { orderId ->
            onOpenOrder(orderId)
            viewModel.consumeCreatedOrder()
        }
    }

    // Exactly what the backend checks: quoting needs `quotes.create`, a price the
    // dispatcher types needs `quotes.override_price`, and only a role that may create a
    // delivery gets the button that creates one.
    val canQuote = Permissions.QuotesCreate in permissions
    val canOverrideRiderCost = canQuote
    val canOverridePrice = Permissions.QuotesOverridePrice in permissions
    val canCreateOrder = Permissions.OrdersCreate in permissions

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.screen),
    ) {
        DemoStrip()

        Spacer(Modifier.height(Spacing.md))
        Text(
            text = ArticalsStrings.Pricing.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = ArticalsStrings.Pricing.subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        state.error?.let {
            Spacer(Modifier.height(Spacing.md))
            Banner(
                text = it.userMessage,
                tone = BannerTone.Danger,
                action = { TextButton(onClick = viewModel::clearError) { Text(ArticalsStrings.Common.close) } },
            )
        }
        state.notice?.let {
            Spacer(Modifier.height(Spacing.md))
            Banner(
                text = it,
                tone = BannerTone.Success,
                action = { TextButton(onClick = viewModel::clearNotice) { Text(ArticalsStrings.Common.close) } },
            )
        }

        Spacer(Modifier.height(Spacing.md))
        QuoteFormFields(
            state = state.form,
            onChange = viewModel::updateForm,
            currency = currency,
            serviceAreas = state.serviceAreas,
            canOverrideRiderCost = canOverrideRiderCost,
            canOverridePrice = canOverridePrice,
            enabled = !state.quoting && !state.creating,
        )

        Spacer(Modifier.height(Spacing.md))
        if (canQuote) {
            Button(
                onClick = { viewModel.requestQuote(save = false) },
                enabled = state.form.canAskForPrice && !state.quoting && !state.creating,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (state.quoting) {
                        ArticalsStrings.Pricing.calculating
                    } else {
                        ArticalsStrings.Pricing.calculate
                    },
                )
            }
        } else {
            Banner(text = ArticalsStrings.Pricing.needsQuotePermission, tone = BannerTone.Info)
        }

        when {
            state.quoting -> {
                Spacer(Modifier.height(Spacing.md))
                LoadingState(message = ArticalsStrings.Pricing.calculating)
            }

            state.quote != null -> {
                Spacer(Modifier.height(Spacing.md))
                QuoteBreakdown(quote = state.quote ?: QuoteDto(), currency = currency)

                Spacer(Modifier.height(Spacing.md))
                QuoteWarnings(
                    warnings = state.quote?.warnings.orEmpty(),
                    acknowledged = state.warningsAcknowledged,
                    onAcknowledge = viewModel::acknowledge,
                    blocked = state.quote?.requiresReviewBeforeAccepting == true,
                )

                Spacer(Modifier.height(Spacing.md))
                CustomerSection(
                    state = state,
                    onModeChange = viewModel::setCustomerMode,
                    onCustomerChange = viewModel::setCustomer,
                    onNewCustomerChange = viewModel::setNewCustomer,
                )

                Spacer(Modifier.height(Spacing.md))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    OutlinedButton(
                        onClick = { viewModel.requestQuote(save = true) },
                        enabled = !state.quoting && !state.creating,
                        modifier = Modifier.weight(1f),
                    ) { Text(ArticalsStrings.Pricing.saveQuote) }

                    Button(
                        onClick = viewModel::createOrder,
                        enabled = canCreateOrder &&
                            state.canCreate &&
                            state.customerValid &&
                            !state.creating,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            if (state.creating) {
                                ArticalsStrings.Common.saving
                            } else {
                                ArticalsStrings.Pricing.createOrder
                            },
                        )
                    }
                }
                if (!canCreateOrder) {
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        text = ArticalsStrings.Pricing.createNeedsPermission,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (!state.canCreate) {
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        text = ArticalsStrings.Pricing.blockedHint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(Spacing.lg))
        SavedQuotesSection(
            quotes = state.savedQuotes,
            currency = currency,
            timeZoneId = timeZoneId,
            onOpenAll = onOpenQuotes,
            onReuse = viewModel::reuseQuote,
        )
        Spacer(Modifier.height(Spacing.xxl))
    }
}

/** Who the delivery is for: a stored customer, a new one, or nobody. */
@Composable
private fun CustomerSection(
    state: CalculatorState,
    onModeChange: (CustomerMode) -> Unit,
    onCustomerChange: (String?) -> Unit,
    onNewCustomerChange: (String, String, String) -> Unit,
) {
    SectionCard(title = ArticalsStrings.Orders.customer) {
        ArticalsDropdown(
            label = ArticalsStrings.Settings.customerType,
            selected = state.customerMode,
            options = CustomerMode.entries.toList(),
            optionLabel = { mode ->
                when (mode) {
                    CustomerMode.None -> ArticalsStrings.Pricing.noCustomer
                    CustomerMode.Existing -> ArticalsStrings.Pricing.existingCustomer
                    CustomerMode.New -> ArticalsStrings.Pricing.newCustomer
                }
            },
            onSelect = onModeChange,
        )

        when (state.customerMode) {
            CustomerMode.None -> {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = ArticalsStrings.Pricing.noCustomerExplain,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            CustomerMode.Existing -> {
                Spacer(Modifier.height(Spacing.sm))
                if (state.customers.isEmpty()) {
                    Banner(text = ArticalsStrings.Settings.customersEmptyBody, tone = BannerTone.Info)
                } else {
                    ArticalsDropdown(
                        label = ArticalsStrings.Orders.customer,
                        selected = state.customers.firstOrNull { it.id == state.customerId },
                        options = state.customers,
                        optionLabel = { customer -> customer.name },
                        onSelect = { onCustomerChange(it.id) },
                    )
                }
            }

            CustomerMode.New -> {
                Spacer(Modifier.height(Spacing.sm))
                ArticalsTextField(
                    value = state.newCustomerName,
                    onValueChange = {
                        onNewCustomerChange(it, state.newCustomerPhone, state.newCustomerType)
                    },
                    label = ArticalsStrings.Settings.customerName,
                )
                Spacer(Modifier.height(Spacing.sm))
                ArticalsTextField(
                    value = state.newCustomerPhone,
                    onValueChange = {
                        onNewCustomerChange(state.newCustomerName, it, state.newCustomerType)
                    },
                    label = ArticalsStrings.Settings.customerPhone,
                )
                Spacer(Modifier.height(Spacing.sm))
                ArticalsDropdown(
                    label = ArticalsStrings.Settings.customerType,
                    selected = state.newCustomerType,
                    options = listOf("individual", "business"),
                    optionLabel = {
                        if (it == "business") {
                            ArticalsStrings.Settings.business
                        } else {
                            ArticalsStrings.Settings.individual
                        }
                    },
                    onSelect = { onNewCustomerChange(state.newCustomerName, state.newCustomerPhone, it) },
                )
            }
        }
    }
}

/** The last few saved quotes, with the one-tap "use this again". */
@Composable
private fun SavedQuotesSection(
    quotes: List<SavedQuoteDto>,
    currency: String,
    timeZoneId: String,
    onOpenAll: () -> Unit,
    onReuse: (SavedQuoteDto) -> Unit,
) {
    SectionCard(title = ArticalsStrings.Pricing.history) {
        if (quotes.isEmpty()) {
            EmptyState(
                title = ArticalsStrings.Pricing.historyEmpty,
                message = ArticalsStrings.Pricing.historyEmptyBody,
            )
            return@SectionCard
        }

        quotes.take(3).forEach { quote ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onReuse(quote) }
                    .padding(vertical = Spacing.xs),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "${quote.pickup.area ?: quote.pickup.address} → " +
                            "${quote.destination.area ?: quote.destination.address}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = formatMinor(quote.recommendedCustomerChargeMinor, currency),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }
                quote.createdAt?.let { createdAt ->
                    Text(
                        text = ArticalsStrings.Pricing.savedAt(
                            relativeTime(
                                fromMillis = parseIsoInstantOrNull(createdAt) ?: 0L,
                                nowMillis = currentTimeMillis(),
                                timeZoneId = timeZoneId,
                            ),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        TextButton(onClick = onOpenAll, modifier = Modifier.fillMaxWidth()) {
            Text(ArticalsStrings.Common.seeAll)
        }
    }
}
