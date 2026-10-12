package com.worldtv.iptvplayer.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MostWatchedTest {

    private val min = 60_000L
    private val hour = 60 * min
    private val day = 24 * hour
    private val now = 1_800_000_000_000L

    private fun views(id: String, count: Int, daysAgo: Int, minutes: Long = 30) =
        List(count) { MostWatched.View(id, now - daysAgo * day - it * hour, minutes * min) }

    @Test
    fun joinsAfterThreeViewsInThirtyDays() {
        assertEquals(emptyList<String>(), MostWatched.rank(views("a", 2, 1), now))
        assertEquals(listOf("a"), MostWatched.rank(views("a", 3, 1), now))
        // Three views, but older than 30 days: not in.
        assertEquals(emptyList<String>(), MostWatched.rank(views("a", 3, 31), now))
    }

    @Test
    fun shortAndAllNightViewsDontCount() {
        val v = views("a", 2, 1) + MostWatched.View("a", now - hour, 4 * min)
        assertEquals(emptyList<String>(), MostWatched.rank(v, now))
        val night = views("b", 2, 1) + MostWatched.View("b", now - hour, 4 * hour + min)
        assertEquals(emptyList<String>(), MostWatched.rank(night, now))
        val exactly5 = views("c", 2, 1) + MostWatched.View("c", now - hour, 5 * min)
        assertEquals(listOf("c"), MostWatched.rank(exactly5, now))
    }

    @Test
    fun recentViewingOutranksOldViewing() {
        // 3 views this week beat 10 views a month ago (3 of them still inside the window).
        val v = views("recent", 3, 1) + views("old", 7, 28) + views("old", 3, 26)
        assertEquals(listOf("recent", "old"), MostWatched.rank(v, now))
    }

    @Test
    fun moreViewsRankHigher() {
        val v = views("few", 3, 2) + views("many", 6, 2)
        assertEquals(listOf("many", "few"), MostWatched.rank(v, now))
    }

    @Test
    fun longerViewsWorthUpToDouble() {
        val short = MostWatched.points(MostWatched.View("a", now, 5 * min), now)
        val hourLong = MostWatched.points(MostWatched.View("a", now, hour), now)
        val threeHours = MostWatched.points(MostWatched.View("a", now, 3 * hour), now)
        assertEquals(2.0, hourLong, 1e-9)
        assertEquals(hourLong, threeHours, 1e-9)
        assertTrue(short < 1.1)
        // Halves every 7 days.
        assertEquals(1.0, MostWatched.points(MostWatched.View("a", now - 7 * day, hour), now), 1e-9)
    }

    // ---- sessions ----

    private val wall = 1_800_000_000_000L

    private fun saves(c: List<WatchSessions.Change>) = c.filterIsInstance<WatchSessions.Change.Save>().map { it.watchedMs }

    @Test
    fun countsOnlyPlayingTimeAndSavesFromFiveMinutes() {
        val s = WatchSessions()
        s.start(1, "a", "A", now = 0, wallNow = wall, playing = false)
        s.playing(true, 2_000) // 2 s loading: not counted
        assertEquals(emptyList<Long>(), saves(s.tick(2_000 + 4 * min)))
        assertEquals(listOf(5 * min), saves(s.tick(2_000 + 5 * min)))
        assertEquals(listOf(6 * min), saves(s.playing(false, 2_000 + 6 * min))) // paused for 10 minutes
        assertEquals(emptyList<Long>(), saves(s.leave(2_000 + 16 * min)))
    }

    @Test
    fun backWithinFifteenMinutesIsTheSameView() {
        val s = WatchSessions()
        s.start(1, "a", "A", 0, wall, playing = true)
        s.start(1, "b", "B", 3 * min, wall + 3 * min, playing = true) // 3 min of a
        s.start(1, "a", "A", 10 * min, wall + 10 * min, playing = true) // back to a after 7 min
        assertEquals(wall, s.current!!.started)
        assertEquals(listOf(5 * min), saves(s.tick(12 * min))) // 3 + 2 minutes
    }

    @Test
    fun backAfterFifteenMinutesIsANewView() {
        val s = WatchSessions()
        s.start(1, "a", "A", 0, wall, playing = true)
        s.start(1, "b", "B", 3 * min, wall + 3 * min, playing = true)
        s.start(1, "a", "A", 19 * min, wall + 19 * min, playing = true) // left 16 min ago
        assertEquals(wall + 19 * min, s.current!!.started)
    }

    @Test
    fun moreThanFourHoursIsDroppedForGood() {
        val s = WatchSessions()
        s.start(1, "a", "A", 0, wall, playing = true)
        assertEquals(listOf(hour), saves(s.tick(hour)))
        val over = s.tick(4 * hour + 1)
        assertEquals(1, over.count { it is WatchSessions.Change.Drop })
        assertEquals(emptyList<Long>(), saves(s.tick(5 * hour)))
        assertNull(s.leave(5 * hour).firstOrNull())
    }

    @Test
    fun sameChannelAgainIsNoChange() {
        val s = WatchSessions()
        s.start(1, "a", "A", 0, wall, playing = true)
        assertEquals(0, s.start(1, "a", "A", 6 * min, wall + 6 * min, playing = true).size)
        assertEquals(listOf(7 * min), saves(s.tick(7 * min)))
    }
}
