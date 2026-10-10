package io.github.sardinemehico.iptvplayer.data.online

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Photos for a movie's cast row, from Wikipedia (no key needed). One request looks up every name
 * at once; names whose page has no picture (often a disambiguation page) are tried again as
 * "Name (actor)" / "Name (actress)". Results are kept for the session, so reopening a page costs
 * nothing. A name with no photo shows initials instead.
 */
class CastPhotos(private val http: OkHttpClient, private val io: CoroutineDispatcher) {

    private val cache = HashMap<String, String?>()

    /** Photo URL (or null) for each of [names]. */
    suspend fun lookup(names: List<String>): Map<String, String?> = withContext(io) {
        val wanted = names.filter { it !in synchronized(cache) { cache.keys } }
        if (wanted.isNotEmpty()) {
            val found = try {
                val first = query(wanted)
                val missing = wanted.filter { first[it] == null }
                val second = if (missing.isEmpty()) emptyMap() else query(missing.flatMap { listOf("$it (actor)", "$it (actress)") })
                wanted.associateWith { n -> first[n] ?: second["$n (actor)"] ?: second["$n (actress)"] }
            } catch (e: Exception) {
                android.util.Log.w("WorldTV.Cast", "photo lookup failed: ${e.message}")
                return@withContext names.associateWith { synchronized(cache) { cache[it] } } // try again next time
            }
            synchronized(cache) { cache.putAll(found) }
        }
        names.associateWith { synchronized(cache) { cache[it] } }
    }

    /** Thumbnail per asked title (following Wikipedia's normalisation and redirects). */
    private fun query(titles: List<String>): Map<String, String?> {
        val out = HashMap<String, String?>()
        for (chunk in titles.chunked(40)) {
            val url = "https://en.wikipedia.org/w/api.php".toHttpUrl().newBuilder()
                .addQueryParameter("action", "query").addQueryParameter("format", "json").addQueryParameter("formatversion", "2")
                .addQueryParameter("prop", "pageimages").addQueryParameter("piprop", "thumbnail").addQueryParameter("pithumbsize", "200")
                .addQueryParameter("redirects", "1").addQueryParameter("titles", chunk.joinToString("|"))
                .build()
            val text = http.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string().orEmpty() }
            val q = JSONObject(text).optJSONObject("query") ?: continue
            // asked -> final title, through normalisation ("tom hanks" -> "Tom hanks") and redirects.
            val rename = HashMap<String, String>()
            for (key in listOf("normalized", "redirects")) {
                val arr = q.optJSONArray(key) ?: continue
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { rename[it.optString("from")] = it.optString("to") }
            }
            val thumbs = HashMap<String, String?>()
            q.optJSONArray("pages")?.let { pages ->
                for (i in 0 until pages.length()) {
                    val p = pages.optJSONObject(i) ?: continue
                    thumbs[p.optString("title")] = p.optJSONObject("thumbnail")?.optString("source")
                }
            }
            for (t in chunk) {
                var final = t
                repeat(3) { rename[final]?.let { final = it } }
                out[t] = thumbs[final]
            }
        }
        return out
    }

    companion object {
        /** "A, B / C; D" -> up to [max] names. */
        fun split(cast: String?, max: Int = 12): List<String> =
            cast.orEmpty().split(',', '/', ';', '|').map { it.trim() }.filter { it.length in 2..60 }.distinct().take(max)
    }
}
