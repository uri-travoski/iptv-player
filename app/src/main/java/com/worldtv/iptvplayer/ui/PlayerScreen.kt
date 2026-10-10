package com.worldtv.iptvplayer.ui

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import com.worldtv.iptvplayer.MainActivity
import com.worldtv.iptvplayer.R
import com.worldtv.iptvplayer.data.model.ContentType
import com.worldtv.iptvplayer.player.PlayerController
import kotlinx.coroutines.launch

/**
 * Something the movie/episode player can play. With [type] and [itemId] (the movie, or the
 * series for an episode) its position is saved for "Continue watching".
 */
class PlayItem(
    val url: String,
    val title: String,
    val type: ContentType? = null,
    val itemId: String? = null,
    val episodeId: String? = null,
    /** Movies and episodes: what to look up online subtitles by (Subtitles > Search online). */
    val subs: com.worldtv.iptvplayer.data.online.SubQuery? = null,
)

/**
 * Full-screen player for movies and episodes. [items] is the queue (one movie, or a season's
 * episodes) and playback starts at [start].
 *
 * Keys: OK shows the controls (then presses the focused button); Left/Right with the controls
 * hidden seek 10 s back / 30 s forward; Play/Pause, FF/REW and Next/Previous media keys work;
 * Back hides the controls first, then leaves the player.
 * Touch (Mobile layout): a tap shows or hides the controls, the bar can be dragged, the arrow
 * top left leaves. On phones the player turns to landscape.
 */
