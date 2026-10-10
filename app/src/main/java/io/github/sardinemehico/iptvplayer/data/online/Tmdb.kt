package io.github.sardinemehico.iptvplayer.data.online

import io.github.sardinemehico.iptvplayer.Prefs
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

    data class CastMember(val name: String, val photo: String?)

    data class Extras(val tmdbId: String, val rating: String?, val trailer: String?, val cast: List<CastMember>)

    val rotation = AccountRotation(OnlineService.TMDB) { prefs.accounts(OnlineService.TMDB) }

    val isSetUp get() = rotation.isSetUp

    private val cache = HashMap<String, Extras?>()

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
                cast += CastMember(name, path?.let { "$IMAGES/w185$it" })
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
    }
}
