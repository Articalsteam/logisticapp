package com.articals.logistics.ui.screen.expenses

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.articals.logistics.core.AppError
import com.articals.logistics.core.AppResult
import com.articals.logistics.core.UiState
import com.articals.logistics.core.isDateKey
import com.articals.logistics.core.parseAmountToMinorOrNull
import com.articals.logistics.core.todayKey
import com.articals.logistics.data.api.ExpensesRepository
import com.articals.logistics.data.dto.CreateExpenseRequest
import com.articals.logistics.data.dto.DeleteExpenseRequest
import com.articals.logistics.data.dto.ExpenseDto
import com.articals.logistics.domain.ExpenseCategories
import com.articals.logistics.domain.ExpenseKind
import com.articals.logistics.ui.text.ArticalsStrings
import com.articals.logistics.util.Idempotency
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The expense form.
 *
 * A delivery expense must name the delivery it belongs to — the backend refuses one
 * without an order, and the form asks for the order number up front rather than
 * letting the save fail. Running costs (rent, salaries, airtime) have no order.
 */
data class ExpenseDraft(
    val category: String = "fuel",
    val amount: String = "",
    val dateKey: String = "",
    val label: String = "",
    val description: String = "",
    val kind: String = ExpenseKind.Operating,
    val orderId: String = "",
) {
    val amountMinor: Long? get() = parseAmountToMinorOrNull(amount)

    val dateValid: Boolean get() = isDateKey(dateKey.trim())

    val orderValid: Boolean get() = kind != ExpenseKind.Delivery || orderId.trim().isNotEmpty()

    val valid: Boolean get() = (amountMinor ?: 0L) > 0L && dateValid && orderValid

    companion object {
        fun start(zoneId: String) = ExpenseDraft(dateKey = todayKey(zoneId))
    }
}

data class ExpensesState(
    val expenses: List<ExpenseDto> = emptyList(),
    val loading: Boolean = true,
    val error: AppError? = null,
    val notice: String? = null,
    val draft: ExpenseDraft? = null,
    val saving: Boolean = false,
    val draftError: AppError? = null,
    val deleting: ExpenseDto? = null,
    val deleteReason: String = "",
) {
    val operatingMinor: Long get() = expenses.filter { it.kind != ExpenseKind.Delivery }.sumOf { it.amountMinor }

    val deliveryMinor: Long get() = expenses.filter { it.kind == ExpenseKind.Delivery }.sumOf { it.amountMinor }

    val totalMinor: Long get() = operatingMinor + deliveryMinor

    val listState: UiState<List<ExpenseDto>>
        get() = when {
            loading -> UiState.Loading
            error != null && expenses.isEmpty() -> UiState.Error(error)
            expenses.isEmpty() -> UiState.Empty(
                title = ArticalsStrings.Settings.expensesEmpty,
                message = ArticalsStrings.Settings.expensesEmptyBody,
                actionLabel = ArticalsStrings.Settings.addExpense,
            )

            else -> UiState.Content(expenses)
        }
}

/**
 * Expenses.
 *
 * Two kinds, one screen: what the company spends to keep running, and what a single
 * delivery cost on top of the rider's fee. Both are money the company has actually
 * paid, which is why the list always shows a total per kind — a number that gets
 * reconciled against the profit figures on the dashboard.
 *
 * Deleting is a request with a reason, never a silent removal: the backend keeps the
 * deletion in the audit log, and the dialog asks for the reason before it will send.
 */
class ExpensesViewModel(
    private val expenses: ExpensesRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(ExpensesState())
    val state: StateFlow<ExpensesState> = mutableState.asStateFlow()

    private var tenantId: String? = null
    private var zoneId: String = "UTC"
    private var addKey: String = Idempotency.newKey("expense.create")

    fun load(tenantId: String, zoneId: String) {
        if (this.tenantId == tenantId) return
        this.tenantId = tenantId
        this.zoneId = zoneId
        mutableState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            expenses.observeExpenses(tenantId).collect { list ->
                mutableState.update { it.copy(expenses = list, loading = false, error = null) }
            }
        }
    }

    fun dismissError() = mutableState.update { it.copy(error = null) }

    fun clearNotice() = mutableState.update { it.copy(notice = null) }

    // --- the form -----------------------------------------------------------

    fun openDraft() = mutableState.update {
        it.copy(draft = ExpenseDraft.start(zoneId), draftError = null)
    }

    fun updateDraft(draft: ExpenseDraft) = mutableState.update { it.copy(draft = draft) }

    fun closeDraft() = mutableState.update { it.copy(draft = null, draftError = null) }

    fun save() {
        val tenantId = tenantId ?: return
        val draft = mutableState.value.draft ?: return
        if (!draft.valid) {
            mutableState.update { it.copy(draftError = ArticalsStrings.Expenses.incomplete()) }
            return
        }

        mutableState.update { it.copy(saving = true, draftError = null) }
        viewModelScope.launch {
            val request = CreateExpenseRequest(
                tenantId = tenantId,
                idempotencyKey = addKey,
                category = draft.category,
                amountMinor = draft.amountMinor ?: 0L,
                expenseDate = draft.dateKey.trim(),
                label = draft.label.trim().takeIf { it.isNotEmpty() }
                    ?: ExpenseCategories.label(draft.category),
                description = draft.description.trim().takeIf { it.isNotEmpty() },
                orderId = draft.orderId.trim().takeIf { it.isNotEmpty() },
                kind = draft.kind,
            )
            when (val result = expenses.create(request)) {
                is AppResult.Failure -> mutableState.update {
                    it.copy(saving = false, draftError = result.error)
                }

                is AppResult.Success -> {
                    addKey = Idempotency.newKey("expense.create")
                    mutableState.update {
                        it.copy(saving = false, draft = null, notice = ArticalsStrings.Expenses.added)
                    }
                }
            }
        }
    }

    // --- deleting, with a reason --------------------------------------------

    fun askDelete(expense: ExpenseDto) = mutableState.update {
        it.copy(deleting = expense, deleteReason = "")
    }

    fun setDeleteReason(reason: String) = mutableState.update { it.copy(deleteReason = reason) }

    fun cancelDelete() = mutableState.update { it.copy(deleting = null, deleteReason = "") }

    fun confirmDelete() {
        val tenantId = tenantId ?: return
        val expense = mutableState.value.deleting ?: return
        val reason = mutableState.value.deleteReason.trim()
        if (reason.length < 3) {
            mutableState.update { it.copy(error = ArticalsStrings.Expenses.deleteReasonRequired()) }
            return
        }

        mutableState.update { it.copy(saving = true, deleting = null) }
        viewModelScope.launch {
            val request = DeleteExpenseRequest(
                tenantId = tenantId,
                expenseId = expense.id,
                reason = reason,
            )
            when (val result = expenses.delete(request)) {
                is AppResult.Failure -> mutableState.update { it.copy(saving = false, error = result.error) }
                is AppResult.Success -> mutableState.update {
                    it.copy(saving = false, notice = ArticalsStrings.Expenses.deleted, deleteReason = "")
                }
            }
        }
    }
}
