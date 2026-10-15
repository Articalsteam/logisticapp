package com.articals.logistics.data.api

import com.articals.logistics.core.AppError
import com.articals.logistics.core.AppResult
import com.articals.logistics.data.UploadPayload
import kotlinx.coroutines.flow.Flow

/**
 * File uploads: delivery proofs, the company logo, and payment evidence for
 * platform administrators. Three paths, each pinned by the storage rules.
 */
interface StorageRepository {
    suspend fun uploadProof(tenantId: String, orderId: String, payload: UploadPayload): AppResult<String>

    suspend fun uploadCompanyLogo(tenantId: String, payload: UploadPayload): AppResult<String>

    suspend fun uploadBillingEvidence(tenantId: String, payload: UploadPayload): AppResult<String>

    /** Upload progress from 0.0 to 1.0, from the real transfer. */
    fun observeProofUpload(tenantId: String, orderId: String, payload: UploadPayload): Flow<Double>

    /** Local validation that mirrors the rules, so the user hears about it first. */
    fun validateProof(payload: UploadPayload): AppError?
}
