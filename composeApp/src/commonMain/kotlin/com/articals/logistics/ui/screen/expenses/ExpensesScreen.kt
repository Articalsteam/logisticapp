package com.articals.logistics.ui.screen.expenses

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
import com.articals.logistics.core.formatMinor
import com.articals.logistics.core.formatDateKeyShort
import com.articals.logistics.data.dto.ExpenseDto
import com.articals.logistics.domain.ExpenseCategories
import com.articals.logistics.domain.ExpenseKind
import com.articals.logistics.ui.component.ArticalsAmountField
import com.articals.logistics.ui.component.ArticalsDropdown
import com.articals.logistics.ui.component.ArticalsTextField
import com.articals.logistics.ui.component.Banner
import com.articals.logistics.ui.component.BannerTone
import com.articals.logistics.ui.component.ChoiceChips
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
 * Expenses.
 *
 * What the company spends, split into the two things it means: running the business,
 * and paying for a delivery that already happened. The totals are at the top because
 * that is the number people come here for; the list underneath is how you check it.
 */
@Composable
fun ExpensesScreen(
    tenantId: String,
    currency: String,
    zoneId: String,
    permissions: Set<String>,
    modifier: Modifier = Modifier,
    viewModel: ExpensesViewModel = koinViewModel(),
) {
    LaunchedEffect(tenantId, zoneId) { viewModel.load(tenantId, zoneId) }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val canManage = Permissions.ExpensesManage in permissions

    Column(modifier = modifier.fillMaxSize()) {
        DemoStrip()

        Column(modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.sm)) {
            Text(
                text = ArticalsStrings.Settings.expenses,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = ArticalsStrings.Settings.expensesSubtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(
            title = ArticalsStrings.Expenses.totals,
            modifier = Modifier.padding(horizontal = Spacing.screen),
        ) {
            LabelValueRow(
                label = ArticalsStrings.Settings.operatingExpenses,
                value = formatMinor(state.operatingMinor, currency),
            )
            LabelValueRow(
                label = ArticalsStrings.Settings.deliveryExpenses,
                value = formatMinor(state.deliveryMinor, currency),
            )
            LabelValueRow(
                label = ArticalsStrings.Expenses.total,
                value = formatMinor(state.totalMinor, currency),
                emphasised = true,
            )
        }

        state.notice?.let {
            Banner(
                text = it,
                tone = BannerTone.Success,
                modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.xs),
                action = { TextButton(onClick = viewModel::clearNotice) { Text(ArticalsStrings.Common.close) } },
            )
        }
        state.error?.let {
            Banner(
                text = it.userMessage,
                tone = BannerTone.Danger,
                modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.xs),
                action = { TextButton(onClick = viewModel::dismissError) { Text(ArticalsStrings.Common.close) } },
            )
        }

        if (canManage) {
            Row(modifier = Modifier.padding(horizontal = Spacing.screen)) {
                TextButton(onClick = viewModel::openDraft) {
                    Text(ArticalsStrings.Settings.addExpense)
                }
            }
        }

        StateHost(
            state = state.listState,
            modifier = Modifier.fillMaxSize(),
            loading = { SkeletonList(rows = 5) },
            onRetry = { viewModel.load(tenantId, zoneId) },
            emptyTitle = ArticalsStrings.Settings.expensesEmpty,
            emptyMessage = ArticalsStrings.Settings.expensesEmptyBody,
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
                items(items = list, key = { it.id }) { expense ->
                    ExpenseRow(
                        expense = expense,
                        currency = currency,
                        canManage = canManage,
                        onDelete = { viewModel.askDelete(expense) },
                    )
                }
            }
        }
    }

    state.draft?.let { draft ->
        ExpenseDraftDialog(
            draft = draft,
            currency = currency,
            error = state.draftError?.userMessage,
            saving = state.saving,
            onChange = viewModel::updateDraft,
            onSave = viewModel::save,
            onDismiss = viewModel::closeDraft,
        )
    }

    state.deleting?.let { expense ->
        com.articals.logistics.ui.component.ConfirmDialog(
            title = ArticalsStrings.Expenses.deleteTitle,
            body = ArticalsStrings.Expenses.deleteBody(
                formatMinor(expense.amountMinor, currency),
                ExpenseCategories.label(expense.category),
            ),
            confirmLabel = ArticalsStrings.Common.delete,
            destructive = true,
            reasonLabel = ArticalsStrings.Settings.expenseDeleteReason,
            reason = state.deleteReason,
            onReasonChange = viewModel::setDeleteReason,
            reasonRequired = true,
            busy = state.saving,
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::cancelDelete,
        )
    }
}

