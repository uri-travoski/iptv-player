package io.github.sardinemehico.iptvplayer.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.PlayerView
import io.github.sardinemehico.iptvplayer.data.sync.Syncer
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The app's single ExoPlayer. Created once, never rebuilt: zapping only swaps the MediaItem,
 * because codec setup is slow on cheap SoCs.
 */
@OptIn(UnstableApi::class)
class PlayerController(context: Context, http: OkHttpClient) {

    interface Listener {
        fun onState(state: State) {}

        /** The stream has sound, but in a format neither the box nor the bundled decoder can play. */
        fun onAudioUnsupported() {}
    }

    enum class State { IDLE, BUFFERING, PLAYING, RECONNECTING, FAILED, ENDED }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<Listener>()
    private var currentUrl: String? = null
    private var retries = 0

    val player: ExoPlayer

    init {
        // Streams get shorter timeouts than playlist downloads: a stuck IPTV server should fail
        // in seconds and be retried (the panel then usually hands out another server), not
        // leave a black preview for a minute.
        val streamHttp = http.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
        // Streams over OkHttp; local files too (downloaded subtitles), which OkHttp can't open.
        val dataSource = DefaultDataSource.Factory(context, OkHttpDataSource.Factory(streamHttp).setUserAgent(Syncer.USER_AGENT))
        // Live TS often has key (IDR) frames only every few seconds; starting on any intra frame
        // shows the picture sooner after a zap. Not FLAG_DETECT_ACCESS_UNITS: on streams that
        // mark their frames (AUD) it cut each frame into ~2.5 samples, and the Allwinner decoder
        // fell behind on 1080p50/60 channels (most frames dropped, sound out of sync, rebuffering).
        val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES,
        )
        val mediaSources = DefaultMediaSourceFactory(dataSource, extractors)
            .setLoadErrorHandlingPolicy(IptvLoadErrorPolicy())
        // Small buffers: caps RAM on 1 GB boxes and keeps zapping quick. Playback starts after
        // 0.5 s of media (rebuffering waits for 2 s, so a slow line doesn't stutter repeatedly).
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(3_000, 15_000, 500, 2_000)
            .setTargetBufferBytes(16 * 1024 * 1024)
            .build()
        val renderers = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            // Box decoders first; the bundled FFmpeg audio decoder only for formats the box lacks
            // (Dolby AC-3/E-AC-3, MP2, DTS on many cheap boxes).
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        player = ExoPlayer.Builder(context, renderers, mediaSources)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            .build()
        // Smoothness in the log (adb logcat -s WorldTV.Player): dropped frames, audio gaps, decoders.
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                Log.i(TAG, "dropped $droppedFrames frames in ${elapsedMs}ms")
            }

            override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
                Log.i(TAG, "audio underrun (${elapsedSinceLastFeedMs}ms since last feed)")
            }

            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                Log.i(TAG, "video decoder $decoderName")
            }

            override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                Log.i(TAG, "audio decoder $decoderName")
            }
        })
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                Log.i(TAG, "state ${stateName(playbackState)} +${sincePlay()}ms")
                when (playbackState) {
                    Player.STATE_BUFFERING -> emit(State.BUFFERING)
                    Player.STATE_READY -> {
                        retries = 0
                        // Remember which format worked for this channel, for the next zap.
                        val url = currentUrl
                        val loaded = loadedUrl
                        if (url != null && loaded != null) {
                            if (loaded != url) preferred[url] = loaded else preferred.remove(url)
                        }
                        emit(State.PLAYING)
                    }
                    Player.STATE_ENDED -> emit(State.ENDED)
                    else -> Unit
                }
            }

            override fun onRenderedFirstFrame() {
                Log.i(TAG, "first frame +${sincePlay()}ms")
            }

            override fun onTracksChanged(tracks: Tracks) {
                Log.i(TAG, "tracks: " + tracks.groups.joinToString { g ->
                    val f = g.getTrackFormat(0)
                    "${f.sampleMimeType}${f.language?.let { "/$it" } ?: ""}${if (g.isSelected) "*" else ""}"
                })
                val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                if (audio.isNotEmpty() && audio.none { it.isSupported }) {
                    for (l in listeners.toList()) l.onAudioUnsupported()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "error ${error.errorCodeName} +${sincePlay()}ms: ${error.cause?.toString()?.take(200)}")
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition()
                    player.prepare()
                    return
                }
                val url = currentUrl
                if (url != null && retries < MAX_RETRIES) {
                    retries++
                    emit(State.RECONNECTING)
                    // Each retry asks the panel again, and it usually hands out a different
                    // server. When the last one was down, also switch .ts <-> .m3u8: panels
                    // often serve the two formats from different servers.
                    val alt = if (StreamErrors.isDeadServer(error)) StreamErrors.alternateFormat(loadedUrl ?: url) else null
                    // Quick first retries (most failures are momentary), then back off.
                    val delay = when (retries) { 1 -> 0L; 2 -> 500L; else -> 1_000L * (retries - 1) }
                    Log.i(TAG, "retry $retries in ${delay}ms${if (alt != null) " as " + alt.substringAfterLast('.') else ""}")
                    main.postDelayed({
                        if (currentUrl != url) return@postDelayed
                        if (alt != null) load(alt) else player.prepare()
                    }, delay)
                } else {
                    emit(State.FAILED)
                }
            }
        })
    }

    fun attach(view: PlayerView) {
        view.player = player
    }

    fun addListener(l: Listener) {
        if (l !in listeners) listeners += l
    }

    fun removeListener(l: Listener) {
        listeners -= l
    }

    /** Plays [url]; does nothing if it is already the current stream. */
    fun play(url: String) {
        if (url == currentUrl && player.playbackState != Player.STATE_IDLE) return
        main.removeCallbacksAndMessages(null)
        currentUrl = url
        retries = 0
        playStartedAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "play ${url.substringAfterLast('/').take(40)}")
        load(playingUrl(url))
        player.playWhenReady = true
        emit(State.BUFFERING)
    }

    /** The URL actually loaded for [url]: its other format if that is what worked last time. */
    private fun playingUrl(url: String) = preferred[url] ?: url

    private fun load(url: String, startMs: Long = C.TIME_UNSET) {
        loadedUrl = url
        val item = MediaItem.Builder().setUri(url)
        if (url.contains(".m3u8", ignoreCase = true)) item.setMimeType(MimeTypes.APPLICATION_M3U8)
        // A subtitle file downloaded for this stream (Search online) rides along, also on retries.
        extraSubtitle?.takeIf { it.first == currentUrl }?.let { item.setSubtitleConfigurations(listOf(it.second)) }
        if (startMs == C.TIME_UNSET) player.setMediaItem(item.build()) else player.setMediaItem(item.build(), startMs)
        player.prepare()
    }

    /** Subtitle file added to the stream now playing: (stream URL, its configuration). */
    private var extraSubtitle: Pair<String, MediaItem.SubtitleConfiguration>? = null

    /**
     * Adds a downloaded subtitle [file] (SubRip) to the movie or episode now playing and shows it.
     * The stream is loaded again with it at the same position: a second or two of buffering.
     */
    fun addSubtitle(file: java.io.File, language: String?) {
        val url = currentUrl ?: return
        val config = MediaItem.SubtitleConfiguration.Builder(android.net.Uri.fromFile(file))
            .setMimeType(MimeTypes.APPLICATION_SUBRIP)
            .setLanguage(language)
            .setLabel("Online")
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_FORCED)
            .build()
        extraSubtitle = url to config
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .apply { if (language != null) setPreferredTextLanguage(language) }
            .build()
        load(loadedUrl ?: url, player.currentPosition.coerceAtLeast(0))
    }

    /**
     * Settings > Subtitle settings: with [enabled], subtitles in [language] (or with no language
     * set) show by themselves; otherwise only when picked in the player.
     */
    fun applySubtitlePrefs(enabled: Boolean, language: String) {
        val b = player.trackSelectionParameters.buildUpon()
        if (enabled) {
            b.setPreferredTextLanguage(language.ifEmpty { java.util.Locale.getDefault().language })
            b.setSelectUndeterminedTextLanguage(true)
        } else {
            b.setPreferredTextLanguages()
            b.setSelectUndeterminedTextLanguage(false)
        }
        player.trackSelectionParameters = b.build()
    }

    val playingUrl: String? get() = currentUrl

    val isPaused: Boolean get() = !player.playWhenReady

    /** Pause or resume. Live streams pick up where the buffer is (or jump to live if it expired). */
    fun togglePause() {
        player.playWhenReady = !player.playWhenReady
    }

    /** Movies and episodes: current position and length in ms (0 if not known yet). */
    val positionMs: Long get() = player.currentPosition.coerceAtLeast(0)
    val durationMs: Long get() = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0

    fun seekBy(deltaMs: Long) {
        val d = durationMs
        val target = (positionMs + deltaMs).coerceAtLeast(0)
        player.seekTo(if (d > 0) target.coerceAtMost(d - 1000) else target)
    }

    /** One selectable audio or subtitle track of the current stream. */
    class Track(val label: String, val group: TrackGroup, val index: Int, val selected: Boolean)

    /** Tracks of [type] (C.TRACK_TYPE_AUDIO or C.TRACK_TYPE_TEXT) the device can play. */
    fun tracks(type: Int): List<Track> {
        val out = ArrayList<Track>()
        for (g in player.currentTracks.groups) {
            if (g.type != type) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                val f = g.getTrackFormat(i)
                val parts = listOfNotNull(
                    f.label,
                    f.language?.takeIf { it != "und" }?.let { java.util.Locale.forLanguageTag(it).displayLanguage.ifEmpty { it } },
                    f.channelCount.takeIf { it > 0 }?.let { "${it}ch" },
                ).distinct()
                val label = parts.joinToString(" · ").ifEmpty { "Track ${out.size + 1}" }
                out += Track(label, g.mediaTrackGroup, i, g.isTrackSelected(i))
            }
        }
        return out
    }

    /** Selects [track]; null turns the type off (used for subtitles). */
    fun selectTrack(type: Int, track: Track?) {
        val b = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type)
        if (track == null) {
            b.setTrackTypeDisabled(type, true)
        } else {
            b.setTrackTypeDisabled(type, false)
            b.setOverrideForType(TrackSelectionOverride(track.group, track.index))
        }
        player.trackSelectionParameters = b.build()
    }

    /** True if [type] is switched off by the user. */
    fun isTrackTypeDisabled(type: Int) = type in player.trackSelectionParameters.disabledTrackTypes

    fun stop() {
        main.removeCallbacksAndMessages(null)
        currentUrl = null
        player.stop()
        player.clearMediaItems()
        emit(State.IDLE)
    }

    private fun emit(state: State) {
        for (l in listeners.toList()) l.onState(state)
    }

    /** URL handed to the player (may be the other format of [currentUrl]). */
    private var loadedUrl: String? = null
    /** Channels that only play in their other format, this session: requested URL -> working URL. */
    private val preferred = HashMap<String, String>()

    /** Start time of the current play(), for the timing lines in the log. */
    private var playStartedAt = 0L

    private fun sincePlay() = SystemClock.elapsedRealtime() - playStartedAt

    private fun stateName(s: Int) = when (s) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        else -> "ENDED"
    }

    private companion object {
        const val TAG = "WorldTV.Player"
        const val MAX_RETRIES = 5
    }
}
