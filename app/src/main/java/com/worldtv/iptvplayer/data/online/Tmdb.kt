package com.worldtv.iptvplayer.data.online

import com.worldtv.iptvplayer.Prefs
import com.worldtv.iptvplayer.data.source.TitleMatch
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale

/**
 * TMDB (themoviedb.org) with the user's own API keys (Settings > Online accounts): cast with
 * photos, rating and trailer for a movie or series page. One request per page (details, credits
 * and videos together), plus a search when the provider gives no TMDB id. When a key is refused
 * or rate-limited the next one is used; with none working, the page falls back to the provider's
 * cast names and Wikipedia photos. Results are kept for the session.
 */
class Tmdb(private val http: OkHttpClient, private val io: CoroutineDispatcher, private val prefs: Prefs) {

    /** [id]: TMDB's person id, for their filmography (null when the cast didn't come from TMDB). */
    data class CastMember(val name: String, val photo: String?, val id: Long? = null)

    /** One film or series an actor played in. */
    data class Credit(val title: String, val originalTitle: String?, val year: String?, val series: Boolean)

    data class Person(val id: Long, val name: String, val photo: String?, val credits: List<Credit>)

    data class Extras(val tmdbId: String, val rating: String?, val trailer: String?, val cast: List<CastMember>)

    val rotation = AccountRotation(OnlineService.TMDB) { prefs.accounts(OnlineService.TMDB) }

    val isSetUp get() = rotation.isSetUp

    private val cache = HashMap<String, Extras?>()
    private val people = HashMap<String, Person?>()

    /**
     * Everything [name] acted in (TMDB's combined credits: movies and TV), by their TMDB [id] when
     * the page's cast came from TMDB, else found by name. Null when TMDB doesn't know them.
     */
    suspend fun person(id: Long?, name: String): Person? = withContext(io) {
        val key = id?.toString() ?: name.lowercase()
        synchronized(people) { if (key in people) return@withContext people[key] }
        val result = rotation.run { account ->
            val pid = id ?: get(account, url("/3/search/person").addQueryParameter("query", name))
                ?.optJSONArray("results")?.optJSONObject(0)?.optLong("id")?.takeIf { it > 0 }
                ?: return@run null
            val json = get(account, url("/3/person/$pid").addQueryParameter("append_to_response", "combined_credits")) ?: return@run null
            val cast = json.optJSONObject("combined_credits")?.optJSONArray("cast")
            val credits = ArrayList<Credit>()
            if (cast != null) {
                for (i in 0 until cast.length()) {
                    val c = cast.optJSONObject(i) ?: continue
                    val series = c.optString("media_type") == "tv"
                    val title = c.optString(if (series) "name" else "title").ifEmpty { continue }
                    val original = c.optString(if (series) "original_name" else "original_title").takeIf { it.isNotEmpty() && it != title }
                    val year = TitleMatch.year(c.optString(if (series) "first_air_date" else "release_date"))
                    credits += Credit(title, original, year, series)
                }
            }
            val path = json.optString("profile_path").takeIf { it.startsWith("/") }
            Person(pid, json.optString("name").ifEmpty { name }, path?.let { "$IMAGES/w185$it" }, credits)
        }
        synchronized(people) { people[key] = result }
        result
    }

    /** Page extras by the provider's [tmdbId], else by [title] and [year]; null when TMDB doesn't know the title. */
    suspend fun extras(series: Boolean, tmdbId: String?, title: String, year: String?): Extras? = withContext(io) {
        val cacheKey = "$series|${tmdbId ?: "$title|$year"}"
        synchronized(cache) { if (cacheKey in cache) return@withContext cache[cacheKey] }
        val kind = if (series) "tv" else "movie"
        val result = rotation.run { account ->
            val id = tmdbId ?: search(account, kind, title, year) ?: return@run null
            val json = get(account, url("/3/$kind/$id").addQueryParameter("append_to_response", "credits,videos")) ?: return@run null
            parse(id, json)
        }
        synchronized(cache) { cache[cacheKey] = result }
        result
    }

    // ---- the home screen slider (data/spotlight) ----

    /** A movie or series TMDB lists as recently released. [date]: "2026-05-01" (first air date for series). */
    data class Release(
        val id: Long,
        val series: Boolean,
        val title: String,
        val originalTitle: String?,
        val date: String,
        val overview: String,
        val genreIds: List<Int>,
        val rating: Double,
        val popularity: Double,
    )

