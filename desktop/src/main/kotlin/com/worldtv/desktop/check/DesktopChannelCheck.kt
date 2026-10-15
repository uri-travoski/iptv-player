package com.worldtv.desktop.check

import com.worldtv.desktop.AppGraph
import com.worldtv.iptvplayer.AutoRefresh
import com.worldtv.iptvplayer.data.check.ChannelProbe
import com.worldtv.iptvplayer.data.check.CheckResult
import com.worldtv.iptvplayer.data.repo.Playlist
import com.worldtv.iptvplayer.data.source.XtreamCredentials
import com.worldtv.iptvplayer.data.source.XtreamUrls
import com.worldtv.iptvplayer.data.sync.Syncer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Settings > Channel check on the desktop: the same checks and rules as on Android (ChannelProbe,
 * 2.5 s between channels, nothing recorded while the server refuses or the account is in use),
 * run by the app while it is open: after a minute with nothing playing from that server, and at
 * 3 am (the nightly reload of the active playlist, which asks for a fresh check).
 */
class DesktopChannelCheck(private val graph: AppGraph) {

    private val repo get() = graph.repo
    private val prefs get() = graph.prefs

    /** Bumped whenever results are saved: Live TV redraws its marks. */
    val updates = MutableStateFlow(0L)

    /** "12 / 40" while a check runs, else null. */
    val progress = MutableStateFlow<String?>(null)

    private val prober = ChannelProbe(graph.http, Syncer.USER_AGENT)

    @Volatile private var lastPlayback = 0L
    @Volatile private var running: Job? = null
    @Volatile private var probing: Playlist? = null
    @Volatile private var probingId = -1L
    private var ticker: Job? = null

    private fun now() = System.nanoTime() / 1_000_000

    /** Starts the minute timer: idle checks, and the 3 am reload. */
    fun start() {
        if (ticker != null) return
        ticker = graph.appScope.launch(Dispatchers.IO) {
            var nextNight = AutoRefresh.nextNight(System.currentTimeMillis())
            delay(IDLE_MS)
            requestDue()
            while (isActive) {
                if (System.currentTimeMillis() >= nextNight) {
                    nextNight = AutoRefresh.nextNight(System.currentTimeMillis())
                    graph.reloadIfDue()
                }
                if (running?.isActive != true && prefs.pendingChecks.isNotEmpty()) runPending()
                delay(60_000)
            }
        }
    }

    fun stop() {
        ticker?.cancel()
        running?.cancel()
        prober.cancel()
    }

    /** Checks [playlistId] (when it has categories chosen). [fresh]: every channel again. [now]: no idle minute. */
    fun request(playlistId: Long, fresh: Boolean = false, now: Boolean = false) {
        if (fresh) {
            prefs.setCheckRoundStart(playlistId, 0)
            prefs.setCheckRoundActive(playlistId, 0)
        }
        if (now) lastPlayback = 0
        prefs.pendingChecks = prefs.pendingChecks + playlistId
        if (running?.isActive != true) runPending()
    }

    /** Owed checks: never checked yet, or a round that was interrupted (the app closed). */
    fun requestDue() {
        graph.appScope.launch(Dispatchers.IO) {
            val due = duePlaylists()
            if (due.isNotEmpty()) prefs.pendingChecks = prefs.pendingChecks + due
            if (prefs.pendingChecks.isNotEmpty() && running?.isActive != true) runPending()
        }
    }

    private suspend fun duePlaylists(): List<Long> = repo.playlists().map { it.id }.filter { id ->
        repo.checkCategories(id).isNotEmpty() && (prefs.lastChannelCheck(id) <= 0 || prefs.checkRoundStart(id) > 0)
    }

    fun beforePlay(url: String) {
        lastPlayback = now()
        val p = probing ?: return
        if (sameServer(p, url)) prober.yieldNow()
    }

    fun afterStop() {
        lastPlayback = now()
    }

    data class Status(val checked: Int, val total: Int, val activeMs: Long, val unfinished: Boolean, val running: Boolean, val doneAt: Long)

    suspend fun status(playlistId: Long): Status {
        val targets = repo.channelsToCheck(playlistId).map { it.itemId }.toHashSet()
        val current = prefs.checkRoundStart(playlistId)
        val unfinished = current > 0
        val last = prefs.checkLastRoundStart(playlistId)
        val since = if (unfinished) current else last.takeIf { it > 0 } ?: (System.currentTimeMillis() - VALID_MS)
        val checked = repo.checkResults(playlistId, since).keys.count { it in targets }
        val active = when {
            unfinished -> prefs.checkRoundActive(playlistId)
            last > 0 -> prefs.checkLastRoundActive(playlistId)
            else -> -1L
        }
        return Status(checked, targets.size, active, unfinished, running?.isActive == true && probingId == playlistId, prefs.lastChannelCheck(playlistId))
    }

    private enum class Outcome { DONE, BUSY, OFFLINE, ACCOUNT_FULL }

