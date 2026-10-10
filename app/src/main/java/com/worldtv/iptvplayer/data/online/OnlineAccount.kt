package com.worldtv.iptvplayer.data.online

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** The online services WorldTV can use with the user's own accounts (Settings > Online accounts). */
enum class OnlineService(val id: String, val title: String, val hasLogin: Boolean) {
    TMDB("tmdb", "TMDB", hasLogin = false),
    OPENSUBTITLES("opensubtitles", "OpenSubtitles", hasLogin = true),
}

/** One account: an API key, and for OpenSubtitles an optional username and password. */
data class OnlineAccount(val key: String, val user: String = "", val password: String = "") {

    /** "abcd…wxyz · username": enough to tell accounts apart without showing the key. */
    val label: String
        get() {
            val k = key.trim()
            val masked = if (k.length > 10) k.take(4) + "…" + k.takeLast(4) else "…" + k.takeLast(3)
            return if (user.isNotEmpty()) "$masked · $user" else masked
        }

    companion object {
        /**
         * Keys WorldTV ships with, so cast/ratings/trailers (TMDB) and online subtitles
         * (OpenSubtitles) work right after install, with no setup. The user's own accounts
         * (Settings > Online accounts) replace them; removing every account switches them off too.
         */
        const val BUILT_IN_TMDB = "03287790b8fa500636583afe276cd82d"
        const val BUILT_IN_OPENSUBTITLES = "03287790b8fa500636583afe276cd82d"

        /** The accounts used until the user sets up their own for [service]. */
        fun builtIns(service: OnlineService): List<OnlineAccount> = when (service) {
            OnlineService.TMDB -> listOf(OnlineAccount(BUILT_IN_TMDB))
            OnlineService.OPENSUBTITLES -> listOf(OnlineAccount(BUILT_IN_OPENSUBTITLES))
        }

        fun listFromJson(s: String): List<OnlineAccount> = runCatching {
            val a = JSONArray(s)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                OnlineAccount(o.optString("key"), o.optString("user"), o.optString("password")).takeIf { it.key.isNotBlank() }
            }
        }.getOrDefault(emptyList())

        fun listToJson(list: List<OnlineAccount>): String = JSONArray().apply {
            list.forEach { put(JSONObject().put("key", it.key.trim()).put("user", it.user.trim()).put("password", it.password)) }
        }.toString()
    }
}

/**
 * A failure from an online service. [tryNext]: the account itself is the problem (refused key,
 * daily limit, login failed) or the service is down, so another account may work; false when
 * the request can't succeed with any account (e.g. nothing found).
 */
class OnlineFailure(message: String, val tryNext: Boolean) : IOException(message)

/**
 * Runs [block] with each account of a service in turn, starting with the one that worked last,
 * until one succeeds. Network errors and [OnlineFailure.tryNext] move on to the next account;
 * when all fail, the last error is thrown. Status per account is kept for Settings.
 */
class AccountRotation(
    private val service: OnlineService,
    private val log: (String) -> Unit = { android.util.Log.i("WorldTV.Online", it) },
    private val accounts: () -> List<OnlineAccount>,
) {

    private var preferredKey: String? = null

    /** Last result per account key, shown in Settings ("works", "refused", "daily limit"…). */
    val status = HashMap<String, String>()

    val isSetUp get() = accounts().isNotEmpty()

    fun <T> run(block: (OnlineAccount) -> T): T {
        val list = accounts()
        if (list.isEmpty()) throw OnlineFailure("No ${service.title} account set up", tryNext = false)
        val start = list.indexOfFirst { it.key == preferredKey }.coerceAtLeast(0)
        var last: Exception? = null
        for (i in list.indices) {
            val account = list[(start + i) % list.size]
            try {
                val result = block(account)
                preferredKey = account.key
                status[account.key] = "OK"
                return result
            } catch (e: OnlineFailure) {
                status[account.key] = e.message.orEmpty()
                if (!e.tryNext) throw e
                log("${service.title} account ${account.label} failed (${e.message}); trying the next")
                last = e
            } catch (e: IOException) {
                status[account.key] = e.message ?: "network error"
                log("${service.title} account ${account.label}: ${e.message}; trying the next")
                last = e
            }
        }
        throw last ?: OnlineFailure("All ${service.title} accounts failed", tryNext = false)
    }
}
