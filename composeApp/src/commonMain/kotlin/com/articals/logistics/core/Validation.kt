package com.articals.logistics.core

import kotlin.math.abs

/**
 * Checks and conversions that exist only to save the user a round trip.
 *
 * The same rules are enforced on the server, where they are the real ones: a form
 * that passes here can still be refused, and the refusal is shown. Nothing in this
 * file is a security control.
 */

/** A deliberately permissive check: something@something.tld, no spaces. */
fun isValidEmail(value: String): Boolean {
    val trimmed = value.trim()
    if (trimmed.length < 5 || trimmed.length > 254) return false
    if (trimmed.any { it.isWhitespace() }) return false
    val at = trimmed.indexOf('@')
    if (at <= 0 || at != trimmed.lastIndexOf('@')) return false
    val local = trimmed.substring(0, at)
    val domain = trimmed.substring(at + 1)
    if (local.isEmpty() || domain.isEmpty()) return false
    if (!domain.contains('.')) return false
    if (domain.startsWith('.') || domain.endsWith('.') || domain.contains("..")) return false
    return true
}

/** Between 7 and 15 digits, which covers Nigerian and international numbers alike. */
fun isValidPhone(value: String): Boolean = value.count { it.isDigit() } in 7..15

/**
 * Parses a typed distance into metres.
 *
 * Distance is the one number in this app a person may type, because the backend
 * refuses to invent a distance it was not given: a quote with no distance comes back
 * blocked, and the dispatcher is asked for it (ADR-002).
 *
 * Accepts "8.4" (kilometres, the unit the field is labelled in), "8.4 km", "8400 m".
 * Returns null when the text is not a usable distance — never 0, which would silently
 * price the delivery as a zero-length journey.
 */
fun parseDistanceToMetresOrNull(input: String): Long? {
    val text = input.trim().lowercase().replace(",", ".")
    if (text.isEmpty()) return null

    val isKilometres = text.contains("km")
    val isMetres = !isKilometres && text.trimEnd().endsWith("m")

    val number = text.replace("km", "").replace("m", "").replace(" ", "").trim()
    if (number.isEmpty()) return null

    val parts = number.split('.')
    if (parts.size > 2) return null
    val wholeText = parts[0].ifEmpty { "0" }
    if (wholeText.any { it !in '0'..'9' }) return null
    val fractionText = parts.getOrNull(1).orEmpty()
    if (fractionText.any { it !in '0'..'9' }) return null
    if (fractionText.length > 3) return null

    val whole = wholeText.toLongOrNull() ?: return null
    val divisor = when (fractionText.length) {
        0 -> 1L
        1 -> 10L
        2 -> 100L
        else -> 1_000L
    }
    val fraction = fractionText.toLongOrNull() ?: 0L
    val thousandthsOfAKilometre = whole * 1_000L + fraction * (1_000L / divisor)
    val metres = if (isMetres) thousandthsOfAKilometre / 1_000L else thousandthsOfAKilometre

    return metres.takeIf { it in MIN_DISTANCE_METRES..MAX_DISTANCE_METRES }
}

/** The backend's own limits, so a refusal here matches a refusal there. */
private const val MIN_DISTANCE_METRES = 1L
private const val MAX_DISTANCE_METRES = 500_000L

/** "8.4 km", "12 km", "750 m" — a unit is never left implicit. */
fun formatDistanceMetres(metres: Long): String {
    if (metres < 1_000L) return "$metres m"
    val hundredMetres = (metres + 50L) / 100L
    val whole = hundredMetres / 10L
    val tenth = hundredMetres % 10L
    return if (tenth == 0L) "$whole km" else "$whole.$tenth km"
}

/** The editable form of a distance: "8.4", or "" when there is no distance. */
fun distanceToEditableKilometres(metres: Long?): String {
    if (metres == null || metres <= 0L) return ""
    val hundredMetres = (metres + 50L) / 100L
    val whole = hundredMetres / 10L
    val tenth = hundredMetres % 10L
    return if (tenth == 0L) whole.toString() else "$whole.$tenth"
}

/**
 * Basis points from a percentage the user typed.
 *
 * "25" means 25% → 2500 bps; "25.5" → 2550 bps. A margin is the one rate that is not
 * money, so it is converted once, here, and kept as basis points everywhere else —
 * exactly as the backend stores it.
 */
fun parsePercentToBpsOrNull(input: String): Int? {
    val text = input.trim().removeSuffix("%").replace(",", ".").trim()
    if (text.isEmpty()) return null
    val parts = text.split('.')
    if (parts.size > 2) return null
    val wholeText = parts[0].ifEmpty { "0" }
    if (wholeText.any { it !in '0'..'9' }) return null
    val fractionText = parts.getOrNull(1).orEmpty().padEnd(2, '0').take(2)
    if (fractionText.any { it !in '0'..'9' }) return null
    val bps = (wholeText.toLongOrNull() ?: return null) * 100L + (fractionText.toLongOrNull() ?: 0L)
    return bps.takeIf { it in 0L..10_000L }?.toInt()
}

/** The editable form of a margin: "25" or "25.5". */
fun bpsToEditablePercent(bps: Int): String {
    val whole = bps / 100
    val fraction = abs(bps) % 100
    return when {
        fraction == 0 -> whole.toString()
        fraction % 10 == 0 -> "$whole.${fraction / 10}"
        else -> "$whole.${fraction.toString().padStart(2, '0')}"
    }
}

/** Trims a free-text field and turns an empty result into null, never into "". */
fun String?.nullIfBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
