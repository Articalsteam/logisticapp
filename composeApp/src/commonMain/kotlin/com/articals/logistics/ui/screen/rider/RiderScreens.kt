package com.articals.logistics.ui.screen.rider

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.articals.logistics.core.UiState
import com.articals.logistics.core.currentTimeMillis
import com.articals.logistics.core.openDialer
import com.articals.logistics.core.openInExternalMap
import com.articals.logistics.core.parseIsoInstantOrNull
import com.articals.logistics.core.relativeTime
import com.articals.logistics.data.dto.OrderDto
import com.articals.logistics.domain.order.FailureReason
import com.articals.logistics.domain.order.OrderStatus
import com.articals.logistics.ui.component.ArticalsDropdown
import com.articals.logistics.ui.component.ArticalsTextField
import com.articals.logistics.ui.component.Banner
import com.articals.logistics.ui.component.BannerTone
import com.articals.logistics.ui.component.EmptyState
import com.articals.logistics.ui.component.ErrorState
import com.articals.logistics.ui.component.LabelValueRow
import com.articals.logistics.ui.component.MetaChip
import com.articals.logistics.ui.component.SectionCard
import com.articals.logistics.ui.component.SkeletonList
import com.articals.logistics.ui.demo.DemoStrip
import com.articals.logistics.ui.screen.orders.StatusPill
import com.articals.logistics.ui.text.ArticalsStrings
import com.articals.logistics.ui.theme.Spacing
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

/**
 * The rider's home: the jobs they are carrying today.
 *
 * It is deliberately not a dashboard. A rider needs three things at a glance — what is
 * next, where it goes, and how to reach the customer — and everything else is in the
 * way. No money appears anywhere on this screen: a rider is not shown the company's
 * margin, and the delivery document they are allowed to read does not carry it.
 */
