package com.worldtv.iptvplayer.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideTest {

    private fun e(title: String, start: Long, stop: Long, archive: Boolean = false) = EpgListing(title, null, start, stop, archive)

    private val day = listOf(e("A", 1000, 2000, true), e("B", 2000, 3000, true), e("C", 3000, 4000, true), e("D", 4000, 5000))

    @Test
    fun nowAndNext() {
        val (now, next) = Guide.nowNext(day, 2500)
        assertEquals("B", now?.title)
        assertEquals("C", next?.title)
    }

    @Test
    fun aGapShowsOnlyNext() {
        val (now, next) = Guide.nowNext(listOf(e("A", 1000, 2000), e("B", 3000, 4000)), 2500)
        assertNull(now)
        assertEquals("B", next?.title)
    }

    @Test
    fun catchupIsFinishedRecordedAndNewestFirst() {
        val list = Guide.catchup(day, now = 3500, days = 1)
        assertEquals(listOf("B", "A"), list.map { it.title })
    }

    @Test
    fun catchupKeepsToTheChannelsDays() {
        val now = 10 * 86_400L
        val old = e("old", now - 3 * 86_400L, now - 3 * 86_400L + 1800, true)
        val recent = e("recent", now - 3600, now - 1800, true)
        assertEquals(listOf("recent"), Guide.catchup(listOf(old, recent), now, days = 2).map { it.title })
        assertTrue(Guide.catchup(listOf(recent), now, days = 0).isEmpty())
    }

    @Test
    fun timeshiftStartInThePanelsTimezone() {
        // 2026-10-13 12:00 UTC
        val t = 1_791_892_800L
        assertEquals("2026-10-13:12-00", Guide.timeshiftStart(t, null))
        assertEquals("2026-10-13:17-00", Guide.timeshiftStart(t, "Asia/Karachi"))
    }

    @Test
    fun minutesRoundUp() {
        assertEquals(30, Guide.minutes(e("x", 0, 1800)))
        assertEquals(31, Guide.minutes(e("x", 0, 1801)))
    }
}