    @Synchronized
    private fun runPending() {
        if (running?.isActive == true) return
        running = graph.appScope.launch(Dispatchers.IO) {
            try {
                // Re-read each pass: a playlist asked for meanwhile joins in.
                val done = HashSet<Long>()
                while (isActive) {
                    val ids = prefs.pendingChecks.filter { it !in done }
                    if (ids.isEmpty()) break
                    var wait = false
                    for (id in ids) {
                        when (checkPlaylist(id)) {
                            Outcome.DONE -> {
                                prefs.pendingChecks = prefs.pendingChecks - id
                                done += id
                            }
                            // Tried again by the minute timer.
                            else -> wait = true
                        }
                    }
                    if (wait) break
                }
            } finally {
                progress.value = null
                running = null
            }
        }
    }

    private suspend fun checkPlaylist(id: Long): Outcome {
        val p = repo.playlist(id) ?: return Outcome.DONE
        val targets = repo.channelsToCheck(id)
        if (targets.isEmpty()) return Outcome.DONE
        if (busy(p)) return Outcome.BUSY
        val urls = if (p.isXtream) XtreamUrls(XtreamCredentials(p.url, p.username.orEmpty(), p.password.orEmpty())) else null
        val ext = if (p.formats.isEmpty() || "ts" in p.formats) "ts" else "m3u8"
        val started = prefs.checkRoundStart(id).takeIf { it > 0 } ?: System.currentTimeMillis().also {
            prefs.setCheckRoundStart(id, it)
            prefs.setCheckRoundActive(id, 0)
        }
        probingId = id
        val done = repo.checkResults(id, started).keys
        val todo = targets.filter { it.itemId !in done }
        var checked = done.size
        var unknownRun = 0
        val failRun = ArrayList<String>()
        val refusedRun = ArrayList<String>()
        android.util.Log.i(TAG, "${p.name}: ${todo.size} of ${targets.size} channels to check")
        try {
            for (t in todo) {
                if (busy(p)) return Outcome.BUSY
                val url = t.streamUrl ?: urls?.live(t.itemId, ext) ?: continue
                progress.value = "${checked + 1} / ${targets.size}"
                val t0 = now()
                val result = try {
                    probing = p
                    prober.probe(url)
                } finally {
                    prefs.setCheckRoundActive(id, prefs.checkRoundActive(id) + now() - t0)
                }
                android.util.Log.d(TAG, "${t.itemId}: $result in ${now() - t0} ms${prober.note}")
                if (prober.yielded) return Outcome.BUSY
                when {
                    result == CheckResult.UNKNOWN -> if (++unknownRun >= 3) return Outcome.OFFLINE
                    result == CheckResult.NOT_WORKING && prober.refused -> {
                        // A server turning everyone away (flood limit, account busy) records nothing.
                        refusedRun += t.itemId
                        if (refusedRun.size >= 3) return Outcome.ACCOUNT_FULL
                    }
                    result == CheckResult.NOT_WORKING -> {
                        unknownRun = 0
                        failRun += refusedRun
                        refusedRun.clear()
                        failRun += t.itemId
                        if (failRun.size >= FAIL_RUN && urls != null) {
                            if (prober.accountFull(urls)) return Outcome.ACCOUNT_FULL
                            save(id, failRun.associateWith { false })
                            checked += failRun.size
                            failRun.clear()
                        }
                    }
                    else -> {
                        unknownRun = 0
                        failRun += refusedRun
                        refusedRun.clear()
                        save(id, failRun.associateWith { false } + (t.itemId to true))
                        checked += failRun.size + 1
                        failRun.clear()
                    }
                }
                delay(GAP_MS) // lets the panel free the account's one connection
                prefs.setCheckRoundActive(id, prefs.checkRoundActive(id) + GAP_MS)
            }
            save(id, (failRun + refusedRun).associateWith { false })
        } finally {
            probing = null
            probingId = -1
        }
        val activeMs = prefs.checkRoundActive(id)
        prefs.finishCheckRound(id, started, activeMs)
        prefs.setLastChannelCheck(id, System.currentTimeMillis())
        val results = repo.checkResults(id, started)
        android.util.Log.i(TAG, "${p.name}: ${results.count { it.value }} of ${results.size} working")
        return Outcome.DONE
    }

    private suspend fun save(id: Long, results: Map<String, Boolean>) {
        if (results.isEmpty()) return
        repo.saveChecks(id, results, System.currentTimeMillis())
        updates.value = updates.value + 1
    }

    /** WorldTV is playing from this playlist's server (one connection), or stopped less than a minute ago. */
    private fun busy(p: Playlist): Boolean {
        val playing = graph.player.url
        if (playing != null) {
            if (!sameServer(p, playing)) return false
            lastPlayback = now()
            return true
        }
        return lastPlayback > 0 && now() - lastPlayback < IDLE_MS - 5_000
    }

    private fun sameServer(p: Playlist, url: String): Boolean {
        if (!p.isXtream) return true
        val host = runCatching { java.net.URI(XtreamCredentials(p.url, "", "").baseUrl).host }.getOrNull()
        val other = runCatching { java.net.URI(url).host }.getOrNull()
        return host == null || other == null || host.equals(other, ignoreCase = true)
    }

    companion object {
        private const val TAG = "WorldTV.Check"
        const val VALID_MS = 7L * 24 * 60 * 60 * 1000
        const val IDLE_MS = 60_000L
        private const val GAP_MS = 2500L
        private const val FAIL_RUN = 5
    }
}
