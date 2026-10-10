package io.github.sardinemehico.iptvplayer.data.source

import io.github.sardinemehico.iptvplayer.data.model.Category
import io.github.sardinemehico.iptvplayer.data.model.ContentType
import io.github.sardinemehico.iptvplayer.data.model.Entry
import java.io.Reader

/** Account details from player_api.php with no action. */
data class XtreamAccount(
    val authenticated: Boolean,
    val status: String?,
    /** Unix seconds, or null for no expiry. */
    val expiresAt: Long?,
    val isTrial: Boolean,
    val activeConnections: Int,
    val maxConnections: Int,
    val allowedFormats: List<String>,
    val serverTimezone: String?,
)

data class EpgListing(
    val title: String,
    val description: String?,
    val startEpoch: Long,
    val stopEpoch: Long,
    val hasArchive: Boolean,
)

data class Episode(
    val id: String,
    val season: Int,
    val number: Int,
    val title: String,
    val containerExt: String?,
    val plot: String?,
    val image: String?,
    val durationSecs: Int,
)

/** Details shown on a movie or series page (get_vod_info / get_series_info "info"). All optional. */
data class VodInfo(
    val plot: String? = null,
    val genre: String? = null,
    val released: String? = null,
    val duration: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val rating: String? = null,
    val image: String? = null,
    /** Movies only: the file extension the panel serves, e.g. "mkv". */
    val containerExt: String? = null,
    /** YouTube video id (or link) of the trailer, when the panel has one. */
    val trailer: String? = null,
    /** TMDB / IMDb ids, when the panel has them: used to find online subtitles. */
    val tmdbId: String? = null,
    val imdbId: String? = null,
)

/**
 * Streaming parsers for Xtream JSON responses. Every field is optional and read leniently:
 * panels disagree on types (numbers vs strings) and on which fields exist at all.
 * List parsers call [onEach] per item instead of building a list, so 50k items cost no memory.
 */
object XtreamParser {

    fun parseAccount(reader: Reader): XtreamAccount {
        var auth = false
        var status: String? = null
        var exp: Long? = null
        var trial = false
        var active = 0
        var max = 0
        val formats = ArrayList<String>()
        var tz: String? = null

        JsonPull(reader).use { json ->
            if (json.peek() != JsonPull.Token.BEGIN_OBJECT) {
                json.skipValue()
                return XtreamAccount(false, null, null, false, 0, 0, emptyList(), null)
            }
            json.beginObject()
            while (json.hasNext()) {
                when (json.nextName()) {
                    "user_info" -> json.obj { name ->
                        when (name) {
                            "auth" -> auth = json.nextLongOrNull() == 1L
                            "status" -> status = json.nextStringOrNull()
                            "exp_date" -> exp = json.nextLongOrNull()
                            "is_trial" -> trial = json.nextLongOrNull() == 1L
                            "active_cons" -> active = json.nextLongOrNull()?.toInt() ?: 0
                            "max_connections" -> max = json.nextLongOrNull()?.toInt() ?: 0
                            "allowed_output_formats" -> json.arr { json.nextStringOrNull()?.let(formats::add) }
                            else -> json.skipValue()
                        }
                    }
                    "server_info" -> json.obj { name ->
                        if (name == "timezone") tz = json.nextStringOrNull() else json.skipValue()
                    }
                    else -> json.skipValue()
                }
            }
        }
        return XtreamAccount(auth, status, exp?.takeIf { it > 0 }, trial, active, max, formats, tz)
    }

    fun parseCategories(reader: Reader, type: ContentType, onEach: (Category) -> Unit) {
        JsonPull(reader).use { json ->
            json.eachObject {
                var id: String? = null
                var name: String? = null
                json.fields { field ->
                    when (field) {
                        "category_id" -> id = json.nextStringOrNull()
                        "category_name" -> name = json.nextStringOrNull()
                        else -> json.skipValue()
                    }
                }
                val i = id
                if (i != null) onEach(Category(i, name?.trim().orEmpty().ifEmpty { i }, type))
            }
        }
    }

