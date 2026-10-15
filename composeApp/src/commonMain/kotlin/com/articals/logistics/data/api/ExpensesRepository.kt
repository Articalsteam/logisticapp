package com.articals.logistics.data.api

import com.articals.logistics.core.AppResult
import com.articals.logistics.data.dto.AckDto
import com.articals.logistics.data.dto.CreateExpenseRequest
import com.articals.logistics.data.dto.CreateExpenseResponse
import com.articals.logistics.data.dto.DeleteExpenseRequest
import com.articals.logistics.data.dto.ExpenseDto
import kotlinx.coroutines.flow.Flow

/**
 * Company money that is not a delivery price.
 *
 * Delivery-level costs belong on the delivery (they change its profit), so this is
 * overheads: fuel, rent, salaries, repairs, airtime.
 */
interface ExpensesRepository {
    fun observeExpenses(tenantId: String, limit: Int = 200): Flow<List<ExpenseDto>>

    suspend fun create(request: CreateExpenseRequest): AppResult<CreateExpenseResponse>

    suspend fun delete(request: DeleteExpenseRequest): AppResult<AckDto>
}
