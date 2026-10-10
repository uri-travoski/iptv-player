package com.worldtv.iptvplayer.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException

/** How IPTV stream failures are told apart. Pure logic, no Android UI. */
object StreamErrors {

    /** The server the panel redirected to doesn't exist or refuses connections: retrying the same URL won't help. */
    fun isDeadServer(error: Throwable?): Boolean {
        var e = error
        while (e != null) {
            if (e is UnknownHostException || e is ConnectException || e is NoRouteToHostException) return true
            if (e is HttpDataSource.InvalidResponseCodeException && (e.responseCode == 404 || e.responseCode >= 500)) return true
            e = e.cause
        }
        return false
    }

    /**
     * The panel refused because the account's connection limit is reached: usually the previous
     * channel's connection, which the server releases a moment after a zap.
     */
    fun isConnectionLimit(error: Throwable?): Boolean {
        var e = error
        while (e != null) {
            if (e is HttpDataSource.InvalidResponseCodeException && e.responseCode in LIMIT_CODES) return true
            e = e.cause
        }
        return false
    }

    /**
     * Same Xtream live channel in the other container (.ts <-> .m3u8). Panels often send the two
     * to different servers, so one can work when the other's server is down. null if not an
     * Xtream live URL.
     */
    fun alternateFormat(url: String): String? {
        val m = XTREAM_LIVE.find(url) ?: return null
        val ext = m.groupValues[1]
        val other = if (ext.equals("ts", ignoreCase = true)) "m3u8" else "ts"
        return url.substring(0, m.groups[1]!!.range.first) + other + url.substring(m.groups[1]!!.range.last + 1)
    }

    private val LIMIT_CODES = setOf(403, 406, 429, 509)
    private val XTREAM_LIVE = Regex("""/live/[^/]+/[^/]+/[^/?#]+\.(ts|m3u8)(?=$|[?#])""", RegexOption.IGNORE_CASE)
}

/**
 * Retries tuned for live IPTV: a dead server fails at once (so the format fallback runs
 * instead of four slow retries), a connection-limit refusal retries after a short pause, and
 * everything else keeps Media3's defaults but with at most 3 attempts.
 */
@OptIn(UnstableApi::class)
class IptvLoadErrorPolicy : DefaultLoadErrorHandlingPolicy(3) {

    override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long = when {
        StreamErrors.isDeadServer(info.exception) -> C.TIME_UNSET
        StreamErrors.isConnectionLimit(info.exception) -> 700L
        else -> super.getRetryDelayMsFor(info)
    }

    override fun getMinimumLoadableRetryCount(dataType: Int) = 3
}
