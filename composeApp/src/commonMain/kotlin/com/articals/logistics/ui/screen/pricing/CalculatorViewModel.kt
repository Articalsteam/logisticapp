package com.articals.logistics.ui.screen.pricing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.articals.logistics.core.AppError
import com.articals.logistics.core.AppResult
import com.articals.logistics.core.UiState
import com.articals.logistics.data.api.CompanyRepository
import com.articals.logistics.data.api.CustomersRepository
import com.articals.logistics.data.api.OrdersRepository
import com.articals.logistics.data.api.PricingRepository
import com.articals.logistics.data.dto.CreateOrderRequest
import com.articals.logistics.data.dto.CustomerDto
import com.articals.logistics.data.dto.CustomerSnapshotDto
import com.articals.logistics.data.dto.NewCustomerDto
import com.articals.logistics.data.dto.QuoteDto
import com.articals.logistics.data.dto.SavedQuoteDto
import com.articals.logistics.ui.text.ArticalsStrings
import com.articals.logistics.util.Idempotency
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Who the delivery is for. Three shapes, because the backend accepts three. */
enum class CustomerMode { None, Existing, New }

data class CalculatorState(
    val form: QuoteFormState = QuoteFormState(),
    val quote: QuoteDto? = null,
    val quoteState: UiState<QuoteDto>? = null,
    val warningsAcknowledged: Set<String> = emptySet(),
    val quoting: Boolean = false,
    val creating: Boolean = false,
    val error: AppError? = null,
    val notice: String? = null,
    val customers: List<CustomerDto> = emptyList(),
    val customerMode: CustomerMode = CustomerMode.None,
    val customerId: String? = null,
    val newCustomerName: String = "",
    val newCustomerPhone: String = "",
    val newCustomerType: String = "individual",
    val serviceAreas: List<String> = emptyList(),
    val savedQuotes: List<SavedQuoteDto> = emptyList(),
    val createdOrderId: String? = null,
) {
    /** True when the price may be turned into a delivery. */
    val canCreate: Boolean
        get() {
            val quote = this.quote ?: return false
            if (quote.requiresReviewBeforeAccepting) return false
            val outstanding = quote.warnings
                .filter { it.severity == "blocker" || it.code in REVIEW_CODES }
                .map { it.code }
                .toSet()
            return outstanding.all { it in warningsAcknowledged }
        }

    val customerValid: Boolean
        get() = when (customerMode) {
            CustomerMode.None -> true
            CustomerMode.Existing -> !customerId.isNullOrBlank()
            CustomerMode.New -> newCustomerName.trim().length >= 2 && newCustomerPhone.trim().length >= 7
        }

    private companion object {
        val REVIEW_CODES = com.articals.logistics.domain.QuoteWarningCodes.reviewRequired
    }
}

/**
 * The price calculator.
 *
 * It asks the pricing owner for a price and shows what came back, line by line. The
 * three things it can then do are the three things a dispatcher actually wants: keep
 * the quote, turn it into a delivery, or change an input and ask again.
 *
 * Overrides (the rider's cost, the customer's price, a target margin) are *inputs* to
 * the engine, not edits to its answer — which is why the price is always recomputed
 * rather than adjusted here (ADR-002).
 */
