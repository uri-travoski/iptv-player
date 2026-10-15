package com.worldtv.iptvplayer.data.spotlight

import com.worldtv.iptvplayer.data.model.ContentType
import com.worldtv.iptvplayer.data.online.Tmdb
import com.worldtv.iptvplayer.data.repo.Repository
import com.worldtv.iptvplayer.data.source.TitleMatch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** One title of the home screen slider: what it shows, and the playlist entry More info opens. */
data class Slide(
    val type: ContentType,
    val itemId: String,
    val tmdbId: Long,
    val title: String,
    val year: String?,
    val genre: String?,
    val rating: String?,
    val overview: String,
    /** The backdrop's file name in the slider folder. */
    val image: String,
) {
    /** Key in the admin's "hidden from the slider" list. */
    val key get() = key(type, tmdbId)

    fun toJson(): JSONObject = JSONObject()
        .put("type", type.name).put("item", itemId).put("tmdb", tmdbId).put("title", title)
        .put("year", year).put("genre", genre).put("rating", rating).put("overview", overview).put("image", image)

    companion object {
        fun key(type: ContentType, tmdbId: Long) = (if (type == ContentType.SERIES) "tv:" else "movie:") + tmdbId

        fun fromJson(o: JSONObject): Slide? = runCatching {
            Slide(
                type = ContentType.valueOf(o.getString("type")),
                itemId = o.getString("item"),
                tmdbId = o.getLong("tmdb"),
                title = o.getString("title"),
                year = o.optString("year").takeIf { it.isNotEmpty() && it != "null" },
                genre = o.optString("genre").takeIf { it.isNotEmpty() && it != "null" },
                rating = o.optString("rating").takeIf { it.isNotEmpty() && it != "null" },
                overview = o.optString("overview"),
                image = o.getString("image"),
            )
        }.getOrNull()

        fun listToJson(list: List<Slide>): String = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

        fun listFromJson(s: String): List<Slide> = runCatching {
            val a = JSONArray(s)
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::fromJson) }
        }.getOrDefault(emptyList())
    }
}

/**
 * Picks the slider's titles: TMDB's recent, family-rated releases that the playlist has (5 movies,
 * newest first, then 3 series), each with a text-free backdrop that [safe] passes. Pure Kotlin
 * (images come and go as bytes) so the desktop app uses it too.
 *
 * [fetch] downloads an image, [safe] judges it (the on-device classifier), [store] keeps an
 * accepted one under a file name. Nothing is shown unless it passed [safe].
 */
class SpotlightPicker(
    private val tmdb: Tmdb,
    private val repo: Repository,
    private val fetch: (String) -> ByteArray?,
    private val safe: (ByteArray) -> Boolean,
    private val store: (name: String, bytes: ByteArray) -> Unit,
    private val log: (String) -> Unit = {},
) {

    suspend fun pick(playlistId: Long, hidden: Set<String>): List<Slide> {
        val movies = pickType(playlistId, ContentType.MOVIE, MOVIES, listOf(365, 730), hidden)
        val series = pickType(playlistId, ContentType.SERIES, SERIES, listOf(120, 365), hidden)
        return movies + series
    }

    private suspend fun pickType(playlistId: Long, type: ContentType, wanted: Int, windows: List<Int>, hidden: Set<String>): List<Slide> {
        val isSeries = type == ContentType.SERIES
        val out = ArrayList<Slide>()
        val tried = HashSet<Long>()
        val genres = runCatching { tmdb.genres(isSeries) }.getOrDefault(emptyMap())
        for (days in windows) {
            if (out.size >= wanted) break
            val releases = tmdb.recentReleases(isSeries, days, PAGES)
                .filter { it.id !in tried && Slide.key(type, it.id) !in hidden }
            val wantedTitles = releases.flatMap { r ->
                val year = TitleMatch.year(r.date)
                listOfNotNull(TitleMatch.Wanted(r.title, year, r), r.originalTitle?.let { TitleMatch.Wanted(it, year, r) })
            }
            val matched = repo.matchEach(playlistId, type, TitleMatch.Index(wantedTitles)).distinctBy { it.second.id }
            // Movies: the newest release first. Series: the most watched now (TMDB popularity).
            val ordered = if (isSeries) matched.sortedByDescending { it.second.popularity } else matched.sortedByDescending { it.second.date }
            log("${type.name.lowercase()}: ${releases.size} recent on TMDB (${days} days), ${matched.size} in the playlist")
            for ((row, r) in ordered) {
                if (out.size >= wanted) break
                if (!tried.add(r.id)) continue
                if (isSeries && !runCatching { tmdb.familySeries(r.id) }.getOrDefault(false)) {
                    log("${r.title}: not rated for the family, skipped")
                    continue
                }
                val image = acceptedBackdrop(type, r) ?: continue
                out += Slide(
                    type = type,
                    itemId = row.itemId,
                    tmdbId = r.id,
                    title = r.title,
                    year = TitleMatch.year(r.date),
                    genre = r.genreIds.firstNotNullOfOrNull { genres[it] },
                    rating = r.rating.takeIf { it > 0 }?.let { String.format(Locale.US, "%.1f", it) },
                    overview = r.overview,
                    image = image,
                )
            }
        }
        return out
    }

    /** The first of the title's best backdrops that passes the image check, stored; null if none does. */
    private suspend fun acceptedBackdrop(type: ContentType, r: Tmdb.Release): String? {
        val paths = runCatching { tmdb.backdrops(type == ContentType.SERIES, r.id) }.getOrDefault(emptyList())
        for (path in paths.take(BACKDROPS_TRIED)) {
            val bytes = fetch(tmdb.imageUrl(path)) ?: continue
            if (!safe(bytes)) {
                log("${r.title}: backdrop $path not shown (image check)")
                continue
            }
            val name = Slide.key(type, r.id).replace(':', '_') + ".jpg"
            store(name, bytes)
            return name
        }
        if (paths.isEmpty()) log("${r.title}: no text-free backdrop")
        return null
    }

    companion object {
        const val MOVIES = 5
        const val SERIES = 3
        private const val PAGES = 5
        private const val BACKDROPS_TRIED = 3
    }
}
