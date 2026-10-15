package com.worldtv.desktop.spotlight

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.worldtv.desktop.AppGraph
import com.worldtv.desktop.Paths
import com.worldtv.iptvplayer.AutoRefresh
import com.worldtv.iptvplayer.data.spotlight.Slide
import com.worldtv.iptvplayer.data.spotlight.SpotlightPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.FloatBuffer
import java.security.MessageDigest
import javax.imageio.ImageIO

/**
 * The home screen slider on the desktop: the same picks and rules as the TV app (shared
 * SpotlightPicker: 5 recent movies then 3 series, family-rated, text-free backdrops), and the
 * same image check, here the ONNX copy of the model run with ONNX Runtime. Built once a day
 * (after the 3 am reload, or when Home shows and it is due), never while something plays.
 */
class DesktopSpotlight(private val graph: AppGraph) {

    private val prefs get() = graph.prefs
    private val dir = File(Paths.data, "spotlight")
    private val modelFile = File(Paths.data, "models/nsfw-mobilenet-v2.onnx")

    val slides = MutableStateFlow(load())

    @Volatile private var job: Job? = null

    fun imageFile(slide: Slide) = File(dir, slide.image)

    private fun load(): List<Slide> {
        val f = File(dir, "slides.json")
        if (!f.exists() || prefs.spotlightPlaylist != prefs.activePlaylist) return emptyList()
        return Slide.listFromJson(runCatching { f.readText() }.getOrDefault("[]"))
            .filter { it.key !in prefs.spotlightHidden && File(dir, it.image).exists() }
    }

    fun refreshIfDue() {
        val playlist = prefs.activePlaylist
        if (playlist < 0) return
        if (prefs.spotlightPlaylist != playlist) slides.value = emptyList() else if (slides.value.isEmpty()) slides.value = load()
        val now = System.currentTimeMillis()
        val built = prefs.spotlightBuiltAt.takeIf { prefs.spotlightPlaylist == playlist } ?: 0
        val due = built < AutoRefresh.lastNight(now, java.util.TimeZone.getDefault())
        if (!due || now - prefs.spotlightAttemptAt < RETRY_MS) return
        build()
    }

    fun onSynced(playlistId: Long) {
        if (playlistId != prefs.activePlaylist) return
        prefs.spotlightAttemptAt = 0
        prefs.spotlightBuiltAt = 0
        refreshIfDue()
    }

    /** Settings > Backdrop region changed: picked again now. */
    fun regionsChanged() {
        prefs.spotlightAttemptAt = 0
        prefs.spotlightBuiltAt = 0
        refreshIfDue()
    }

    fun hide(slide: Slide) {
        prefs.spotlightHidden = prefs.spotlightHidden + slide.key
        slides.value = slides.value.filter { it.key != slide.key }
        prefs.spotlightAttemptAt = 0
        prefs.spotlightBuiltAt = 0
        refreshIfDue()
    }

    private fun build() {
        if (job?.isActive == true || graph.player.url != null) return
        prefs.spotlightAttemptAt = System.currentTimeMillis()
        job = graph.appScope.launch {
            try {
                withContext(Dispatchers.IO) { buildNow() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.w(TAG, "build failed", e)
            }
        }
    }

    private suspend fun buildNow() {
        val playlist = prefs.activePlaylist
        val started = System.currentTimeMillis()
        val model = ensureModel()
        if (model == null) {
            android.util.Log.w(TAG, "no image check model: nothing shown")
            return
        }
        val check = ImageCheck(model)
        val staging = File(Paths.data, "spotlight.new").apply { deleteRecursively(); mkdirs() }
        val picked = try {
            SpotlightPicker(
                tmdb = graph.tmdb,
                repo = graph.repo,
                fetch = ::download,
                safe = check::safe,
                store = { name, bytes -> File(staging, name).writeBytes(bytes) },
                log = { android.util.Log.i(TAG, it) },
            ).pick(playlist, prefs.spotlightHidden, com.worldtv.iptvplayer.data.spotlight.SliderRegions.of(prefs.spotlightRegions))
        } finally {
            check.close()
        }
        if (playlist != prefs.activePlaylist) return
        File(staging, "slides.json").writeText(Slide.listToJson(picked))
        dir.deleteRecursively()
        staging.renameTo(dir)
        prefs.spotlightPlaylist = playlist
        prefs.spotlightBuiltAt = System.currentTimeMillis()
        slides.value = load()
        android.util.Log.i(TAG, "${picked.size} slides in ${System.currentTimeMillis() - started} ms")
    }

    private fun download(url: String): ByteArray? = runCatching {
        graph.http.newCall(Request.Builder().url(url).header("User-Agent", graph.userAgent).build()).execute()
            .use { r -> if (r.isSuccessful) r.body?.bytes() else null }
    }.getOrNull()

    private fun ensureModel(): File? {
        if (modelFile.exists() && sha256(modelFile.readBytes()) == MODEL_SHA256) return modelFile
        val bytes = download(System.getenv("WORLDTV_MODEL_URL") ?: MODEL_URL) ?: return null
        if (sha256(bytes) != MODEL_SHA256) {
            android.util.Log.w(TAG, "model download corrupt")
            return null
        }
        modelFile.parentFile?.mkdirs()
        val tmp = File(modelFile.path + ".tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(modelFile)
        return modelFile
    }

    /** GantMan's nsfw_model (MIT) on three square crops of the backdrop; the highest score of each class counts. */
    private class ImageCheck(model: File) : AutoCloseable {
        private val env = OrtEnvironment.getEnvironment()
        private val session: OrtSession = env.createSession(model.path, OrtSession.SessionOptions())
        private val inputName = session.inputNames.first()

        fun scores(bytes: ByteArray): FloatArray? {
            val img = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull() ?: return null
            val side = minOf(img.width, img.height)
            val worst = FloatArray(5)
            for (x in listOf(0, (img.width - side) / 2, img.width - side)) {
                val small = BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB)
                val g = small.createGraphics()
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g.drawImage(img, 0, 0, SIZE, SIZE, x, 0, x + side, side, null)
                g.dispose()
                val data = FloatArray(SIZE * SIZE * 3)
                var k = 0
                for (y in 0 until SIZE) for (xx in 0 until SIZE) {
                    val p = small.getRGB(xx, y)
                    data[k++] = ((p shr 16) and 0xff) / 255f
                    data[k++] = ((p shr 8) and 0xff) / 255f
                    data[k++] = (p and 0xff) / 255f
                }
                OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, SIZE.toLong(), SIZE.toLong(), 3)).use { t ->
                    session.run(mapOf(inputName to t)).use { r ->
                        @Suppress("UNCHECKED_CAST")
                        val out = (r[0].value as Array<FloatArray>)[0]
                        for (i in 0 until 5) worst[i] = maxOf(worst[i], out[i])
                    }
                }
            }
            return worst
        }

        /** As strict as the TV app's. */
        fun safe(bytes: ByteArray): Boolean {
            val s = scores(bytes) ?: return false
            return s[1] <= 0.25f && s[3] <= 0.05f && s[4] <= 0.06f
        }

        override fun close() = session.close()

        companion object {
            const val SIZE = 224
        }
    }

    companion object {
        private const val TAG = "WorldTV.Spotlight"
        private const val RETRY_MS = 60 * 60 * 1000L
        const val MODEL_URL = "https://raw.githubusercontent.com/uri-travoski/iptv-player/main/models/nsfw-mobilenet-v2.onnx"
        const val MODEL_SHA256 = "2696c7758069dc3504b7dae4e8f62f4bb4989abfad0020509248aedeb006909e"

        private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    }
}