    fun parseLiveStreams(reader: Reader, onEach: (Entry) -> Unit) = parseEntries(reader, ContentType.LIVE, onEach)

    fun parseVodStreams(reader: Reader, onEach: (Entry) -> Unit) = parseEntries(reader, ContentType.MOVIE, onEach)

    fun parseSeries(reader: Reader, onEach: (Entry) -> Unit) = parseEntries(reader, ContentType.SERIES, onEach)

    private fun parseEntries(reader: Reader, type: ContentType, onEach: (Entry) -> Unit) {
        JsonPull(reader).use { json ->
            var order = 0
            json.eachObject {
                var id: String? = null
                var name: String? = null
                var category: String? = null
                var logo: String? = null
                var epgId: String? = null
                var archive = false
                var archiveDays = 0
                var ext: String? = null
                var rating: String? = null
                var rating5: String? = null
                var plot: String? = null
                var added = 0L
                var adult = false
                var cast: String? = null
                var year: String? = null
                json.fields { field ->
                    when (field) {
                        "stream_id", "series_id" -> id = json.nextStringOrNull()
                        "name", "title" -> { val v = json.nextStringOrNull(); if (name == null) name = v }
                        "category_id" -> category = json.nextStringOrNull()
                        "stream_icon", "cover" -> { val v = json.nextStringOrNull(); if (logo.isNullOrEmpty()) logo = v }
                        "epg_channel_id" -> epgId = json.nextStringOrNull()
                        "tv_archive" -> archive = json.nextLongOrNull() == 1L
                        "tv_archive_duration" -> archiveDays = json.nextLongOrNull()?.toInt() ?: 0
                        "container_extension" -> ext = json.nextStringOrNull()
                        "rating" -> rating = json.nextStringOrNull()
                        "rating_5based" -> rating5 = json.nextStringOrNull()
                        "plot" -> plot = json.nextStringOrNull()
                        "added", "last_modified" -> added = json.nextLongOrNull() ?: added
                        "is_adult" -> adult = json.nextLongOrNull() == 1L
                        "cast", "actors" -> { val v = json.nextStringOrNull(); if (cast.isNullOrBlank()) cast = v }
                        "year", "releaseDate", "release_date" -> { val v = json.nextStringOrNull(); if (year == null) year = TitleMatch.year(v) }
                        else -> json.skipValue()
                    }
                }
                val i = id ?: return@eachObject
                onEach(
                    Entry(
                        id = i,
                        type = type,
                        name = name?.trim().orEmpty().ifEmpty { i },
                        categoryId = category,
                        logo = logo?.trim()?.takeIf { it.isNotEmpty() },
                        epgId = epgId?.trim()?.takeIf { it.isNotEmpty() },
                        catchupDays = if (archive) archiveDays.coerceAtLeast(1) else 0,
                        containerExt = ext?.trim()?.takeIf { it.isNotEmpty() },
                        rating = Ratings.fromPanel(rating, rating5),
                        plot = plot?.trim()?.takeIf { it.isNotEmpty() },
                        added = added,
                        order = order++,
                        adult = adult,
                        cast = cast?.trim()?.takeIf { it.isNotEmpty() },
                        year = year,
                    ),
                )
            }
        }
    }

    /** get_short_epg and get_simple_data_table both wrap listings in {"epg_listings": [...]}. */
    fun parseEpg(reader: Reader, onEach: (EpgListing) -> Unit) {
        JsonPull(reader).use { json ->
            if (json.peek() != JsonPull.Token.BEGIN_OBJECT) { json.skipValue(); return }
            json.fields { field ->
                if (field != "epg_listings") { json.skipValue(); return@fields }
                json.eachObject {
                    var title: String? = null
                    var desc: String? = null
                    var start = 0L
                    var stop = 0L
                    var archive = false
                    json.fields { f ->
                        when (f) {
                            "title" -> title = json.nextStringOrNull()
                            "description" -> desc = json.nextStringOrNull()
                            "start_timestamp" -> start = json.nextLongOrNull() ?: 0
                            "stop_timestamp" -> stop = json.nextLongOrNull() ?: 0
                            "has_archive" -> archive = json.nextLongOrNull() == 1L
                            else -> json.skipValue()
                        }
                    }
                    val t = Base64Text.maybeDecode(title)?.trim()
                    if (!t.isNullOrEmpty() && stop > start) {
                        onEach(EpgListing(t, Base64Text.maybeDecode(desc)?.trim()?.ifEmpty { null }, start, stop, archive))
                    }
                }
            }
        }
    }

