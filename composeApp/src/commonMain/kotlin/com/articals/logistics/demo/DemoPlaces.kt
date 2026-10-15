package com.articals.logistics.demo

import com.articals.logistics.core.AppResult
import com.articals.logistics.data.UploadPayload
import com.articals.logistics.data.api.InAppNotifications
import com.articals.logistics.data.api.PlaceSuggestionSource
import com.articals.logistics.data.api.ProofPhotoCapture
import com.articals.logistics.data.dto.AppNotificationDto
import com.articals.logistics.data.dto.PlaceSuggestionDto
import com.articals.logistics.data.dto.QuoteLocationDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One known pickup/destination pair in the demo world. */
data class DemoDistance(
    val metres: Long,
    val minutes: Int,
) {
    val summary: String get() = "$kilometres km, about $minutes minutes"
    val kilometres: String get() = "${metres / 1_000}.${(metres % 1_000) / 100}"
}

/**
 * Distances between the demo's areas.
 *
 * Deliberately a fixed table: demo mode has no routing provider, and inventing a
 * road distance for an arbitrary pair of addresses would be exactly the kind of
 * fabrication the project forbids. A pair that is not in the table produces the
 * `distance_required` warning and the manual-distance field — the same path a
 * dispatcher uses in production when a provider cannot resolve an address.
 */
object DemoDistanceTable {

    val zones: List<String> = listOf(
        "ikeja", "maryland", "yaba", "surulere", "gbagada", "mushin",
        "victoria_island", "ikoyi", "lekki", "ajah", "apapa", "festac",
    )

    private val metres: Map<String, Long> = buildMap {
        fun pair(a: String, b: String, distance: Long) {
            put(key(a, b), distance)
            put(key(b, a), distance)
        }
        pair("ikeja", "maryland", 3_400)
        pair("ikeja", "yaba", 8_400)
        pair("ikeja", "surulere", 11_200)
        pair("ikeja", "mushin", 6_100)
        pair("ikeja", "gbagada", 12_600)
        pair("ikeja", "victoria_island", 19_800)
        pair("ikeja", "ikoyi", 17_400)
        pair("ikeja", "lekki", 24_900)
        pair("ikeja", "ajah", 31_500)
        pair("ikeja", "apapa", 15_300)
        pair("ikeja", "festac", 21_700)
        pair("maryland", "yaba", 7_200)
        pair("maryland", "mushin", 4_900)
        pair("maryland", "gbagada", 9_100)
        pair("maryland", "surulere", 10_600)
        pair("maryland", "victoria_island", 18_200)
        pair("maryland", "ikoyi", 15_900)
        pair("maryland", "lekki", 23_400)
        pair("yaba", "surulere", 4_300)
        pair("yaba", "mushin", 3_800)
        pair("yaba", "gbagada", 8_700)
        pair("yaba", "victoria_island", 11_500)
        pair("yaba", "ikoyi", 10_200)
        pair("yaba", "lekki", 17_800)
        pair("yaba", "apapa", 7_400)
        pair("surulere", "victoria_island", 9_600)
        pair("surulere", "ikoyi", 8_300)
        pair("surulere", "apapa", 5_200)
        pair("surulere", "festac", 12_400)
        pair("gbagada", "victoria_island", 13_100)
        pair("gbagada", "ikoyi", 11_900)
        pair("gbagada", "lekki", 19_600)
        pair("gbagada", "ajah", 26_800)
        pair("victoria_island", "ikoyi", 2_900)
        pair("victoria_island", "lekki", 7_600)
        pair("victoria_island", "ajah", 14_200)
        pair("victoria_island", "apapa", 8_900)
        pair("ikoyi", "lekki", 8_400)
        pair("ikoyi", "ajah", 15_700)
        pair("lekki", "ajah", 7_200)
        pair("apapa", "festac", 9_800)
        pair("mushin", "surulere", 3_100)
    }

    private fun key(from: String, to: String): String = "$from>$to"