    /**
     * Recent, family-safe releases, most popular first ([pages] of 20). Movies: released in the
     * last [days], with one of [certs] in [certCountry] (TMDB lists only films that have one), no horror.
     * Series: an episode aired in the last [days]; no news, reality or talk shows. The series'
     * own rating is checked separately ([familySeries]).
     */
    suspend fun recentReleases(
        series: Boolean,
        days: Int,
        pages: Int,
        /** Original languages ("hi|ta|…"), null for any. */
        languages: List<String>? = null,
        /** Movies: whose certificates count (default the US, PG-13 or lower). */
        certCountry: String = "US",
        certs: List<String> = listOf("G", "PG", "PG-13"),
    ): List<Release> = withContext(io) {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val today = f.format(java.util.Date())
        val since = f.format(java.util.Date(System.currentTimeMillis() - days * 86_400_000L))
        val out = ArrayList<Release>()
        rotation.run { account ->
            out.clear()
            for (page in 1..pages) {
                val u = url(if (series) "/3/discover/tv" else "/3/discover/movie")
                    .addQueryParameter("include_adult", "false")
                    .addQueryParameter("sort_by", "popularity.desc")
                    .addQueryParameter("page", page.toString())
                if (languages != null) u.addQueryParameter("with_original_language", languages.joinToString("|"))
                if (series) {
                    u.addQueryParameter("air_date.gte", since)
                        .addQueryParameter("air_date.lte", today)
                        .addQueryParameter("without_genres", "10763,10764,10767")
                } else {
                    u.addQueryParameter("primary_release_date.gte", since)
                        .addQueryParameter("primary_release_date.lte", today)
                        .addQueryParameter("certification_country", certCountry)
                        .addQueryParameter("certification", certs.joinToString("|"))
                        .addQueryParameter("without_genres", "27")
                }
                val json = get(account, u) ?: break
                val results = json.optJSONArray("results") ?: break
                for (i in 0 until results.length()) {
                    val o = results.optJSONObject(i) ?: continue
                    if (o.optBoolean("adult")) continue
                    val title = o.optString(if (series) "name" else "title").ifEmpty { continue }
                    val original = o.optString(if (series) "original_name" else "original_title").takeIf { it.isNotEmpty() && it != title }
                    val genres = o.optJSONArray("genre_ids")?.let { a -> (0 until a.length()).map { a.optInt(it) } }.orEmpty()
                    out += Release(
                        id = o.optLong("id"),
                        series = series,
                        title = title,
                        originalTitle = original,
                        date = o.optString(if (series) "first_air_date" else "release_date"),
                        overview = o.optString("overview"),
                        genreIds = genres,
                        rating = o.optDouble("vote_average", 0.0),
                        popularity = o.optDouble("popularity", 0.0),
                    )
                }
                if (page >= json.optInt("total_pages", 1)) break
            }
        }
        out
    }

    private val genreNames = HashMap<Boolean, Map<Int, String>>()

    /** TMDB's genre names (English), by id. */
    suspend fun genres(series: Boolean): Map<Int, String> = withContext(io) {
        synchronized(genreNames) { genreNames[series]?.let { return@withContext it } }
        val map = rotation.run { account ->
            val a = get(account, url(if (series) "/3/genre/tv/list" else "/3/genre/movie/list"))?.optJSONArray("genres")
            val m = HashMap<Int, String>()
            if (a != null) for (i in 0 until a.length()) a.optJSONObject(i)?.let { m[it.optInt("id")] = it.optString("name") }
            m
        }
        synchronized(genreNames) { genreNames[series] = map }
        map
    }

    /**
     * A series fit for the family TV, by the first rating it has of the US, Australia, the UK,
     * Canada and Germany (then [more], e.g. India for Indian series): TV-14 / M / 12 / 14+ / 12 or lower. MA15+, TV-MA, 15, 16, 18 and
     * unrated series: false.
     */
    suspend fun familySeries(id: Long, more: List<Pair<String, Set<String>>> = emptyList()): Boolean = withContext(io) {
        val ratings = rotation.run { account -> get(account, url("/3/tv/$id/content_ratings"))?.optJSONArray("results") }
            ?: return@withContext false
        val byCountry = HashMap<String, String>()
        for (i in 0 until ratings.length()) {
            val o = ratings.optJSONObject(i) ?: continue
            val r = o.optString("rating").trim().uppercase()
            if (r.isNotEmpty()) byCountry[o.optString("iso_3166_1")] = r
        }
        for ((country, family) in TV_FAMILY + more) {
            val r = byCountry[country] ?: continue
            return@withContext r in family
        }
        false
    }

    /**
     * Wide backdrop images of a title without text on them (TMDB's "no language" ones), best
     * voted first; image paths for [imageUrl]. Wide enough for a TV (1280+ px).
     */
    suspend fun backdrops(series: Boolean, id: Long): List<String> = withContext(io) {
        val list = rotation.run { account ->
            get(account, url("/3/${if (series) "tv" else "movie"}/$id/images").addQueryParameter("include_image_language", "null"))
                ?.optJSONArray("backdrops")
        } ?: return@withContext emptyList()
        (0 until list.length()).mapNotNull { list.optJSONObject(it) }
            .filter { it.optInt("width") >= 1280 && it.optString("file_path").startsWith("/") }
            .sortedWith(compareByDescending<JSONObject> { it.optDouble("vote_average") }.thenByDescending { it.optInt("vote_count") })
            .map { it.optString("file_path") }
    }

