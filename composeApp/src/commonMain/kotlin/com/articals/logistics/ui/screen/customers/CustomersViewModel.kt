package com.articals.logistics.ui.screen.customers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.articals.logistics.core.AppError
import com.articals.logistics.core.AppResult
import com.articals.logistics.core.UiState
import com.articals.logistics.data.api.CustomersRepository
import com.articals.logistics.data.api.OrdersRepository
import com.articals.logistics.data.dto.CreateCustomerRequest
import com.articals.logistics.data.dto.CustomerDto
import com.articals.logistics.data.dto.OrderDto
import com.articals.logistics.data.dto.UpdateCustomerRequest
import com.articals.logistics.ui.text.ArticalsStrings
import com.articals.logistics.util.Idempotency
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The customer being added or edited. `id == null` means a new one. */
data class CustomerEditor(
    val id: String? = null,
    val name: String = "",
    val phone: String = "",
    val email: String = "",
    val address: String = "",
    val customerType: String = "individual",
    val notes: String = "",
) {
    val nameValid: Boolean get() = name.trim().length >= 2

    val phoneValid: Boolean get() = phone.trim().length >= 7

    val valid: Boolean get() = nameValid && phoneValid

    val isNew: Boolean get() = id == null
}

data class CustomersState(
    val query: String = "",
    val showArchived: Boolean = false,
    val customers: List<CustomerDto> = emptyList(),
    val loading: Boolean = true,
    val error: AppError? = null,
    val notice: String? = null,
    val editor: CustomerEditor? = null,
    val saving: Boolean = false,
    val editorError: AppError? = null,
    val archiving: CustomerDto? = null,
) {
    /** What the list shows: the search, then the archived filter. */
    val visible: List<CustomerDto>
        get() {
            val needle = query.trim().lowercase()
            return customers
                .filter { (it.status == "archived") == showArchived }
                .filter {
                    needle.isEmpty() ||
                        it.name.lowercase().contains(needle) ||
                        it.phone?.contains(needle) == true ||
                        it.email?.lowercase()?.contains(needle) == true
                }
        }

    val archivedCount: Int get() = customers.count { it.status == "archived" }

    val activeCount: Int get() = customers.count { it.status != "archived" }

    val listState: UiState<List<CustomerDto>>
        get() = when {
            loading -> UiState.Loading
            error != null && customers.isEmpty() -> UiState.Error(error)
            visible.isEmpty() -> UiState.Empty(
                title = if (query.isBlank()) {
                    ArticalsStrings.Settings.customersEmpty
                } else {
                    ArticalsStrings.Customers.noMatches
                },
                message = if (query.isBlank()) {
                    ArticalsStrings.Settings.customersEmptyBody
                } else {
                    ArticalsStrings.Customers.noMatchesBody
                },
                actionLabel = if (query.isBlank()) ArticalsStrings.Settings.addCustomer else null,
            )

            else -> UiState.Content(visible)
        }
}

/**
 * The customer book.
 *
 * Customers are one of the two things a delivery is *for* (the other being a rider),
 * so this screen is where an order's "who is this for" comes from. Editing is
 * deliberately small: a name, a way to reach them, where they are, and whether they
 * are a person or a business. Anything more belongs on the delivery.
 *
 * Archiving is the only removal. A customer with deliveries behind it cannot be
 * deleted — the deliveries would lose their history of who they were for.
 */
