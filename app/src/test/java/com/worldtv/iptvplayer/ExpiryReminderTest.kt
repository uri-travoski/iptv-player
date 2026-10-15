package com.worldtv.iptvplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExpiryReminderTest {
    private val day = 24 * 60 * 60 * 1000L
    private val end = 1_800_000_000_000L

    @Test
    fun nothingEarlier() = assertNull(ExpiryReminder.due(end, end - 4 * day, null))

    @Test
    fun threeTwoThenExpired() {
        var shown: String? = null
        assertEquals(ExpiryReminder.THREE_DAYS, ExpiryReminder.due(end, end - 3 * day + 1, shown))
        shown = ExpiryReminder.record(end, ExpiryReminder.THREE_DAYS)
        assertNull(ExpiryReminder.due(end, end - 2 * day - 60_000, shown)) // still 3 days: once
        assertEquals(ExpiryReminder.TWO_DAYS, ExpiryReminder.due(end, end - 2 * day, shown))
        shown = ExpiryReminder.record(end, ExpiryReminder.TWO_DAYS)
        assertNull(ExpiryReminder.due(end, end - day, shown))
        assertEquals(ExpiryReminder.EXPIRED, ExpiryReminder.due(end, end + 1, shown))
        shown = ExpiryReminder.record(end, ExpiryReminder.EXPIRED)
        assertNull(ExpiryReminder.due(end, end + 5 * day, shown))
    }

    @Test
    fun boxOffShowsOnlyTheLatest() {
        assertEquals(ExpiryReminder.EXPIRED, ExpiryReminder.due(end, end + day, null))
        assertNull(ExpiryReminder.due(end, end + 2 * day, ExpiryReminder.record(end, ExpiryReminder.EXPIRED)))
    }

    @Test
    fun renewalStartsOver() {
        val shown = ExpiryReminder.record(end, ExpiryReminder.EXPIRED)
        val renewed = end + 30 * day
        assertEquals(ExpiryReminder.THREE_DAYS, ExpiryReminder.due(renewed, renewed - 3 * day, shown))
    }

    @Test
    fun daysLeftRoundsUp() {
        assertEquals(3, ExpiryReminder.daysLeft(end, end - 2 * day - 3_600_000))
        assertEquals(1, ExpiryReminder.daysLeft(end, end - 60_000))
    }
}
