package com.worldtv.iptvplayer.data.check

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import com.worldtv.iptvplayer.App
import com.worldtv.iptvplayer.AutoRefresh
import com.worldtv.iptvplayer.data.repo.Playlist
import com.worldtv.iptvplayer.data.repo.Repository
import com.worldtv.iptvplayer.data.source.XtreamCredentials
import com.worldtv.iptvplayer.data.source.XtreamParser
import com.worldtv.iptvplayer.data.source.XtreamUrls
import com.worldtv.iptvplayer.data.sync.Syncer
import com.worldtv.iptvplayer.data.telemetry.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Channel check (Settings > Channel check): finds out which live channels in the chosen
 * categories work, in the background, so Live TV can mark them (a green dot, or dimmed).
 *
 * Each channel's stream is read for a few seconds and measured ([TsStats]): nothing is decoded or
 * shown, so the picture, the sound and the HDMI output never change, and the box doesn't wake its
 * screen (timers only wake the processor). An account allows one connection, so a check never
 * runs while that account's server is playing in WorldTV (pressing play stops it at once), nor
 * while another app plays; it carries on later, from where it was. Results count for 7 days.
 *
 * Runs after a playlist is added or reloaded, at night after the 3 am reload, and every few
 * hours when the box is idle.
 */
class ChannelCheck(
    private val app: Context,
    http: OkHttpClient,
    private val repo: Repository,
    private val prefs: com.worldtv.iptvplayer.Prefs,
    private val telemetry: Telemetry,
    private val scope: CoroutineScope,
    /** The stream WorldTV is playing, or null; never creates the player. */
    private val playingUrl: () -> String?,
    /** The nightly reload of the active playlist, when Automatic refresh says it is due. */
    private val reloadIfDue: suspend () -> Unit,
) {

    /** Bumped whenever results are saved: Live TV redraws its marks. */
    val updates = MutableStateFlow(0L)

    /** "12 / 40" while a check runs, else null (Settings shows it). */
    val progress = MutableStateFlow<String?>(null)

    private val probeHttp: OkHttpClient = http.newBuilder()
        // A new connection per check (an old one may already be closed by the panel), closed after.
        .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.SECONDS))
        .followRedirects(false) // followed by hand: the panel answering tells a dead channel from no network
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(READ_MS + 1000, TimeUnit.MILLISECONDS)
        .build()

    private val roundStart = HashMap<Long, Long>()

    /** The job's work while it runs (one at a time). */
    @Volatile private var running: Job? = null

    @Volatile private var probing: Playlist? = null
    @Volatile private var call: Call? = null
    @Volatile private var yielded = false

    /** Why the last check failed, for the log. */
    private var lastNote = ""

    /** The last check was turned away (blocked, flood limit, server error), not a dead channel. */
    private var refused = false

    // ---- requests ----

    /**
     * Checks [playlistId] (when it has categories chosen). [fresh]: start over, re-checking every
     * channel. Runs as a background job: Android freezes an app that isn't on screen, but not
     * while its job runs.
     */
    fun request(playlistId: Long, fresh: Boolean = false) {
        synchronized(roundStart) { if (fresh) roundStart.remove(playlistId) }
        prefs.pendingChecks = prefs.pendingChecks + playlistId
        if (running?.isActive != true) scheduleJob(JOB_RUN, 0)
    }

    /** Every playlist with chosen categories whose last full check is older than [olderThanMs]. */
    fun requestDue(olderThanMs: Long = EVERY_MS) {
        scope.launch {
            val due = duePlaylists(olderThanMs)
            if (due.isNotEmpty()) {
                prefs.pendingChecks = prefs.pendingChecks + due
                if (running?.isActive != true) scheduleJob(JOB_RUN, 0)
            }
        }
    }

    private suspend fun duePlaylists(olderThanMs: Long): List<Long> {
        val now = System.currentTimeMillis()
        return repo.playlists().map { it.id }.filter { id ->
            val last = lastCheck(id)
            repo.checkCategories(id).isNotEmpty() && (last <= 0 || now - last >= olderThanMs || now < last)
        }
    }

    /** When the last full check of [playlistId] finished (ms), 0 = never. */
    fun lastCheck(playlistId: Long): Long = prefs.lastChannelCheck(playlistId)

    /**
     * From the player, on the main thread, before a stream starts: a check reading from the same
     * server lets go of the connection at once.
     */
    fun beforePlay(url: String) {
        val p = probing ?: return
        if (!sameServer(p, url)) return
        yielded = true
        call?.cancel()
    }

    // ---- the job ----

    /**
     * [CheckJobService] started [jobId]: the nightly reload first (JOB_NIGHT), then every pending
     * playlist. What can't be checked now (something playing, the server refusing, no network)
     * is tried again by a later job. [finished] when done (also after [stopJob]).
     */
    fun runJob(jobId: Int, finished: () -> Unit) {
        if (running?.isActive == true) {
            // A check is on: the nightly reload waits for it rather than being skipped.
            if (jobId == JOB_NIGHT) scheduleJob(JOB_NIGHT, 10L * 60 * 1000)
            finished()
            return
        }
        running = scope.launch(Dispatchers.IO) {
            try {
                if (jobId == JOB_NIGHT) {
                    try {
                        reloadIfDue()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "night reload failed", e)
                    }
                }
                val olderThan = if (jobId == JOB_NIGHT) NIGHT_RECHECK_MS else EVERY_MS
                prefs.pendingChecks = prefs.pendingChecks + duePlaylists(olderThan)
                var wait = 0L
                while (true) {
                    val ids = prefs.pendingChecks.toList()
                    if (ids.isEmpty()) break
                    wait = 0L
                    for (id in ids) {
                        when (checkPlaylist(id)) {
                            Outcome.DONE -> prefs.pendingChecks = prefs.pendingChecks - id
                            Outcome.BUSY -> wait = maxOf(wait, BUSY_RETRY_MS)
                            Outcome.OFFLINE -> wait = maxOf(wait, OFFLINE_RETRY_MS)
                            Outcome.ACCOUNT_FULL -> wait = maxOf(wait, FULL_RETRY_MS)
                        }
                    }
                    if (wait > 0) break
                }
                if (wait > 0) scheduleJob(JOB_RUN, wait)
            } finally {
                progress.value = null
                running = null
                finished()
            }
        }
    }

    /** Android stopped the job early (it ran long, the network went): the next one carries on. */
    fun stopJob() {
        running?.cancel()
        call?.cancel()
    }

    private fun scheduleJob(id: Int, delayMs: Long) {
        val js = app.getSystemService(Context.JOB_SCHEDULER_SERVICE) as android.app.job.JobScheduler
        val info = android.app.job.JobInfo.Builder(id, android.content.ComponentName(app, CheckJobService::class.java))
            .setRequiredNetworkType(android.app.job.JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency(delayMs)
            .build()
        js.schedule(info)
    }

    private enum class Outcome { DONE, BUSY, OFFLINE, ACCOUNT_FULL }

    // ---- one playlist ----

    private suspend fun checkPlaylist(id: Long): Outcome {
        val p = repo.playlist(id) ?: return Outcome.DONE
        val targets = repo.channelsToCheck(id)
        if (targets.isEmpty()) return Outcome.DONE
        val urls = if (p.isXtream) XtreamUrls(XtreamCredentials(p.url, p.username.orEmpty(), p.password.orEmpty())) else null
        val ext = if (p.formats.isEmpty() || "ts" in p.formats) "ts" else "m3u8"
        val started = synchronized(roundStart) { roundStart.getOrPut(id) { System.currentTimeMillis() } }
        val done = repo.checkResults(id, started).keys
        val todo = targets.filter { it.itemId !in done }
        var checked = done.size
        var unknownRun = 0
        val failRun = ArrayList<String>()
        val refusedRun = ArrayList<String>()
        Log.i(TAG, "${p.name}: ${todo.size} of ${targets.size} channels to check")
        try {
            for (t in todo) {
                if (busy(p)) {
                    Log.i(TAG, "${p.name}: something is playing, check later")
                    return Outcome.BUSY
                }
                val url = t.streamUrl ?: urls?.live(t.itemId, ext) ?: continue
                progress.value = "${checked + 1} / ${targets.size}"
                val t0 = SystemClock.elapsedRealtime()
                val result = probe(p, url)
                Log.i(TAG, "${t.itemId}: $result in ${SystemClock.elapsedRealtime() - t0} ms$lastNote")
                if (yielded) {
                    Log.i(TAG, "${p.name}: stopped for playback")
                    return Outcome.BUSY
                }
                when {
                    result == CheckResult.UNKNOWN -> if (++unknownRun >= 3) {
                        Log.i(TAG, "${p.name}: server not answering, check later")
                        return Outcome.OFFLINE
                    }
                    result == CheckResult.NOT_WORKING && refused -> {
                        // Turned away: a server blocking for a while (too many connections, flood
                        // protection) refuses every channel, so a run of these records nothing.
                        // One on its own, between real answers, is that channel refused.
                        refusedRun += t.itemId
                        if (refusedRun.size >= 3) {
                            Log.i(TAG, "${p.name}: server refusing, check later")
                            return Outcome.ACCOUNT_FULL
                        }
                    }
                    result == CheckResult.NOT_WORKING -> {
                        unknownRun = 0
                        failRun += refusedRun
                        refusedRun.clear()
                        failRun += t.itemId
                        // Several failures in a row: maybe the account is in use elsewhere (the
                        // other box), which refuses every channel. Then nothing is recorded.
                        if (failRun.size >= FAIL_RUN && urls != null) {
                            if (accountFull(urls)) {
                                Log.i(TAG, "${p.name}: account in use elsewhere, check later")
                                return Outcome.ACCOUNT_FULL
                            }
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
            }
            save(id, (failRun + refusedRun).associateWith { false })
            failRun.clear()
        } finally {
            probing = null
            call = null
        }
        val now = System.currentTimeMillis()
        val results = repo.checkResults(id, started)
        synchronized(roundStart) { roundStart.remove(id) }
        prefs.setLastChannelCheck(id, now)
        Log.i(TAG, "${p.name}: ${results.count { it.value }} of ${results.size} working")
        telemetry.event(
            "channel_check",
            "done",
            mapOf(
                "playlist" to p.name,
                "categories" to repo.checkCategories(id).size,
                "channels" to results.size,
                "working" to results.count { it.value },
                "minutes" to (now - started) / 60_000,
            ),
        )
        return Outcome.DONE
    }

    private suspend fun save(id: Long, results: Map<String, Boolean>) {
        if (results.isEmpty()) return
        repo.saveChecks(id, results, System.currentTimeMillis())
        updates.value = updates.value + 1
    }

    /**
     * Something is playing: WorldTV on this playlist's server (one connection), or another app.
     * WorldTV playing another server: this one can still be checked.
     */
    private fun busy(p: Playlist): Boolean {
        val playing = playingUrl()
        if (playing != null) return sameServer(p, playing)
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return audio.isMusicActive
    }

    private fun sameServer(p: Playlist, url: String): Boolean {
        if (!p.isXtream) return true // M3U links can point anywhere: wait for any WorldTV playback
        val host = runCatching { java.net.URI(XtreamCredentials(p.url, "", "").baseUrl).host }.getOrNull()
        val other = runCatching { java.net.URI(url).host }.getOrNull()
        return host == null || other == null || host.equals(other, ignoreCase = true)
    }

    private fun accountFull(urls: XtreamUrls): Boolean = try {
        val req = Request.Builder().url(urls.api()).header("User-Agent", Syncer.USER_AGENT).build()
        probeHttp.newCall(req).execute().use { r ->
            val a = r.body?.charStream()?.let { XtreamParser.parseAccount(it) }
            a != null && a.maxConnections > 0 && a.activeConnections >= a.maxConnections
        }
    } catch (e: IOException) {
        false
    }

    // ---- one channel ----

    /**
     * Reads the channel for up to [READ_MS] once data flows. The panel not answering at all is
     * [CheckResult.UNKNOWN] (no network): only an answer can say a channel doesn't work.
     */
    private fun probe(p: Playlist, url: String): CheckResult {
        probing = p
        yielded = false
        refused = false
        lastNote = ""
        var target = url
        repeat(MAX_REDIRECTS) { hop ->
            val c = probeHttp.newCall(
                Request.Builder().url(target).header("User-Agent", Syncer.USER_AGENT).header("Connection", "close").build(),
            )
            c.timeout().timeout(CALL_MS, TimeUnit.MILLISECONDS)
            call = c
            if (yielded) c.cancel()
            val response: Response = try {
                c.execute()
            } catch (e: IOException) {
                lastNote = " (${e.javaClass.simpleName}: ${e.message})"
                if (yielded) return CheckResult.UNKNOWN
                // The panel itself unreachable: can't tell. A redirect target dead: not working.
                return if (hop == 0) CheckResult.UNKNOWN else CheckResult.NOT_WORKING
            }
            response.use { r ->
                if (r.isRedirect) {
                    val next = r.header("Location")?.let { r.request.url.resolve(it) } ?: return CheckResult.NOT_WORKING
                    target = next.toString()
                    return@repeat
                }
                if (!r.isSuccessful) {
                    lastNote = " (HTTP ${r.code})"
                    if (r.code in REFUSED || r.code >= 500) refused = true
                    return CheckResult.NOT_WORKING
                }
                return read(r, c)
            }
        }
        return CheckResult.NOT_WORKING
    }

    private fun read(r: Response, c: Call): CheckResult {
        val stats = TsStats()
        val body = r.body ?: return CheckResult.NOT_WORKING
        val input = body.byteStream()
        val buf = ByteArray(16 * 1024)
        var startedAt = 0L
        var total = 0L
        try {
            val first = input.read(buf)
            if (first <= 0) return CheckResult.NOT_WORKING
            if (isPlaylist(buf, first)) return hls(r, String(buf, 0, first, Charsets.UTF_8) + readText(input))
            startedAt = SystemClock.elapsedRealtime()
            stats.feed(buf, first)
            total += first
            while (SystemClock.elapsedRealtime() - startedAt < READ_MS) {
                val n = input.read(buf)
                if (n < 0) break
                stats.feed(buf, n)
                total += n
                // Decided: enough live picture, or clearly a filler (minutes of black in a burst).
                if (stats.seconds >= ENOUGH_SECONDS && stats.kbps >= CheckVerdict.MIN_VIDEO_KBPS) break
                if (stats.seconds >= FILLER_SECONDS && stats.kbps < CheckVerdict.MIN_VIDEO_KBPS) break
                if (!stats.isTs && total > 256 * 1024) break
                if (total >= MAX_BYTES) break // a cap on data use per channel
            }
        } catch (e: IOException) {
            if (yielded) return CheckResult.UNKNOWN
            // Timed out mid-stream: judged on what arrived (a stall shows as too little media).
        } finally {
            c.cancel() // closes the connection now; a live stream never ends by itself
        }
        lastNote = " (%.1f s, %.0f kbit/s, %d KB%s)".format(java.util.Locale.US, stats.seconds, stats.kbps, total / 1024, if (stats.isTs) "" else ", not TS")
        return CheckVerdict.of(stats)
    }

    private fun isPlaylist(b: ByteArray, n: Int): Boolean {
        val s = String(b, 0, minOf(n, 16), Charsets.ISO_8859_1).trimStart('﻿', ' ', '\r', '\n')
        return s.startsWith("#EXTM3U")
    }

    private fun readText(input: java.io.InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < 256 * 1024) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toString("UTF-8")
    }

    /** HLS: the newest segment of the (first) variant is downloaded and measured. */
    private fun hls(r: Response, text: String): CheckResult {
        var base = r.request.url
        var lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val variant = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF") }
        if (variant >= 0) {
            val uri = lines.drop(variant + 1).firstOrNull { !it.startsWith("#") } ?: return CheckResult.NOT_WORKING
            val next = base.resolve(uri) ?: return CheckResult.NOT_WORKING
            val body = get(next.toString()) ?: return CheckResult.NOT_WORKING
            base = next
            lines = String(body, Charsets.UTF_8).lines().map { it.trim() }.filter { it.isNotEmpty() }
        }
        val segment = lines.lastOrNull { !it.startsWith("#") } ?: return CheckResult.NOT_WORKING
        val url = base.resolve(segment) ?: return CheckResult.NOT_WORKING
        val bytes = get(url.toString(), limit = 6 * 1024 * 1024) ?: return CheckResult.NOT_WORKING
        val stats = TsStats().also { it.feed(bytes) }
        // fMP4 segments aren't TS: a full-size segment is taken as working.
        if (!stats.isTs) return if (bytes.size >= 100 * 1024) CheckResult.WORKING else CheckResult.NOT_WORKING
        return CheckVerdict.of(stats)
    }

    private fun get(url: String, limit: Int = 256 * 1024): ByteArray? = try {
        val c = probeHttp.newBuilder().followRedirects(true).build()
            .newCall(Request.Builder().url(url).header("User-Agent", Syncer.USER_AGENT).build())
        c.timeout().timeout(CALL_MS, TimeUnit.MILLISECONDS)
        call = c
        c.execute().use { r ->
            if (!r.isSuccessful) return null
            val input = r.body?.byteStream() ?: return null
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (out.size() < limit) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
    } catch (e: IOException) {
        null
    }

    // ---- waking up: alarms and jobs ----

    /**
     * The 3 am alarm (it schedules the nightly job) and the every-few-hours job. Called whenever
     * the app starts; setting them again changes nothing.
     */
    fun schedule() {
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, AutoRefresh.nextNight(System.currentTimeMillis()) + NIGHT_DELAY_MS, nightAlarm())
        val js = app.getSystemService(Context.JOB_SCHEDULER_SERVICE) as android.app.job.JobScheduler
        if (js.getPendingJob(JOB_PERIODIC) == null) {
            js.schedule(
                android.app.job.JobInfo.Builder(JOB_PERIODIC, android.content.ComponentName(app, CheckJobService::class.java))
                    .setRequiredNetworkType(android.app.job.JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(EVERY_MS, 60L * 60 * 1000)
                    .setPersisted(true)
                    .build(),
            )
        }
    }

    private fun nightAlarm(): PendingIntent = PendingIntent.getBroadcast(
        app,
        1,
        Intent(app, CheckAlarmReceiver::class.java).setAction(ACTION_NIGHT),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** 3 am: tomorrow's alarm, and the reload + check as a job (it runs as soon as there is network). */
    fun onNightAlarm() {
        schedule()
        scheduleJob(JOB_NIGHT, 0)
    }

    companion object {
        private const val TAG = "WorldTV.Check"
        const val ACTION_NIGHT = "com.worldtv.iptvplayer.CHECK_NIGHT"

        /** Job ids (JobScheduler, app-wide). */
        const val JOB_RUN = 4101
        const val JOB_NIGHT = 4102
        const val JOB_PERIODIC = 4103

        /** Results older than this are not shown (the box was off): channels look normal again. */
        const val VALID_MS = 7L * 24 * 60 * 60 * 1000

        /** Idle checks during the day, at most this often. */
        const val EVERY_MS = 4L * 60 * 60 * 1000

        /** At night everything is checked again unless done within this. */
        private const val NIGHT_RECHECK_MS = 60L * 60 * 1000

        /** Reading time per channel once data flows. */
        const val READ_MS = 3500L

        /** Enough measured media to stop reading early (saves data). */
        private const val ENOUGH_SECONDS = 2.0

        /** A filler sends minutes at once: this much of it settles it. */
        private const val FILLER_SECONDS = 6.0

        /** At most this much downloaded per channel. */
        private const val MAX_BYTES = 3L * 1024 * 1024

        private const val CALL_MS = 9000L
        /** Between channels: lets the panel free the connection, and stays under flood limits. */
        private const val GAP_MS = 2500L

        /** Answers that turn a client away rather than say a channel is dead. */
        private val REFUSED = setOf(401, 403, 429, 456, 458)
        private const val FAIL_RUN = 5
        private const val MAX_REDIRECTS = 4
        private const val BUSY_RETRY_MS = 60_000L
        private const val OFFLINE_RETRY_MS = 10L * 60 * 1000
        private const val FULL_RETRY_MS = 30L * 60 * 1000

        /** After the 3 am reload starts: the reload goes first. */
        private const val NIGHT_DELAY_MS = 60_000L
    }
}

/** The 3 am alarm. Never opens a screen: it only schedules the nightly job. */
class CheckAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ChannelCheck.ACTION_NIGHT) App.graph.channelCheck.onNightAlarm()
    }
}

/**
 * Runs the channel check (and the nightly reload) as a JobScheduler job: Android keeps the app
 * running and the processor awake while it lasts, even with WorldTV off screen or the box asleep.
 * Nothing is shown, so the screen stays off and no HDMI signal goes to the TV.
 */
class CheckJobService : android.app.job.JobService() {
    override fun onStartJob(params: android.app.job.JobParameters): Boolean {
        App.graph.channelCheck.runJob(params.jobId) { jobFinished(params, false) }
        return true
    }

    override fun onStopJob(params: android.app.job.JobParameters): Boolean {
        App.graph.channelCheck.stopJob()
        return true // again later; finished channels aren't checked twice
    }
}
