package com.articals.logistics.data.repository

import com.articals.logistics.core.AppError
import com.articals.logistics.core.AppResult
import com.articals.logistics.core.appRunCatching
import com.articals.logistics.data.FirestorePaths
import com.articals.logistics.data.toStorageData
import dev.gitlive.firebase.storage.FirebaseStorage
import dev.gitlive.firebase.storage.FirebaseStorageMetadata
import dev.gitlive.firebase.storage.getDownloadUrl
import dev.gitlive.firebase.storage.putData
import dev.gitlive.firebase.storage.putDataResumable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Bytes plus the metadata Storage needs to accept the upload. */
class UploadPayload(
    val bytes: ByteArray,
    val contentType: String,
    val fileName: String,
) {
    val sizeBytes: Int get() = bytes.size

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is UploadPayload &&
                fileName == other.fileName &&
                contentType == other.contentType &&
                bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * (31 * bytes.contentHashCode() + contentType.hashCode()) + fileName.hashCode()
}

/**
 * File uploads.
 *
 * Only three things are ever uploaded in the MVP (spec §26): a delivery proof
 * photo, a company logo, and — for platform administrators — offline payment
 * evidence. Each lands in a path whose security rules pin who may write it and how
 * large it may be, and proofs are create-only: a second upload cannot replace
 * evidence of what was delivered.
 *
 * Progress is reported from the real upload, and a failure is reported as a
 * failure. The app never claims a photo was saved when Storage refused it — the
 * delivery then simply has no proof photo, which the dispatcher can see.
 */
class StorageRepository(
    private val storage: FirebaseStorage,
) {
    /** Uploads a proof photo; resolves to the storage path it was written to. */
    suspend fun uploadProof(
        tenantId: String,
        orderId: String,
        payload: UploadPayload,
    ): AppResult<String> = appRunCatching {
        val path = FirestorePaths.proofPath(tenantId, orderId, payload.fileName)
        storage.reference(path).putData(
            payload.bytes.toStorageData(),
            FirebaseStorageMetadata(contentType = payload.contentType),
        )
        path
    }

    /** Uploads the company logo; resolves to a download URL the app can show. */
    suspend fun uploadCompanyLogo(tenantId: String, payload: UploadPayload): AppResult<String> = appRunCatching {
        val path = FirestorePaths.companyLogoPath(tenantId, payload.fileName)
        val reference = storage.reference(path)
        reference.putData(
            payload.bytes.toStorageData(),
            FirebaseStorageMetadata(contentType = payload.contentType),
        )
        reference.getDownloadUrl()
    }

    /** Platform administrators only: evidence attached to an offline payment record. */
    suspend fun uploadBillingEvidence(tenantId: String, payload: UploadPayload): AppResult<String> = appRunCatching {
        val path = "billing/$tenantId/${payload.fileName}"
        storage.reference(path).putData(
            payload.bytes.toStorageData(),
            FirebaseStorageMetadata(contentType = payload.contentType),
        )
        path
    }

    /**
     * Progress for the proof-photo screen, as a fraction between 0 and 1.
     *
     * The upload is started by [uploadProof]; this resumable handle exists so the
     * screen can show a real progress bar instead of an indefinite spinner.
     */
    fun observeProofUpload(tenantId: String, orderId: String, payload: UploadPayload): Flow<Double> {
        val path = FirestorePaths.proofPath(tenantId, orderId, payload.fileName)
        val flow = storage.reference(path).putDataResumable(
            payload.bytes.toStorageData(),
            FirebaseStorageMetadata(contentType = payload.contentType),
        )
        return flow.map { progress ->
            val total = progress.totalByteCount.toDouble().coerceAtLeast(1.0)
            (progress.bytesTransferred.toDouble() / total).coerceIn(0.0, 1.0)
        }
    }

    /** Local validation that mirrors the storage rules, so the user hears about it first. */
    fun validateProof(payload: UploadPayload): AppError? = when {
        payload.bytes.isEmpty() -> AppError.InvalidInput(detail = "file_empty", message = "That photo is empty.")
        payload.sizeBytes > MAX_PROOF_BYTES ->
            AppError.InvalidInput(detail = "file_too_large", message = "Proof photos must be smaller than 5 MB.")
        payload.contentType !in ALLOWED_IMAGE_TYPES ->
            AppError.InvalidInput(detail = "file_type", message = "Proof photos must be a JPEG or PNG image.")
        else -> null
    }

    companion object {
        const val MAX_PROOF_BYTES: Int = 5 * 1024 * 1024
        const val MAX_LOGO_BYTES: Int = 2 * 1024 * 1024
        val ALLOWED_IMAGE_TYPES = setOf("image/jpeg", "image/png")
    }
}
