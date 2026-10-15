package com.articals.logistics.ui.screen.orders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.articals.logistics.core.AppError
import com.articals.logistics.core.Permissions
import com.articals.logistics.core.UiState
import com.articals.logistics.core.copyToClipboard
import com.articals.logistics.core.formatMinor
import com.articals.logistics.core.currentTimeMillis
import com.articals.logistics.core.openDialer
import com.articals.logistics.core.openInExternalMap
import com.articals.logistics.core.parseAmountToMinorOrNull
import com.articals.logistics.core.parseIsoInstantOrNull
import com.articals.logistics.core.relativeTime
import com.articals.logistics.core.sharePlainText
import com.articals.logistics.data.dto.DeliveryEventDto
import AdditionalCostDto
import com.articals.logistics.data.dto.OrderDto
import com.articals.logistics.data.dto.RiderDto
import com.articals.logistics.domain.order.FailureReason
import com.articals.logistics.domain.order.OrderStatus
import com.articals.logistics.ui.component.ArticalsAmountField
import com.articals.logistics.ui.component.ArticalsDropdown
import com.articals.logistics.ui.component.ArticalsTextField
import com.articals.logistics.ui.component.Banner
import com.articals.logistics.ui.component.BannerTone
import com.articals.logistics.ui.component.ErrorState
import com.articals.logistics.ui.component.EstimateVsActualRow
import com.articals.logistics.ui.component.MarginText
import com.articals.logistics.ui.component.LabelValueRow
import com.articals.logistics.ui.component.MetaChip
import com.articals.logistics.ui.component.SectionCard
import com.articals.logistics.ui.component.SkeletonList
import com.articals.logistics.ui.demo.DemoStrip
import com.articals.logistics.ui.text.ArticalsStrings
import com.articals.logistics.ui.theme.Spacing
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

/**
 * One delivery, in full.
 *
 * The order of the sections is the order of the questions a dispatcher asks: what is
 * it and where is it going, who is taking it, what is left to do, and what is it
 * worth. Money is the last of those on purpose — it matters, but it is not what the
 * person holding the phone at 7am needs first.
 *
 * Every action here is permission-gated twice: the screen hides what the role cannot
 * do, and the repository refuses it anyway. The screen's job is to never offer a
 * button that will fail; the backend's job is to never trust the screen.
 */
@Composable
fun OrderDetailScreen(
    tenantId: String,
    orderId: String,
    currency: String,
    timeZoneId: String,
    companyName: String,
    permissions: Set<String>,
    isDemoMode: Boolean,
    isAssignedRider: Boolean,
    onOpenTracking: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    viewModel: OrderDetailViewModel = koinViewModel(),
) {
    LaunchedEffect(tenantId, orderId) { viewModel.load(tenantId, orderId) }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var shareMessage by remember { mutableStateOf<String?>(null) }
    var shareNotice by remember { mutableStateOf<String?>(null) }

    val canAssign = Permissions.OrdersAssign in permissions
    val canUpdateStatus = Permissions.OrdersUpdateStatus in permissions
    val canRecordActuals = Permissions.OrdersRecordActuals in permissions
    val canCorrectStatus = Permissions.OrdersCorrectStatus in permissions

    Box(modifier = modifier.fillMaxSize()) {
        when (val order = state.order) {
            is UiState.Loading -> SkeletonList(rows = 4, height = 96.dp)
            is UiState.Error -> ErrorState(
                error = order.error,
                onRetry = { viewModel.load(tenantId, orderId) },
            )

            is UiState.Empty -> ErrorState(error = AppError.NotFound(detail = "order_not_found"))
            is UiState.Content -> OrderDetailContent(
                order = order.data,
                events = state.events,
                riders = state.riders,
                currency = currency,
                timeZoneId = timeZoneId,
                companyName = companyName,
                busy = state.busy,
                canAssign = canAssign,
                canUpdateStatus = canUpdateStatus,
                canRecordActuals = canRecordActuals,
                canCorrectStatus = canCorrectStatus,
                isAssignedRider = isAssignedRider,
                isDemoMode = isDemoMode,
                notice = state.notice ?: shareNotice,
                error = state.error,
                onAssign = viewModel::assign,
                onUpdateStatus = viewModel::updateStatus,
                onRecordActuals = viewModel::recordActuals,
                onOpenAssign = { viewModel.setAssigning(it) },
                onOpenActuals = { viewModel.setActualsOpen(it) },
                assigning = state.assigning,
                actualsOpen = state.actualsOpen,
                onClearError = viewModel::clearError,
                onClearNotice = {
                    viewModel.clearNotice()
                    shareNotice = null
                },
                onOpenTracking = onOpenTracking,
                onShareMessage = { message -> shareMessage = message },
                onCopyTracking = { value ->
                    copyToClipboard(
                        text = value,
                        label = ArticalsStrings.Orders.trackingLink,
                    )
                },
                onShareTracking = { message ->
                    scope.launch {
                        val shared = sharePlainText(
                            subject = ArticalsStrings.Orders.trackingLink,
                            text = message,
                        )
                        shareNotice = if (shared) {
                            ArticalsStrings.Share.openedWhatsApp
                        } else {
                            ArticalsStrings.Share.couldNotShare
                        }
                    }
                },
            )
        }

        shareMessage?.let { message ->
            ShareMessageDialog(
                message = message,
                onDismiss = { shareMessage = null },
                onCopy = {
                    copyToClipboard(
                        text = message,
                        label = ArticalsStrings.Orders.trackingLink,
                    )
                    shareNotice = ArticalsStrings.Share.copied
                    shareMessage = null
                },
            )
        }
    }
}

