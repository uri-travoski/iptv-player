package com.worldtv.iptvplayer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoRefreshTest {

    private val hour = 60L * 60 * 1000
    private val now = 1_800_000_000_000L

    @Test
    fun everyStartOncePerRun() {
        assertTrue(AutoRefresh.isDue(AutoRefresh.EVERY_START, now - hour, now, refreshedThisRun = false))
        assertFalse(AutoRefresh.isDue(AutoRefresh.EVERY_START, now - hour, now, refreshedThisRun = true))
    }

    @Test
    fun dailyAndEveryTwoDays() {
        assertFalse(AutoRefresh.isDue(AutoRefresh.DAILY, now - 23 * hour, now, false))
        assertTrue(AutoRefresh.isDue(AutoRefresh.DAILY, now - 24 * hour, now, false))
        assertFalse(AutoRefresh.isDue(AutoRefresh.EVERY_2_DAYS, now - 47 * hour, now, false))
        assertTrue(AutoRefresh.isDue(AutoRefresh.EVERY_2_DAYS, now - 48 * hour, now, false))
    }

    @Test
    fun neverLoadedOrClockBackIsDue() {
        assertTrue(AutoRefresh.isDue(AutoRefresh.EVERY_2_DAYS, 0, now, false))
        assertTrue(AutoRefresh.isDue(AutoRefresh.EVERY_2_DAYS, now + hour, now, false))
    }
}
