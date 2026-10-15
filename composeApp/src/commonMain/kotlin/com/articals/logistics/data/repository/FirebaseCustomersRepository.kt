package com.articals.logistics.data.repository

import com.articals.logistics.data.api.CustomersRepository
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
import dev.gitlive.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The customer directory: the businesses and people who place deliveries. */
class FirebaseCustomersRepository(
    private val firestore: FirebaseFirestore,
    private val caller: FunctionsCaller,
) : CustomersRepository {
    override fun observeCustomers(tenantId: String, limit: Int): Flow<List<CustomerDto>> =
        firestore.collection(FirestorePaths.customers)
            .where { "tenantId" equalTo tenantId }
            .limit(limit)
            .observeDocuments { id, customer -> customer.copy(id = id) }
            // Ordered here rather than in the query on purpose: an equality filter needs
            // no composite index, and client-side ordering keeps every screen working on
            // a fresh project before firestore.indexes.json has finished building.
            .map { customers -> customers.sortedBy { it.name.lowercase() } }

    /** Archived customers stay readable so historic deliveries keep their context. */
    override fun observeActiveCustomers(tenantId: String, limit: Int): Flow<List<CustomerDto>> =
        firestore.collection(FirestorePaths.customers)
            .where {
                all(
                    "tenantId" equalTo tenantId,
                    "status" equalTo "active",
                )
            }
            .limit(limit)
            .observeDocuments { id, customer -> customer.copy(id = id) }
            // Ordered here rather than in the query on purpose: an equality filter needs
            // no composite index, and client-side ordering keeps every screen working on
            // a fresh project before firestore.indexes.json has finished building.
            .map { customers -> customers.sortedBy { it.name.lowercase() } }

    override suspend fun create(request: CreateCustomerRequest): AppResult<CreateCustomerResponse> =
        caller.call(BackendFunctions.createCustomer, request)

    override suspend fun update(request: UpdateCustomerRequest): AppResult<AckDto> =
        caller.call(BackendFunctions.updateCustomer, request)

    override suspend fun archive(tenantId: String, customerId: String): AppResult<AckDto> =
        caller.call(
            BackendFunctions.updateCustomer,
            UpdateCustomerRequest(tenantId = tenantId, customerId = customerId, status = "archived"),
        )
}
