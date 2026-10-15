package com.articals.logistics.ui.screen.customers

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
import com.articals.logistics.core.currentTimeMillis
import com.articals.logistics.core.formatMinor
import com.articals.logistics.core.parseIsoInstantOrNull
import com.articals.logistics.core.relativeTime
import com.articals.logistics.data.dto.CustomerDto
import com.articals.logistics.ui.component.ArticalsTextField
import com.articals.logistics.ui.component.Banner
import com.articals.logistics.ui.component.BannerTone
import com.articals.logistics.ui.component.ChoiceChips
import com.articals.logistics.ui.component.ConfirmDialog
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
 * Customers: the people and businesses the company delivers for.
 *
 * Search, an archived filter and the four fields that matter. The archived list is a
 * separate view on purpose — a customer that was archived should not quietly reappear
 * in a search for a live one.
 */
@Composable
fun CustomersScreen(
    tenantId: String,
    permissions: Set<String>,
    onOpenCustomer: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CustomersViewModel = koinViewModel(),
) {
    LaunchedEffect(tenantId) { viewModel.load(tenantId) }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val canManage = Permissions.CustomersManage in permissions

    Column(modifier = modifier.fillMaxSize()) {
        DemoStrip()

        Column(modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.sm)) {
            Text(
                text = ArticalsStrings.Settings.customers,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = ArticalsStrings.Customers.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ArticalsTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            label = ArticalsStrings.Customers.search,
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
                selected = state.showArchived,
                optionLabel = { archived ->
                    if (archived) {
                        ArticalsStrings.Customers.archivedChip(state.archivedCount)
                    } else {
                        ArticalsStrings.Customers.activeChip(state.activeCount)
                    }
                },
                onSelect = viewModel::setShowArchived,
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

        if (canManage) {
            Row(modifier = Modifier.padding(horizontal = Spacing.screen)) {
                TextButton(onClick = { viewModel.openEditor(null) }) {
                    Text(ArticalsStrings.Settings.addCustomer)
                }
            }
        }

        StateHost(
            state = state.listState,
            modifier = Modifier.fillMaxSize(),
            loading = { SkeletonList(rows = 5) },
            onRetry = { viewModel.load(tenantId) },
            emptyTitle = ArticalsStrings.Settings.customersEmpty,
            emptyMessage = ArticalsStrings.Settings.customersEmptyBody,
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
                items(items = list, key = { it.id }) { customer ->
                    CustomerRow(
                        customer = customer,
                        canManage = canManage,
                        onOpen = { onOpenCustomer(customer.id) },
                        onArchive = { viewModel.askArchive(customer) },
                        onRestore = { viewModel.restore(customer) },
                    )
                }
            }
        }
    }

    state.editor?.let { editor ->
        CustomerEditorDialog(
            editor = editor,
            error = state.editorError?.userMessage,
            saving = state.saving,
            onChange = viewModel::updateEditor,
            onSave = viewModel::saveEditor,
            onDismiss = viewModel::closeEditor,
        )
    }

    state.archiving?.let { customer ->
        ConfirmDialog(
            title = ArticalsStrings.Customers.archiveTitle(customer.name),
            body = ArticalsStrings.Customers.archiveBody,
            confirmLabel = ArticalsStrings.Customers.archiveConfirm,
            destructive = true,
            busy = state.saving,
            onConfirm = viewModel::confirmArchive,
            onDismiss = viewModel::cancelArchive,
        )
    }
}

@Composable
private fun CustomerRow(
    customer: CustomerDto,
    canManage: Boolean,
    onOpen: () -> Unit,
    onArchive: () -> Unit,
    onRestore: () -> Unit,
) {
    SectionCard(modifier = Modifier.clickable(onClick = onOpen)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = customer.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                customer.phone?.takeIf { it.isNotBlank() }?.let { phone ->
                    Text(
                        text = phone,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = ArticalsStrings.Customers.ordersCount(customer.totalOrders),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MetaChip(
                    text = if (customer.customerType == "business") {
                        ArticalsStrings.Settings.business
                    } else {
                        ArticalsStrings.Settings.individual
                    },
                )
            }
        }

        if (customer.status == "archived") {
            Spacer(Modifier.height(Spacing.xs))
            MetaChip(text = ArticalsStrings.Customers.archivedChipLabel)
        }

        if (canManage) {
            Spacer(Modifier.height(Spacing.xs))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                if (customer.status == "archived") {
                    TextButton(onClick = onRestore) { Text(ArticalsStrings.Customers.restore) }
                } else {
                    TextButton(onClick = onArchive) { Text(ArticalsStrings.Customers.archiveConfirm) }
                }
            }
        }
    }
}