class PlayerScreen(
    activity: MainActivity,
    private val items: List<PlayItem>,
    start: Int,
    startPositionMs: Long = 0,
) : Screen(activity) {

    override val root: View = inflater.inflate(R.layout.screen_player, null)

    private val overlay: View = root.findViewById(R.id.overlay)
    private val title: TextView = root.findViewById(R.id.title)
    private val positionText: TextView = root.findViewById(R.id.position)
    private val durationText: TextView = root.findViewById(R.id.duration)
    private val progress: SeekBar = root.findViewById(R.id.progress)
    private val osdStatus: TextView = root.findViewById(R.id.osd_status)
    private val ctlPrev: View = root.findViewById(R.id.ctl_prev)
    private val ctlNext: View = root.findViewById(R.id.ctl_next)
    private val ctlPause: ImageView = root.findViewById(R.id.ctl_pause)
    private val ctlAspect: ImageView = root.findViewById(R.id.ctl_aspect)
    private val tips = OsdTips(
        root.findViewById(R.id.osd_tip),
        listOf(
            R.id.ctl_audio, R.id.ctl_subs, R.id.ctl_prev, R.id.ctl_back, R.id.ctl_pause, R.id.ctl_fwd, R.id.ctl_next, R.id.ctl_aspect, R.id.ctl_external,
        ).map { root.findViewById<View>(it) },
    )
    /** True while a finger drags the seek bar: the bar is not updated under it. */
    private var dragging = false

    private var index = start.coerceIn(0, items.lastIndex)
    /** Where to pick up after the app was in the background (onStop stops the stream). */
    private var resumeAt = startPositionMs
    /** True once the current item actually played; a stream that never starts saves nothing. */
    private var started = false
    private val handler = Handler(Looper.getMainLooper())
    private val hide = Runnable { hideOverlay() }

    /** Updates the time and bar once a second, only while the overlay is up. */
    private val tick = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 1_000)
        }
    }

    private val listener = object : PlayerController.Listener {
        override fun onState(state: PlayerController.State) {
            val text = when (state) {
                PlayerController.State.BUFFERING -> activity.getString(R.string.loading)
                PlayerController.State.RECONNECTING -> activity.getString(R.string.reconnecting)
                PlayerController.State.FAILED -> activity.getString(R.string.stream_failed)
                else -> ""
            }
            osdStatus.text = text
            osdStatus.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
            if (state == PlayerController.State.PLAYING) started = true
            if (state == PlayerController.State.ENDED) {
                if (index < items.lastIndex) step(+1) else activity.pop()
            }
        }

        override fun onAudioUnsupported() {
            activity.toast(activity.getString(R.string.audio_unsupported))
        }
    }

    init {
        val queue = if (items.size > 1) View.VISIBLE else View.GONE
        ctlPrev.visibility = queue
        ctlNext.visibility = queue
        ctlPrev.setOnClickListener { step(-1) }
        ctlNext.setOnClickListener { step(+1) }
        root.findViewById<View>(R.id.ctl_back).setOnClickListener { seek(-10_000) }
        root.findViewById<View>(R.id.ctl_fwd).setOnClickListener { seek(30_000) }
        ctlPause.setOnClickListener { togglePause() }
        root.findViewById<View>(R.id.ctl_audio).setOnClickListener { chooseTrack(C.TRACK_TYPE_AUDIO) }
        root.findViewById<View>(R.id.ctl_subs).setOnClickListener { chooseTrack(C.TRACK_TYPE_TEXT) }
        ctlAspect.setOnClickListener {
            PlayerUi.cycleAspect(activity)
            PlayerUi.showAspect(activity, ctlAspect)
            tips.refresh()
            scheduleHide()
        }
        ctlAspect.contentDescription = activity.getString(PlayerUi.aspectLabel(activity))
        root.setOnClickListener { if (overlay.visibility == View.VISIBLE) hideOverlay() else showOverlay() }
        val exit = root.findViewById<View>(R.id.ctl_exit)
        if (graph.prefs.isMobile) exit.visibility = View.VISIBLE
        exit.setOnClickListener { activity.pop() }
        progress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                val dur = graph.player.durationMs
                if (dur > 0) positionText.text = PlayerUi.time(dur * value / 1000)
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                dragging = true
                handler.removeCallbacks(hide)
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                dragging = false
                val dur = graph.player.durationMs
                if (dur > 0) graph.player.player.seekTo(dur * bar.progress / 1000)
                scheduleHide()
            }
        })
        root.findViewById<View>(R.id.ctl_external).setOnClickListener {
            val item = items[index]
            resumeAt = graph.player.positionMs
            PlayerUi.openExternal(activity, item.url, item.title)
        }
    }

    override fun onShown() {
        activity.setFullscreen(true)
        activity.setVideoRect(null)
        activity.keepScreenOn(true)
        graph.player.addListener(listener)
        root.requestFocus()
        play()
    }

    override fun onHidden() {
        activity.setFullscreen(false)
        activity.keepScreenOn(false)
        graph.player.removeListener(listener)
        resumeAt = graph.player.positionMs
        saveProgress()
        graph.player.stop()
        handler.removeCallbacksAndMessages(null)
        overlay.visibility = View.GONE
    }

    override fun onBack(): Boolean {
        if (overlay.visibility == View.VISIBLE) {
            hideOverlay()
            return true
        }
        return false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // The overlay can be up just to show the time after a seek; the buttons are only "live"
        // once one of them has focus.
        val shown = overlay.visibility == View.VISIBLE && activity.currentFocus !== root
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (shown) { scheduleHide(); return false }
                showOverlay()
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (shown) { scheduleHide(); return false }
                seek(if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -10_000 else 30_000)
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_INFO -> showOverlay()
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> togglePause()
            KeyEvent.KEYCODE_MEDIA_REWIND -> seek(-10_000)
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seek(30_000)
            KeyEvent.KEYCODE_MEDIA_NEXT -> if (items.size > 1) step(+1) else return false
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> if (items.size > 1) step(-1) else return false
            else -> return false
        }
        return true
    }

    private fun play() {
        val item = items[index]
        title.text = item.title
        started = false
        graph.player.play(item.url)
        if (resumeAt > 0) graph.player.player.seekTo(resumeAt)
        graph.telemetry.event(
            "playback",
            "watch",
            mapOf(
                "content_type" to item.type?.name?.lowercase(),
                "item_id" to item.itemId,
                "item_name" to item.title,
                "episode_id" to item.episodeId,
                "position_ms" to resumeAt.takeIf { it > 0 },
            ),
        )
        resumeAt = 0
        updatePauseLabel()
    }

    private fun step(delta: Int) {
        val next = index + delta
        if (next !in items.indices) return
        saveProgress()
        index = next
        resumeAt = 0
        play()
        showOverlay()
    }

    /**
     * Saves where the current item is. Runs in the activity's scope: this screen's own scope is
     * cancelled the moment it is popped, before the write could happen.
     */
    private fun saveProgress() {
        val item = items[index]
        val type = item.type ?: return
        val id = item.itemId ?: return
        if (!started) return
        val pos = graph.player.positionMs
        val dur = graph.player.durationMs
        val playlistId = graph.prefs.activePlaylist
        activity.lifecycleScope.launch { graph.repo.saveProgress(playlistId, type, id, item.episodeId, pos, dur, graph.adultNames()) }
    }

    private fun seek(deltaMs: Long) {
        graph.player.seekBy(deltaMs)
        showOverlay(focusControls = false)
    }

    private fun togglePause() {
        graph.player.togglePause()
        updatePauseLabel()
        showOverlay(focusControls = overlay.visibility == View.VISIBLE)
    }

    private fun updatePauseLabel() {
        val paused = graph.player.isPaused
        ctlPause.setImageResource(if (paused) R.drawable.ic_play else R.drawable.ic_pause)
        ctlPause.contentDescription = activity.getString(if (paused) R.string.ctl_play else R.string.ctl_pause)
        tips.refresh()
    }

    private fun chooseTrack(type: Int) {
        handler.removeCallbacks(hide)
        val query = items[index].subs
        PlayerUi.chooseTrack(activity, type, onSearchOnline = query?.let { q -> { searchSubtitles(q) } }) { scheduleHide() }
    }

    /**
     * Subtitles > Search online: OpenSubtitles results in the preferred language (then English),
     * pick one and it is added to the movie at the current position. Needs the user's own API key
     * (Settings > Subtitle settings); without one this explains how to get it.
     */
    private fun searchSubtitles(q: com.worldtv.iptvplayer.data.online.SubQuery) {
        val subs = graph.openSubs
        if (!subs.isSetUp) {
            android.app.AlertDialog.Builder(activity)
                .setTitle(R.string.subs_online_title)
                .setMessage(R.string.subs_online_needs_key)
                .setPositiveButton(android.R.string.ok, null)
                .setOnDismissListener { scheduleHide() }
                .show()
            return
        }
        val lang = graph.prefs.subsLanguage.ifEmpty { java.util.Locale.getDefault().language }.ifEmpty { "en" }
        activity.toast(activity.getString(R.string.subs_searching))
        activity.lifecycleScope.launch {
            try {
                var results = subs.search(q, lang)
                if (results.isEmpty() && lang != "en") results = subs.search(q, "en")
                graph.telemetry.event("subtitle", "search", mapOf("item_name" to q.title, "language" to lang, "results" to results.size))
                if (results.isEmpty()) {
                    activity.toast(activity.getString(R.string.subs_none_found, q.title))
                    return@launch scheduleHide()
                }
                val top = results.take(25)
                val labels = top.map { r ->
                    val hi = if (r.hearingImpaired) " · HI" else ""
                    "${r.language.uppercase()}$hi · ${r.release.take(70)}  (${r.downloads}↓)"
                }.toTypedArray()
                android.app.AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.subs_results, q.title))
                    .setItems(labels) { _, which ->
                        activity.lifecycleScope.launch {
                            try {
                                val file = subs.download(top[which])
                                graph.player.addSubtitle(file, top[which].language.take(2))
                                activity.toast(activity.getString(R.string.subs_added))
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                activity.toast(e.message ?: activity.getString(R.string.subs_failed))
                            }
                            scheduleHide()
                        }
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> scheduleHide() }
                    .show()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                activity.toast(e.message ?: activity.getString(R.string.subs_failed))
                scheduleHide()
            }
        }
    }

    /** Title and progress; with [focusControls] the buttons take focus so OK presses them. */
    private fun showOverlay(focusControls: Boolean = true) {
        val wasShown = overlay.visibility == View.VISIBLE
        overlay.visibility = View.VISIBLE
        updatePauseLabel()
        updateProgress()
        if (!wasShown) {
            handler.removeCallbacks(tick)
            handler.postDelayed(tick, 1_000)
        }
        if (focusControls && activity.currentFocus === root) ctlPause.requestFocus()
        scheduleHide()
    }

    private fun hideOverlay() {
        handler.removeCallbacks(hide)
        handler.removeCallbacks(tick)
        overlay.visibility = View.GONE
        root.requestFocus()
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hide)
        handler.postDelayed(hide, 6_000)
    }

    private fun updateProgress() {
        if (dragging) return
        val pos = graph.player.positionMs
        val dur = graph.player.durationMs
        positionText.text = PlayerUi.time(pos)
        durationText.text = if (dur > 0) PlayerUi.time(dur) else ""
        progress.isEnabled = dur > 0
        progress.progress = if (dur > 0) (pos * 1000 / dur).toInt() else 0
        progress.secondaryProgress = if (dur > 0) (graph.player.player.bufferedPosition * 1000 / dur).toInt() else 0
    }
}