@Composable
private fun OrderDetailContent(
    order: OrderDto,
    events: List<DeliveryEventDto>,
    riders: List<RiderDto>,
    currency: String,
    timeZoneId: String,
    companyName: String,
    busy: Boolean,
    canAssign: Boolean,
    canUpdateStatus: Boolean,
    canRecordActuals: Boolean,
    canCorrectStatus: Boolean,
    isAssignedRider: Boolean,
    isDemoMode: Boolean,
    notice: String?,
    error: AppError?,
    assigning: Boolean,
    actualsOpen: Boolean,
    onAssign: (String?, String) -> Unit,
    onUpdateStatus: (String, String?, String?, String?) -> Unit,
    onRecordActuals: (Long, Long, List<AdditionalCostDto>, String?) -> Unit,
    onOpenAssign: (Boolean) -> Unit,
    onOpenActuals: (Boolean) -> Unit,
    onClearError: () -> Unit,
    onClearNotice: () -> Unit,
    onOpenTracking: (() -> Unit)?,
    onShareMessage: (String) -> Unit,
    onCopyTracking: (String) -> Unit,
    onShareTracking: (String) -> Unit,
) {
    var statusChoice by remember { mutableStateOf<String?>(null) }

    val trackingMessage = ArticalsStrings.Share.trackingMessage(
        companyName = companyName,
        orderNumber = order.orderNumber,
        link = null,
        isDemo = isDemoMode,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.screen),
    ) {
        DemoStrip()

        if (isDemoMode) {
            Spacer(Modifier.height(Spacing.md))
            Banner(text = ArticalsStrings.Demo.bannerBody, tone = BannerTone.Info)
        }

        error?.let {
            Spacer(Modifier.height(Spacing.md))
            Banner(
                text = it.userMessage,
                tone = BannerTone.Danger,
                action = {
                    TextButton(onClick = onClearError) { Text(ArticalsStrings.Common.close) }
                },
            )
        }

        notice?.let {
            Spacer(Modifier.height(Spacing.md))
            Banner(
                text = it,
                tone = BannerTone.Success,
                action = {
                    TextButton(onClick = onClearNotice) { Text(ArticalsStrings.Common.close) }
                },
            )
        }

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

        attentionLabelOf(order)?.let { reason ->
            Spacer(Modifier.height(Spacing.sm))
            Banner(text = reason, tone = BannerTone.Warning)
        }

        // --- the delivery itself ------------------------------------------
        Spacer(Modifier.height(Spacing.lg))
        SectionCard(title = ArticalsStrings.Orders.sectionDetails) {
            LabelValueRow(label = ArticalsStrings.Orders.customer, value = order.customerSnapshot?.name ?: "—")
            order.customerSnapshot?.phone?.let { phone ->
                LabelValueRow(label = ArticalsStrings.Orders.contact, value = phone)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    val scope = rememberCoroutineScope()
                    TextButton(onClick = { scope.launch { openDialer(phone) } }) {
                        Text(ArticalsStrings.Orders.call)
                    }
                }
            }
            LabelValueRow(
                label = ArticalsStrings.Orders.description,
                value = order.description ?: ArticalsStrings.Common.notAvailable,
            )
            LabelValueRow(
                label = ArticalsStrings.Orders.itemCategory,
                value = order.itemCategory
                    ?.let { com.articals.logistics.domain.order.ItemCategory.label(it) }
                    ?: ArticalsStrings.Common.notAvailable,
            )
            order.scheduledAt?.takeIf { it.isNotBlank() }?.let {
                LabelValueRow(label = ArticalsStrings.Orders.scheduled, value = it)
            }
        }

        Spacer(Modifier.height(Spacing.md))
        SectionCard(title = ArticalsStrings.Orders.route) {
            AddressBlock(
                title = ArticalsStrings.Orders.pickup,
                area = order.pickup.area,
                address = order.pickup.address,
                contactName = order.pickup.contactName,
                contactPhone = order.pickup.contactPhone,
                latitude = order.pickup.latitude,
                longitude = order.pickup.longitude,
            )
            Spacer(Modifier.height(Spacing.md))
            AddressBlock(
                title = ArticalsStrings.Orders.dropoff,
                area = order.destination.area,
                address = order.destination.address,
                contactName = order.destination.contactName,
                contactPhone = order.destination.contactPhone,
                latitude = order.destination.latitude,
                longitude = order.destination.longitude,
            )

            order.distance?.let { distance ->
                Spacer(Modifier.height(Spacing.md))
                LabelValueRow(
                    label = ArticalsStrings.Orders.distance,
                    value = "${distance.metres / 1000.0} km",
                )
                Text(
                    text = if (distance.source == "manual") {
                        ArticalsStrings.Orders.distanceManual
                    } else {
                        ArticalsStrings.Orders.distanceProvider
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // --- who is taking it ---------------------------------------------
        Spacer(Modifier.height(Spacing.md))
        SectionCard(title = ArticalsStrings.Orders.rider) {
            LabelValueRow(
                label = ArticalsStrings.Orders.status,
                value = OrderStatus.label(order.status),
            )
            LabelValueRow(
                label = ArticalsStrings.Orders.rider,
                value = order.riderSnapshot?.name ?: ArticalsStrings.Orders.unassigned,
            )
            order.riderSnapshot?.phone?.let { phone ->
                val scope = rememberCoroutineScope()
                TextButton(onClick = { scope.launch { openDialer(phone) } }) {
                    Text(ArticalsStrings.Orders.call)
                }
            }
            if (canAssign && !isAssignedRider) {
                Spacer(Modifier.height(Spacing.sm))
                OutlinedButton(
                    onClick = { onOpenAssign(true) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (order.assignedRiderId == null) {
                            ArticalsStrings.Orders.assignRider
                        } else {
                            ArticalsStrings.Orders.reassign
                        },
                    )
                }
            }
        }

        // --- what is left to do -------------------------------------------
        val next = OrderStatus.allowedNext(
            from = order.status,
            isAssignedRider = isAssignedRider,
            canCorrectStatus = canCorrectStatus,
        )
        val actionable = next.filter { it != OrderStatus.Failed && it != OrderStatus.Cancelled }

        Spacer(Modifier.height(Spacing.md))
        SectionCard(title = ArticalsStrings.Orders.sectionActions) {
            if (!canUpdateStatus && !canRecordActuals) {
                Text(
                    text = ArticalsStrings.Shell.noAccessBody,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (actionable.isEmpty() && OrderStatus.terminal.contains(order.status)) {
                Text(
                    text = ArticalsStrings.Orders.nothingToDo,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (canUpdateStatus) {
                actionable.forEach { target ->
                    val isCorrection = target in correctionsFor(order.status)
                    Button(
                        onClick = { statusChoice = target },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    ) {
                        Text(
                            if (isCorrection) {
                                "${ArticalsStrings.Orders.correctStatus}: ${OrderStatus.label(target)}"
                            } else {
                                OrderStatus.label(target)
                            },
                        )
                    }
                }

                if (next.contains(OrderStatus.Failed)) {
                    OutlinedButton(
                        onClick = { statusChoice = OrderStatus.Failed },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs),
                    ) { Text(ArticalsStrings.Orders.failureTitle) }
                }
                if (next.contains(OrderStatus.Cancelled)) {
                    OutlinedButton(
                        onClick = { statusChoice = OrderStatus.Cancelled },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs),
                    ) { Text(ArticalsStrings.Orders.cancelOrder) }
                }
            }

            if (canRecordActuals) {
                Spacer(Modifier.height(Spacing.sm))
                OutlinedButton(
                    onClick = { onOpenActuals(true) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (order.actualsRecordedAt == null) {
                            ArticalsStrings.Orders.recordActuals
                        } else {
                            ArticalsStrings.Orders.actualsRecorded
                        },
                    )
                }
                Text(
                    text = ArticalsStrings.Orders.actualsExplain,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // --- money ----------------------------------------------------------
        Spacer(Modifier.height(Spacing.md))
        SectionCard(title = ArticalsStrings.Orders.sectionMoney) {
            // Estimated against actual, side by side: the difference is the whole point
            // of recording final amounts, and it is the first thing an owner looks at.
            EstimateVsActualRow(
                label = ArticalsStrings.Orders.actualCharge,
                estimatedMinor = order.customerChargeMinor,
                actualMinor = order.actualCustomerChargeMinor,
                currency = currency,
            )
            EstimateVsActualRow(
                label = ArticalsStrings.Orders.actualRiderCost,
                estimatedMinor = order.estimatedRiderCostMinor,
                actualMinor = order.actualRiderCostMinor,
                currency = currency,
            )
            EstimateVsActualRow(
                label = ArticalsStrings.Orders.profit,
                estimatedMinor = order.estimatedProfitMinor,
                actualMinor = order.actualProfitMinor,
                currency = currency,
            )
            LabelValueRow(
                label = ArticalsStrings.Orders.additionalCosts,
                value = formatMinor(order.estimatedAdditionalCostsMinor, currency),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = ArticalsStrings.Orders.margin,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MarginText(bps = order.estimatedMarginBps)
            }
            if (order.actualCustomerChargeMinor == null) {
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    text = ArticalsStrings.Orders.noActuals,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            order.pricingSnapshot.version?.let { version ->
                Spacer(Modifier.height(Spacing.sm))
                MetaChip(text = "${ArticalsStrings.Settings.version} $version")
            }
        }

        // --- the tracking link ----------------------------------------------
        Spacer(Modifier.height(Spacing.md))
        SectionCard(title = ArticalsStrings.Orders.shareTitle, subtitle = ArticalsStrings.Orders.shareBody) {
            Text(
                text = order.trackingReference,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(Spacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                onOpenTracking?.let { open ->
                    OutlinedButton(onClick = open) { Text(ArticalsStrings.Tracking.title) }
                }
                OutlinedButton(onClick = { onCopyTracking(order.trackingReference) }) {
                    Text(ArticalsStrings.Orders.copyLink)
                }
            }
            Spacer(Modifier.height(Spacing.sm))
            Button(
                onClick = {
                    onShareTracking(trackingMessage)
                    onShareMessage(trackingMessage)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(ArticalsStrings.Orders.openWhatsApp) }
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = ArticalsStrings.Share.notSent,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // --- the timeline ----------------------------------------------------
        Spacer(Modifier.height(Spacing.md))
        SectionCard(title = ArticalsStrings.Orders.timeline) {
            if (events.isEmpty()) {
                Text(
                    text = ArticalsStrings.Orders.noEvents,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            events.forEach { event ->
                TimelineRow(event = event, timeZoneId = timeZoneId)
            }
        }

        Spacer(Modifier.height(Spacing.xxl))
    }

    statusChoice?.let { target ->
        StatusChangeDialog(
            order = order,
            target = target,
            isCorrection = target in correctionsFor(order.status),
            busy = busy,
            onDismiss = { statusChoice = null },
            onConfirm = { notes, failureReason, reason ->
                onUpdateStatus(target, notes, failureReason, reason)
                statusChoice = null
            },
        )
    }

    if (assigning) {
        AssignRiderDialog(
            riders = riders,
            assignedRiderId = order.assignedRiderId,
            busy = busy,
            onDismiss = { onOpenAssign(false) },
            onConfirm = { riderId, reason ->
                onAssign(riderId, reason)
            },
        )
    }

    if (actualsOpen) {
        ActualsDialog(
            currency = currency,
            order = order,
            busy = busy,
            onDismiss = { onOpenActuals(false) },
            onConfirm = { charge, riderCost, extras, notes ->
                onRecordActuals(charge, riderCost, extras, notes)
            },
        )
    }
}

/** A status is a correction when the plain workflow does not allow it. */
private fun correctionsFor(from: String): Set<String> =
    OrderStatus.allowedNext(from, isAssignedRider = false, canCorrectStatus = true).toSet() -
        OrderStatus.allowedNext(from, isAssignedRider = false, canCorrectStatus = false).toSet()

@Composable
private fun AddressBlock(
    title: String,
    area: String?,
    address: String,
    contactName: String?,
    contactPhone: String?,
    latitude: Double?,
    longitude: Double?,
) {
    val scope = rememberCoroutineScope()
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(text = address, style = MaterialTheme.typography.bodyMedium)
        area?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (contactName != null || contactPhone != null) {
            Text(
                text = listOfNotNull(contactName, contactPhone).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (latitude != null && longitude != null) {
            TextButton(onClick = { scope.launch { openInExternalMap(latitude, longitude, title) } }) {
                Text(ArticalsStrings.Orders.openInMaps)
            }
        }
    }
}

@Composable
private fun TimelineRow(event: DeliveryEventDto, timeZoneId: String) {
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

/**
 * Confirming a status change.
 *
 * The dialog asks for exactly what the transition needs and nothing more: a failure
 * reason for a failed delivery, a written reason for a correction, and an optional
 * note everywhere else.
 */
@Composable
private fun StatusChangeDialog(
    order: OrderDto,
    target: String,
    isCorrection: Boolean,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (notes: String?, failureReason: String?, reason: String?) -> Unit,
) {
    var notes by remember { mutableStateOf("") }
    var failureReason by remember { mutableStateOf(FailureReason.CustomerUnreachable) }
    var reason by remember { mutableStateOf("") }

    val isFailure = target == OrderStatus.Failed
    val isCancellation = target == OrderStatus.Cancelled
    val reasonRequired = isCorrection || isCancellation

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("${OrderStatus.label(order.status)} → ${OrderStatus.label(target)}") },
        text = {
            Column {
                if (isCorrection) {
                    Text(
                        text = ArticalsStrings.Orders.correctionExplain,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(Spacing.sm))
                }
                if (isFailure) {
                    ArticalsDropdown(
                        label = ArticalsStrings.Orders.failureReason,
                        options = FailureReason.all,
                        selected = failureReason,
                        optionLabel = { FailureReason.label(it) },
                        onSelect = { failureReason = it },
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    ArticalsTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = ArticalsStrings.Orders.failureNotes,
                        singleLine = false,
                        minLines = 2,
                    )
                } else {
                    ArticalsTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = ArticalsStrings.Orders.statusNotes,
                        singleLine = false,
                        minLines = 2,
                    )
                }
                if (reasonRequired) {
                    Spacer(Modifier.height(Spacing.sm))
                    ArticalsTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        label = if (isCancellation) {
                            ArticalsStrings.Orders.cancelReason
                        } else {
                            ArticalsStrings.Orders.assignmentReason
                        },
                        singleLine = false,
                        minLines = 2,
                        error = if (reason.trim().length < 3) {
                            ArticalsStrings.Orders.cancelReasonRequired
                        } else {
                            null
                        },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        notes.trim().takeIf { it.isNotEmpty() },
                        if (isFailure) failureReason else null,
                        reason.trim().takeIf { it.isNotEmpty() },
                    )
                },
                enabled = !busy && (!reasonRequired || reason.trim().length >= 3),
            ) { Text(ArticalsStrings.Common.confirm) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(ArticalsStrings.Common.cancel) }
        },
    )
}

/**
 * Choosing a rider, and saying why.
 *
 * The list is the company's active riders — fetched from the repository, never
 * invented — and the reason is mandatory because an assignment change is exactly the
 * kind of decision a manager later needs to explain.
 */
@Composable
private fun AssignRiderDialog(
    riders: List<RiderDto>,
    assignedRiderId: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (riderId: String?, reason: String) -> Unit,
) {
    var selected by remember { mutableStateOf(assignedRiderId ?: riders.firstOrNull()?.id.orEmpty()) }
    var reason by remember { mutableStateOf("") }
    val reasonTooShort = reason.trim().length < 3

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Text(
                if (assignedRiderId == null) {
                    ArticalsStrings.Orders.assignRider
                } else {
                    ArticalsStrings.Orders.reassign
                },
            )
        },
        text = {
            Column {
                if (riders.isEmpty()) {
                    Text(
                        text = ArticalsStrings.Settings.ridersEmptyBody,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    ArticalsDropdown(
                        label = ArticalsStrings.Orders.rider,
                        selected = riders.firstOrNull { it.id == selected },
                        options = riders,
                        optionLabel = { rider -> rider.name },
                        onSelect = { selected = it.id },
                        enabled = !busy,
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    ArticalsTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        label = ArticalsStrings.Orders.assignmentReason,
                        singleLine = false,
                        minLines = 2,
                        enabled = !busy,
                        error = if (reasonTooShort && reason.isNotEmpty()) {
                            ArticalsStrings.Orders.cancelReasonRequired
                        } else {
                            null
                        },
                    )
                    if (assignedRiderId != null) {
                        Spacer(Modifier.height(Spacing.sm))
                        TextButton(
                            onClick = { onConfirm(null, reason) },
                            enabled = !busy && !reasonTooShort,
                        ) { Text(ArticalsStrings.Orders.unassign) }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selected.takeIf { it.isNotBlank() }, reason) },
                enabled = !busy && riders.isNotEmpty() && !reasonTooShort,
            ) { Text(ArticalsStrings.Orders.assign) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(ArticalsStrings.Common.cancel) }
        },
    )
}

/** Final amounts, entered by the person who was there. */
@Composable
private fun ActualsDialog(
    currency: String,
    order: OrderDto,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (chargeMinor: Long, riderCostMinor: Long, extras: List<AdditionalCostDto>, notes: String?) -> Unit,
) {
    var charge by remember { mutableStateOf("") }
    var riderCost by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }

    val chargeMinor = parseAmountToMinorOrNull(charge)
    val riderCostMinor = parseAmountToMinorOrNull(riderCost)
    val valid = chargeMinor != null && riderCostMinor != null

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(ArticalsStrings.Orders.recordActuals) },
        text = {
            Column {
                Text(
                    text = ArticalsStrings.Orders.actualsExplain,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(Spacing.sm))
                ArticalsAmountField(
                    value = charge,
                    onValueChange = { charge = it },
                    label = ArticalsStrings.Orders.actualCharge,
                    currency = currency,
                    enabled = !busy,
                    helper = formatMinor(order.customerChargeMinor, currency),
                )
                Spacer(Modifier.height(Spacing.sm))
                ArticalsAmountField(
                    value = riderCost,
                    onValueChange = { riderCost = it },
                    label = ArticalsStrings.Orders.actualRiderCost,
                    currency = currency,
                    enabled = !busy,
                    helper = formatMinor(order.estimatedRiderCostMinor, currency),
                )
                Spacer(Modifier.height(Spacing.sm))
                ArticalsTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = ArticalsStrings.Orders.statusNotes,
                    singleLine = false,
                    minLines = 2,
                    enabled = !busy,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        chargeMinor ?: 0L,
                        riderCostMinor ?: 0L,
                        order.additionalCosts,
                        notes.trim().takeIf { it.isNotEmpty() },
                    )
                },
                enabled = valid && !busy,
            ) { Text(ArticalsStrings.Common.save) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(ArticalsStrings.Common.cancel) }
        },
    )
}

/** Shows the message that would be sent, so nothing goes out unseen. */
@Composable
private fun ShareMessageDialog(
    message: String,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ArticalsStrings.Orders.shareTitle) },
        text = {
            Column {
                Text(text = message, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    text = ArticalsStrings.Share.notSent,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = onCopy) { Text(ArticalsStrings.Orders.copyMessage) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(ArticalsStrings.Common.close) }
        },
    )
}
