package com.articals.logistics.data.repository

import com.articals.logistics.core.AppError
import com.articals.logistics.core.AppResult
import com.articals.logistics.data.FirestorePaths
import com.articals.logistics.data.UploadPayload
import com.articals.logistics.data.api.InAppNotifications
import com.articals.logistics.data.api.PlaceSuggestionSource
import com.articals.logistics.data.api.ProofPhotoCapture
import com.articals.logistics.data.dto.AppNotificationDto
import com.articals.logistics.data.dto.OrderDto
import com.articals.logistics.data.dto.PlaceSuggestionDto
import dev.gitlive.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.serializer

/**
 * Photographing proof of delivery.
 *
 * The camera module (Android `ActivityResultContracts.TakePicture`, iOS
 * `UIImagePickerController`) is platform code that is not compiled into this build
 * yet, so this implementation says so. It never returns invented bytes: a delivery
 * without a proof photo is visible to the dispatcher, which is honest — a fake
 * image would not be.
 */
class UnavailableProofPhotoCapture : ProofPhotoCapture {
    override val isSimulated: Boolean = false

    override suspend fun capture(): AppResult<UploadPayload> = AppResult.Failure(
        AppError.Unsupported(
            detail = "camera_not_configured",
            message = "Taking the proof photo needs the camera module, which is not part of this build. " +
                "Use demo mode to walk the flow, or run the pilot build once the camera module is compiled in.",
        ),
    )
}

/**
 * Address suggestions from the company's own delivery history.
 *
 * This is real data the app is allowed to read (the rules let staff read their own
 * company's orders) and it needs no API key. It deliberately cannot produce a
 * distance or a coordinate: that is the backend's job (ADR-002).
 */
class FirestoreHistoryPlaceSuggestionSource(
    private val firestore: FirebaseFirestore,
) : PlaceSuggestionSource {
    override val isSimulated: Boolean = false

    override suspend fun search(
        tenantId: String,
        query: String,
        serviceAreas: List<String>,
    ): AppResult<List<PlaceSuggestionDto>> = com.articals.logistics.core.appRunCatching {
        if (query.trim().length < 3) return@appRunCatching emptyList()
        val strategy = serializer<OrderDto>()
        val snapshot = firestore.collection(FirestorePaths.orders)
            .where { "tenantId" equalTo tenantId }
            .limit(RECENT_ORDERS)
            .get()

        val needle = query.trim().lowercase()
        val suggestions = linkedMapOf<String, PlaceSuggestionDto>()
        for (document in snapshot.documents) {
            if (!document.exists) continue
            val order = document.data(strategy)
            for (place in listOf(order.pickup, order.destination)) {
                if (place.address.isBlank()) continue
                if (!place.address.lowercase().contains(needle)) continue
                val key = place.address.lowercase()
                if (suggestions.containsKey(key)) continue
                suggestions[key] = PlaceSuggestionDto(
                    label = place.area ?: place.address.substringBefore(','),
                    address = place.address,
                    area = place.area,
                    zoneId = place.zoneId,
                    latitude = place.latitude,
                    longitude = place.longitude,
                    simulated = false,
                )
                if (suggestions.size >= MAX_SUGGESTIONS) break
            }
            if (suggestions.size >= MAX_SUGGESTIONS) break
        }
        val unservedArea = serviceAreas.firstOrNull { area ->
            needle.contains(area.lowercase()) && suggestions.isEmpty()
        }
        if (unservedArea != null) {
            suggestions[unservedArea] = PlaceSuggestionDto(
                label = unservedArea,
                address = unservedArea,
                area = unservedArea,
                zoneId = unservedArea.lowercase().replace(' ', '_'),
                simulated = false,
            )
        }
        suggestions.values.toList()
    }

    private companion object {
        const val RECENT_ORDERS = 120
        const val MAX_SUGGESTIONS = 8
    }
}

/**
 * Push notifications in Firebase mode.
 *
 * Real pushes arrive through FCM and are rendered by the operating system, so this
 * publisher stays silent. When the FCM module lands, this becomes the place where
 * foreground messages are surfaced — and it will still never invent one.
 */
class SilentInAppNotifications : InAppNotifications {
    private val state = MutableStateFlow<List<AppNotificationDto>>(emptyList())

    override val notifications: StateFlow<List<AppNotificationDto>> = state.asStateFlow()

    override fun publish(title: String, body: String, simulated: Boolean) {
        // Intentionally empty: see the class documentation.
    }

    override fun dismiss(id: String) {
        // Intentionally empty: nothing is ever published here.
    }
}
