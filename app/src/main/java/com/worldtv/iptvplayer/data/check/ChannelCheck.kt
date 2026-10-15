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
import com.worldtv.iptvplayer.data.source.XtreamUrls
import com.worldtv.iptvplayer.data.sync.Syncer
import com.worldtv.iptvplayer.data.telemetry.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
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
 * Runs after a playlist is added or reloaded (every night after the 3 am reload): once all its
 * channels are checked, the next check waits for the next reload. A check cut short (something
 * played, the box went off) carries on once nothing has played for a minute.
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

    private val prober = ChannelProbe(http, Syncer.USER_AGENT)

    /** When playback (WorldTV or another app) was last seen; checks start after [IDLE_MS] without it. */
    @Volatile private var lastPlayback = 0L

    /** The job's work while it runs (one at a time). */
    @Volatile private var running: Job? = null

    @Volatile private var probing: Playlist? = null

    // ---- requests ----

    /**
     * Checks [playlistId] (when it has categories chosen). [fresh]: start over, re-checking every
     * channel. Runs as a background job: Android freezes an app that isn't on screen, but not
     * while its job runs.
     */
    fun request(playlistId: Long, fresh: Boolean = false, now: Boolean = false) {
        if (fresh) {
            prefs.setCheckRoundStart(playlistId, 0)
            prefs.setCheckRoundActive(playlistId, 0)
        }
        if (now) lastPlayback = 0 // Check now: no idle minute to wait for
        prefs.pendingChecks = prefs.pendingChecks + playlistId
        if (running?.isActive != true) scheduleJob(JOB_RUN, 0)
    }

    /**
     * Checks that are owed: a playlist never checked yet, or a round that was interrupted (the
     * box was busy, off or the app closed). A finished check isn't repeated until the playlist
     * is reloaded (every night at 3 am with Automatic refresh), or Check now.
     */
    fun requestDue() {
        scope.launch {
            val due = duePlaylists()
            if (due.isNotEmpty() || prefs.pendingChecks.isNotEmpty()) {
                prefs.pendingChecks = prefs.pendingChecks + due
                if (running?.isActive != true) scheduleJob(JOB_RUN, 0)
            }
        }
    }

    private suspend fun duePlaylists(): List<Long> = repo.playlists().map { it.id }.filter { id ->
        repo.checkCategories(id).isNotEmpty() && (lastCheck(id) <= 0 || prefs.checkRoundStart(id) > 0)
    }

    /** When the last full check of [playlistId] finished (ms), 0 = never. */
    fun lastCheck(playlistId: Long): Long = prefs.lastChannelCheck(playlistId)

    /**
     * From the player, on the main thread, before a stream starts: a check reading from the same
     * server lets go of the connection at once.
     */
    fun beforePlay(url: String) {
        lastPlayback = SystemClock.elapsedRealtime()
        val p = probing ?: return
        if (!sameServer(p, url)) return
        prober.yieldNow()
    }

    /** WorldTV stopped playing: a check that is due or unfinished starts after a minute of idle. */
    fun afterStop() {
        lastPlayback = SystemClock.elapsedRealtime()
        if (running?.isActive == true) return
        scope.launch {
            if (prefs.pendingChecks.isNotEmpty() || duePlaylists().isNotEmpty()) scheduleJob(JOB_RUN, IDLE_MS)
        }
    }

    /** What Settings shows: "33 / 335 · …". */
    data class Status(
        val checked: Int,
        val total: Int,
        /** Scanning time of the round (not the pauses while something played); -1 unknown. */
        val activeMs: Long,
        /** The round is still going (running now, or waiting for the box to be idle). */
        val unfinished: Boolean,
        val running: Boolean,
        /** When the last round finished, 0 = never. */
        val doneAt: Long,
    )

    suspend fun status(playlistId: Long): Status {
        val targets = repo.channelsToCheck(playlistId).map { it.itemId }.toHashSet()
        val current = prefs.checkRoundStart(playlistId)
        val unfinished = current > 0
        val last = prefs.checkLastRoundStart(playlistId)
        // Checked before rounds were recorded (2.33-2.34): the last 7 days, time unknown (-1).
        val since = if (unfinished) current else last.takeIf { it > 0 } ?: (System.currentTimeMillis() - VALID_MS)
        val checked = repo.checkResults(playlistId, since).keys.count { it in targets }
        val active = when {
            unfinished -> prefs.checkRoundActive(playlistId)
            last > 0 -> prefs.checkLastRoundActive(playlistId)
            else -> -1L
        }
        return Status(checked, targets.size, active, unfinished, running?.isActive == true && probingId == playlistId, lastCheck(playlistId))
    }

    @Volatile private var probingId = -1L

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
                // (A reload above already asked for its check.)
                prefs.pendingChecks = prefs.pendingChecks + duePlaylists()
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
        prober.cancel()
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
                val result = try {
                    probing = p
                    prober.probe(url)
                } finally {
                    prefs.setCheckRoundActive(id, prefs.checkRoundActive(id) + SystemClock.elapsedRealtime() - t0)
                }
                Log.i(TAG, "${t.itemId}: $result in ${SystemClock.elapsedRealtime() - t0} ms${prober.note}")
                if (prober.yielded) {
                    Log.i(TAG, "${p.name}: stopped for playback")
                    return Outcome.BUSY
                }
                when {
                    result == CheckResult.UNKNOWN -> if (++unknownRun >= 3) {
                        Log.i(TAG, "${p.name}: server not answering, check later")
                        return Outcome.OFFLINE
                    }
                    result == CheckResult.NOT_WORKING && prober.refused -> {
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
                            if (prober.accountFull(urls)) {
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
                prefs.setCheckRoundActive(id, prefs.checkRoundActive(id) + GAP_MS)
            }
            save(id, (failRun + refusedRun).associateWith { false })
            failRun.clear()
        } finally {
            probing = null
            probingId = -1
        }
        val now = System.currentTimeMillis()
        val results = repo.checkResults(id, started)
        val activeMs = prefs.checkRoundActive(id)
        prefs.finishCheckRound(id, started, activeMs)
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
                "scan_seconds" to activeMs / 1000,
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
        val now = SystemClock.elapsedRealtime()
        val playing = playingUrl()
        if (playing != null) {
            if (!sameServer(p, playing)) return false
            lastPlayback = now
            return true
        }
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (audio.isMusicActive) {
            lastPlayback = now
            return true
        }
        // Nothing playing: start once it has been like that for a minute.
        // (A few seconds' slack: the job that was set for a minute after playback may run early.)
        return lastPlayback > 0 && now - lastPlayback < IDLE_MS - 5_000
    }

    private fun sameServer(p: Playlist, url: String): Boolean {
        if (!p.isXtream) return true // M3U links can point anywhere: wait for any WorldTV playback
        val host = runCatching { java.net.URI(XtreamCredentials(p.url, "", "").baseUrl).host }.getOrNull()
        val other = runCatching { java.net.URI(url).host }.getOrNull()
        return host == null || other == null || host.equals(other, ignoreCase = true)
    }

    // ---- waking up: alarms and jobs ----

    /**
     * The 3 am alarm (it schedules the nightly job) and the 15-minute job that resumes unfinished checks. Called whenever
     * the app starts; setting them again changes nothing.
     */
    fun schedule() {
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val at = AutoRefresh.nextNight(System.currentTimeMillis()) + NIGHT_DELAY_MS
        // Exact where allowed (Android 12-13 allow it by default); an inexact alarm may come
        // hours late. Otherwise a 30-minute window from 3 am.
        val exact = android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, nightAlarm())
        } else {
            am.setWindow(AlarmManager.RTC_WAKEUP, at, NIGHT_WINDOW_MS, nightAlarm())
        }
        val js = app.getSystemService(Context.JOB_SCHEDULER_SERVICE) as android.app.job.JobScheduler
        if (js.getPendingJob(JOB_PERIODIC)?.intervalMillis != PERIODIC_MS) {
            js.schedule(
                android.app.job.JobInfo.Builder(JOB_PERIODIC, android.content.ComponentName(app, CheckJobService::class.java))
                    .setRequiredNetworkType(android.app.job.JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(PERIODIC_MS, 5L * 60 * 1000)
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
        /** Nothing played for this long: the box counts as idle and a check may start. */
        const val IDLE_MS = 60_000L

        /** How often an idle box looks for a check that is due (Android's shortest period). */
        private const val PERIODIC_MS = 15L * 60 * 1000


        /** Between channels: lets the panel free the connection, and stays under flood limits. */
        private const val GAP_MS = 2500L

        private const val FAIL_RUN = 5
        private const val BUSY_RETRY_MS = 60_000L
        private const val OFFLINE_RETRY_MS = 10L * 60 * 1000
        private const val FULL_RETRY_MS = 30L * 60 * 1000

        private const val NIGHT_WINDOW_MS = 30L * 60 * 1000

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
