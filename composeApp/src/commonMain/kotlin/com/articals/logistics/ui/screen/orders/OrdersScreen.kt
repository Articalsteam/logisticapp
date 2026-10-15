package com.articals.logistics.ui.screen.orders

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.articals.logistics.core.formatMinor
import com.articals.logistics.core.relativeTime
import com.articals.logistics.core.currentTimeMillis
import com.articals.logistics.core.UiState
import com.articals.logistics.data.dto.OrderDto
import com.articals.logistics.domain.order.OrderStatus
import com.articals.logistics.ui.component.ChoiceChips
import com.articals.logistics.ui.component.EmptyState
import com.articals.logistics.ui.component.ErrorState
import com.articals.logistics.ui.component.SkeletonList
import com.articals.logistics.ui.component.SortMenu
import com.articals.logistics.ui.demo.DemoStrip
import com.articals.logistics.ui.text.ArticalsStrings
import com.articals.logistics.ui.theme.Spacing
import org.koin.compose.viewmodel.koinViewModel

/**
 * The delivery list.
 *
 * Search, filter, sort and paging all run on what the device already has, so the list
 * is usable while the network is not. The header states the window it covers, because
 * a list that quietly stops at two weeks ago would be a lie by omission.
 */
@Composable
fun OrdersScreen(
    tenantId: String,
    timeZoneId: String,
    currency: String,
    onOpenOrder: (String) -> Unit,
    onNewOrder: (() -> Unit)?,
    modifier: Modifier = Modifier,
    viewModel: OrdersViewModel = koinViewModel(),
) {
    LaunchedEffect(tenantId, timeZoneId) { viewModel.load(tenantId, timeZoneId) }

    val state by viewModel.state.collectAsStateWithLifecycle()

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            DemoStrip()

            Column(modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.sm)) {
                OutlinedTextField(
                    value = state.search,
                    onValueChange = viewModel::onSearchChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(ArticalsStrings.Orders.searchHint) },
                    trailingIcon = {
                        if (state.search.isNotEmpty()) {
                            IconButton(onClick = viewModel::clearSearch) {
                                Icon(Icons.Default.Close, contentDescription = ArticalsStrings.Common.clear)
                            }
                        }
                    },
                )
                Spacer(Modifier.height(Spacing.sm))
                ChoiceChips(
                    options = OrderFilter.entries.toList(),
                    selected = state.filter,
                    optionLabel = ::filterLabel,
                    onSelect = viewModel::onFilterChange,
                )
                Spacer(Modifier.height(Spacing.sm))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${ArticalsStrings.Orders.filter}: ${state.totalMatching} " +
                            "(${state.windowDays} days)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SortMenu(
                        current = state.sort,
                        label = ::sortLabel,
                        options = OrderSort.entries.toList(),
                        onSelect = viewModel::onSortChange,
                    )
                }
            }

            when (val orders = state.orders) {
                is UiState.Loading -> SkeletonList(rows = 5, height = 92.dp)
                is UiState.Error -> ErrorState(error = orders.error, onRetry = { viewModel.load(tenantId, timeZoneId) })
                is UiState.Empty -> EmptyState(
                    title = orders.title,
                    message = orders.message,
                    actionLabel = if (onNewOrder != null) ArticalsStrings.Orders.newOrder else null,
                    onAction = onNewOrder,
                )

                is UiState.Content -> {
                    if (state.page.isEmpty()) {
                        EmptyState(
                            title = if (state.search.isNotEmpty()) {
                                ArticalsStrings.Orders.emptySearch
                            } else {
                                ArticalsStrings.Orders.emptyFilter
                            },
                            message = if (state.search.isNotEmpty()) {
                                ArticalsStrings.Orders.emptySearchBody
                            } else {
                                ArticalsStrings.Orders.emptyFilterBody
                            },
                            actionLabel = ArticalsStrings.Common.clear,
                            onAction = viewModel::clearSearch,
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                start = Spacing.screen,
                                end = Spacing.screen,
                                bottom = 96.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) {
                            items(items = state.page, key = { it.id }) { order ->
                                OrderRow(
                                    order = order,
                                    currency = currency,
                                    timeZoneId = timeZoneId,
                                    onClick = { onOpenOrder(order.id) },
                                )
                            }
                            if (state.hasMore) {
                                item {
                                    TextButton(
                                        onClick = viewModel::loadMore,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) { Text(ArticalsStrings.Orders.loadMore) }
                                }
                            } else {
                                item {
                                    Text(
                                        text = ArticalsStrings.Orders.showingAll,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.fillMaxWidth().padding(Spacing.sm),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // The button exists only when the screen behind it does. A floating button
        // that opens nothing would be worse than no button at all.
        if (onNewOrder != null) {
            ExtendedFloatingActionButton(
                onClick = onNewOrder,
                modifier = Modifier.align(Alignment.BottomEnd).padding(Spacing.lg),
                text = { Text(ArticalsStrings.Orders.newOrder) },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
            )
        }
    }
}

/** One delivery, with the two facts a dispatcher needs: where, and how much. */
@Composable
private fun OrderRow(
    order: OrderDto,
    currency: String,
    timeZoneId: String,
    onClick: () -> Unit,
) {
    val attention = attentionLabelOf(order)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(Spacing.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = order.orderNumber,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = order.customerSnapshot?.name
                        ?: order.customerId?.let { ArticalsStrings.Orders.customer }
                        ?: ArticalsStrings.Orders.customer,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusPill(status = order.status)
        }

        Spacer(Modifier.height(Spacing.sm))

        Text(
            text = "${order.pickup.area ?: order.pickup.address} → " +
                "${order.destination.area ?: order.destination.address}",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
        )

        Spacer(Modifier.height(Spacing.xs))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = order.riderSnapshot?.name ?: ArticalsStrings.Orders.unassigned,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatMinor(order.customerChargeMinor, currency),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }

        order.createdAt?.let { createdAt ->
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = relativeTime(
                    fromMillis = com.articals.logistics.core.parseIsoInstantOrNull(createdAt) ?: 0L,
                    nowMillis = currentTimeMillis(),
                    timeZoneId = timeZoneId,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (attention != null) {
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = attention,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** Colour plus words: colour alone would exclude anybody who cannot distinguish it. */
@Composable
fun StatusPill(status: String, modifier: Modifier = Modifier) {
    val (container, onContainer) = statusColors(status)
    Box(
        modifier = modifier
            .background(container, RoundedCornerShape(8.dp))
            .padding(horizontal = Spacing.sm, vertical = 2.dp),
    ) {
        Text(
            text = OrderStatus.shortLabel(status),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = onContainer,
        )
    }
}

@Composable
private fun statusColors(status: String): Pair<Color, Color> = when (status) {
    OrderStatus.Delivered -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    OrderStatus.Failed -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    OrderStatus.Cancelled -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    OrderStatus.InTransit, OrderStatus.PickedUp ->
        MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer

    else -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
}

private fun filterLabel(filter: OrderFilter): String = when (filter) {
    OrderFilter.All -> ArticalsStrings.Orders.filterAll
    OrderFilter.Open -> ArticalsStrings.Orders.filterOpen
    OrderFilter.NeedsAttention -> ArticalsStrings.Orders.filterNeedsAttention
    OrderFilter.Today -> ArticalsStrings.Orders.filterToday
}

private fun sortLabel(sort: OrderSort): String = when (sort) {
    OrderSort.Newest -> ArticalsStrings.Orders.sortNewest
    OrderSort.Oldest -> ArticalsStrings.Orders.sortOldest
    OrderSort.HighestValue -> ArticalsStrings.Orders.sortHighestValue
    OrderSort.Status -> ArticalsStrings.Orders.sortStatus
}