    fun metresBetween(fromZone: String?, toZone: String?): Long? {
        if (fromZone == null || toZone == null) return null
        if (fromZone == toZone) return 2_500
        return metres[key(fromZone, toZone)]
    }

    /** Looks up a distance for the two locations, inferring the area from the text if needed. */
    fun lookup(pickup: QuoteLocationDto, destination: QuoteLocationDto): DemoDistance? {
        val from = pickup.zoneId ?: inferZone(pickup.address)
        val to = destination.zoneId ?: inferZone(destination.address)
        val distance = metresBetween(from, to) ?: return null
        return DemoDistance(metres = distance, minutes = ((distance / 1_000) * 3 + 6).toInt())
    }

    /** Recognises a demo area from an address string ("12 Allen Avenue, Ikeja"). */
    fun inferZone(address: String): String? {
        val lowered = address.lowercase()
        return zones.firstOrNull { zone ->
            lowered.contains(zone.replace('_', ' ')) ||
                aliases[zone]?.any { lowered.contains(it) } == true
        }
    }

    private val aliases: Map<String, List<String>> = mapOf(
        "ikeja" to listOf("allen avenue", "awolowo way", "opebi", "computer village"),
        "maryland" to listOf("iwaya", "onigbongbo"),
        "yaba" to listOf("herbert macaulay", "sabo", "akoka"),
        "surulere" to listOf("adeniran ogunsanya", "bode thomas", "aguda"),
        "gbagada" to listOf("diya street", "ifako"),
        "mushin" to listOf("idioro", "isolo road"),
        "victoria_island" to listOf("adeola odeku", "falomo", "oniru", "landmark"),
        "ikoyi" to listOf("awolowo road", "bourdillon", "parkview"),
        "lekki" to listOf("admiralty way", "ikate", "chevron", "elegushi"),
        "ajah" to listOf("sangotedo", "badore", "abraham adesanya"),
        "apapa" to listOf("wharf road", "marine road"),
        "festac" to listOf("amuw o", "amuw o-odofin", "mile 2"),
    )
}

/** The demo address book used while typing, mirroring the backend's own history search. */
class DemoPlaceSuggestionSource : PlaceSuggestionSource {
    override val isSimulated: Boolean = true

    override suspend fun search(
        tenantId: String,
        query: String,
        serviceAreas: List<String>,
    ): AppResult<List<PlaceSuggestionDto>> {
        if (query.trim().length < 2) return AppResult.Success(emptyList())
        val needle = query.trim().lowercase()
        val results = demoPlaces
            .filter { place ->
                (place.address.lowercase().contains(needle) ||
                    place.label.lowercase().contains(needle) ||
                    place.area?.lowercase()?.contains(needle) == true) &&
                    (serviceAreas.isEmpty() || place.area == null || serviceAreas.any {
                        it.equals(place.area, ignoreCase = true)
                    })
            }
            .take(8)
        return AppResult.Success(results)
    }

