package com.articals.logistics.core

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Dates and times.
 *
 * Business dates are decided in the *company's* time zone, not the device's: a
 * delivery completed at 00:30 in Lagos belongs to that day's report whether or not
 * the dispatcher's phone is set to another zone (ADR-011 — instants travel as
 * ISO-8601 UTC, dates as `YYYY-MM-DD` keys).
 *
 * Time-zone conversion is delegated to the platform's own database through
 * kotlinx-datetime, so daylight-saving rules are always the operating system's
 * current ones — no hand-rolled offsets anywhere in this app.
 */

/** Wall-clock fields in a specific time zone. */
data class CivilDateTime(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
)

private val FALLBACK_ZONE = TimeZone.of("UTC")

/** Resolves a time-zone id, falling back to UTC rather than throwing on bad input. */
fun timeZoneOf(timeZoneId: String): TimeZone = runCatching { TimeZone.of(timeZoneId) }.getOrDefault(FALLBACK_ZONE)

/** Converts an instant to the wall clock of [timeZoneId]. */
fun civilDateTimeOf(epochMillis: Long, timeZoneId: String): CivilDateTime {
    val local = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(timeZoneOf(timeZoneId))
    return CivilDateTime(local.year, local.month.number, local.day, local.hour, local.minute, local.second)
}

/** Converts wall-clock fields in [timeZoneId] back to an instant. */
fun epochMillisOfCivil(
    year: Int,
    month: Int,
    day: Int,
    hour: Int,
    minute: Int,
    second: Int,
    timeZoneId: String,
): Long = runCatching {
    LocalDateTime(year, month, day, hour, minute, second).toInstant(timeZoneOf(timeZoneId)).toEpochMilliseconds()
}.getOrDefault(0L)

/** The device's time zone, used to pre-select a sensible default for a new company. */
fun deviceTimeZoneId(): String = TimeZone.currentSystemDefault().id

/** True when the platform recognizes the time-zone identifier. */
fun isValidTimeZoneId(timeZoneId: String): Boolean =
    runCatching { TimeZone.of(timeZoneId) }.isSuccess

/** Current time. */
fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()

/** "2026-10-14" in the company's time zone. */
fun businessDateKey(epochMillis: Long, timeZoneId: String): String {
    val civil = civilDateTimeOf(epochMillis, timeZoneId)
    return dateKeyOf(civil.year, civil.month, civil.day)
}

/** Today's business date, for the company workspace on screen. */
fun todayKey(timeZoneId: String): String = businessDateKey(currentTimeMillis(), timeZoneId)

fun dateKeyOf(year: Int, month: Int, day: Int): String =
    "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"

/** Start of a business date (local midnight) as an instant. */
fun startOfBusinessDateMillis(dateKey: String, timeZoneId: String): Long {
    val parts = splitDateKey(dateKey) ?: return currentTimeMillis()
    return epochMillisOfCivil(parts.first, parts.second, parts.third, 0, 0, 0, timeZoneId)
}

/** End of a business date (23:59:59.999 local) as an instant. */
fun endOfBusinessDateMillis(dateKey: String, timeZoneId: String): Long =
    startOfBusinessDateMillis(dateKey, timeZoneId) + 86_399_999L

/** Shifts a date key by whole days. Noon is used so a DST change cannot skip a day. */
fun shiftDateKey(dateKey: String, days: Int, timeZoneId: String): String {
    val parts = splitDateKey(dateKey) ?: return dateKey
    val midday = epochMillisOfCivil(parts.first, parts.second, parts.third, 12, 0, 0, timeZoneId)
    return businessDateKey(midday + days * 86_400_000L, timeZoneId)
}

fun splitDateKey(dateKey: String): Triple<Int, Int, Int>? {
    val segments = dateKey.split("-")
    if (segments.size != 3) return null
    val year = segments[0].toIntOrNull() ?: return null
    val month = segments[1].toIntOrNull() ?: return null
    val day = segments[2].toIntOrNull() ?: return null
    return Triple(year, month, day)
}

