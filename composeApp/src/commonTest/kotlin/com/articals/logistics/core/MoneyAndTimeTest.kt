package com.articals.logistics.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two things every screen depends on: money as whole minor units, and dates in the
 * company's own time zone.
 *
 * Both are places where a plausible-looking implementation is wrong in a way nobody
 * notices until a customer does — a floating-point naira, a delivery filed on the wrong
 * day because the phone was somewhere else.
 */
class MoneyAndTimeTest {

    @Test
    fun `money is parsed into whole minor units`() {
        assertEquals(196_000L, parseAmountToMinorOrNull("1960"))
        assertEquals(196_050L, parseAmountToMinorOrNull("1960.50"))
        assertEquals(196_000L, parseAmountToMinorOrNull("₦1,960"))
        assertEquals(196_055L, parseAmountToMinorOrNull("1,960.55"))
        assertEquals(0L, parseAmountToMinorOrNull("0"))
    }

    @Test
    fun `money that is not money is refused rather than rounded`() {
        assertNull(parseAmountToMinorOrNull(""))
        assertNull(parseAmountToMinorOrNull("abc"))
        assertNull(parseAmountToMinorOrNull("1.2.3"))
        assertNull(parseAmountToMinorOrNull("1960.505"))
    }

    @Test
    fun `a negative amount is kept, because a variance can be negative`() {
        assertEquals(-50_000L, parseAmountToMinorOrNull("-500"))
    }

    @Test
    fun `money survives a round trip through the editor`() {
        listOf(0L, 1L, 99L, 100L, 196_000L, 1_500_000L).forEach { minor ->
            val typed = minorToMajorString(minor)
            assertEquals(minor, parseAmountToMinorOrNull(typed), "round trip lost $minor (typed as $typed)")
        }
    }

    @Test
    fun `formatting is stable for the same amount`() {
        assertEquals(formatMinor(196_000L, "NGN"), formatMinor(196_000L, "NGN"))
        assertTrue(formatMinor(196_000L, "NGN").contains("1,960"))
    }

    @Test
    fun `a percentage becomes basis points without floating point drift`() {
        assertEquals(2_500, parsePercentToBpsOrNull("25"))
        assertEquals(745, parsePercentToBpsOrNull("7.45"))
        assertEquals(10_000, parsePercentToBpsOrNull("100"))
        assertEquals(0, parsePercentToBpsOrNull("0"))
        assertNull(parsePercentToBpsOrNull(""))
    }

    @Test
    fun `business dates belong to the company's time zone, not the phone's`() {
        // 00:30 on 15 March in Lagos is still 14 March in UTC: the opposite of the
        // mistake this rule exists to prevent.
        val instant = epochMillisOfCivil(
            year = 2026,
            month = 3,
            day = 15,
            hour = 0,
            minute = 30,
            timeZoneId = "Africa/Lagos",
        )

        assertEquals("2026-03-15", businessDateKey(instant, "Africa/Lagos"))
        assertEquals("2026-03-14", businessDateKey(instant, "UTC"))
    }

    @Test
    fun `date keys shift a day at a time across a month boundary`() {
        assertEquals("2026-03-01", shiftDateKey("2026-02-28", 1, "UTC"))
        assertEquals("2026-02-28", shiftDateKey("2026-03-01", -1, "UTC"))
        assertEquals("2027-01-01", shiftDateKey("2026-12-31", 1, "UTC"))
    }

    @Test
    fun `a date key is recognised, and nonsense is not`() {
        assertTrue(isDateKey("2026-03-14"))
        assertTrue(!isDateKey("14/03/2026"))
        assertTrue(!isDateKey("2026-13-01"))
        // 30 February is not a date, even though the numbers are in range.
        assertTrue(!isDateKey("2026-02-30"))
        assertTrue(!isDateKey(""))
    }

    @Test
    fun `a start-of-day boundary contains the whole business day`() {
        val start = startOfBusinessDateMillis("2026-03-14", "Africa/Lagos")
        val end = endOfBusinessDateMillis("2026-03-14", "Africa/Lagos")

        assertEquals("2026-03-14", businessDateKey(start, "Africa/Lagos"))
        // The end is the last millisecond of the day, not the first of the next one.
        assertEquals("2026-03-14", businessDateKey(end, "Africa/Lagos"))
        assertEquals("2026-03-15", businessDateKey(end + 1, "Africa/Lagos"))
        assertTrue(end > start)
    }

    @Test
    fun `distances are parsed in metres from what a person types`() {
        assertEquals(8_400L, parseDistanceToMetresOrNull("8.4"))
        assertEquals(8_400L, parseDistanceToMetresOrNull("8,4"))
        assertEquals(1_000L, parseDistanceToMetresOrNull("1 km"))
        assertNull(parseDistanceToMetresOrNull(""))
        assertNull(parseDistanceToMetresOrNull("0"))
        assertNull(parseDistanceToMetresOrNull("not far"))
    }

    @Test
    fun `a distance too long for a delivery is refused on the client too`() {
        // The backend allows up to 2 000 km; the client stops at 500 km, because a
        // delivery longer than that is a typo rather than a delivery.
        assertNull(parseDistanceToMetresOrNull("600"))
        assertEquals(500_000L, parseDistanceToMetresOrNull("500"))
    }
}
