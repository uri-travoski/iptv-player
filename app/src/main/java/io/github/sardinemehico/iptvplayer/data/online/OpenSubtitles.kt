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
            t = t.replace(YEAR, " ").replace(TAGS, " ").replace(Regex("[\\[\\](){}|_\"]+"), " ").replace(Regex("\\s+"), " ").trim(' ', '-', ':', '.')
            return SubQuery(t, y, tmdbId, imdbId, season, episode)
        }
    }
}

data class SubResult(val fileId: Long, val language: String, val release: String, val downloads: Int, val hearingImpaired: Boolean)

/**
 * OpenSubtitles.com REST API (https://opensubtitles.stoplight.io), with the user's own accounts
 * (Settings > Online accounts): an API key each, optionally with a login for a higher daily
 * download allowance. When an account is refused or out of downloads, the next one is used.
 * Nothing is sent anywhere unless the user searches from the player (or tests an account).
 */
class OpenSubtitles(private val http: OkHttpClient, private val io: CoroutineDispatcher, private val prefs: Prefs, private val cacheDir: File, private val agent: String) {

    val rotation = AccountRotation(OnlineService.OPENSUBTITLES) { prefs.accounts(OnlineService.OPENSUBTITLES) }

    val isSetUp get() = rotation.isSetUp

    /** Login per account (key + user): token and the API host it was given. */
    private class Session(val token: String?, val host: String)

    private val sessions = HashMap<OnlineAccount, Session>()

    /** Subtitles for [q] in [languages] (ISO 639-1, comma separated), most downloaded first. */
    suspend fun search(q: SubQuery, languages: String): List<SubResult> = withContext(io) {
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
        rotation.run { account ->
            val s = session(account)
            // The API wants parameters in alphabetical order and lower case (else it redirects).
            val url = "https://${s.host}/api/v1/subtitles".toHttpUrl().newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
            parseResults(call(account, s, Request.Builder().url(url).get()))
        }
    }

    /** Downloads one subtitle as SubRip into the app's cache; returns the file. */
    suspend fun download(r: SubResult): File = withContext(io) {
        val link = rotation.run { account ->
            val s = session(account)
            val body = JSONObject().put("file_id", r.fileId).put("sub_format", "srt").toString()
            val json = call(account, s, Request.Builder().url("https://${s.host}/api/v1/download").post(body.toRequestBody(JSON)))
            json.optString("link").ifEmpty { throw OnlineFailure(json.optString("message").ifEmpty { "no download link" }, tryNext = true) }
        }
        val dir = File(cacheDir, "subtitles").apply { mkdirs() }
        // Keep only a few: they are small, but the cache is shared with posters.
        dir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(10)?.forEach { it.delete() }
        val file = File(dir, "${r.fileId}.srt")
        http.newCall(Request.Builder().url(link).header("User-Agent", agent).build()).execute().use { res ->
            if (!res.isSuccessful) throw OnlineFailure("Download failed (HTTP ${res.code})", tryNext = false)
            file.outputStream().use { out -> res.body!!.byteStream().copyTo(out) }
        }
        file
    }

    /** Settings > Online accounts > Test: whether [account] works, and its downloads left today when known. */
    suspend fun test(account: OnlineAccount): String = withContext(io) {
        sessions.remove(account)
        try {
            val s = session(account)
            val text = if (s.token != null) {
                val info = call(account, s, Request.Builder().url("https://${s.host}/api/v1/infos/user").get()).optJSONObject("data")
                val left = info?.optInt("remaining_downloads", -1) ?: -1
                val allowed = info?.optInt("allowed_downloads", -1) ?: -1
                if (left >= 0) "Works · $left of $allowed downloads left today" else "Works (signed in)"
            } else {
                // A small search: the info endpoints answer even with a wrong key, a search doesn't.
                call(account, s, Request.Builder().url("https://${s.host}/api/v1/subtitles?languages=en&query=matrix").get())
                "Works (API key only: a login allows more downloads a day)"
            }
            rotation.status[account.key] = "OK"
            text
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            rotation.status[account.key] = msg
            "Failed: $msg"
        }
    }

    /** Signs in once per account that has a login; without one, the key alone is used. */
    private fun session(account: OnlineAccount): Session {
        sessions[account]?.let { return it }
        val s = if (account.user.isEmpty()) {
            Session(null, "api.opensubtitles.com")
        } else {
            val body = JSONObject().put("username", account.user).put("password", account.password).toString()
            val json = call(account, Session(null, "api.opensubtitles.com"), Request.Builder().url("https://api.opensubtitles.com/api/v1/login").post(body.toRequestBody(JSON)))
            val token = json.optString("token").ifEmpty { throw OnlineFailure("login failed: check the username and password", tryNext = true) }
            Session(token, json.optString("base_url").ifEmpty { "api.opensubtitles.com" }.removePrefix("https://").trimEnd('/'))
        }
        sessions[account] = s
        return s
    }

    private fun parseResults(json: JSONObject): List<SubResult> {
        val data = json.optJSONArray("data") ?: return emptyList()
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
        return out
    }

    private fun call(account: OnlineAccount, s: Session, b: Request.Builder): JSONObject {
        b.header("Api-Key", account.key.trim()).header("User-Agent", agent).header("Accept", "application/json")
        s.token?.let { b.header("Authorization", "Bearer $it") }
        http.newCall(b.build()).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                val msg = runCatching { JSONObject(text).optString("message") }.getOrNull().orEmpty()
                if (res.code == 401 || res.code == 403) sessions.remove(account)
                // The account (or the service) is the problem: another account may work.
                throw when (res.code) {
                    401, 403 -> OnlineFailure("key or login refused" + if (msg.isNotEmpty()) " ($msg)" else "", tryNext = true)
                    406, 429 -> OnlineFailure(msg.ifEmpty { "daily download limit reached" }, tryNext = true)
                    in 500..599 -> OnlineFailure("OpenSubtitles is not answering (HTTP ${res.code})", tryNext = true)
                    else -> OnlineFailure(msg.ifEmpty { "OpenSubtitles error (HTTP ${res.code})" }, tryNext = false)
                }
            }
            return runCatching { JSONObject(text) }.getOrElse { throw OnlineFailure("unexpected answer from OpenSubtitles", tryNext = true) }
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
