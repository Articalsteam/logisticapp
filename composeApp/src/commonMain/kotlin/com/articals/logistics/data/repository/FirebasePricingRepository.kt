package com.articals.logistics.data.repository

import com.articals.logistics.data.api.PricingRepository
import com.articals.logistics.core.AppResult
import com.articals.logistics.data.BackendFunctions
import com.articals.logistics.data.FirestorePaths
import com.articals.logistics.data.FunctionsCaller
import com.articals.logistics.data.dto.PricingConfigDto
import com.articals.logistics.data.dto.QuoteRequestDto
import com.articals.logistics.data.dto.QuoteResponseDto
import com.articals.logistics.data.dto.SavedQuoteDto
import com.articals.logistics.data.dto.UpdatePricingRequest
import com.articals.logistics.data.dto.UpdatePricingResponse
import com.articals.logistics.data.documentFlow
import com.articals.logistics.data.observeDocuments
import dev.gitlive.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The delivery price and the company's pricing rules.
 *
 * The price is *always* computed by the backend (ADR-005). The app sends the two
 * locations and renders the returned breakdown; it never applies the formula
 * itself, so a change to the rules takes effect on every device immediately and no
 * two clients can disagree about what a delivery costs.
 */
class FirebasePricingRepository(
    private val firestore: FirebaseFirestore,
    private val caller: FunctionsCaller,
) : PricingRepository {
    /**
     * Asks the backend for a quote.
     *
     * The callable timeout is longer than the default because the backend may wait
     * on a routing provider; a quote that is still running when the dispatcher
     * changes the address is simply superseded.
     */
    override suspend fun requestQuote(request: QuoteRequestDto): AppResult<QuoteResponseDto> = caller.call(
        BackendFunctions.calculateDeliveryQuote,
        request,
        timeoutMillis = FunctionsCaller.QUOTE_TIMEOUT_MILLIS,
    )

    /** Saves pricing rules; the backend validates them and keeps the previous version. */
    override suspend fun savePricingRules(config: PricingConfigDto): AppResult<UpdatePricingResponse> = caller.call(
        BackendFunctions.updateCompanyPricing,
        UpdatePricingRequest(tenantId = config.tenantId, settings = config, configId = config.id.ifBlank { null }),
    )

    /** The rules currently in force, for the read-only "how this price was reached" view. */
    override fun observeActivePricingConfig(configId: String): Flow<PricingConfigDto?> =
        firestore.documentFlow(FirestorePaths.pricingConfig(configId))

    /**
     * Saved quotes, newest first.
     *
     * The security rules let a member with `quotes.create` read the quotes of their
     * own company, and the composite index (tenantId, createdAt) is already declared
     * in firestore.indexes.json — so this is a plain read, no callable needed.
     */
    override fun observeSavedQuotes(tenantId: String, limit: Int): Flow<List<SavedQuoteDto>> =
        firestore.collection(FirestorePaths.quotes)
            .where { "tenantId" equalTo tenantId }
            .limit(limit)
            .observeDocuments { id, quote -> quote.copy(id = id) }
            // Ordered here rather than in the query on purpose: an equality filter needs
            // no composite index, and client-side ordering keeps every screen working on
            // a fresh project before firestore.indexes.json has finished building.
            .map { quotes -> quotes.sortedByDescending { it.createdAt ?: "" } }

    /** The active config id is stored on the company document. */
    override fun observeActivePricingConfigId(tenantId: String): Flow<String?> =
        firestore.documentFlow<CompanyIdHolder>(FirestorePaths.company(tenantId)).map { it?.activePricingConfigId }
}

/** Minimal projection used when only the company's active pricing pointer is needed. */
@kotlinx.serialization.Serializable
internal data class CompanyIdHolder(
    val activePricingConfigId: String?,
    val currency: String?,
)
