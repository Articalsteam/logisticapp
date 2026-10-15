package com.articals.logistics.ui.screen.riders

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
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
import com.articals.logistics.core.copyToClipboard
import com.articals.logistics.core.formatMinor
import com.articals.logistics.core.sharePlainText
import com.articals.logistics.data.dto.RiderDto
import com.articals.logistics.domain.RiderStatuses
import com.articals.logistics.domain.VehicleTypes
import com.articals.logistics.domain.order.OrderStatus
import com.articals.logistics.ui.component.ArticalsDropdown
import com.articals.logistics.ui.component.ArticalsTextField
import com.articals.logistics.ui.component.Banner
import com.articals.logistics.ui.component.BannerTone
import com.articals.logistics.ui.component.ChoiceChips
import com.articals.logistics.ui.component.EmptyState
import com.articals.logistics.ui.component.ErrorState
import com.articals.logistics.ui.component.LabelValueRow
import com.articals.logistics.ui.component.MetaChip
import com.articals.logistics.ui.component.SectionCard
import com.articals.logistics.ui.component.SkeletonList
import com.articals.logistics.ui.component.StateHost
import com.articals.logistics.ui.demo.DemoStrip
import com.articals.logistics.ui.text.ArticalsStrings
import com.articals.logistics.ui.theme.Spacing
import org.koin.compose.viewmodel.koinViewModel

/**
 * Riders.
 *
 * The list is the dispatcher's roster: who is available, who is suspended, and what
 * they have ridden. The rider's own view of the same work lives in the rider tab; this
 * is the office's side of it.
 */
@Composable
fun RidersScreen(
    tenantId: String,
    permissions: Set<String>,
    onOpenRider: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RidersViewModel = koinViewModel(),
) {
    LaunchedEffect(tenantId) { viewModel.load(tenantId) }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val canManage = Permissions.RidersManage in permissions

    Column(modifier = modifier.fillMaxSize()) {
        DemoStrip()

        Column(modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.sm)) {
            Text(
                text = ArticalsStrings.Settings.riders,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = ArticalsStrings.Riders.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ArticalsTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            label = ArticalsStrings.Riders.search,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.screen),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.screen, vertical = Spacing.xs),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChoiceChips(
                options = listOf(false, true),
                selected = state.showInactive,
                optionLabel = { inactive ->
                    if (inactive) {
                        ArticalsStrings.Riders.inactiveChip(state.inactiveCount)
                    } else {
                        ArticalsStrings.Riders.activeChip(state.activeCount)
                    }
                },
                onSelect = viewModel::setShowInactive,
            )
        }

        state.notice?.let {
            Banner(
                text = it,
                tone = BannerTone.Success,
                modifier = Modifier.padding(horizontal = Spacing.screen),
                action = { TextButton(onClick = viewModel::clearNotice) { Text(ArticalsStrings.Common.close) } },
            )
        }
        state.error?.let {
            Banner(
                text = it.userMessage,
                tone = BannerTone.Danger,
                modifier = Modifier.padding(horizontal = Spacing.screen),
                action = { TextButton(onClick = viewModel::dismissError) { Text(ArticalsStrings.Common.close) } },
            )
        }

        if (canManage) {
            Row(modifier = Modifier.padding(horizontal = Spacing.screen)) {
                TextButton(onClick = { viewModel.openEditor(null) }) {
                    Text(ArticalsStrings.Settings.addRider)
                }
            }
        }

        StateHost(
            state = state.listState,
            modifier = Modifier.fillMaxSize(),
            loading = { SkeletonList(rows = 5) },
            onRetry = { viewModel.load(tenantId) },
            emptyTitle = ArticalsStrings.Settings.ridersEmpty,
            emptyMessage = ArticalsStrings.Settings.ridersEmptyBody,
        ) { list ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Spacing.screen,
                    end = Spacing.screen,
                    bottom = Spacing.xxl,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                items(items = list, key = { it.id }) { rider ->
                    RiderRow(
                        rider = rider,
                        canManage = canManage,
                        onOpen = { onOpenRider(rider.id) },
                        onEdit = { viewModel.openEditor(rider) },
                        onInvite = { viewModel.openInvite(rider) },
                        onToggleStatus = {
                            viewModel.setStatus(
                                rider = rider,
                                status = if (rider.status == RiderStatuses.Active) {
                                    RiderStatuses.Suspended
                                } else {
                                    RiderStatuses.Active
                                },
                            )
                        },
                    )
                }
            }
        }
    }

    state.editor?.let { editor ->
        RiderEditorDialog(
            editor = editor,
            error = state.editorError?.userMessage,
            saving = state.saving,
            onChange = viewModel::updateEditor,
            onSave = viewModel::saveEditor,
            onDismiss = viewModel::closeEditor,
        )
    }

    state.invitingFor?.let { rider ->
        RiderInviteDialog(
            rider = rider,
            email = state.inviteEmail,
            invitation = state.invitation,
            error = state.inviteError?.userMessage,
            saving = state.saving,
            onEmailChange = viewModel::setInviteEmail,
            onSend = viewModel::sendInvitation,
            onDismiss = viewModel::closeInvite,
        )
    }
}

