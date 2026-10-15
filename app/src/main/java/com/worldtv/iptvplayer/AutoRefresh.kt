package com.worldtv.iptvplayer

/**
 * Settings > Automatic refresh: when the active playlist is downloaded again by itself. Daily and
 * every-2-days loads happen at 3 am local time, when nobody is likely to be watching (an alarm
 * wakes the box for it); a box that was off then catches up the next time it shows its home screen.
 */
object AutoRefresh {
    const val EVERY_START = "start"
    const val DAILY = "daily"
    const val EVERY_2_DAYS = "2days"
    val MODES = listOf(EVERY_START, DAILY, EVERY_2_DAYS)

    /** Local hour of the nightly load. */
    const val HOUR = 3

    /**
     * True when a playlist last loaded at [lastSyncMs] should load again now. [refreshedThisRun]:
     * already reloaded since the app started (EVERY_START does it once per start, not on every
     * return to the home screen). A clock set back counts as due rather than never.
     */
    fun isDue(
        mode: String,
        lastSyncMs: Long,
        nowMs: Long,
        refreshedThisRun: Boolean,
        tz: java.util.TimeZone = java.util.TimeZone.getDefault(),
    ): Boolean {
        if (refreshedThisRun) return false
        if (lastSyncMs <= 0 || nowMs < lastSyncMs) return true
        return when (mode) {
            EVERY_START -> true
            DAILY -> lastSyncMs < lastNight(nowMs, tz, 0)
            else -> lastSyncMs < lastNight(nowMs, tz, 1)
        }
    }

    /** The latest 3 am at or before [nowMs], [daysBack] days earlier. */
    fun lastNight(nowMs: Long, tz: java.util.TimeZone, daysBack: Int = 0): Long {
        val c = java.util.Calendar.getInstance(tz)
        c.timeInMillis = nowMs
        c.set(java.util.Calendar.HOUR_OF_DAY, HOUR)
        c.set(java.util.Calendar.MINUTE, 0)
        c.set(java.util.Calendar.SECOND, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
        if (c.timeInMillis > nowMs) c.add(java.util.Calendar.DAY_OF_YEAR, -1)
        c.add(java.util.Calendar.DAY_OF_YEAR, -daysBack)
        return c.timeInMillis
    }

    /** The next 3 am after [nowMs]. */
    fun nextNight(nowMs: Long, tz: java.util.TimeZone = java.util.TimeZone.getDefault()): Long {
        val c = java.util.Calendar.getInstance(tz)
        c.timeInMillis = lastNight(nowMs, tz)
        c.add(java.util.Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }
}
