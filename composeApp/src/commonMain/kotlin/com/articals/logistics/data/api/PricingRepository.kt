package com.articals.logistics.data.api

import com.articals.logistics.core.AppResult
import com.articals.logistics.data.dto.PricingConfigDto
import com.articals.logistics.data.dto.QuoteRequestDto
import com.articals.logistics.data.dto.QuoteResponseDto
import com.articals.logistics.data.dto.SavedQuoteDto
import com.articals.logistics.data.dto.UpdatePricingResponse
import kotlinx.coroutines.flow.Flow

/**
 * The delivery price and the company's rate card.
 *
 * The price is produced by whoever owns the rules — the backend in Firebase mode,
 * the demo quote simulator in demo mode. In both cases the screen only ever renders
 * a breakdown it was handed (ADR-002, ADR-013).
 */
interface PricingRepository {
    suspend fun requestQuote(request: QuoteRequestDto): AppResult<QuoteResponseDto>

    suspend fun savePricingRules(config: PricingConfigDto): AppResult<UpdatePricingResponse>

    fun observeActivePricingConfig(configId: String): Flow<PricingConfigDto?>

    /** The active config id and currency, held on the company document. */
    fun observeActivePricingConfigId(tenantId: String): Flow<String?>

    /** Saved quotes, newest first: the quote history screen and the order detail trail. */
    fun observeSavedQuotes(tenantId: String, limit: Int = 50): Flow<List<SavedQuoteDto>>
}
