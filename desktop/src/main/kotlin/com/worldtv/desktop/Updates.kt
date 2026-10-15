package com.worldtv.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.worldtv.desktop.ui.Toaster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray

/**
 * Settings > Check for updates: the newest WorldTV release on GitHub with Linux packages.
 * The desktop app doesn't install anything itself (the system's package manager, Flatpak or a
 * new AppImage does): it says a new version is out and opens its download page.
 */
object Updates {
    const val PAGE = "https://github.com/uri-travoski/iptv-player/releases"
    private const val API = "https://api.github.com/repos/uri-travoski/iptv-player/releases?per_page=10"

    data class Release(val version: String, val page: String)

    /** A newer release, once found (Home shows it). */
    var available by mutableStateOf<Release?>(null)
        private set

    suspend fun checkAtStart(graph: AppGraph, toaster: Toaster) {
        if (!graph.prefs.autoUpdate) return
        if (System.currentTimeMillis() - graph.prefs.lastUpdateCheck < 6 * 3600_000L) return
        val r = runCatching { newest(graph) }.getOrNull() ?: return
        graph.prefs.lastUpdateCheck = System.currentTimeMillis()
        if (r.version == graph.prefs.skippedUpdate) return
        toaster.show("WorldTV ${r.version} is available: Settings › Check for updates")
    }

    /** The newest release with Linux packages, when it is newer than this one; null if none. */
    suspend fun newest(graph: AppGraph): Release? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(API).header("User-Agent", graph.userAgent).header("Accept", "application/vnd.github+json").build()
        graph.http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("GitHub answered ${resp.code}")
            val list = JSONArray(resp.body!!.string())
            var best: Release? = null
            for (i in 0 until list.length()) {
                val o = list.getJSONObject(i)
                if (o.optBoolean("draft")) continue
                val tag = o.optString("tag_name").trim().removePrefix("v")
                val assets = o.optJSONArray("assets") ?: continue
                val hasLinux = (0 until assets.length()).any {
                    val n = assets.getJSONObject(it).optString("name")
                    n.endsWith(".deb") || n.endsWith(".rpm") || n.endsWith(".AppImage") || n.endsWith(".flatpak")
                }
                if (!hasLinux || tag.isEmpty()) continue
                if (best == null || com.worldtv.iptvplayer.data.update.Updates.isNewer(tag, best.version)) {
                    best = Release(tag, o.optString("html_url").ifEmpty { PAGE })
                }
            }
            best?.takeIf { com.worldtv.iptvplayer.data.update.Updates.isNewer(it.version, BuildInfo.VERSION) }.also { available = it }
        }
    }

    /** Opens [url] in the browser (xdg-open works inside Flatpak too, through its portal). */
    fun open(url: String) {
        runCatching { ProcessBuilder("xdg-open", url).start() }.onFailure {
            runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
        }
    }
}
