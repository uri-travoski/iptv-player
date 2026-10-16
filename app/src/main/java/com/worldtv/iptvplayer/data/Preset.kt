package com.worldtv.iptvplayer.data

import android.content.Context
import com.worldtv.iptvplayer.AppGraph
import com.worldtv.iptvplayer.Prefs
import com.worldtv.iptvplayer.data.model.ContentType
import com.worldtv.iptvplayer.data.repo.Pin
import com.worldtv.iptvplayer.data.repo.Playlist
import org.json.JSONObject

/**
 * A set-up-for-you copy of the app (made by hand for a family member, never published) carries
 * `assets/preset.json`; the normal app has none and this does nothing. On the very first start
 * it stands in for Free TV and the TV / Mobile question: the playlist with its PIN, TV layout,
 * update check and theme. Once that playlist has loaded, the categories to check and the ones
 * to put on top are found by name (category ids are the provider's, names are what was picked).
 * Same package and key as the normal app, so updates install over it and keep the setup.
 */
object Preset {
    private const val FILE = "preset.json"
    private const val PENDING = "preset_pending_playlist"

    private fun read(context: Context): JSONObject? =
        runCatching { context.assets.open(FILE).use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) } }.getOrNull()

    /** First start only (no playlist, Free TV never added): true when a preset was applied. */
    suspend fun applyAtFirstStart(context: Context, graph: AppGraph): Boolean {
        val prefs = graph.prefs
        if (prefs.defaultPlaylistAdded || graph.repo.playlists().isNotEmpty()) return false
        val j = read(context) ?: return false
        val p = j.getJSONObject("playlist")
        val pin = p.optString("pin").takeIf { Pin.isValid(it) }
        val id = graph.repo.addPlaylist(
            p.getString("name"),
            p.optString("kind", Playlist.KIND_XTREAM),
            p.getString("url"),
            p.optString("username").ifEmpty { null },
            p.optString("password").ifEmpty { null },
            pin?.let { Pin.hash(it) },
        )
        prefs.activePlaylist = id
        // Free TV is never added, and doesn't come back.
        prefs.defaultPlaylistAdded = true
        prefs.uiMode = if (j.optString("layout") == Prefs.UI_MOBILE) Prefs.UI_MOBILE else Prefs.UI_TV
        if (j.has("checkUpdates")) prefs.autoUpdate = j.getBoolean("checkUpdates")
        if (j.has("autoStart")) prefs.autoStart = j.getBoolean("autoStart")
        j.optString("theme").takeIf { it.isNotEmpty() }?.let { prefs.theme = it }
        if (j.has("checkCategories") || j.has("topCategories")) prefs.raw.edit().putLong(PENDING, id).apply()
        return true
    }

    /** After a load: the first one of the preset playlist picks its categories. True when it did. */
    suspend fun afterSync(context: Context, graph: AppGraph, playlistId: Long): Boolean {
        val sp = graph.prefs.raw
        if (sp.getLong(PENDING, -1) != playlistId) return false
        val j = read(context) ?: return false
        val repo = graph.repo
        val key = { s: String -> s.trim().lowercase() }

        j.optJSONArray("checkCategories")?.let { arr ->
            val wanted = (0 until arr.length()).map { key(arr.getString(it)) }.toSet()
            val ids = repo.categories(playlistId, ContentType.LIVE).filter { key(it.name) in wanted }.map { it.key }
            if (ids.isNotEmpty()) repo.setCheckCategories(playlistId, ids)
        }
        j.optJSONObject("topCategories")?.let { top ->
            for ((field, type) in listOf("live" to ContentType.LIVE, "movies" to ContentType.MOVIE, "series" to ContentType.SERIES)) {
                val arr = top.optJSONArray(field) ?: continue
                var place = 0
                for (i in 0 until arr.length()) {
                    val from = repo.categoryOrder(playlistId, type).indexOfFirst { key(it.name) == key(arr.getString(i)) }
                    if (from < 0) continue
                    repo.moveCategory(playlistId, type, from, place)
                    place++
                }
            }
        }
        sp.edit().remove(PENDING).apply()
        return true
    }
}
