package com.articals.logistics.ui.screen.pricing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.articals.logistics.core.currentTimeMillis
import com.articals.logistics.core.formatDistanceMetres
import com.articals.logistics.core.formatMinor
import com.articals.logistics.core.parseIsoInstantOrNull
import com.articals.logistics.core.relativeTime
import com.articals.logistics.ui.component.EmptyState
import com.articals.logistics.ui.component.LabelValueRow
import com.articals.logistics.ui.component.MetaChip
import com.articals.logistics.ui.component.SectionCard
import com.articals.logistics.ui.demo.DemoStrip
import com.articals.logistics.ui.text.ArticalsStrings
import com.articals.logistics.ui.theme.Spacing
import com.articals.logistics.domain.QuoteWarningCodes
import org.koin.compose.viewmodel.koinViewModel

/**
 * Quote history.
 *
 * A saved quote is a record of what a price was and why, so each entry shows the
 * journey, the price, the rider's expected cost and the profit — the four figures
 * somebody is looking for when they come back to a quote days later.
 */
@Composable
fun QuotesScreen(
    tenantId: String,
    currency: String,
    timeZoneId: String,
    onReuse: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: QuotesViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    androidx.compose.runtime.LaunchedEffect(tenantId) { viewModel.load(tenantId) }

    Column(modifier = modifier.fillMaxSize()) {
        DemoStrip()

        Column(modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.sm)) {
            Text(
                text = ArticalsStrings.Pricing.quoteHistoryTitle,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = ArticalsStrings.Pricing.quoteHistoryBody,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.quotes.isEmpty()) {
            EmptyState(
                title = ArticalsStrings.Pricing.historyEmpty,
                message = ArticalsStrings.Pricing.historyEmptyBody,
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.screen),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            items(items = state.quotes, key = { it.id }) { quote ->
                SectionCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${quote.pickup.area ?: quote.pickup.address} → " +
                                    "${quote.destination.area ?: quote.destination.address}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
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
                        Text(
                            text = formatMinor(quote.recommendedCustomerChargeMinor, currency),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    Spacer(Modifier.height(Spacing.sm))
                    quote.distance?.let { distance ->
                        LabelValueRow(
                            label = ArticalsStrings.Pricing.distance,
                            value = formatDistanceMetres(distance.metres),
                        )
                    }
                    LabelValueRow(
                        label = ArticalsStrings.Pricing.riderCost,
                        value = formatMinor(quote.estimatedRiderCost.amountMinor, currency),
                    )
                    LabelValueRow(
                        label = ArticalsStrings.Pricing.profit,
                        value = formatMinor(quote.estimatedProfitMinor, currency),
                    )

                    if (quote.warnings.isNotEmpty()) {
                        Spacer(Modifier.height(Spacing.sm))
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            quote.warnings.take(3).forEach { warning ->
                                MetaChip(text = QuoteWarningCodes.label(warning.code))
                            }
                        }
                    }

                    Spacer(Modifier.height(Spacing.sm))
                    OutlinedButton(onClick = { onReuse(quote.id) }) {
                        Text(ArticalsStrings.Pricing.useThisAgain)
                    }
                }
            }
        }
    }
}