/** Add or edit a customer. Small on purpose: this is not a CRM. */
@Composable
private fun CustomerEditorDialog(
    editor: CustomerEditor,
    error: String?,
    saving: Boolean,
    onChange: (CustomerEditor) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = {
            Text(
                if (editor.isNew) {
                    ArticalsStrings.Settings.addCustomer
                } else {
                    ArticalsStrings.Customers.editTitle
                },
            )
        },
        text = {
            Column {
                ArticalsTextField(
                    value = editor.name,
                    onValueChange = { onChange(editor.copy(name = it)) },
                    label = ArticalsStrings.Settings.customerName,
                    enabled = !saving,
                    isError = editor.name.isNotEmpty() && !editor.nameValid,
                )
                Spacer(Modifier.height(Spacing.sm))
                ArticalsTextField(
                    value = editor.phone,
                    onValueChange = { onChange(editor.copy(phone = it)) },
                    label = ArticalsStrings.Settings.customerPhone,
                    enabled = !saving,
                    isError = editor.phone.isNotEmpty() && !editor.phoneValid,
                )
                Spacer(Modifier.height(Spacing.sm))
                ArticalsTextField(
                    value = editor.email,
                    onValueChange = { onChange(editor.copy(email = it)) },
                    label = ArticalsStrings.Settings.customerEmail,
                    enabled = !saving,
                )
                Spacer(Modifier.height(Spacing.sm))
                ArticalsTextField(
                    value = editor.address,
                    onValueChange = { onChange(editor.copy(address = it)) },
                    label = ArticalsStrings.Pricing.address,
                    enabled = !saving,
                )
                Spacer(Modifier.height(Spacing.sm))
                ChoiceChips(
                    options = listOf("individual", "business"),
                    selected = editor.customerType,
                    optionLabel = {
                        if (it == "business") {
                            ArticalsStrings.Settings.business
                        } else {
                            ArticalsStrings.Settings.individual
                        }
                    },
                    onSelect = { onChange(editor.copy(customerType = it)) },
                    label = ArticalsStrings.Settings.customerType,
                )
                Spacer(Modifier.height(Spacing.sm))
                ArticalsTextField(
                    value = editor.notes,
                    onValueChange = { onChange(editor.copy(notes = it)) },
                    label = ArticalsStrings.Customers.notes,
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
                Text(
                    if (saving) ArticalsStrings.Common.saving else ArticalsStrings.Common.save,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text(ArticalsStrings.Common.cancel) }
        },
    )
}

/**
 * One customer: their details, what they have cost, and every delivery behind them.
 */
@Composable
fun CustomerDetailScreen(
    tenantId: String,
    customerId: String,
    currency: String,
    timeZoneId: String,
    onOpenOrder: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CustomerDetailViewModel = koinViewModel(),
) {
    LaunchedEffect(tenantId, customerId) { viewModel.load(tenantId, customerId) }

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
                text = state.customer?.name ?: ArticalsStrings.Settings.customers,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (state.loading) {
            SkeletonList(rows = 4)
            return@Column
        }

        val customer = state.customer
        if (customer == null) {
            ErrorState(
                error = com.articals.logistics.core.AppError.NotFound(
                    message = ArticalsStrings.Customers.notFound,
                ),
                onRetry = { viewModel.load(tenantId, customerId) },
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
                SectionCard(title = ArticalsStrings.Orders.customer) {
                    LabelValueRow(ArticalsStrings.Settings.customerName, customer.name)
                    customer.phone?.takeIf { it.isNotBlank() }?.let {
                        LabelValueRow(ArticalsStrings.Settings.customerPhone, it)
                    }
                    customer.email?.takeIf { it.isNotBlank() }?.let {
                        LabelValueRow(ArticalsStrings.Settings.customerEmail, it)
                    }
                    customer.address?.takeIf { it.isNotBlank() }?.let {
                        LabelValueRow(ArticalsStrings.Pricing.address, it)
                    }
                    LabelValueRow(
                        label = ArticalsStrings.Settings.customerType,
                        value = if (customer.customerType == "business") {
                            ArticalsStrings.Settings.business
                        } else {
                            ArticalsStrings.Settings.individual
                        },
                    )
                    customer.createdAt?.let { createdAt ->
                        LabelValueRow(
                            label = ArticalsStrings.Customers.customerSince,
                            value = relativeTime(
                                fromMillis = parseIsoInstantOrNull(createdAt) ?: 0L,
                                nowMillis = currentTimeMillis(),
                                timeZoneId = timeZoneId,
                            ),
                        )
                    }
                    customer.notes?.takeIf { it.isNotBlank() }?.let {
                        LabelValueRow(ArticalsStrings.Customers.notes, it)
                    }
                }
            }

            item {
                SectionCard(title = ArticalsStrings.Customers.history) {
                    LabelValueRow(
                        label = ArticalsStrings.Customers.deliveries,
                        value = state.deliveries.toString(),
                    )
                    LabelValueRow(
                        label = ArticalsStrings.Customers.charged,
                        value = formatMinor(state.spendMinor, currency),
                        emphasised = true,
                    )
                }
            }

            if (state.orders.isEmpty()) {
                item {
                    EmptyState(
                        title = ArticalsStrings.Customers.noOrders,
                        message = ArticalsStrings.Customers.noOrdersBody,
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
                            Text(
                                text = formatMinor(order.customerChargeMinor, currency),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Text(
                            text = "${order.pickup.area ?: order.pickup.address} → " +
                                "${order.destination.area ?: order.destination.address}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(Spacing.xs))
                        MetaChip(text = com.articals.logistics.domain.order.OrderStatus.label(order.status))
                    }
                }
            }
        }
    }
}