    /** get_vod_info: {"info": {...}, "movie_data": {"container_extension": ...}}. */
    fun parseVodInfo(reader: Reader): VodInfo {
        var info = VodInfo()
        var ext: String? = null
        JsonPull(reader).use { json ->
            if (json.peek() != JsonPull.Token.BEGIN_OBJECT) { json.skipValue(); return info }
            json.fields { field ->
                when (field) {
                    "info" -> info = readInfo(json)
                    "movie_data" -> json.fields { f ->
                        if (f == "container_extension") ext = json.nextStringOrNull() else json.skipValue()
                    }
                    else -> json.skipValue()
                }
            }
        }
        return info.copy(containerExt = ext?.trim()?.takeIf { it.isNotEmpty() })
    }

    /** The "info" object shared by get_vod_info and get_series_info. Panels name fields differently. */
    private fun readInfo(json: JsonPull): VodInfo {
        var plot: String? = null
        var genre: String? = null
        var released: String? = null
        var duration: String? = null
        var durationSecs = 0L
        var cast: String? = null
        var director: String? = null
        var rating: String? = null
        var rating5: String? = null
        var image: String? = null
        var trailer: String? = null
        var tmdb: String? = null
        var imdb: String? = null
        fun keep(old: String?, new: String?) = old?.takeIf { it.isNotBlank() } ?: new?.trim()?.takeIf { it.isNotEmpty() }
        json.fields { f ->
            when (f) {
                "plot", "description" -> plot = keep(plot, json.nextStringOrNull())
                "genre" -> genre = keep(genre, json.nextStringOrNull())
                "releasedate", "releaseDate", "release_date" -> released = keep(released, json.nextStringOrNull())
                "duration" -> duration = keep(duration, json.nextStringOrNull())
                "duration_secs", "episode_run_time" -> durationSecs = json.nextLongOrNull() ?: durationSecs
                "cast", "actors" -> cast = keep(cast, json.nextStringOrNull())
                "director" -> director = keep(director, json.nextStringOrNull())
                "rating" -> rating = keep(rating, json.nextStringOrNull())
                "rating_5based" -> rating5 = keep(rating5, json.nextStringOrNull())
                "movie_image", "cover_big", "cover" -> image = keep(image, json.nextStringOrNull())
                "youtube_trailer", "trailer" -> trailer = keep(trailer, json.nextStringOrNull())
                "tmdb_id", "tmdb" -> tmdb = keep(tmdb, json.nextStringOrNull())
                "imdb_id" -> imdb = keep(imdb, json.nextStringOrNull())
                else -> json.skipValue()
            }
        }
        if (duration == null && durationSecs > 0) {
            // episode_run_time is minutes, duration_secs is seconds.
            val mins = if (durationSecs > 600) durationSecs / 60 else durationSecs
            duration = if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "${mins}m"
        }
        return VodInfo(
            plot, genre, released, duration, cast, director, Ratings.fromPanel(rating, rating5), image,
            trailer = trailer?.takeIf { it != "0" },
            tmdbId = tmdb?.takeIf { it.any(Char::isDigit) && it != "0" },
            imdbId = imdb?.takeIf { it.startsWith("tt") },
        )
    }

    fun parseSeriesEpisodes(reader: Reader, onEach: (Episode) -> Unit) {
        parseSeriesInfo(reader, onEach)
    }

