package com.worldtv.iptvplayer.data.check

import com.worldtv.iptvplayer.data.source.XtreamParser
import com.worldtv.iptvplayer.data.source.XtreamUrls
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Tells whether one channel works (Settings > Channel check): opens the stream, reads it for a
 * few seconds and measures it with [TsStats]. HLS: the newest segment is measured. One check at
 * a time. Pure Kotlin + OkHttp, shared with the desktop app.
 */
class ChannelProbe(http: OkHttpClient, private val userAgent: String) {

    private val probeHttp: OkHttpClient = http.newBuilder()
        // A new connection per check (an old one may already be closed by the panel), closed after.
        .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.SECONDS))
        .followRedirects(false) // followed by hand: the panel answering tells a dead channel from no network
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(READ_MS + 1000, TimeUnit.MILLISECONDS)
        .build()

    @Volatile private var call: Call? = null

    /** The last check let go of its connection for playback ([yieldNow]). */
    @Volatile var yielded = false
        private set

    /** Why the last check failed, for the log. */
    var note = ""
        private set

    /** The last check was turned away (blocked, flood limit, server error), not a dead channel. */
    var refused = false
        private set

    /** Playback is starting on the same server: the running check lets go of the connection now. */
    fun yieldNow() {
        yielded = true
        call?.cancel()
    }

    /** Stops the running check (it ends as [CheckResult.UNKNOWN] or not working). */
    fun cancel() {
        call?.cancel()
    }

    private fun now() = System.nanoTime() / 1_000_000

    fun accountFull(urls: XtreamUrls): Boolean = try {
        val req = Request.Builder().url(urls.api()).header("User-Agent", userAgent).build()
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
    fun probe(url: String): CheckResult {
        yielded = false
        refused = false
        note = ""
        var target = url
        repeat(MAX_REDIRECTS) { hop ->
            val c = probeHttp.newCall(
                Request.Builder().url(target).header("User-Agent", userAgent).header("Connection", "close").build(),
            )
            c.timeout().timeout(CALL_MS, TimeUnit.MILLISECONDS)
            call = c
            if (yielded) c.cancel()
            val response: Response = try {
                c.execute()
            } catch (e: IOException) {
                note = " (${e.javaClass.simpleName}: ${e.message})"
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
                    note = " (HTTP ${r.code})"
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
            startedAt = now()
            stats.feed(buf, first)
            total += first
            while (now() - startedAt < READ_MS) {
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
        note = " (%.1f s, %.0f kbit/s, %d KB%s)".format(java.util.Locale.US, stats.seconds, stats.kbps, total / 1024, if (stats.isTs) "" else ", not TS")
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
            .newCall(Request.Builder().url(url).header("User-Agent", userAgent).build())
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

    companion object {
        /** Reading time per channel once data flows. */
        const val READ_MS = 3500L

        /** Enough measured media to stop reading early (saves data). */
        private const val ENOUGH_SECONDS = 2.0

        /** A filler sends minutes at once: this much of it settles it. */
        private const val FILLER_SECONDS = 6.0

        /** At most this much downloaded per channel. */
        private const val MAX_BYTES = 3L * 1024 * 1024

        private const val CALL_MS = 9000L

        /** Answers that turn a client away rather than say a channel is dead. */
        private val REFUSED = setOf(401, 403, 429, 456, 458)
        private const val MAX_REDIRECTS = 4
    }
}
