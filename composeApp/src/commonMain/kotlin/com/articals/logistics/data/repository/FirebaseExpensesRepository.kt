package com.articals.logistics.data.repository

import com.articals.logistics.data.api.ExpensesRepository
import com.articals.logistics.core.AppResult
import com.articals.logistics.data.BackendFunctions
import com.articals.logistics.data.FirestorePaths
import com.articals.logistics.data.FunctionsCaller
import com.articals.logistics.data.dto.AckDto
import com.articals.logistics.data.dto.CreateExpenseRequest
import com.articals.logistics.data.dto.CreateExpenseResponse
import com.articals.logistics.data.dto.DeleteExpenseRequest
import com.articals.logistics.data.dto.ExpenseDto
import com.articals.logistics.data.observeDocuments
import dev.gitlive.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Company money that is not a delivery price: fuel, rent, salaries, repairs.
 *
 * Delivery-level costs belong on the delivery itself (they change its profit), so
 * this screen deliberately covers overheads only.
 */
class FirebaseExpensesRepository(
    private val firestore: FirebaseFirestore,
    private val caller: FunctionsCaller,
) : ExpensesRepository {
    override fun observeExpenses(tenantId: String, limit: Int): Flow<List<ExpenseDto>> =
        firestore.collection(FirestorePaths.expenses)
            .where { "tenantId" equalTo tenantId }
            .limit(limit)
            .observeDocuments { id, expense -> expense.copy(id = id) }
            // Ordered here rather than in the query on purpose: an equality filter needs
            // no composite index, and client-side ordering keeps every screen working on
            // a fresh project before firestore.indexes.json has finished building.
            .map { expenses -> expenses.sortedByDescending { it.expenseDate } }

    override suspend fun create(request: CreateExpenseRequest): AppResult<CreateExpenseResponse> =
        caller.call(BackendFunctions.createExpense, request)

    /** Deleting is soft: the record is kept with a reason, because money was reported on it. */
    override suspend fun delete(request: DeleteExpenseRequest): AppResult<AckDto> =
        caller.call(BackendFunctions.deleteExpense, request)
}