    fun imageUrl(path: String, size: String = "w1280") = "$IMAGES/$size$path"

    /** Settings > Online accounts > Test. */
    suspend fun test(account: OnlineAccount): String = withContext(io) {
        try {
            get(account, url("/3/configuration")) ?: throw OnlineFailure("not found", tryNext = false)
            rotation.status[account.key] = "OK"
            "Works"
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            rotation.status[account.key] = msg
            "Failed: $msg"
        }
    }

    private fun search(account: OnlineAccount, kind: String, title: String, year: String?): String? {
        val u = url("/3/search/$kind").addQueryParameter("query", title)
        if (year != null) u.addQueryParameter(if (kind == "tv") "first_air_date_year" else "year", year)
        val results = get(account, u)?.optJSONArray("results") ?: return null
        return results.optJSONObject(0)?.optLong("id")?.takeIf { it > 0 }?.toString()
    }

    private fun parse(id: String, json: JSONObject): Extras {
        val votes = json.optInt("vote_count")
        val avg = json.optDouble("vote_average", 0.0)
        val rating = if (votes > 0 && avg > 0) String.format(Locale.US, "%.1f", avg) else null
        // Official YouTube trailer first, then any YouTube trailer, then a teaser.
        val videos = json.optJSONObject("videos")?.optJSONArray("results")
        var trailer: String? = null
        if (videos != null) {
            val list = (0 until videos.length()).mapNotNull { videos.optJSONObject(it) }.filter { it.optString("site") == "YouTube" }
            trailer = (list.firstOrNull { it.optString("type") == "Trailer" && it.optBoolean("official") }
                ?: list.firstOrNull { it.optString("type") == "Trailer" }
                ?: list.firstOrNull { it.optString("type") == "Teaser" })?.optString("key")
        }
        val castJson = json.optJSONObject("credits")?.optJSONArray("cast")
        val cast = ArrayList<CastMember>()
        if (castJson != null) {
            for (i in 0 until minOf(castJson.length(), 12)) {
                val c = castJson.optJSONObject(i) ?: continue
                val name = c.optString("name").ifEmpty { continue }
                val path = c.optString("profile_path").takeIf { it.startsWith("/") }
                cast += CastMember(name, path?.let { "$IMAGES/w185$it" }, c.optLong("id").takeIf { it > 0 })
            }
        }
        return Extras(id, rating, trailer, cast)
    }

    private fun url(path: String): HttpUrl.Builder = "$API$path".toHttpUrl().newBuilder()

    /** GET with [account]'s key: a v3 API key goes in the URL, a v4 read access token as a bearer header. */
    private fun get(account: OnlineAccount, u: HttpUrl.Builder): JSONObject? {
        val key = account.key.trim()
        val bearer = key.length > 60 // v4 tokens are long JWTs; v3 keys are 32 hex characters
        if (!bearer) u.addQueryParameter("api_key", key)
        val b = Request.Builder().url(u.build()).header("Accept", "application/json")
        if (bearer) b.header("Authorization", "Bearer $key")
        http.newCall(b.build()).execute().use { res ->
            val text = res.body?.string().orEmpty()
            return when {
                res.isSuccessful -> runCatching { JSONObject(text) }.getOrElse { throw OnlineFailure("unexpected answer from TMDB", tryNext = true) }
                res.code == 404 -> null // TMDB doesn't know it: no other key will
                else -> {
                    val msg = runCatching { JSONObject(text).optString("status_message") }.getOrNull().orEmpty()
                    throw OnlineFailure(
                        when (res.code) {
                            401 -> "key refused" + if (msg.isNotEmpty()) " ($msg)" else ""
                            429 -> "too many requests"
                            else -> msg.ifEmpty { "TMDB error (HTTP ${res.code})" }
                        },
                        tryNext = true,
                    )
                }
            }
        }
    }

    private companion object {
        const val API = "https://api.themoviedb.org"
        const val IMAGES = "https://image.tmdb.org/t/p"
        /** Ratings fit for the family TV, by country, in the order they are looked at. */
        val TV_FAMILY = listOf(
            "US" to setOf("TV-Y", "TV-Y7", "TV-Y7-FV", "TV-G", "TV-PG", "TV-14"),
            "AU" to setOf("G", "PG", "M", "P", "C"),
            "GB" to setOf("U", "PG", "12", "12A"),
            "CA" to setOf("C", "C8", "G", "PG", "14+"),
            "DE" to setOf("0", "6", "12"),
        )
    }
}
