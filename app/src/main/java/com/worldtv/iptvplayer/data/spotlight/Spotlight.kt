package com.worldtv.iptvplayer.data.spotlight

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.worldtv.iptvplayer.AutoRefresh
import com.worldtv.iptvplayer.Prefs
import com.worldtv.iptvplayer.data.online.Tmdb
import com.worldtv.iptvplayer.data.repo.Repository
import com.worldtv.iptvplayer.data.telemetry.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * The home screen slider (TV layout): 5 recent movies and 3 recent series of the active playlist,
 * with large backdrops. Built once a day (after the 3 am reload, or when Home shows and it is
 * due) on one low-priority thread, never while something plays; Home only reads the stored
 * images, so it costs nothing at start.
 *
 * Every backdrop passes an on-device image check first (a MobileNet classifier, downloaded once
 * from models/ in the app's GitHub repository, ~5 MB): anything scored as sexy, nude or explicit, even a
 * little, is not shown. With no model (no download yet), nothing is shown.
 */
class Spotlight(
    private val app: Context,
    private val http: OkHttpClient,
    private val tmdb: Tmdb,
    private val repo: Repository,
    private val prefs: Prefs,
    private val telemetry: Telemetry,
    private val scope: CoroutineScope,
    /** True while WorldTV plays something: no build then. */
    private val playing: () -> Boolean,
) {

    private val dir = File(app.filesDir, "spotlight")
    private val modelFile = File(app.filesDir, "models/nsfw-mobilenet-v2-int8.tflite")

    /** The slides on show, with their playlist; empty when none are ready. */
    val slides = MutableStateFlow(load())

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "spotlight")
    }.asCoroutineDispatcher()

    @Volatile private var job: Job? = null

    fun imageFile(slide: Slide) = File(dir, slide.image)

    private fun load(): List<Slide> {
        val f = File(dir, "slides.json")
        if (!f.exists() || prefs.spotlightPlaylist != prefs.activePlaylist) return emptyList()
        return Slide.listFromJson(runCatching { f.readText() }.getOrDefault("[]"))
            .filter { it.key !in prefs.spotlightHidden && File(dir, it.image).exists() }
    }

    /** Home shows: builds when due (none for this playlist yet, or not since the last 3 am). */
    fun refreshIfDue() {
        val playlist = prefs.activePlaylist
        if (playlist < 0) return
        if (prefs.spotlightPlaylist != playlist) slides.value = emptyList()
        val now = System.currentTimeMillis()
        val built = prefs.spotlightBuiltAt.takeIf { prefs.spotlightPlaylist == playlist } ?: 0
        val due = built < AutoRefresh.lastNight(now, java.util.TimeZone.getDefault())
        // A failed or skipped build is tried again an hour later, not on every visit.
        if (!due || now - prefs.spotlightAttemptAt < RETRY_MS) return
        build()
    }

    /** After a playlist load (the 3 am one too): its titles may have changed. */
    fun onSynced(playlistId: Long) {
        if (playlistId != prefs.activePlaylist) return
        prefs.spotlightAttemptAt = 0
        prefs.spotlightBuiltAt = 0
        refreshIfDue()
    }

    /** Settings > Backdrop region changed: picked again now (Home keeps the old slides meanwhile). */
    fun regionsChanged() {
        prefs.spotlightAttemptAt = 0
        prefs.spotlightBuiltAt = 0
        refreshIfDue()
    }

    /** Home > Menu on More info > Hide (behind the playlist PIN): gone now, and from later builds. */
    fun hide(slide: Slide) {
        prefs.spotlightHidden = prefs.spotlightHidden + slide.key
        slides.value = slides.value.filter { it.key != slide.key }
        prefs.spotlightAttemptAt = 0
        prefs.spotlightBuiltAt = 0
        refreshIfDue() // a replacement
    }

    private fun build() {
        if (job?.isActive == true) return
        if (playing()) {
            Log.i(TAG, "something is playing: later")
            return
        }
        prefs.spotlightAttemptAt = System.currentTimeMillis()
        job = scope.launch {
            try {
                withContext(worker) { buildNow() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "build failed", e)
            }
        }
    }

    private suspend fun buildNow() {
        val playlist = prefs.activePlaylist
        val started = System.currentTimeMillis()
        val model = ensureModel()
        if (model == null) {
            Log.w(TAG, "no image check model: nothing shown")
            return
        }
        // The interpreter is native code (ARM builds only): no slider where it can't load.
        val check = try {
            ImageCheck.open(model)
        } catch (e: Throwable) {
            Log.w(TAG, "image check unavailable: nothing shown", e)
            return
        }
        val staging = File(app.filesDir, "spotlight.new").apply { deleteRecursively(); mkdirs() }
        val width = app.resources.displayMetrics.widthPixels.coerceIn(1280, 1920)
        val picked = try {
            SpotlightPicker(
                tmdb = tmdb,
                repo = repo,
                fetch = ::download,
                safe = check::safe,
                store = { name, bytes -> saveScaled(bytes, File(staging, name), width) },
                log = { Log.i(TAG, it) },
            ).pick(playlist, prefs.spotlightHidden, SliderRegions.of(prefs.spotlightRegions))
        } finally {
            check.close()
        }
        if (playlist != prefs.activePlaylist) return // switched meanwhile: the next visit builds again
        File(staging, "slides.json").writeText(Slide.listToJson(picked))
        dir.deleteRecursively()
        staging.renameTo(dir)
        prefs.spotlightPlaylist = playlist
        prefs.spotlightBuiltAt = System.currentTimeMillis()
        slides.value = load()
        val ms = System.currentTimeMillis() - started
        Log.i(TAG, "${picked.size} slides in $ms ms")
        telemetry.event(
            "spotlight",
            "built",
            mapOf("movies" to picked.count { it.type.name == "MOVIE" }, "series" to picked.count { it.type.name == "SERIES" }, "ms" to ms),
        )
    }

    private fun download(url: String): ByteArray? = runCatching {
        http.newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
    }.getOrNull()

    /** The classifier, downloaded once and checked against its SHA-256. */
    private fun ensureModel(): File? {
        if (modelFile.exists() && sha256(modelFile) == MODEL_SHA256) return modelFile
        val bytes = download(app.getString(com.worldtv.iptvplayer.R.string.spotlight_model_url)) ?: return null
        if (sha256(bytes) != MODEL_SHA256) {
            Log.w(TAG, "model download corrupt")
            return null
        }
        modelFile.parentFile?.mkdirs()
        val tmp = File(modelFile.path + ".tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(modelFile)
        return modelFile
    }

    /** Backdrop at the screen's width (TV: 1280–1920 px), JPEG: Home decodes it at the size shown. */
    private fun saveScaled(bytes: ByteArray, to: File, width: Int) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= width) {
            to.writeBytes(bytes)
            return
        }
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
        val scaled = Bitmap.createScaledBitmap(src, width, src.height * width / src.width, true)
        to.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        if (scaled !== src) scaled.recycle()
        src.recycle()
    }

    /**
     * The image check: GantMan's nsfw_model (MobileNet v2, MIT), int8, run on three square crops
     * (left, middle, right) of the wide backdrop; the highest score of each class counts.
     */
    class ImageCheck private constructor(private val interpreter: org.tensorflow.lite.Interpreter) : AutoCloseable {

        private val input = ByteBuffer.allocateDirect(SIZE * SIZE * 3 * 4).order(ByteOrder.nativeOrder())
        private val pixels = IntArray(SIZE * SIZE)
        private val output = Array(1) { FloatArray(5) }

        /** Classes: drawings, hentai, neutral, porn, sexy. */
        fun scores(bytes: ByteArray): FloatArray? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outHeight / (sample * 2) >= SIZE * 2) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val side = minOf(bmp.width, bmp.height)
            val worst = FloatArray(5)
            try {
                for (x in listOf(0, (bmp.width - side) / 2, bmp.width - side)) {
                    val crop = Bitmap.createBitmap(bmp, x, 0, side, side)
                    val small = Bitmap.createScaledBitmap(crop, SIZE, SIZE, true)
                    small.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
                    if (small !== crop) small.recycle()
                    if (crop !== bmp) crop.recycle()
                    input.rewind()
                    for (p in pixels) {
                        input.putFloat(((p shr 16) and 0xff) / 255f)
                        input.putFloat(((p shr 8) and 0xff) / 255f)
                        input.putFloat((p and 0xff) / 255f)
                    }
                    input.rewind()
                    interpreter.run(input, output)
                    for (i in 0 until 5) worst[i] = maxOf(worst[i], output[0][i])
                }
            } finally {
                bmp.recycle()
            }
            return worst
        }

        /** Strict, for the family TV: hardly a trace of sexy, nude or explicit. */
        fun safe(bytes: ByteArray): Boolean {
            val s = scores(bytes) ?: return false
            return s[HENTAI] <= 0.25f && s[PORN] <= 0.05f && s[SEXY] <= 0.06f
        }

        override fun close() = interpreter.close()

        companion object {
            private const val SIZE = 224
            private const val HENTAI = 1
            private const val PORN = 3
            private const val SEXY = 4

            fun open(model: File): ImageCheck =
                ImageCheck(org.tensorflow.lite.Interpreter(model, org.tensorflow.lite.Interpreter.Options().setNumThreads(2)))
        }
    }

    companion object {
        private const val TAG = "WorldTV.Spotlight"
        private const val RETRY_MS = 60 * 60 * 1000L
        const val MODEL_SHA256 = "7fc0b9d3ab6f419dba6d50219899f5c550fefc5f07fab88949654b09947f2dea"

        private fun sha256(f: File) = sha256(f.readBytes())

        private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    }
}