class CustomersViewModel(
    private val customers: CustomersRepository,
    private val orders: OrdersRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(CustomersState())
    val state: StateFlow<CustomersState> = mutableState.asStateFlow()

    private var tenantId: String? = null
    private var addKey: String = Idempotency.newKey("customer.create")

    fun load(tenantId: String) {
        if (this.tenantId == tenantId) return
        this.tenantId = tenantId
        mutableState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            customers.observeCustomers(tenantId).collect { list ->
                mutableState.update { it.copy(customers = list, loading = false, error = null) }
            }
        }
    }

    fun setQuery(query: String) = mutableState.update { it.copy(query = query) }

    fun setShowArchived(show: Boolean) = mutableState.update { it.copy(showArchived = show) }

    // --- the editor ---------------------------------------------------------

    fun openEditor(customer: CustomerDto? = null) = mutableState.update {
        it.copy(
            editor = if (customer == null) {
                CustomerEditor()
            } else {
                CustomerEditor(
                    id = customer.id,
                    name = customer.name,
                    phone = customer.phone.orEmpty(),
                    email = customer.email.orEmpty(),
                    address = customer.address.orEmpty(),
                    customerType = customer.customerType,
                    notes = customer.notes.orEmpty(),
                )
            },
            editorError = null,
        )
    }

    fun closeEditor() = mutableState.update { it.copy(editor = null, editorError = null) }

    fun updateEditor(editor: CustomerEditor) = mutableState.update { it.copy(editor = editor) }

    fun clearEditorError() = mutableState.update { it.copy(editorError = null) }

    fun clearNotice() = mutableState.update { it.copy(notice = null) }

    /**
     * Saves the editor.
     *
     * Creating a customer carries an idempotency key so a retry after a lost response
     * produces one customer rather than two — the same rule every create follows.
     */
    fun saveEditor() {
        val tenantId = tenantId ?: return
        val editor = mutableState.value.editor ?: return
        if (!editor.valid) {
            mutableState.update { it.copy(editorError = ArticalsStrings.Customers.incomplete()) }
            return
        }

        mutableState.update { it.copy(saving = true, editorError = null) }
        viewModelScope.launch {
            val result = if (editor.isNew) {
                customers.create(
                    CreateCustomerRequest(
                        tenantId = tenantId,
                        idempotencyKey = addKey,
                        name = editor.name.trim(),
                        customerType = editor.customerType,
                        phone = editor.phone.trim(),
                        email = editor.email.trim().takeIf { it.isNotEmpty() },
                        address = editor.address.trim().takeIf { it.isNotEmpty() },
                        notes = editor.notes.trim().takeIf { it.isNotEmpty() },
                    ),
                )
            } else {
                customers.update(
                    UpdateCustomerRequest(
                        tenantId = tenantId,
                        customerId = editor.id.orEmpty(),
                        name = editor.name.trim(),
                        phone = editor.phone.trim(),
                        customerType = editor.customerType,
                        email = editor.email.trim().takeIf { it.isNotEmpty() },
                        address = editor.address.trim().takeIf { it.isNotEmpty() },
                        notes = editor.notes.trim().takeIf { it.isNotEmpty() },
                    ),
                )
            }

            when (result) {
                is AppResult.Failure -> mutableState.update {
                    it.copy(saving = false, editorError = result.error)
                }

                is AppResult.Success -> {
                    if (editor.isNew) addKey = Idempotency.newKey("customer.create")
                    mutableState.update {
                        it.copy(
                            saving = false,
                            editor = null,
                            editorError = null,
                            notice = if (editor.isNew) {
                                ArticalsStrings.Customers.added
                            } else {
                                ArticalsStrings.Customers.saved
                            },
                        )
                    }
                }
            }
        }
    }

    // --- archiving ----------------------------------------------------------

    fun askArchive(customer: CustomerDto) = mutableState.update { it.copy(archiving = customer) }

    fun cancelArchive() = mutableState.update { it.copy(archiving = null) }

    fun confirmArchive() {
        val tenantId = tenantId ?: return
        val customer = mutableState.value.archiving ?: return
        mutableState.update { it.copy(archiving = null, saving = true) }
        viewModelScope.launch {
            when (val result = customers.archive(tenantId, customer.id)) {
                is AppResult.Failure -> mutableState.update { it.copy(saving = false, error = result.error) }
                is AppResult.Success -> mutableState.update {
                    it.copy(saving = false, notice = ArticalsStrings.Customers.archived(customer.name))
                }
            }
        }
    }

    /** Restores an archived customer, which is the same update with the status back. */
    fun restore(customer: CustomerDto) {
        val tenantId = tenantId ?: return
        viewModelScope.launch {
            val request = UpdateCustomerRequest(
                tenantId = tenantId,
                customerId = customer.id,
                status = "active",
            )
            when (val result = customers.update(request)) {
                is AppResult.Failure -> mutableState.update { it.copy(error = result.error) }
                is AppResult.Success -> mutableState.update {
                    it.copy(notice = ArticalsStrings.Customers.restored(customer.name))
                }
            }
        }
    }

    fun dismissError() = mutableState.update { it.copy(error = null) }
}

data class CustomerDetailState(
    val customer: CustomerDto? = null,
    val orders: List<OrderDto> = emptyList(),
    val loading: Boolean = true,
    val error: AppError? = null,
) {
    val spendMinor: Long get() = orders.mapNotNull { it.customerChargeMinor }.sum()

    val deliveries: Int get() = orders.size
}

/**
 * One customer, with the deliveries they are behind.
 *
 * The order list is the reason this screen exists: "who is this, and what have we done
 * for them" is one question, asked in one place, and the answer comes from the same
 * `orders.observeCustomerOrders` the orders screens use rather than a new query.
 */
class CustomerDetailViewModel(
    private val customers: CustomersRepository,
    private val orders: OrdersRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(CustomerDetailState())
    val state: StateFlow<CustomerDetailState> = mutableState.asStateFlow()

    private var customerId: String? = null

    fun load(tenantId: String, customerId: String) {
        if (this.customerId == customerId) return
        this.customerId = customerId
        viewModelScope.launch {
            customers.observeCustomers(tenantId).collect { list ->
                mutableState.update {
                    it.copy(
                        customer = list.firstOrNull { customer -> customer.id == customerId },
                        loading = false,
                    )
                }
            }
        }
        viewModelScope.launch {
            orders.observeCustomerOrders(tenantId, customerId).collect { list ->
                mutableState.update { it.copy(orders = list, loading = false) }
            }
        }
    }
}
