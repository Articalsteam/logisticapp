package com.articals.logistics.data.repository

import com.articals.logistics.core.AppResult
import com.articals.logistics.data.BackendFunctions
import com.articals.logistics.data.FirestorePaths
import com.articals.logistics.data.FunctionsCaller
import com.articals.logistics.data.dto.AckDto
import com.articals.logistics.data.dto.CreateCustomerRequest
import com.articals.logistics.data.dto.CreateCustomerResponse
import com.articals.logistics.data.dto.CustomerDto
import com.articals.logistics.data.dto.UpdateCustomerRequest
import com.articals.logistics.data.observeDocuments
import dev.gitlive.firebase.firestore.Direction
import dev.gitlive.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.Flow

/** The customer directory: the businesses and people who place deliveries. */
class CustomersRepository(
    private val firestore: FirebaseFirestore,
    private val caller: FunctionsCaller,
) {
    fun observeCustomers(tenantId: String, limit: Int = 200): Flow<List<CustomerDto>> =
        firestore.collection(FirestorePaths.customers)
            .where { "tenantId" equalTo tenantId }
            .orderBy("name", Direction.ASCENDING)
            .limit(limit)
            .observeDocuments { id, customer -> customer.copy(id = id) }

    /** Archived customers stay readable so historic deliveries keep their context. */
    fun observeActiveCustomers(tenantId: String, limit: Int = 200): Flow<List<CustomerDto>> =
        firestore.collection(FirestorePaths.customers)
            .where {
                all(
                    "tenantId" equalTo tenantId,
                    "status" equalTo "active",
                )
            }
            .orderBy("name", Direction.ASCENDING)
            .limit(limit)
            .observeDocuments { id, customer -> customer.copy(id = id) }

    suspend fun create(request: CreateCustomerRequest): AppResult<CreateCustomerResponse> =
        caller.call(BackendFunctions.createCustomer, request)

    suspend fun update(request: UpdateCustomerRequest): AppResult<AckDto> =
        caller.call(BackendFunctions.updateCustomer, request)

    suspend fun archive(tenantId: String, customerId: String): AppResult<AckDto> =
        caller.call(
            BackendFunctions.updateCustomer,
            UpdateCustomerRequest(tenantId = tenantId, customerId = customerId, status = "archived"),
        )
}
