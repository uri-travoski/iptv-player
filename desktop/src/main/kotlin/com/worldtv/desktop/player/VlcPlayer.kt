package com.worldtv.desktop.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import com.worldtv.iptvplayer.data.sync.Syncer
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The one player for the app's lifetime (like the Android PlayerController): libVLC through vlcj.
 * VLC decodes (hardware decoding where the system has it); each frame is copied into a Skia
 * bitmap that Compose draws, so menus and banners can sit on top of the picture. Audio goes
 * straight from VLC to the sound system.
 *
 * Live streams that drop are reopened by themselves, a few times, as on Android.
 */
class VlcPlayer {

    enum class State { IDLE, OPENING, BUFFERING, PLAYING, PAUSED, ENDED, ERROR }

    class Track(val id: Int, val label: String, val selected: Boolean)

    /** Null when libVLC isn't installed: the app shows how to install it. */
    val available: Boolean

    private val factory: MediaPlayerFactory?
    private val player: EmbeddedMediaPlayer?

    /** Single thread for every call into VLC: none run on the UI thread, none from VLC's own callbacks. */
    private val vlcThread = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "vlc").apply { isDaemon = true } }

    // ---- Compose state ----

    /** The latest frame, or null (nothing playing yet). */
    var frame by mutableStateOf<ImageBitmap?>(null)
        private set

    /** Width / height the picture should be shown at (sample aspect applied). */
    var frameAspect by mutableStateOf(16f / 9f)
        private set

    var state by mutableStateOf(State.IDLE)
        private set

    var bufferPercent by mutableStateOf(0f)
        private set

    var positionMs by mutableStateOf(0L)
        private set

    var lengthMs by mutableStateOf(0L)
        private set

    var url by mutableStateOf<String?>(null)
        private set

    /** Last error line ("Couldn't open the stream"), shown over the picture. */
    var error by mutableStateOf<String?>(null)
        private set

    var volume by mutableStateOf(100)
        private set

    var muted by mutableStateOf(false)
        private set

    /** Bumped when the stream's tracks change (audio / subtitle menus). */
    var tracksVersion by mutableStateOf(0)
        private set

    /** Called before a stream starts (the channel check lets go of the same server). */
    var beforePlay: ((String) -> Unit)? = null

    /** Called after playback stops. */
    var afterStop: (() -> Unit)? = null

    private var live = false
    private var retries = 0
    private var generation = 0

    init {
        var f: MediaPlayerFactory? = null
        var p: EmbeddedMediaPlayer? = null
        try {
            // AppImage and Flatpak carry their own VLC: its folder comes in WORLDTV_VLC_DIR
            // (VLC_PLUGIN_PATH is set by their launcher too). Else the system's VLC is found.
            val bundled = System.getenv("WORLDTV_VLC_DIR")?.takeIf { java.io.File(it, "libvlc.so.5").exists() }
            if (bundled != null) {
                com.sun.jna.NativeLibrary.addSearchPath("vlc", bundled)
                com.sun.jna.NativeLibrary.addSearchPath("vlccore", bundled)
            }
            if (bundled != null || NativeDiscovery().discover()) {
                // WORLDTV_VLC_ARGS: extra libVLC options, for trouble-shooting (e.g. "--aout=dummy -vvv").
                val extra = System.getenv("WORLDTV_VLC_ARGS")?.split(' ')?.filter { it.isNotBlank() }.orEmpty()
                f = MediaPlayerFactory(listOf("--no-video-title-show", "--quiet", "--no-osd", "--no-snapshot-preview") + extra)
                p = f.mediaPlayers().newEmbeddedMediaPlayer()
            }
        } catch (t: Throwable) {
            System.err.println("W/WorldTV: libVLC not usable: $t")
            f = null
            p = null
        }
        factory = f
        player = p
        available = p != null
        p?.let { setUp(it) }
    }

    private fun setUp(p: EmbeddedMediaPlayer) {
        val surface = factory!!.videoSurfaces().newVideoSurface(Formats(), Renderer(), true)
        p.videoSurface().set(surface)
        p.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun opening(mediaPlayer: MediaPlayer) {
                state = State.OPENING
            }

            override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) {
                bufferPercent = newCache
                if (newCache < 100f && state != State.PAUSED) state = State.BUFFERING
                else if (newCache >= 100f && state == State.BUFFERING) state = State.PLAYING
            }

            override fun playing(mediaPlayer: MediaPlayer) {
                state = State.PLAYING
                error = null
            }

            override fun paused(mediaPlayer: MediaPlayer) {
                state = State.PAUSED
            }

            override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) {
                positionMs = newTime
                // Picture flowing again: the retries start over.
                if (newTime > 5_000) retries = 0
            }

            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) {
                lengthMs = newLength
            }

            override fun elementaryStreamAdded(mediaPlayer: MediaPlayer, type: uk.co.caprica.vlcj.media.TrackType, id: Int) {
                tracksVersion++
            }

            override fun elementaryStreamSelected(mediaPlayer: MediaPlayer, type: uk.co.caprica.vlcj.media.TrackType, id: Int) {
                tracksVersion++
            }

            override fun finished(mediaPlayer: MediaPlayer) {
                if (live) retry("The stream ended") else state = State.ENDED
            }

            override fun error(mediaPlayer: MediaPlayer) {
                retry("Couldn't play this stream")
            }
        })
    }

    /** A live stream that broke: opened again (at once, then after 1, 2, 4 s); then the error shows. */
    private fun retry(message: String) {
        val u = url ?: return
        val gen = generation
        if (retries >= MAX_RETRIES) {
            state = State.ERROR
            error = message
            return
        }
        val delay = if (retries == 0) 0L else 1000L shl (retries - 1)
        retries++
        state = State.BUFFERING
        vlcThread.schedule({
            if (gen == generation) open(u, 0)
        }, delay, TimeUnit.MILLISECONDS)
    }

    // ---- controls (any thread) ----

    /** Plays [url]. [isLive]: a channel (reopened when it drops). [startMs]: resume point for films. */
    fun play(url: String, isLive: Boolean, startMs: Long = 0) {
        val p = player ?: run {
            error = "VLC is not installed"
            state = State.ERROR
            return
        }
        generation++
        this.url = url
        live = isLive
        retries = 0
        error = null
        positionMs = 0
        lengthMs = 0
        state = State.OPENING
        beforePlay?.invoke(url)
        vlcThread.execute { open(url, startMs) }
    }

    private fun open(url: String, startMs: Long) {
        val p = player ?: return
        val options = ArrayList<String>()
        options += ":http-user-agent=${Syncer.USER_AGENT}"
        options += ":network-caching=" + if (live) LIVE_CACHE_MS else VOD_CACHE_MS
        if (live) options += ":http-reconnect"
        if (startMs > 0) options += ":start-time=" + (startMs / 1000.0)
        p.media().play(url, *options.toTypedArray())
        p.audio().setVolume(volume)
        p.audio().setMute(muted)
    }

    fun stop() {
        val had = url != null
        generation++
        url = null
        state = State.IDLE
        error = null
        frame = null
        positionMs = 0
        lengthMs = 0
        vlcThread.execute { player?.controls()?.stop() }
        if (had) afterStop?.invoke()
    }

    val isPaused get() = state == State.PAUSED

    fun togglePause() {
        vlcThread.execute { player?.controls()?.pause() }
    }

    fun seekTo(ms: Long) {
        val target = ms.coerceIn(0, if (lengthMs > 0) lengthMs - 1000 else Long.MAX_VALUE)
        positionMs = target
        vlcThread.execute { player?.controls()?.setTime(target) }
    }

    fun seekBy(deltaMs: Long) = seekTo(positionMs + deltaMs)

    fun changeVolume(v: Int) {
        volume = v.coerceIn(0, 150)
        if (muted && v > 0) muted = false
        vlcThread.execute {
            player?.audio()?.setVolume(volume)
            player?.audio()?.setMute(muted)
        }
    }

    fun toggleMute() {
        muted = !muted
        vlcThread.execute { player?.audio()?.setMute(muted) }
    }

    fun initVolume(v: Int, mute: Boolean) {
        volume = v.coerceIn(0, 150)
        muted = mute
    }

    // ---- tracks ----

    fun audioTracks(): List<Track> = tracks(audio = true)

    fun subtitleTracks(): List<Track> = tracks(audio = false)

    private fun tracks(audio: Boolean): List<Track> {
        val p = player ?: return emptyList()
        return runCatching {
            vlcThread.submit<List<Track>> {
                val list = if (audio) p.audio().trackDescriptions() else p.subpictures().trackDescriptions()
                val current = if (audio) p.audio().track() else p.subpictures().track()
                list.map { Track(it.id(), it.description() ?: "Track ${it.id()}", it.id() == current) }
            }.get(2, TimeUnit.SECONDS)
        }.getOrDefault(emptyList())
    }

    fun selectAudio(id: Int) = vlcThread.execute {
        player?.audio()?.setTrack(id)
        tracksVersion++
    }

    /** -1 turns subtitles off. */
    fun selectSubtitle(id: Int) = vlcThread.execute {
        player?.subpictures()?.setTrack(id)
        tracksVersion++
    }

    fun addSubtitleFile(file: java.io.File) = vlcThread.execute {
        player?.subpictures()?.setSubTitleFile(file)
        tracksVersion++
    }

    fun release() {
        generation++
        runCatching {
            vlcThread.submit {
                player?.controls()?.stop()
                player?.release()
                factory?.release()
            }.get(3, TimeUnit.SECONDS)
        }
        vlcThread.shutdownNow()
    }

    // ---- video frames ----

    /** Two bitmaps, filled in turn: Compose draws one while VLC writes the other. */
    private var bitmaps = arrayOfNulls<Bitmap>(2)
    private var next = 0
    private var pixels = ByteArray(0)
    private var info: ImageInfo? = null
    private var displayAspect = 16f / 9f

    private inner class Formats : BufferFormatCallback {
        override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
            // Bigger than full HD is scaled down by VLC: the copy per frame stays reasonable.
            var w = sourceWidth
            var h = sourceHeight
            if (w > MAX_WIDTH) {
                h = (h.toLong() * MAX_WIDTH / w).toInt() and 1.inv()
                w = MAX_WIDTH
            }
            info = ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
            pixels = ByteArray(w * h * 4)
            bitmaps = arrayOf(Bitmap().apply { allocPixels(info!!) }, Bitmap().apply { allocPixels(info!!) })
            displayAspect = w.toFloat() / h
            return RV32BufferFormat(w, h)
        }

        override fun newFormatSize(bufferWidth: Int, bufferHeight: Int, displayWidth: Int, displayHeight: Int) {
            if (displayWidth > 0 && displayHeight > 0) displayAspect = displayWidth.toFloat() / displayHeight
        }

        override fun allocatedBuffers(buffers: Array<out ByteBuffer>) {}
    }

    private inner class Renderer : RenderCallback {
        override fun lock(mediaPlayer: MediaPlayer) {}

        override fun display(mediaPlayer: MediaPlayer, nativeBuffers: Array<out ByteBuffer>, bufferFormat: BufferFormat, displayWidth: Int, displayHeight: Int) {
            val i = info ?: return
            val b = bitmaps[next] ?: return
            val buf = nativeBuffers[0]
            buf.rewind()
            if (buf.remaining() < pixels.size) return
            buf.get(pixels)
            b.installPixels(i, pixels, i.width * 4)
            next = 1 - next
            if (displayWidth > 0 && displayHeight > 0) displayAspect = displayWidth.toFloat() / displayHeight
            if (frameAspect != displayAspect) frameAspect = displayAspect
            frame = b.asComposeImageBitmap()
        }

        override fun unlock(mediaPlayer: MediaPlayer) {}
    }

    companion object {
        private const val MAX_RETRIES = 4
        private const val LIVE_CACHE_MS = 2000
        private const val VOD_CACHE_MS = 3000
        private const val MAX_WIDTH = 1920
    }
}
