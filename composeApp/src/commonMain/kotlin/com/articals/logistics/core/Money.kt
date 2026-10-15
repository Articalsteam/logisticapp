package com.articals.logistics.core

/**
 * Money on the client.
 *
 * The same rule as the backend applies here: an amount is an integer number of
 * minor units (kobo). There is no `Double` anywhere in this file, and there must
 * never be — a rounded kobo in a delivery price is a disagreement about money
 * between the dispatcher and the customer (ADR-007).
 *
 * This file formats and parses amounts for display only. It never computes a
 * price: every number shown in the app came from a Cloud Function (ADR-005).
 */
typealias Minor = Long

/** Nominal currency of the company workspace; each company carries its own code. */
const val DEFAULT_CURRENCY = "NGN"

private val CURRENCY_SYMBOLS: Map<String, String> = mapOf(
    "NGN" to "₦",
    "GHS" to "GH₵",
    "KES" to "KSh",
    "ZAR" to "R",
    "USD" to "$",
    "EUR" to "€",
    "GBP" to "£",
)

fun currencySymbol(currency: String): String =
    CURRENCY_SYMBOLS[currency.uppercase()] ?: currency.uppercase()

/** "1,250.50" — grouping separators, always two decimals, sign preserved. */
fun minorToMajorString(amountMinor: Minor): String {
    val negative = amountMinor < 0
    val absolute = if (negative) -amountMinor else amountMinor
    val whole = absolute / 100
    val fraction = absolute % 100
    val grouped = groupThousands(whole.toString())
    val sign = if (negative) "-" else ""
    return "$sign$grouped.${fraction.toString().padStart(2, '0')}"
}

/** "₦1,250.50" — what the interface shows. */
fun formatMinor(amountMinor: Minor, currency: String = DEFAULT_CURRENCY): String =
    currencySymbol(currency) + minorToMajorString(amountMinor)

/**
 * Compact form for dashboard tiles, where horizontal space is scarce:
 * "₦1.96k", "₦1.5m", "₦2.4b". Never used for an amount a user must act on
 * exactly — those always use [formatMinor].
 */
fun formatMinorCompact(amountMinor: Minor, currency: String = DEFAULT_CURRENCY): String {
    val negative = amountMinor < 0
    val absolute = if (negative) -amountMinor else amountMinor
    val sign = if (negative) "-" else ""
    val symbol = currencySymbol(currency)
    val major = absolute / 100
    return when {
        absolute < 100_000L -> formatMinor(amountMinor, currency)
        major < 1_000_000L -> "$sign$symbol${oneDecimal(major, 1_000L)}k"
        major < 1_000_000_000L -> "$sign$symbol${oneDecimal(major, 1_000_000L)}m"
        else -> "$sign$symbol${oneDecimal(major, 1_000_000_000L)}b"
    }
}

private fun oneDecimal(major: Long, divisor: Long): String {
    val tenths = (major * 10 + divisor / 2) / divisor
    return "${tenths / 10}.${tenths % 10}"
}

/** "74.49%" from 7449 basis points. */
fun formatBpsAsPercent(bps: Int): String {
    val whole = bps / 100
    val fraction = (if (bps < 0) -bps else bps) % 100
    return "$whole.${fraction.toString().padStart(2, '0')}%"
}

/**
 * Parses what a user typed into exact minor units, or null when the text is not a
 * usable amount. Accepts: "1250", "1250.5", "1,250.50", "₦1,250.50", ".50".
 */
fun parseAmountToMinorOrNull(input: String): Minor? {
    val cleaned = input
        .replace(",", "")
        .replace(" ", "")
        .trim()
        .let { text -> text.dropWhile { it != '-' && it !in '0'..'9' && it != '.' } }
        .let { text -> text.takeWhile { it == '-' || it in '0'..'9' || it == '.' } }
    if (cleaned.isEmpty() || cleaned == "-" || cleaned == ".") return null
    val negative = cleaned.startsWith("-")
    val unsigned = if (negative) cleaned.drop(1) else cleaned
    val parts = unsigned.split('.')
    if (parts.size > 2) return null
    val wholeText = parts.first().ifEmpty { "0" }
    val fractionText = parts.getOrNull(1) ?: ""
    if (wholeText.any { it !in '0'..'9' } || fractionText.any { it !in '0'..'9' }) return null
    if (fractionText.length > 2) return null
    val whole = wholeText.toLongOrNull() ?: return null
    val fraction = (fractionText.padEnd(2, '0')).ifEmpty { "00" }.toLongOrNull() ?: return null
    val total = whole * 100 + fraction
    return if (negative) -total else total
}

/** Adds thousands separators without pulling in platform-specific formatting. */
internal fun groupThousands(digits: String): String {
    if (digits.length <= 3) return digits
    val builder = StringBuilder()
    val offset = digits.length % 3
    digits.forEachIndexed { index, character ->
        if (index > 0 && (index - offset) % 3 == 0) builder.append(',')
        builder.append(character)
    }
    return builder.toString()
}

/** Sums amounts exactly (Long arithmetic; no floating point anywhere). */
fun sumMinor(values: Iterable<Minor>): Minor = values.fold(0L) { total, value -> total + value }