@Composable
fun RiderHomeScreen(
    tenantId: String,
    userId: String,
    timeZoneId: String,
    onOpenJob: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RiderHomeViewModel = koinViewModel(),
) {
    LaunchedEffect(tenantId, userId, timeZoneId) {
        viewModel.load(tenantId = tenantId, userId = userId, timeZoneId = timeZoneId)
    }

    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        DemoStrip()

        Column(modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.sm)) {
            Text(
                text = state.riderName ?: ArticalsStrings.Rider.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                MetaChip(text = "${ArticalsStrings.Rider.open}: ${state.activeCount}")
                MetaChip(text = "${ArticalsStrings.Rider.today}: ${state.doneTodayCount}")
            }
        }

        when (val jobs = state.jobs) {
            is UiState.Loading -> SkeletonList(rows = 3, height = 96.dp)
            is UiState.Error -> ErrorState(
                error = jobs.error,
                onRetry = { viewModel.load(tenantId, userId, timeZoneId) },
            )

            is UiState.Empty -> EmptyState(title = jobs.title, message = jobs.message)
            is UiState.Content -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = Spacing.screen,
                    end = Spacing.screen,
                    bottom = Spacing.xxl,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                items(items = jobs.data, key = { it.id }) { order ->
                    RiderJobCard(
                        order = order,
                        timeZoneId = timeZoneId,
                        onClick = { onOpenJob(order.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RiderJobCard(order: OrderDto, timeZoneId: String, onClick: () -> Unit) {
    SectionCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
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
                    text = order.destination.area ?: order.destination.address,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            StatusPill(status = order.status)
        }
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = "${order.pickup.area ?: order.pickup.address} → ${order.destination.address}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
        order.createdAt?.let { createdAt ->
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = relativeTime(
                    fromMillis = parseIsoInstantOrNull(createdAt) ?: 0L,
                    nowMillis = currentTimeMillis(),
                    timeZoneId = timeZoneId,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One job, from the rider's side.
 *
 * Only the steps the rider's own workflow allows are offered — pickup, on my way,
 * delivered, could not deliver — and the backend refuses anything else regardless. The
 * proof photo is a separate action rather than part of "delivered", because a rider in
 * a hurry should still be able to see that proof is expected before they close the job.
 */
@Composable
fun RiderJobScreen(
    tenantId: String,
    orderId: String,
    timeZoneId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RiderJobViewModel = koinViewModel(),
) {
    LaunchedEffect(tenantId, orderId) { viewModel.load(tenantId, orderId) }

    val state by viewModel.state.collectAsStateWithLifecycle()
    var problemOpen by remember { mutableStateOf(false) }
    var failureChoice by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.screen),
    ) {
        DemoStrip()

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

        when (val order = state.order) {
            is UiState.Loading -> SkeletonList(rows = 3, height = 96.dp)
            is UiState.Error -> ErrorState(
                error = order.error,
                onRetry = { viewModel.load(tenantId, orderId) },
            )

            is UiState.Empty -> ErrorState(error = com.articals.logistics.core.AppError.NotFound(detail = "order_not_found"))
            is UiState.Content -> RiderJobContent(
                order = order.data,
                state = state,
                timeZoneId = timeZoneId,
                onUpdateStatus = viewModel::updateStatus,
                onCaptureProof = viewModel::captureProof,
                onOpenProblem = { failureChoice = null; problemOpen = true },
            )
        }

        Spacer(Modifier.height(Spacing.lg))
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(ArticalsStrings.Common.back)
        }
    }

    if (problemOpen) {
        ProblemDialog(
            busy = state.busy,
            chosen = failureChoice,
            onChoose = { failureChoice = it },
            onDismiss = { problemOpen = false },
            onConfirm = { reason, notes ->
                viewModel.reportProblem(reason, notes)
                problemOpen = false
            },
        )
    }
}

@Composable
private fun RiderJobContent(
    order: OrderDto,
    state: RiderJobState,
    timeZoneId: String,
    onUpdateStatus: (String, String?, String?) -> Unit,
    onCaptureProof: () -> Unit,
    onOpenProblem: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    Spacer(Modifier.height(Spacing.lg))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = order.orderNumber,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = order.trackingReference,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StatusPill(status = order.status)
    }

    Spacer(Modifier.height(Spacing.lg))
    SectionCard(title = ArticalsStrings.Rider.jobDetail) {
        LabelValueRow(
            label = ArticalsStrings.Orders.customer,
            value = order.customerSnapshot?.name ?: ArticalsStrings.Common.notAvailable,
        )
        LabelValueRow(
            label = ArticalsStrings.Orders.description,
            value = order.description ?: ArticalsStrings.Common.notAvailable,
        )
        LabelValueRow(
            label = ArticalsStrings.Orders.pickup,
            value = order.pickup.address,
        )
        order.pickup.contactPhone?.let { phone ->
            TextButton(onClick = { scope.launch { openDialer(phone) } }) {
                Text(ArticalsStrings.Rider.callDispatcher)
            }
        }
        order.pickup.latitude?.let { latitude ->
            order.pickup.longitude?.let { longitude ->
                TextButton(onClick = { scope.launch { openInExternalMap(latitude, longitude, ArticalsStrings.Orders.pickup) } }) {
                    Text(ArticalsStrings.Rider.navToPickup)
                }
            }
        }

        Spacer(Modifier.height(Spacing.md))
        LabelValueRow(
            label = ArticalsStrings.Orders.dropoff,
            value = order.destination.address,
        )
        order.destination.area?.let { area ->
            Text(
                text = area,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        order.destination.notes?.let { notes ->
            Text(
                text = notes,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        order.destination.contactPhone?.let { phone ->
            TextButton(onClick = { scope.launch { openDialer(phone) } }) {
                Text(ArticalsStrings.Rider.callCustomer)
            }
        }
        order.destination.latitude?.let { latitude ->
            order.destination.longitude?.let { longitude ->
                TextButton(
                    onClick = {
                        scope.launch {
                            openInExternalMap(latitude, longitude, ArticalsStrings.Orders.dropoff)
                        }
                    },
                ) { Text(ArticalsStrings.Rider.navToDestination) }
            }
        }
    }

    // --- the steps ------------------------------------------------------------
    val next = OrderStatus.allowedNext(from = order.status, isAssignedRider = true)
    Spacer(Modifier.height(Spacing.md))
    SectionCard(title = ArticalsStrings.Rider.whatHappensNext) {
        if (next.isEmpty()) {
            Text(
                text = ArticalsStrings.Orders.nothingToDo,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        next.filter { it != OrderStatus.Failed }.forEach { target ->
            Button(
                onClick = { onUpdateStatus(target, null, null) },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            ) { Text(stepLabel(target)) }
        }
        if (next.contains(OrderStatus.Failed)) {
            OutlinedButton(
                onClick = onOpenProblem,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs),
            ) { Text(ArticalsStrings.Rider.failed) }
        }
    }

    // --- proof ---------------------------------------------------------------
    Spacer(Modifier.height(Spacing.md))
    SectionCard(title = ArticalsStrings.Rider.proofTitle) {
        if (state.proofSimulated) {
            Spacer(Modifier.height(Spacing.xs))
            Banner(text = ArticalsStrings.Rider.proofSimulated, tone = BannerTone.Info)
        }
        val uploaded = state.proofPath != null
        Spacer(Modifier.height(Spacing.sm))
        Button(
            onClick = onCaptureProof,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (uploaded) {
                    ArticalsStrings.Rider.proofRetake
                } else {
                    ArticalsStrings.Rider.proofTake
                },
            )
        }
        if (uploaded) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = "${ArticalsStrings.Rider.proofUploaded} — ${state.proofPath}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = ArticalsStrings.Rider.sendProofThenDeliver,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // --- history --------------------------------------------------------------
    if (state.events.isNotEmpty()) {
        Spacer(Modifier.height(Spacing.md))
        SectionCard(title = ArticalsStrings.Orders.timeline) {
            state.events.forEach { event ->
                Column(modifier = Modifier.padding(vertical = Spacing.xs)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = OrderStatus.label(event.status),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        event.createdAt?.let { at ->
                            Text(
                                text = relativeTime(
                                    fromMillis = parseIsoInstantOrNull(at) ?: 0L,
                                    nowMillis = currentTimeMillis(),
                                    timeZoneId = timeZoneId,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    event.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                        Text(
                            text = notes,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun stepLabel(status: String): String = when (status) {
    OrderStatus.PickedUp -> ArticalsStrings.Rider.pickedUp
    OrderStatus.InTransit -> ArticalsStrings.Rider.inTransit
    OrderStatus.Delivered -> ArticalsStrings.Rider.delivered
    else -> OrderStatus.label(status)
}

/** Reporting a problem, which for this backend means failing the delivery with a reason. */
@Composable
private fun ProblemDialog(
    busy: Boolean,
    chosen: String?,
    onChoose: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (reason: String, notes: String?) -> Unit,
) {
    var notes by remember { mutableStateOf("") }
    val reason = chosen ?: FailureReason.CustomerUnreachable

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(ArticalsStrings.Rider.problemTitle) },
        text = {
            Column {
                Text(
                    text = ArticalsStrings.Rider.problemExplain,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(Spacing.sm))
                ArticalsDropdown(
                    label = ArticalsStrings.Rider.failureReason,
                    selected = reason,
                    options = FailureReason.all,
                    optionLabel = { FailureReason.label(it) },
                    onSelect = onChoose,
                    enabled = !busy,
                )
                Spacer(Modifier.height(Spacing.sm))
                ArticalsTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = ArticalsStrings.Rider.problemHint,
                    singleLine = false,
                    minLines = 3,
                    enabled = !busy,
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = ArticalsStrings.Rider.problemBody,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(reason, notes.trim().takeIf { it.isNotEmpty() }) },
                enabled = !busy,
            ) { Text(ArticalsStrings.Rider.problemSend) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(ArticalsStrings.Common.cancel) }
        },
    )
}
