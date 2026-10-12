package com.worldtv.iptvplayer.player

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.Player
import com.worldtv.iptvplayer.data.repo.Repository
import com.worldtv.iptvplayer.data.repo.WatchSessions
import com.worldtv.iptvplayer.data.source.AdultNames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Counts how long each Live TV channel's picture plays, for Most watched (see MostWatched).
 * Live TV says which channel is on ([start]) and when it is left ([stop]); the time itself comes
 * from the player (playing = picture on, not loading, paused or failed).
 */
class WatchTracker(
    private val player: Player,
    private val scope: CoroutineScope,
    private val repo: Repository,
    private val adultNames: () -> AdultNames,
) {
    private val sessions = WatchSessions()
    private val main = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            apply(sessions.tick(SystemClock.elapsedRealtime()))
            if (sessions.current != null) main.postDelayed(this, TICK_MS)
        }
    }

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                apply(sessions.playing(isPlaying, SystemClock.elapsedRealtime()))
            }
        })
    }

    /** Channel [itemId] of playlist [playlistId] is on now (call after asking the player to play it). */
    fun start(playlistId: Long, itemId: String, name: String) {
        apply(sessions.start(playlistId, itemId, name, SystemClock.elapsedRealtime(), System.currentTimeMillis(), player.isPlaying))
        main.removeCallbacks(tick)
        main.postDelayed(tick, TICK_MS)
    }

    /** Live TV closed or playback stopped. */
    fun stop() {
        main.removeCallbacks(tick)
        apply(sessions.leave(SystemClock.elapsedRealtime()))
    }

    private fun apply(changes: List<WatchSessions.Change>) {
        for (c in changes) {
            scope.launch {
                try {
                    when (c) {
                        is WatchSessions.Change.Save -> with(c.session) { repo.saveView(playlistId, itemId, name, started, c.watchedMs, adultNames()) }
                        is WatchSessions.Change.Drop -> with(c.session) { repo.deleteView(playlistId, itemId, started) }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("WorldTV.Watch", "could not store a view", e)
                }
            }
        }
    }

    private companion object {
        const val TICK_MS = 60_000L
    }
}
