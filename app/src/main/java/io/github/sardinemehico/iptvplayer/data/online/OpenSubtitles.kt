package io.github.sardinemehico.iptvplayer.data.online

import io.github.sardinemehico.iptvplayer.Prefs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** What to look subtitles up by: the panel's ids when it has them, else the cleaned-up title. */
data class SubQuery(
    val title: String,
    val year: String? = null,
    val tmdbId: String? = null,
    val imdbId: String? = null,
    /** Episodes: the series' ids and title above, and these. */
    val season: Int? = null,
    val episode: Int? = null,
) {
    companion object {
        private val PREFIX = Regex("^\\s*(?:[|\\[(]?[A-Z]{2,3}[|\\])]?\\s*[-:|]\\s*|\\|[A-Z]{2,3}\\|\\s*)")
        private val YEAR = Regex("[(\\[]?((?:19|20)\\d{2})[)\\]]?")
        private val TAGS = Regex("(?i)\\b(4k|uhd|fhd|hd|sd|hevc|x26[45]|cam|ts|multi\\s*(sub|audio)?|dubbed|subbed|vostfr|1080p|720p|2160p)\\b")

        /** "EN - The Movie (2014) FHD" -> title "The Movie", year "2014". */
        fun fromName(name: String, year: String? = null, tmdbId: String? = null, imdbId: String? = null, season: Int? = null, episode: Int? = null): SubQuery {
            var t = name.replace(PREFIX, "")
            val y = year?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) } ?: YEAR.find(t)?.groupValues?.get(1)
            t = t.replace(YEAR, " ").replace(TAGS, " ").replace(Regex("[\\[\\](){}|_]+"), " ").replace(Regex("\\s+"), " ").trim(' ', '-', ':', '.')
            return SubQuery(t, y, tmdbId, imdbId, season, episode)
        }
    }
}

data class SubResult(val fileId: Long, val language: String, val release: String, val downloads: Int, val hearingImpaired: Boolean)

/**
 * OpenSubtitles.com REST API (https://opensubtitles.stoplight.io). Needs the user's own free API
 * key (Settings > Subtitle settings); a free account login is optional and allows more downloads
 * per day. Nothing is sent anywhere unless the user searches from the player.
 */
class OpenSubtitles(private val http: OkHttpClient, private val io: CoroutineDispatcher, private val prefs: Prefs, private val cacheDir: File, private val agent: String) {

    class Failure(message: String) : IOException(message)

    private var token: String? = null
    private var tokenFor: String? = null
    private var host = "api.opensubtitles.com"

    val isSetUp get() = prefs.openSubsKey.isNotEmpty()

    /** Subtitles for [q] in [languages] (ISO 639-1, comma separated), most downloaded first. */
    suspend fun search(q: SubQuery, languages: String): List<SubResult> = withContext(io) {
        login()
        val params = sortedMapOf<String, String>()
        params["languages"] = languages.lowercase()
        params["order_by"] = "download_count"
        val episode = q.season != null && q.episode != null
        if (episode) {
            params["season_number"] = q.season.toString()
            params["episode_number"] = q.episode.toString()
        }
        when {
            q.tmdbId != null -> params[if (episode) "parent_tmdb_id" else "tmdb_id"] = q.tmdbId
            q.imdbId != null -> params[if (episode) "parent_imdb_id" else "imdb_id"] = q.imdbId.removePrefix("tt").trimStart('0')
            else -> {
                params["query"] = q.title.lowercase()
                if (!episode && q.year != null) params["year"] = q.year
            }
        }
        // The API wants parameters in alphabetical order and lower case (else it redirects).
        val url = "https://$host/api/v1/subtitles".toHttpUrl().newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val json = call(Request.Builder().url(url).get())
        val data = json.optJSONArray("data") ?: return@withContext emptyList()
        val out = ArrayList<SubResult>()
        for (i in 0 until data.length()) {
            val a = data.getJSONObject(i).optJSONObject("attributes") ?: continue
            val file = a.optJSONArray("files")?.optJSONObject(0) ?: continue
            out += SubResult(
                fileId = file.optLong("file_id"),
                language = a.optString("language"),
                release = a.optString("release").ifEmpty { file.optString("file_name") },
                downloads = a.optInt("download_count"),
                hearingImpaired = a.optBoolean("hearing_impaired"),
            )
        }
        out
    }

    /** Downloads one subtitle as SubRip into the app's cache; returns the file. */
    suspend fun download(r: SubResult): File = withContext(io) {
        login()
        val body = JSONObject().put("file_id", r.fileId).put("sub_format", "srt").toString()
        val json = call(Request.Builder().url("https://$host/api/v1/download").post(body.toRequestBody(JSON)))
        val link = json.optString("link").ifEmpty { throw Failure(json.optString("message").ifEmpty { "No download link" }) }
        val dir = File(cacheDir, "subtitles").apply { mkdirs() }
        // Keep only a few: they are small, but the cache is shared with posters.
        dir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(10)?.forEach { it.delete() }
        val file = File(dir, "${r.fileId}.srt")
        http.newCall(Request.Builder().url(link).header("User-Agent", agent).build()).execute().use { res ->
            if (!res.isSuccessful) throw Failure("Download failed (HTTP ${res.code})")
            file.outputStream().use { out -> res.body!!.byteStream().copyTo(out) }
        }
        file
    }

    /** With an account set: signs in once (per account) for the higher download allowance. */
    private fun login() {
        val user = prefs.openSubsUser
        if (user.isEmpty()) {
            token = null
            host = "api.opensubtitles.com"
            return
        }
        if (token != null && tokenFor == user) return
        val body = JSONObject().put("username", user).put("password", prefs.openSubsPassword).toString()
        val json = call(Request.Builder().url("https://api.opensubtitles.com/api/v1/login").post(body.toRequestBody(JSON)), auth = false)
        token = json.optString("token").ifEmpty { throw Failure("OpenSubtitles login failed: check the username and password") }
        tokenFor = user
        json.optString("base_url").takeIf { it.isNotEmpty() }?.let { host = it.removePrefix("https://").trimEnd('/') }
    }

    private fun call(b: Request.Builder, auth: Boolean = true): JSONObject {
        val key = prefs.openSubsKey.ifEmpty { throw Failure("No OpenSubtitles API key set") }
        b.header("Api-Key", key).header("User-Agent", agent).header("Accept", "application/json")
        if (auth) token?.let { b.header("Authorization", "Bearer $it") }
        http.newCall(b.build()).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                val msg = runCatching { JSONObject(text).optString("message") }.getOrNull().orEmpty()
                throw Failure(
                    when (res.code) {
                        401, 403 -> "OpenSubtitles refused the API key" + if (msg.isNotEmpty()) ": $msg" else ""
                        406, 429 -> msg.ifEmpty { "Daily download limit reached; try again tomorrow" }
                        else -> msg.ifEmpty { "OpenSubtitles error (HTTP ${res.code})" }
                    },
                )
            }
            return runCatching { JSONObject(text) }.getOrElse { throw Failure("Unexpected answer from OpenSubtitles") }
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