@Composable
private fun RiderRow(
    rider: RiderDto,
    canManage: Boolean,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onInvite: () -> Unit,
    onToggleStatus: () -> Unit,
) {
    SectionCard(modifier = Modifier.clickable(onClick = onOpen)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rider.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                rider.phone?.takeIf { it.isNotBlank() }?.let { phone ->
                    Text(
                        text = phone,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                MetaChip(text = RiderStatuses.label(rider.status))
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = ArticalsStrings.Riders.deliveredCount(rider.deliveriesCompleted),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(Spacing.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            rider.vehicleType?.takeIf { it.isNotBlank() }?.let { vehicle ->
                MetaChip(text = VehicleTypes.label(vehicle))
            }
            if (rider.userId == null) {
                MetaChip(text = ArticalsStrings.Settings.riderNoLogin)
            } else {
                MetaChip(text = ArticalsStrings.Settings.riderHasLogin)
            }
        }

        if (canManage) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                TextButton(onClick = onEdit) { Text(ArticalsStrings.Common.save) }
                TextButton(onClick = onInvite, enabled = rider.userId == null) {
                    Text(ArticalsStrings.Settings.inviteRider)
                }
                TextButton(onClick = onToggleStatus) {
                    Text(
                        if (rider.status == RiderStatuses.Active) {
                            ArticalsStrings.Settings.disable
                        } else {
                            ArticalsStrings.Settings.enable
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RiderEditorDialog(
    editor: RiderEditor,
    error: String?,
    saving: Boolean,
    onChange: (RiderEditor) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = {
            Text(
                if (editor.isNew) ArticalsStrings.Settings.addRider else ArticalsStrings.Riders.editTitle,
            )
        },
        text = {
            Column {
                ArticalsTextField(
                    value = editor.name,
                    onValueChange = { onChange(editor.copy(name = it)) },
                    label = ArticalsStrings.Settings.riderName,
                    enabled = !saving,
                    isError = editor.name.isNotEmpty() && !editor.nameValid,
                )
                Spacer(Modifier.height(Spacing.sm))
                ArticalsTextField(
                    value = editor.phone,
                    onValueChange = { onChange(editor.copy(phone = it)) },
                    label = ArticalsStrings.Settings.riderPhone,
                    enabled = !saving,
                    isError = editor.phone.isNotEmpty() && !editor.phoneValid,
                )
                Spacer(Modifier.height(Spacing.sm))
                ArticalsTextField(
                    value = editor.email,
                    onValueChange = { onChange(editor.copy(email = it)) },
                    label = ArticalsStrings.Settings.riderEmail,
                    enabled = !saving,
                    helper = ArticalsStrings.Riders.emailExplain,
                )
                Spacer(Modifier.height(Spacing.sm))
                ArticalsDropdown(
                    label = ArticalsStrings.Settings.riderVehicle,
                    selected = editor.vehicleType,
                    options = VehicleTypes.all,
                    optionLabel = { VehicleTypes.label(it) },
                    onSelect = { onChange(editor.copy(vehicleType = it)) },
                    enabled = !saving,
                )
                Spacer(Modifier.height(Spacing.sm))
                ArticalsTextField(
                    value = editor.vehiclePlate,
                    onValueChange = { onChange(editor.copy(vehiclePlate = it)) },
                    label = ArticalsStrings.Riders.plate,
                    enabled = !saving,
                )
                if (!editor.isNew) {
                    Spacer(Modifier.height(Spacing.sm))
                    ChoiceChips(
                        options = RiderStatuses.all,
                        selected = editor.status,
                        optionLabel = { RiderStatuses.label(it) },
                        onSelect = { onChange(editor.copy(status = it)) },
                        label = ArticalsStrings.Riders.status,
                    )
                }
                Spacer(Modifier.height(Spacing.sm))
                ArticalsTextField(
                    value = editor.notes,
                    onValueChange = { onChange(editor.copy(notes = it)) },
                    label = ArticalsStrings.Settings.riderNotes,
                    enabled = !saving,
                    singleLine = false,
                    minLines = 2,
                )
                error?.let {
                    Spacer(Modifier.height(Spacing.sm))
                    Banner(text = it, tone = BannerTone.Danger)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = editor.valid && !saving) {
                Text(if (saving) ArticalsStrings.Common.saving else ArticalsStrings.Common.save)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text(ArticalsStrings.Common.cancel) }
        },
    )
}

/**
 * The rider's login invitation.
 *
 * The link is created here and handed to the person to send. The wording says exactly
 * that, because an app that cannot send email must not look like it did.
 */
@Composable
private fun RiderInviteDialog(
    rider: RiderDto,
    email: String,
    invitation: com.articals.logistics.data.dto.RiderInvitationResponse?,
    error: String?,
    saving: Boolean,
    onEmailChange: (String) -> Unit,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(ArticalsStrings.Riders.inviteTitle(rider.name)) },
        text = {
            Column {
                Text(
                    text = ArticalsStrings.Riders.inviteBody,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.sm))
                ArticalsTextField(
                    value = email,
                    onValueChange = onEmailChange,
                    label = ArticalsStrings.Settings.riderEmail,
                    enabled = !saving && invitation == null,
                )
                error?.let {
                    Spacer(Modifier.height(Spacing.sm))
                    Banner(text = it, tone = BannerTone.Danger)
                }
                invitation?.let { created ->
                    Spacer(Modifier.height(Spacing.sm))
                    SectionCard(title = ArticalsStrings.Riders.inviteLink) {
                        Text(
                            text = created.inviteUrl ?: created.token,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            text = ArticalsStrings.Share.notSent,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(Spacing.xs))
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            TextButton(
                                onClick = {
                                    copyToClipboard(
                                        text = created.inviteUrl ?: created.token,
                                        label = ArticalsStrings.Riders.inviteLink,
                                    )
                                },
                            ) { Text(ArticalsStrings.Common.copy) }
                            TextButton(
                                onClick = {
                                    sharePlainText(
                                        subject = ArticalsStrings.Riders.inviteTitle(rider.name),
                                        text = created.inviteUrl ?: created.token,
                                    )
                                },
                            ) { Text(ArticalsStrings.Common.share) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (invitation == null) {
                TextButton(onClick = onSend, enabled = saving || email.isNotBlank()) {
                    Text(if (saving) ArticalsStrings.Common.saving else ArticalsStrings.Settings.inviteSend)
                }
            } else {
                TextButton(onClick = onDismiss) { Text(ArticalsStrings.Common.done) }
            }
        },
        dismissButton = {
            if (invitation == null) {
                TextButton(onClick = onDismiss, enabled = !saving) { Text(ArticalsStrings.Common.cancel) }
            }
        },
    )
}

/** One rider: their record, their running totals, and their deliveries. */
@Composable
fun RiderDetailScreen(
    tenantId: String,
    riderId: String,
    currency: String,
    onOpenOrder: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RiderDetailViewModel = koinViewModel(),
) {
    LaunchedEffect(tenantId, riderId) { viewModel.load(tenantId, riderId) }

    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        DemoStrip()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.screen, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text(ArticalsStrings.Common.back) }
            Text(
                text = state.rider?.name ?: ArticalsStrings.Settings.riders,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (state.loading) {
            SkeletonList(rows = 4)
            return@Column
        }

        val rider = state.rider
        if (rider == null) {
            ErrorState(
                error = com.articals.logistics.core.AppError.NotFound(
                    message = ArticalsStrings.Riders.notFound,
                ),
                onRetry = { viewModel.load(tenantId, riderId) },
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Spacing.screen,
                end = Spacing.screen,
                bottom = Spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item {
                SectionCard(title = ArticalsStrings.Settings.riders) {
                    LabelValueRow(ArticalsStrings.Settings.riderName, rider.name)
                    rider.phone?.takeIf { it.isNotBlank() }?.let {
                        LabelValueRow(ArticalsStrings.Settings.riderPhone, it)
                    }
                    rider.email?.takeIf { it.isNotBlank() }?.let {
                        LabelValueRow(ArticalsStrings.Settings.riderEmail, it)
                    }
                    rider.vehicleType?.takeIf { it.isNotBlank() }?.let {
                        LabelValueRow(ArticalsStrings.Settings.riderVehicle, VehicleTypes.label(it))
                    }
                    rider.vehiclePlate?.takeIf { it.isNotBlank() }?.let {
                        LabelValueRow(ArticalsStrings.Riders.plate, it)
                    }
                    LabelValueRow(ArticalsStrings.Riders.status, RiderStatuses.label(rider.status))
                    LabelValueRow(
                        label = ArticalsStrings.Settings.riderHasLogin,
                        value = if (rider.userId == null) {
                            ArticalsStrings.Common.no
                        } else {
                            ArticalsStrings.Common.yes
                        },
                    )
                    rider.notes?.takeIf { it.isNotBlank() }?.let {
                        LabelValueRow(ArticalsStrings.Settings.riderNotes, it)
                    }
                }
            }

            item {
                SectionCard(title = ArticalsStrings.Riders.totals) {
                    LabelValueRow(ArticalsStrings.Riders.delivered, state.delivered.toString())
                    LabelValueRow(ArticalsStrings.Riders.openJobs, state.open.toString())
                    LabelValueRow(ArticalsStrings.Riders.failed, state.failed.toString())
                    LabelValueRow(
                        label = ArticalsStrings.Riders.paidToRider,
                        value = formatMinor(state.earnedMinor, currency),
                        emphasised = true,
                    )
                    rider.rating?.let {
                        LabelValueRow(ArticalsStrings.Riders.rating, it.toString())
                    }
                }
            }

            if (state.orders.isEmpty()) {
                item {
                    EmptyState(
                        title = ArticalsStrings.Riders.noJobs,
                        message = ArticalsStrings.Riders.noJobsBody,
                    )
                }
            } else {
                items(items = state.orders, key = { it.id }) { order ->
                    SectionCard(modifier = Modifier.clickable { onOpenOrder(order.id) }) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = order.orderNumber.ifBlank { order.id },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            MetaChip(text = OrderStatus.label(order.status))
                        }
                        Text(
                            text = "${order.pickup.area ?: order.pickup.address} → " +
                                "${order.destination.area ?: order.destination.address}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