    /**
     * get_series_info. Episodes come as {"1": [...], "2": [...]} keyed by season on most panels,
     * or as an array of arrays on some. Returns the series' "info" block.
     */
    fun parseSeriesInfo(reader: Reader, onEach: (Episode) -> Unit): VodInfo {
        var info = VodInfo()
        JsonPull(reader).use { json ->
            if (json.peek() != JsonPull.Token.BEGIN_OBJECT) { json.skipValue(); return info }
            json.fields { field ->
                if (field == "info") { info = readInfo(json); return@fields }
                if (field != "episodes") { json.skipValue(); return@fields }
                when (json.peek()) {
                    JsonPull.Token.BEGIN_OBJECT -> json.fields { seasonKey ->
                        val season = seasonKey.toIntOrNull() ?: 0
                        json.eachObject { readEpisode(json, season, onEach) }
                    }
                    JsonPull.Token.BEGIN_ARRAY -> json.arr {
                        if (json.peek() == JsonPull.Token.BEGIN_ARRAY) {
                            json.eachObject { readEpisode(json, 0, onEach) }
                        } else {
                            json.skipValue()
                        }
                    }
                    else -> json.skipValue()
                }
            }
        }
        return info
    }

    private fun readEpisode(json: JsonPull, seasonFromKey: Int, onEach: (Episode) -> Unit) {
        var id: String? = null
        var season = seasonFromKey
        var number = 0
        var title: String? = null
        var ext: String? = null
        var plot: String? = null
        var image: String? = null
        var duration = 0
        json.fields { field ->
            when (field) {
                "id" -> id = json.nextStringOrNull()
                "season" -> season = json.nextLongOrNull()?.toInt() ?: season
                "episode_num" -> number = json.nextLongOrNull()?.toInt() ?: 0
                "title" -> title = json.nextStringOrNull()
                "container_extension" -> ext = json.nextStringOrNull()
                "info" -> {
                    if (json.peek() == JsonPull.Token.BEGIN_OBJECT) {
                        json.fields { f ->
                            when (f) {
                                "plot" -> plot = json.nextStringOrNull()
                                "movie_image" -> image = json.nextStringOrNull()
                                "duration_secs" -> duration = json.nextLongOrNull()?.toInt() ?: 0
                                else -> json.skipValue()
                            }
                        }
                    } else {
                        json.skipValue()
                    }
                }
                else -> json.skipValue()
            }
        }
        val i = id ?: return
        onEach(
            Episode(
                id = i,
                season = season,
                number = number,
                title = title?.trim().orEmpty().ifEmpty { "Episode $number" },
                containerExt = ext?.trim()?.takeIf { it.isNotEmpty() },
                plot = plot?.trim()?.takeIf { it.isNotEmpty() },
                image = image?.trim()?.takeIf { it.isNotEmpty() },
                durationSecs = duration,
            ),
        )
    }

    // ---- small helpers over JsonPull ----

    /** Reads an object's fields; values must be consumed by [onField]. Skips non-objects. */
    private inline fun JsonPull.fields(onField: (String) -> Unit) {
        if (peek() != JsonPull.Token.BEGIN_OBJECT) { skipValue(); return }
        beginObject()
        while (hasNext()) onField(nextName())
        endObject()
    }

    private inline fun JsonPull.obj(onField: (String) -> Unit) = fields(onField)

    /** Reads each element of an array; elements must be consumed by [onElement]. Skips non-arrays. */
    private inline fun JsonPull.arr(onElement: () -> Unit) {
        if (peek() != JsonPull.Token.BEGIN_ARRAY) { skipValue(); return }
        beginArray()
        while (hasNext()) onElement()
        endArray()
    }

    /**
     * Calls [onObject] for each object in an array; the object itself must be read with [fields].
     * Non-object elements are skipped. A non-array value (panels return {} or false on errors) is skipped.
     */
    private inline fun JsonPull.eachObject(onObject: () -> Unit) {
        arr {
            if (peek() == JsonPull.Token.BEGIN_OBJECT) onObject() else skipValue()
        }
    }
}