@Composable
private fun ExpenseRow(
    expense: ExpenseDto,
    currency: String,
    canManage: Boolean,
    onDelete: () -> Unit,
) {
    SectionCard {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = expense.label ?: ExpenseCategories.label(expense.category),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = formatDateKeyShort(expense.expenseDate),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = formatMinor(expense.amountMinor, currency),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Spacer(Modifier.height(Spacing.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            MetaChip(text = ExpenseCategories.label(expense.category))
            MetaChip(
                text = if (expense.kind == ExpenseKind.Delivery) {
                    ArticalsStrings.Settings.deliveryExpenses
                } else {
                    ArticalsStrings.Settings.operatingExpenses
                },
            )
        }

        expense.description?.takeIf { it.isNotBlank() }?.let { description ->
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (canManage) {
            Row {
                TextButton(onClick = onDelete) { Text(ArticalsStrings.Settings.expenseDelete) }
            }
        }
    }
}

/**
 * The expense form.
 *
 * "Running cost" and "Delivery cost" are the first choice, because that one answer
 * decides whether an order number is required: a delivery expense that cannot name its
 * delivery is not a delivery expense.
 */
@Composable
private fun ExpenseDraftDialog(
    draft: ExpenseDraft,
    currency: String,
    error: String?,
    saving: Boolean,
    onChange: (ExpenseDraft) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isDelivery = draft.kind == ExpenseKind.Delivery
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(ArticalsStrings.Settings.addExpense) },
        text = {
            Column {
                ChoiceChips(
                    options = listOf(ExpenseKind.Operating, ExpenseKind.Delivery),
                    selected = draft.kind,
                    optionLabel = {
                        if (it == ExpenseKind.Delivery) {
                            ArticalsStrings.Settings.deliveryExpenses
                        } else {
                            ArticalsStrings.Settings.operatingExpenses
                        }
                    },
                    onSelect = { onChange(draft.copy(kind = it)) },
                    label = ArticalsStrings.Expenses.kind,
                )
                Spacer(Modifier.height(Spacing.sm))
                ArticalsDropdown(
                    label = ArticalsStrings.Settings.expenseCategory,
                    selected = draft.category,
                    options = ExpenseCategories.all,
                    optionLabel = { ExpenseCategories.label(it) },
                    onSelect = { onChange(draft.copy(category = it)) },
                    enabled = !saving,
                )
                Spacer(Modifier.height(Spacing.sm))
                ArticalsAmountField(
                    value = draft.amount,
                    onValueChange = { onChange(draft.copy(amount = it)) },
                    label = ArticalsStrings.Settings.expenseAmount,
                    currency = currency,
                    enabled = !saving,
                    error = if (draft.amount.isNotEmpty() && draft.amountMinor == null) {
                        ArticalsStrings.Expenses.amountInvalid
                    } else {
                        null
                    },
                )
                Spacer(Modifier.height(Spacing.sm))
                ArticalsTextField(
                    value = draft.dateKey,
                    onValueChange = { onChange(draft.copy(dateKey = it)) },
                    label = ArticalsStrings.Settings.expenseDate,
                    placeholder = ArticalsStrings.Expenses.dateFormat,
                    enabled = !saving,
                    helper = ArticalsStrings.Expenses.dateFormat,
                    error = if (draft.dateKey.isNotEmpty() && !draft.dateValid) {
                        ArticalsStrings.Expenses.dateInvalid
                    } else {
                        null
                    },
                )
                if (isDelivery) {
                    Spacer(Modifier.height(Spacing.sm))
                    ArticalsTextField(
                        value = draft.orderId,
                        onValueChange = { onChange(draft.copy(orderId = it)) },
                        label = ArticalsStrings.Expenses.orderId,
                        enabled = !saving,
                        helper = ArticalsStrings.Expenses.orderIdExplain,
                    )
                }
                Spacer(Modifier.height(Spacing.sm))
                ArticalsTextField(
                    value = draft.label,
                    onValueChange = { onChange(draft.copy(label = it)) },
                    label = ArticalsStrings.Settings.expenseLabel,
                    enabled = !saving,
                )
                Spacer(Modifier.height(Spacing.sm))
                ArticalsTextField(
                    value = draft.description,
                    onValueChange = { onChange(draft.copy(description = it)) },
                    label = ArticalsStrings.Expenses.description,
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
            TextButton(onClick = onSave, enabled = draft.valid && !saving) {
                Text(if (saving) ArticalsStrings.Common.saving else ArticalsStrings.Common.save)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text(ArticalsStrings.Common.cancel) }
        },
    )
}
