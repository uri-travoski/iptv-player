package com.worldtv.iptvplayer.data.update

import com.worldtv.iptvplayer.data.source.JsonPull
import java.io.Reader

/** A WorldTV release on GitHub with its APK. */
data class Release(
    val version: String,
    val apkUrl: String,
    val apkSize: Long,
    /** "sha256:<hex>" when GitHub provides it, else null. */
    val digest: String?,
    val notes: String?,
)

/**
 * Finding the newest WorldTV release. Pure Kotlin (no Android) so it runs as a JVM unit test.
 * Releases are tagged with the plain version ("0.1.16") and carry a "worldtv-<version>.apk".
 */
object Updates {

    // Required: the public repo whose GitHub Releases carry the APKs the app updates from.
    const val RELEASES_API = "https://api.github.com/repos/uri-travoski/iptv-player/releases?per_page=10"

    /** Newest release with an APK, from GitHub's releases list (drafts skipped). */
    fun newest(reader: Reader): Release? {
        var best: Release? = null
        JsonPull(reader).use { json ->
            if (json.peek() != JsonPull.Token.BEGIN_ARRAY) { json.skipValue(); return null }
            json.beginArray()
            while (json.hasNext()) {
                val r = readRelease(json) ?: continue
                if (best == null || compare(r.version, best!!.version) > 0) best = r
            }
            json.endArray()
        }
        return best
    }

    /** True if [candidate] is a later version than [current] ("0.1.10" > "0.1.9"). */
    fun isNewer(candidate: String, current: String) = compare(candidate, current) > 0

    /** Numeric, part by part; missing parts count as 0; non-numeric suffixes ("-beta") ignored. */
    fun compare(a: String, b: String): Int {
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    private fun parts(v: String) = v.trim().removePrefix("v").substringBefore('-')
        .split('.').map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    private fun readRelease(json: JsonPull): Release? {
        if (json.peek() != JsonPull.Token.BEGIN_OBJECT) { json.skipValue(); return null }
        var tag: String? = null
        var draft = false
        var notes: String? = null
        var apk: Triple<String, Long, String?>? = null
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "tag_name" -> tag = json.nextStringOrNull()
                "draft" -> draft = json.nextStringOrNull() == "true"
                "body" -> notes = json.nextStringOrNull()
                "assets" -> apk = readApk(json)
                else -> json.skipValue()
            }
        }
        json.endObject()
        val t = tag?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val a = apk ?: return null
        if (draft) return null
        return Release(t.removePrefix("v"), a.first, a.second, a.third, notes)
    }

    /** First asset named *.apk: (download URL, size, digest). */
    private fun readApk(json: JsonPull): Triple<String, Long, String?>? {
        if (json.peek() != JsonPull.Token.BEGIN_ARRAY) { json.skipValue(); return null }
        var found: Triple<String, Long, String?>? = null
        json.beginArray()
        while (json.hasNext()) {
            if (json.peek() != JsonPull.Token.BEGIN_OBJECT) { json.skipValue(); continue }
            var name: String? = null
            var url: String? = null
            var size = 0L
            var digest: String? = null
            json.beginObject()
            while (json.hasNext()) {
                when (json.nextName()) {
                    "name" -> name = json.nextStringOrNull()
                    "browser_download_url" -> url = json.nextStringOrNull()
                    "size" -> size = json.nextLongOrNull() ?: 0
                    "digest" -> digest = json.nextStringOrNull()
                    else -> json.skipValue()
                }
            }
            json.endObject()
            if (found == null && name?.endsWith(".apk", ignoreCase = true) == true && url != null) {
                found = Triple(url, size, digest)
            }
        }
        json.endArray()
        return found
    }
}