private val MONTHS = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

private val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/** "14 Oct 2026" */
fun formatDateKeyLong(dateKey: String): String {
    val parts = splitDateKey(dateKey) ?: return dateKey
    return "${parts.third} ${MONTHS.getOrElse(parts.second - 1) { "?" }} ${parts.first}"
}

/** "14 Oct" — for lists where the year is obvious. */
fun formatDateKeyShort(dateKey: String): String {
    val parts = splitDateKey(dateKey) ?: return dateKey
    return "${parts.third} ${MONTHS.getOrElse(parts.second - 1) { "?" }}"
}

/** "09:41" in the company's time zone. */
fun formatTimeShort(epochMillis: Long, timeZoneId: String): String {
    val civil = civilDateTimeOf(epochMillis, timeZoneId)
    return "${civil.hour.toString().padStart(2, '0')}:${civil.minute.toString().padStart(2, '0')}"
}

/** "14 Oct 2026, 09:41" in the company's time zone. */
fun formatDateTimeLong(epochMillis: Long, timeZoneId: String): String {
    val civil = civilDateTimeOf(epochMillis, timeZoneId)
    return "${civil.day} ${MONTHS.getOrElse(civil.month - 1) { "?" }} ${civil.year}, " +
        "${civil.hour.toString().padStart(2, '0')}:${civil.minute.toString().padStart(2, '0')}"
}

/** Weekday name for a date key, derived through the platform calendar. */
fun weekdayLabel(dateKey: String, timeZoneId: String): String {
    val parts = splitDateKey(dateKey) ?: return ""
    val millis = epochMillisOfCivil(parts.first, parts.second, parts.third, 12, 0, 0, timeZoneId)
    val daysSinceEpoch = millis.floorDiv(86_400_000L)
    // 1970-01-01 was a Thursday, which is index 3 in a Monday-first week.
    val index = ((daysSinceEpoch + 3).mod(7)).toInt()
    return WEEKDAYS.getOrElse(index) { "" }
}

/**
 * Human relative time: "just now", "12 min ago", "3 h ago", "yesterday",
 * "4 days ago", and finally a date. Terse, and never negative.
 */
fun relativeTime(fromMillis: Long, nowMillis: Long, timeZoneId: String): String {
    val deltaSeconds = (nowMillis - fromMillis) / 1_000
    return when {
        deltaSeconds < 45 -> "just now"
        deltaSeconds < 3_600 -> "${deltaSeconds / 60} min ago"
        deltaSeconds < 86_400 -> "${deltaSeconds / 3_600} h ago"
        deltaSeconds < 172_800 -> "yesterday"
        deltaSeconds < 604_800 -> "${deltaSeconds / 86_400} days ago"
        else -> formatDateKeyLong(businessDateKey(fromMillis, timeZoneId))
    }
}

/** Whole days between two date keys, derived through the platform calendar. */
fun daysBetweenDateKeys(fromKey: String, toKey: String, timeZoneId: String): Int {
    val from = startOfBusinessDateMillis(fromKey, timeZoneId)
    val to = startOfBusinessDateMillis(toKey, timeZoneId)
    return ((to - from) / 86_400_000L).toInt()
}

/** ISO-8601 UTC instant — the format every backend operation expects. */
fun isoInstant(epochMillis: Long): String = Instant.fromEpochMilliseconds(epochMillis).toString()

/** Parses an ISO-8601 instant, or null when the string is not one. */
fun parseIsoInstantOrNull(value: String): Long? =
    runCatching { Instant.parse(value).toEpochMilliseconds() }.getOrNull()

/** True when [value] is a well-formed business date key. */
fun isDateKey(value: String): Boolean {
    val parts = splitDateKey(value) ?: return false
    if (parts.second !in 1..12 || parts.third !in 1..31) return false
    return runCatching { LocalDateTime(parts.first, parts.second, parts.third, 0, 0).toInstant(FALLBACK_ZONE) }
        .isSuccess
}
