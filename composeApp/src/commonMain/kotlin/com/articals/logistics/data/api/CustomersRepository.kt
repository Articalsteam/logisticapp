package com.articals.logistics.data.api

import com.articals.logistics.core.AppResult
import com.articals.logistics.data.dto.AckDto
import com.articals.logistics.data.dto.CreateCustomerRequest
import com.articals.logistics.data.dto.CreateCustomerResponse
import com.articals.logistics.data.dto.CustomerDto
import com.articals.logistics.data.dto.UpdateCustomerRequest
import kotlinx.coroutines.flow.Flow

/** The customer directory. */
interface CustomersRepository {
    fun observeCustomers(tenantId: String, limit: Int = 200): Flow<List<CustomerDto>>

    fun observeActiveCustomers(tenantId: String, limit: Int = 200): Flow<List<CustomerDto>>

    suspend fun create(request: CreateCustomerRequest): AppResult<CreateCustomerResponse>

    suspend fun update(request: UpdateCustomerRequest): AppResult<AckDto>

    /** Soft delete: the record stays readable so historic deliveries keep their context. */
    suspend fun archive(tenantId: String, customerId: String): AppResult<AckDto>
}
