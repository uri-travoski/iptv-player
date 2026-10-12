package com.worldtv.iptvplayer.data.repo

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Live TV's "Most watched" list, worked out from the viewer's own viewing: no stream is ever
 * checked. A view is time with the picture actually playing (not loading, paused or failed):
 * 5 minutes or more counts, so the channel worked and was wanted; more than 4 hours doesn't
 * (a box left on all night). Going back to a channel within 15 minutes carries on the same view,
 * so zapping away and back doesn't make two.
 *
 * A channel joins the list after 3 views in the last 30 days. It is ranked by "frecency": each
 * view is worth up to 2 points (1 point, plus up to 1 more for watching up to an hour), halving
 * every 7 days. Watched often lately = on top; no longer watched = slowly drops off.
 */
object MostWatched {
    const val MIN_VIEW_MS = 5 * 60_000L
    const val MAX_VIEW_MS = 4 * 3_600_000L
    const val MERGE_MS = 15 * 60_000L
    const val MIN_VIEWS = 3
    const val WINDOW_MS = 30 * DAY_MS
    const val HALF_LIFE_MS = 7 * DAY_MS
    /** Older views are deleted: by then they are worth almost nothing. */
    const val KEEP_MS = 90 * DAY_MS
    /** Channels in the list, at most. */
    const val SIZE = 25

    private const val HOUR_MS = 3_600_000L

    /** One counted view of a channel: when it started (wall clock) and how long the picture played. */
    class View(val itemId: String, val started: Long, val watchedMs: Long)

    /** Points of one view at [now]. A clock set back counts the view as new rather than from the future. */
    fun points(v: View, now: Long): Double {
        val age = max(0L, now - v.started)
        val length = 1.0 + min(v.watchedMs, HOUR_MS).toDouble() / HOUR_MS
        return length * 0.5.pow(age.toDouble() / HALF_LIFE_MS)
    }

    /** Item ids that qualify, best first; ties go to the one watched last. */
    fun rank(views: List<View>, now: Long): List<String> =
        views.asSequence()
            .filter { it.watchedMs in MIN_VIEW_MS..MAX_VIEW_MS }
            .groupBy { it.itemId }
            .filter { (_, vs) -> vs.count { now - it.started <= WINDOW_MS } >= MIN_VIEWS }
            .map { (id, vs) -> Triple(id, vs.sumOf { points(it, now) }, vs.maxOf { it.started }) }
            .sortedWith(compareByDescending<Triple<String, Double, Long>> { it.second }.thenByDescending { it.third })
            .map { it.first }
}

private const val DAY_MS = 24 * 3_600_000L

/**
 * Viewing sessions of Live TV channels, fed with explicit clock readings (testable without a
 * player). Each call returns what to store: a session's time once it reaches 5 minutes (and as
 * it grows), or its removal when it passes 4 hours.
 */
class WatchSessions {

    /** [started]: wall clock, the view's key and age. [watchedMs]: picture time so far. */
    class Session(val playlistId: Long, val itemId: String, val name: String, val started: Long) {
        var watchedMs = 0L
        /** Clock reading when left; 0 while it is the channel on now. */
        var leftAt = 0L
        /** Watched time last stored, to skip writes that change nothing. */
        var savedMs = 0L
        /** Past 4 hours: removed, and never stored again. */
        var dropped = false
    }

    sealed class Change {
        class Save(val session: Session, val watchedMs: Long) : Change()
        class Drop(val session: Session) : Change()
    }

    var current: Session? = null
        private set
    /** Channels left within the last 15 minutes: going back to one carries on its session. */
    private val recent = ArrayList<Session>()
    /** The picture is playing, counted from [playingSince] (a clock reading). */
    private var isPlaying = false
    private var playingSince = 0L

    /** A channel starts ([now]: monotonic clock, [wallNow]: wall clock). [playing]: picture already on. */
    fun start(playlistId: Long, itemId: String, name: String, now: Long, wallNow: Long, playing: Boolean): List<Change> {
        val cur = current
        if (cur != null && cur.playlistId == playlistId && cur.itemId == itemId) return emptyList()
        val out = leave(now)
        recent.removeAll { now - it.leftAt > MostWatched.MERGE_MS }
        val back = recent.firstOrNull { it.playlistId == playlistId && it.itemId == itemId }
        if (back != null) recent -= back
        current = (back ?: Session(playlistId, itemId, name, wallNow)).also { it.leftAt = 0 }
        isPlaying = playing
        playingSince = now
        return out
    }

    /** The picture started or stopped playing (loading, paused, failed). */
    fun playing(isPlaying: Boolean, now: Long): List<Change> {
        if (current == null) return emptyList()
        if (isPlaying) {
            if (!this.isPlaying) {
                this.isPlaying = true
                playingSince = now
            }
            return emptyList()
        }
        return fold(now).also { this.isPlaying = false }
    }

    /** Every minute or so while a channel is on: stores the time so far (a power cut loses at most that). */
    fun tick(now: Long): List<Change> = fold(now)

    /** Stopped, or another channel: the session is kept for 15 minutes in case of a return. */
    fun leave(now: Long): List<Change> {
        val s = current ?: return emptyList()
        val out = fold(now)
        isPlaying = false
        s.leftAt = now
        recent += s
        current = null
        return out
    }

    private fun fold(now: Long): List<Change> {
        val s = current ?: return emptyList()
        if (isPlaying) {
            s.watchedMs += now - playingSince
            playingSince = now
        }
        return when {
            s.dropped -> emptyList()
            s.watchedMs > MostWatched.MAX_VIEW_MS -> {
                s.dropped = true
                if (s.savedMs > 0) listOf(Change.Drop(s)) else emptyList()
            }
            s.watchedMs >= MostWatched.MIN_VIEW_MS && s.watchedMs != s.savedMs -> {
                s.savedMs = s.watchedMs
                listOf(Change.Save(s, s.watchedMs))
            }
            else -> emptyList()
        }
    }
}
