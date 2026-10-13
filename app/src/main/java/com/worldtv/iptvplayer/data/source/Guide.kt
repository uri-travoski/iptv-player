package com.worldtv.iptvplayer.data.source

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Now / next and catch-up picks from a channel's guide listings (times in Unix seconds). */
object Guide {

    /** The programme on now and the one after it; either may be null. [listings] sorted by start. */
    fun nowNext(listings: List<EpgListing>, now: Long): Pair<EpgListing?, EpgListing?> {
        val i = listings.indexOfFirst { now >= it.startEpoch && now < it.stopEpoch }
        if (i >= 0) return listings[i] to listings.getOrNull(i + 1)
        // Between programmes (a gap in the guide): nothing on, the next one still shown.
        return null to listings.firstOrNull { it.startEpoch > now }
    }

    /**
     * Programmes that can be watched again: recorded by the panel, already finished, and no older
     * than the channel's [days] of catch-up. Newest first.
     */
    fun catchup(listings: List<EpgListing>, now: Long, days: Int): List<EpgListing> {
        if (days <= 0) return emptyList()
        val oldest = now - days * 86_400L
        return listings
            .filter { it.hasArchive && it.stopEpoch <= now && it.startEpoch >= oldest }
            .distinctBy { it.startEpoch }
            .sortedByDescending { it.startEpoch }
    }

    /** Start of a programme as the timeshift URL wants it: "YYYY-MM-DD:HH-MM" in the panel's timezone. */
    fun timeshiftStart(epoch: Long, serverTz: String?): String {
        val f = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US)
        f.timeZone = serverTz?.takeIf { it.isNotBlank() }?.let { TimeZone.getTimeZone(it) } ?: TimeZone.getTimeZone("UTC")
        return f.format(Date(epoch * 1000))
    }

    /** Length in whole minutes, rounded up, so the end of the programme is never cut off. */
    fun minutes(e: EpgListing): Int = ((e.stopEpoch - e.startEpoch + 59) / 60).toInt().coerceAtLeast(1)
}
