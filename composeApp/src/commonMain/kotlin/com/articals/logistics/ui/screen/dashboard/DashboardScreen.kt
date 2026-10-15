package com.articals.logistics.ui.screen.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.articals.logistics.core.formatMinorCompact
import com.articals.logistics.data.dto.DashboardMetricsDto
import com.articals.logistics.ui.component.Banner
import com.articals.logistics.ui.component.BannerTone
import com.articals.logistics.ui.component.MetaChip
import com.articals.logistics.ui.component.SectionCard
import com.articals.logistics.ui.component.SectionHeader
import com.articals.logistics.ui.component.SkeletonList
import com.articals.logistics.ui.component.StateHost
import com.articals.logistics.ui.text.ArticalsStrings
import com.articals.logistics.ui.theme.Spacing
import org.koin.compose.viewmodel.koinViewModel

/**
 * Where a working day starts.
 *
 * It answers three questions before anything else: what is happening today, what is
 * waiting for a decision, and is the money where it should be. When a role may not
 * see money, the financial block is replaced by an explanation of why — an empty
 * space would read as "we made nothing today", which is a very different statement.
 */
@Composable
fun DashboardScreen(
    tenantId: String,
    timeZoneId: String,
    currency: String,
    onOpenOrder: (String) -> Unit,
    onOpenOrders: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DashboardViewModel = koinViewModel(),
) {
    LaunchedEffect(tenantId, timeZoneId) { viewModel.load(tenantId, timeZoneId) }

    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.screen),
    ) {
        StateHost(
            state = state.metrics,
            loading = { SkeletonList(rows = 3, height = 88.dp) },
            onRetry = viewModel::refresh,
        ) { metrics ->
            MetricsBlock(metrics = metrics, currency = currency, refreshing = state.refreshing)
        }

        Spacer(Modifier.height(Spacing.lg))

        SectionHeader(title = ArticalsStrings.Dashboard.attentionTitle)
        StateHost(
            state = state.attention,
            loading = { SkeletonList(rows = 2, height = 64.dp) },
        ) { items ->
            Column {
                items.forEach { item ->
                    AttentionRow(item = item, onClick = { onOpenOrder(item.order.id) })
                }
                Spacer(Modifier.height(Spacing.sm))
                TextButton(onClick = onOpenOrders, modifier = Modifier.fillMaxWidth()) {
                    Text(ArticalsStrings.Common.seeAll)
                }
            }
        }
    }
}

@Composable
private fun MetricsBlock(
    metrics: DashboardMetricsDto,
    currency: String,
    refreshing: Boolean,
) {
    Column {
        TileGrid(
            tiles = listOf(
                ArticalsStrings.Dashboard.deliveriesToday to metrics.counts.deliveriesToday.toString(),
                ArticalsStrings.Dashboard.openNow to metrics.counts.inProgress.toString(),
                ArticalsStrings.Dashboard.waitingAssignment to metrics.counts.awaitingAssignment.toString(),
                ArticalsStrings.Dashboard.needsAttention to (
                    metrics.counts.failed + metrics.counts.pending
                    ).toString(),
            ),
        )

        Spacer(Modifier.height(Spacing.md))

        val financials = metrics.financials
        if (financials == null) {
            Banner(
                text = ArticalsStrings.Dashboard.noFinancialAccess,
                tone = BannerTone.Info,
            )
        } else {
            SectionCard(
                title = ArticalsStrings.Dashboard.revenue,
                subtitle = "${metrics.range.fromDateKey} – ${metrics.range.toDateKey}",
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = ArticalsStrings.Dashboard.revenue,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = formatMinorCompact(financials.revenueMinor, currency),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = ArticalsStrings.Dashboard.profit,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = formatMinorCompact(financials.actualProfitMinor, currency),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "${ArticalsStrings.Dashboard.estimatedProfit}: " +
                                formatMinorCompact(financials.estimatedProfitMinor, currency),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (financials.deliveriesAwaitingFinalAmounts > 0) {
                    Spacer(Modifier.height(Spacing.sm))
                    Banner(
                        text = "${ArticalsStrings.Dashboard.awaitingFinalAmounts}: " +
                            "${financials.deliveriesAwaitingFinalAmounts}",
                        tone = BannerTone.Warning,
                    )
                }

                Spacer(Modifier.height(Spacing.md))
                TrendBars(metrics = metrics)
            }
        }

        Spacer(Modifier.height(Spacing.md))
        DataQualityNote(metrics = metrics)
        if (refreshing) {
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = ArticalsStrings.Common.refresh,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TileGrid(tiles: List<Pair<String, String>>) {
    Column {
        tiles.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                row.forEach { (label, value) ->
                    SectionCard(modifier = Modifier.weight(1f)) {
                        Text(
                            text = value,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * A fourteen-day bar chart for completed deliveries.
 *
 * Bars rather than a line, and a fixed scale rather than an animated one, because the
 * point is comparison at a glance on a phone in daylight. Days with no deliveries are
 * drawn as empty slots, not skipped — a gap in a chart is a different claim from a
 * zero.
 */
@Composable
private fun TrendBars(metrics: DashboardMetricsDto) {
    val points = metrics.trends
    if (points.isEmpty()) return
    val max = points.maxOf { it.deliveriesCompleted }.coerceAtLeast(1)

    Column {
        Text(
            text = ArticalsStrings.Dashboard.trend,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.sm))
        Row(
            modifier = Modifier.fillMaxWidth().height(72.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            points.forEach { point ->
                val fraction = point.deliveriesCompleted.toFloat() / max
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height((72.dp * fraction).coerceAtLeast(2.dp))
                        .background(
                            color = if (point.deliveriesCompleted == 0) {
                                MaterialTheme.colorScheme.surfaceVariant
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            shape = MaterialTheme.shapes.extraSmall,
                        ),
                )
            }
        }
        Spacer(Modifier.height(Spacing.xs))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = points.first().date,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = points.last().date,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Says plainly what the numbers do and do not include. */
@Composable
private fun DataQualityNote(metrics: DashboardMetricsDto) {
    SectionCard(title = ArticalsStrings.Dashboard.dataQuality) {
        Text(
            text = ArticalsStrings.Dashboard.missingDays,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (metrics.dataQuality.hasUnfinalizedRevenue) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = ArticalsStrings.Dashboard.hasUnfinalized,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (metrics.dataQuality.pricingVersionChanged) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = ArticalsStrings.Dashboard.pricingChanged,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (metrics.dataQuality.missingDays.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                metrics.dataQuality.missingDays.take(4).forEach { day ->
                    MetaChip(text = day)
                }
                if (metrics.dataQuality.missingDays.size > 4) {
                    MetaChip(text = "+${metrics.dataQuality.missingDays.size - 4}")
                }
            }
        }
    }
}

/** One delivery that needs a decision, and why. */
@Composable
private fun AttentionRow(item: AttentionItem, onClick: () -> Unit) {
    SectionCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Spacing.sm)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.order.orderNumber,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = item.order.customerSnapshot?.name ?: item.order.destination.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.xs))
                MetaChip(text = item.reasonLabel)
            }
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = com.articals.logistics.domain.order.OrderStatus.label(item.order.status),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
