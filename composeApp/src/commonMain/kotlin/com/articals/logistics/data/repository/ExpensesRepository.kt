package com.articals.logistics.data.repository

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
import dev.gitlive.firebase.firestore.Direction
import dev.gitlive.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.Flow

/**
 * Company money that is not a delivery price: fuel, rent, salaries, repairs.
 *
 * Delivery-level costs belong on the delivery itself (they change its profit), so
 * this screen deliberately covers overheads only.
 */
class ExpensesRepository(
    private val firestore: FirebaseFirestore,
    private val caller: FunctionsCaller,
) {
    fun observeExpenses(tenantId: String, limit: Int = 200): Flow<List<ExpenseDto>> =
        firestore.collection(FirestorePaths.expenses)
            .where { "tenantId" equalTo tenantId }
            .orderBy("expenseDate", Direction.DESCENDING)
            .limit(limit)
            .observeDocuments { id, expense -> expense.copy(id = id) }

    suspend fun create(request: CreateExpenseRequest): AppResult<CreateExpenseResponse> =
        caller.call(BackendFunctions.createExpense, request)

    /** Deleting is soft: the record is kept with a reason, because money was reported on it. */
    suspend fun delete(request: DeleteExpenseRequest): AppResult<AckDto> =
        caller.call(BackendFunctions.deleteExpense, request)
}
