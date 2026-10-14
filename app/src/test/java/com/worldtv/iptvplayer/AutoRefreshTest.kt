package com.worldtv.iptvplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class AutoRefreshTest {

    private val hour = 60L * 60 * 1000
    private val utc = TimeZone.getTimeZone("UTC")
    private val perth = TimeZone.getTimeZone("Australia/Perth")

    /** 2027-01-15 10:00 UTC. */
    private val now = 1_800_000_000_000L - (1_800_000_000_000L % (24 * hour)) + 10 * hour
    private val today3 = now - 7 * hour

    @Test
    fun everyStartOncePerRun() {
        assertTrue(AutoRefresh.isDue(AutoRefresh.EVERY_START, now - hour, now, refreshedThisRun = false, tz = utc))
        assertFalse(AutoRefresh.isDue(AutoRefresh.EVERY_START, now - hour, now, refreshedThisRun = true, tz = utc))
    }

    @Test
    fun dailyLoadsOnceAfterEach3am() {
        assertFalse(AutoRefresh.isDue(AutoRefresh.DAILY, today3 + 1, now, false, utc))
        assertTrue(AutoRefresh.isDue(AutoRefresh.DAILY, today3 - 1, now, false, utc))
        // Loaded at 11 pm: due again at 3 am, not 24 h later.
        assertTrue(AutoRefresh.isDue(AutoRefresh.DAILY, today3 - 4 * hour, today3 + 60_000, false, utc))
        assertFalse(AutoRefresh.isDue(AutoRefresh.DAILY, today3 - 4 * hour, today3 - 60_000, false, utc))
    }

    @Test
    fun everyTwoDays() {
        assertFalse(AutoRefresh.isDue(AutoRefresh.EVERY_2_DAYS, today3 - 2 * hour, now, false, utc))
        assertTrue(AutoRefresh.isDue(AutoRefresh.EVERY_2_DAYS, today3 - 25 * hour, now, false, utc))
    }

    @Test
    fun neverLoadedOrClockBackIsDue() {
        assertTrue(AutoRefresh.isDue(AutoRefresh.EVERY_2_DAYS, 0, now, false, utc))
        assertTrue(AutoRefresh.isDue(AutoRefresh.EVERY_2_DAYS, now + hour, now, false, utc))
    }

    @Test
    fun nightsAreLocal3am() {
        assertEquals(today3, AutoRefresh.lastNight(now, utc))
        assertEquals(today3 + 24 * hour, AutoRefresh.nextNight(now, utc))
        assertEquals(today3, AutoRefresh.nextNight(today3 - 1, utc))
        // Perth is UTC+8: 3 am there is 19:00 UTC the day before.
        assertEquals(today3 - 8 * hour, AutoRefresh.lastNight(now, perth))
    }
}