class CalculatorViewModel(
    private val pricing: PricingRepository,
    private val orders: OrdersRepository,
    private val customers: CustomersRepository,
    private val companies: CompanyRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(CalculatorState())
    val state: StateFlow<CalculatorState> = mutableState.asStateFlow()

    private var tenantId: String? = null
    private var loadedFor: String? = null

    /**
     * The idempotency key for the delivery currently being composed.
     *
     * Generated once and reused until a create succeeds, so a retry after a lost
     * response produces one delivery rather than two.
     */
    private var createKey: String = Idempotency.newKey("order.create")

    /**
     * A quote the user asked to reuse, remembered until the saved quotes have loaded.
     *
     * The history and the calculator are separate screens with separate view models, so
     * the hand-off is by id rather than by object: nothing is shared in memory, and a
     * screen opened from a deep link behaves exactly like one opened from the list.
     */
    private var pendingQuoteId: String? = null

    fun load(tenantId: String) {
        this.tenantId = tenantId
        if (loadedFor == tenantId) return
        loadedFor = tenantId
        createKey = Idempotency.newKey("order.create")

        viewModelScope.launch {
            customers.observeActiveCustomers(tenantId).collect { list ->
                mutableState.update { it.copy(customers = list) }
            }
        }
        viewModelScope.launch {
            companies.observeCompany(tenantId).collect { company ->
                mutableState.update { it.copy(serviceAreas = company?.serviceAreas.orEmpty()) }
            }
        }
        viewModelScope.launch {
            pricing.observeSavedQuotes(tenantId).collect { quotes ->
                mutableState.update { it.copy(savedQuotes = quotes) }
                val wanted = pendingQuoteId ?: return@collect
                quotes.firstOrNull { it.id == wanted }?.let { saved ->
                    reuseQuote(saved)
                    pendingQuoteId = null
                }
            }
        }
    }

    fun updateForm(form: QuoteFormState) = mutableState.update {
        // A changed input invalidates the price it produced: showing a stale total
        // beside new addresses would be worse than showing none.
        it.copy(form = form, quote = null, quoteState = null, warningsAcknowledged = emptySet())
    }

    fun setCustomerMode(mode: CustomerMode) = mutableState.update { it.copy(customerMode = mode) }

    fun setCustomer(customerId: String?) = mutableState.update { it.copy(customerId = customerId) }

    fun setNewCustomer(name: String, phone: String, type: String) = mutableState.update {
        it.copy(newCustomerName = name, newCustomerPhone = phone, newCustomerType = type)
    }

    fun acknowledge(code: String) = mutableState.update {
        it.copy(warningsAcknowledged = it.warningsAcknowledged + code)
    }

    fun clearError() = mutableState.update { it.copy(error = null) }

    fun clearNotice() = mutableState.update { it.copy(notice = null) }

    fun consumeCreatedOrder() = mutableState.update { it.copy(createdOrderId = null) }

    /** Asks for a price. Saving the quote puts it in the history as well. */
    fun requestQuote(save: Boolean) {
        val tenantId = tenantId ?: return
        val form = mutableState.value.form
        if (!form.canAskForPrice) {
            mutableState.update {
                it.copy(
                    error = AppError.InvalidInput(
                        detail = "quote.incomplete",
                        message = ArticalsStrings.Pricing.subtitle,
                        fields = mapOf(
                            "pickup.address" to ArticalsStrings.Pricing.address,
                            "destination.address" to ArticalsStrings.Pricing.address,
                        ),
                    ),
                )
            }
            return
        }

        mutableState.update { it.copy(quoting = true, error = null, notice = null, quoteState = UiState.Loading) }
        viewModelScope.launch {
            val request = form.toRequest(
                tenantId = tenantId,
                extraCosts = form.additionalCosts(),
                riderCostOverride = form.riderCostOverrideOrNull(),
                priceOverride = form.priceOverrideOrNull(),
                targetProfitOverride = form.targetProfitOverrideOrNull(),
                saveQuote = save,
            )
            when (val result = pricing.requestQuote(request)) {
                is AppResult.Failure -> mutableState.update {
                    it.copy(quoting = false, error = result.error, quoteState = UiState.Error(result.error))
                }

                is AppResult.Success -> {
                    val quote = result.value.quote
                    mutableState.update { current ->
                        current.copy(
                            quoting = false,
                            quote = quote,
                            quoteState = quote?.let { UiState.Content(it) },
                            warningsAcknowledged = emptySet(),
                            notice = if (save && result.value.quoteId != null) {
                                ArticalsStrings.Pricing.quoteSaved
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
    }

    /**
     * Creates the delivery from what is on screen.
     *
     * The price is not sent: the backend recomputes it from the same inputs and the
     * same rate card, which is the only way a client can be sure the price it shows is
     * the price that was charged.
     */
    fun createOrder() {
        val tenantId = tenantId ?: return
        val current = mutableState.value
        val form = current.form
        if (!form.canAskForPrice || !current.canCreate || !current.customerValid) return

        mutableState.update { it.copy(creating = true, error = null, notice = null) }
        viewModelScope.launch {
            // The same inputs the quote was built from, so the backend's recomputation
            // cannot disagree with the price on screen.
            val priced = form.toRequest(
                tenantId = tenantId,
                extraCosts = emptyList(),
                riderCostOverride = null,
                priceOverride = null,
                targetProfitOverride = null,
                saveQuote = null,
            )
            val request = CreateOrderRequest(
                tenantId = tenantId,
                idempotencyKey = createKey,
                pickup = priced.pickup,
                destination = priced.destination,
                serviceLevel = form.serviceLevel,
                description = form.description.trim(),
                itemCategory = form.itemCategory,
                customerId = current.customerId?.takeIf { current.customerMode == CustomerMode.Existing },
                newCustomer = if (current.customerMode == CustomerMode.New) {
                    NewCustomerDto(
                        name = current.newCustomerName.trim(),
                        phone = current.newCustomerPhone.trim(),
                        customerType = current.newCustomerType,
                    )
                } else {
                    null
                },
                customerSnapshot = CustomerSnapshotDto(
                    name = form.pickupContactName.trim().takeIf { it.isNotEmpty() }
                        ?: current.newCustomerName.trim().takeIf { it.isNotEmpty() }
                        ?: form.pickupAddress.trim(),
                    phone = form.pickupContactPhone.trim().takeIf { it.isNotEmpty() },
                ).takeIf { current.customerMode == CustomerMode.None },
                additionalCosts = form.additionalCosts(),
                riderCostOverride = form.riderCostOverrideOrNull(),
                customerPriceOverride = form.priceOverrideOrNull(),
                targetProfitOverride = form.targetProfitOverrideOrNull(),
                acknowledgedWarningCodes = current.warningsAcknowledged.toList().takeIf { it.isNotEmpty() },
            )

            when (val result = orders.createOrder(request)) {
                is AppResult.Failure -> mutableState.update {
                    it.copy(creating = false, error = result.error)
                }

                is AppResult.Success -> {
                    // A new key: the next delivery is a different delivery.
                    createKey = Idempotency.newKey("order.create")
                    mutableState.update {
                        it.copy(
                            creating = false,
                            createdOrderId = result.value.orderId,
                            notice = ArticalsStrings.Orders.orderCreated,
                        )
                    }
                }
            }
        }
    }

    /** Asks for a saved quote to be loaded once it arrives. Used by the history screen. */
    fun reuseQuoteById(quoteId: String) {
        pendingQuoteId = quoteId
        mutableState.value.savedQuotes.firstOrNull { it.id == quoteId }?.let { saved ->
            reuseQuote(saved)
            pendingQuoteId = null
        }
    }

    /** Loads a saved quote back into the form so it can be reused or turned into an order. */
    fun reuseQuote(saved: SavedQuoteDto) {
        val form = QuoteFormState(
            pickupAddress = saved.pickup.address,
            pickupArea = saved.pickup.area.orEmpty(),
            pickupContactName = saved.pickup.contactName.orEmpty(),
            pickupContactPhone = saved.pickup.contactPhone.orEmpty(),
            destinationAddress = saved.destination.address,
            destinationArea = saved.destination.area.orEmpty(),
            destinationContactName = saved.destination.contactName.orEmpty(),
            destinationContactPhone = saved.destination.contactPhone.orEmpty(),
            distanceKm = com.articals.logistics.core.distanceToEditableKilometres(saved.distance?.metres),
            serviceLevel = saved.serviceLevel,
            description = "",
        )
        mutableState.update {
            it.copy(form = form, quote = null, quoteState = null, warningsAcknowledged = emptySet())
        }
    }
}