    companion object {
        /** Fictional businesses on fictional Lagos streets. */
        val demoPlaces: List<PlaceSuggestionDto> = listOf(
            PlaceSuggestionDto("Allen Avenue", "48 Allen Avenue, Ikeja, Lagos", "Ikeja", "ikeja", 6.6018, 3.3515),
            PlaceSuggestionDto("Computer Village", "1 Otigba Street, Computer Village, Ikeja", "Ikeja", "ikeja", 6.5947, 3.3412),
            PlaceSuggestionDto("Opebi Market", "12 Opebi Road, Ikeja", "Ikeja", "ikeja", 6.5991, 3.3566),
            PlaceSuggestionDto("Herbert Macaulay Way", "5 Herbert Macaulay Way, Yaba", "Yaba", "yaba", 6.5095, 3.3711),
            PlaceSuggestionDto("Yaba Tech Gate", "Sabo, Yaba, Lagos", "Yaba", "yaba", 6.5169, 3.3757),
            PlaceSuggestionDto("Ozone Plaza", "1 Ogunlana Drive, Surulere", "Surulere", "surulere", 6.5014, 3.3539),
            PlaceSuggestionDto("Adeniran Ogunsanya Mall", "23 Adeniran Ogunsanya, Surulere", "Surulere", "surulere", 6.5059, 3.3612),
            PlaceSuggestionDto("Third Mainland Bridge Approach", "Ifako, Gbagada, Lagos", "Gbagada", "gbagada", 6.5546, 3.3866),
            PlaceSuggestionDto("Idioro Junction", "Idioro, Mushin, Lagos", "Mushin", "mushin", 6.5276, 3.3484),
            PlaceSuggestionDto("Falomo Roundabout", "Falomo, Ikoyi, Lagos", "Ikoyi", "ikoyi", 6.4486, 3.4294),
            PlaceSuggestionDto("Awolowo Road", "101 Awolowo Road, Ikoyi", "Ikoyi", "ikoyi", 6.4519, 3.4346),
            PlaceSuggestionDto("Adeola Odeku Street", "14 Adeola Odeku Street, Victoria Island", "Victoria Island", "victoria_island", 6.4272, 3.4183),
            PlaceSuggestionDto("Oniru Market", "Oniru, Victoria Island, Lagos", "Victoria Island", "victoria_island", 6.4265, 3.4463),
            PlaceSuggestionDto("Admiralty Way", "22 Admiralty Way, Lekki Phase 1", "Lekki", "lekki", 6.4431, 3.4739),
            PlaceSuggestionDto("Ikate Elegushi", "Ikate, Lekki, Lagos", "Lekki", "lekki", 6.4322, 3.4888),
            PlaceSuggestionDto("Sangotedo Mall", "Sangotedo, Ajah, Lagos", "Ajah", "ajah", 6.4664, 3.5711),
            PlaceSuggestionDto("Abraham Adesanya Estate", "Ajah, Lagos", "Ajah", "ajah", 6.4529, 3.5713),
            PlaceSuggestionDto("Apapa Wharf", "Wharf Road, Apapa, Lagos", "Apapa", "apapa", 6.4487, 3.3606),
            PlaceSuggestionDto("Festac Town", "21 Road, Festac Town, Lagos", "Festac", "festac", 6.4744, 3.2877),
            PlaceSuggestionDto("Maryland Mall", "1 Ajao Estate Road, Maryland", "Maryland", "maryland", 6.5715, 3.3695),
        )
    }
}

/**
 * Proof of delivery in demo mode.
 *
 * Returns a bundled placeholder photograph, and says so: [isSimulated] is true, so
 * the screen labels it "simulated" instead of implying a real photo of a real
 * parcel exists. No camera permission is involved, which is why the demo runs on
 * any device without prompts.
 */
class DemoProofPhotoCapture(
    private val bytes: ByteArray = DemoAssets.proofPhoto,
) : ProofPhotoCapture {
    override val isSimulated: Boolean = true

    override suspend fun capture(): AppResult<UploadPayload> = AppResult.Success(
        UploadPayload(
            bytes = bytes,
            contentType = "image/png",
            fileName = "proof_demo_${DemoStore.SEED}.png",
        ),
    )
}

/**
 * Notifications in demo mode.
 *
 * Published in-app, clearly labelled simulated, and stored in the demo store so the
 * same notification appears on the rider's home, in the company's notification list
 * and in the bell badge — the rider-assignment flow can be seen end to end without a
 * push provider, and nothing ever claims a real notification was delivered.
 */
class DemoInAppNotifications(
    private val store: DemoStore,
) : InAppNotifications {

    override val notifications: StateFlow<List<AppNotificationDto>> = store.notifications

    override fun publish(title: String, body: String, simulated: Boolean) {
        store.publishNotification(title = title, body = body, simulated = simulated)
    }

    override fun dismiss(id: String) {
        store.dismissNotification(id)
    }
}
