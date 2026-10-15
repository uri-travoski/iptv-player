package com.worldtv.iptvplayer

import com.worldtv.iptvplayer.data.model.ContentType
import com.worldtv.iptvplayer.data.net.AppDns
import com.worldtv.iptvplayer.data.online.OnlineAccount
import com.worldtv.iptvplayer.data.online.OnlineService
import com.worldtv.iptvplayer.data.repo.Sort
import com.worldtv.iptvplayer.data.source.AdultNames
import java.io.File
import java.util.Properties

/**
 * The desktop app's settings and "last used" state, in ~/.config/worldtv/settings.properties.
 * Same names and meanings as the Android Prefs where both have a setting (the shared online
 * services read [accounts]). Written on every change; tiny.
 */
class Prefs(private val file: File) {

    private val p = Properties()

    init {
        runCatching { if (file.exists()) file.inputStream().use { p.load(it) } }
    }

    @Synchronized
    private fun put(key: String, value: String?) {
        if (value == null) p.remove(key) else p.setProperty(key, value)
        save()
    }

    @Synchronized
    private fun save() {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.outputStream().use { p.store(it, "WorldTV settings") }
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    private fun str(key: String): String? = p.getProperty(key)
    private fun long(key: String, def: Long) = str(key)?.toLongOrNull() ?: def
    private fun int(key: String, def: Int) = str(key)?.toIntOrNull() ?: def
    private fun bool(key: String, def: Boolean) = str(key)?.toBooleanStrictOrNull() ?: def

    var activePlaylist: Long
        get() = long("active_playlist", -1)
        set(v) = put("active_playlist", v.toString())

    var defaultPlaylistAdded: Boolean
        get() = bool("default_playlist_added", false)
        set(v) = put("default_playlist_added", v.toString())

    var lastLiveCategory: String?
        get() = str("last_live_cat")
        set(v) = put("last_live_cat", v)

    var lastLiveItem: String?
        get() = str("last_live_item")
        set(v) = put("last_live_item", v)

    /** Themes.CLASSIC, GLASS or SHOWTIME. */
    var theme: String
        get() = str("theme") ?: "classic"
        set(v) = put("theme", v)

    /** AutoRefresh.EVERY_START, DAILY (default) or EVERY_2_DAYS. */
    var autoRefresh: String
        get() = str("auto_refresh")?.takeIf { it in AutoRefresh.MODES } ?: AutoRefresh.DAILY
        set(v) = put("auto_refresh", v)

    /** AppDns.MODE_*: which DNS the app's own connections use. */
    var dnsMode: Int
        get() = int("dns_mode", AppDns.MODE_BOTH)
        set(v) = put("dns_mode", v.toString())

    /** Check GitHub for a newer WorldTV at start. */
    var autoUpdate: Boolean
        get() = bool("auto_update", true)
        set(v) = put("auto_update", v.toString())

    var skippedUpdate: String?
        get() = str("skipped_update")
        set(v) = put("skipped_update", v)

    var lastUpdateCheck: Long
        get() = long("last_update_check", 0)
        set(v) = put("last_update_check", v.toString())

    /** Player volume, 0..150 (VLC's scale; 100 = normal). */
    var volume: Int
        get() = int("volume", 100).coerceIn(0, 150)
        set(v) = put("volume", v.coerceIn(0, 150).toString())

    var muted: Boolean
        get() = bool("muted", false)
        set(v) = put("muted", v.toString())

    /** Picture shape: 0 fit, 1 fill (crop), 2 stretch, 3 16:9, 4 4:3. */
    var aspect: Int
        get() = int("aspect", 0)
        set(v) = put("aspect", v.toString())

    var adultCategoryWords: List<String>
        get() = words("adult_category_words") ?: AdultNames.DEFAULT_CATEGORY_WORDS
        set(v) = put("adult_category_words", v.joinToString("\n"))

    var adultEntryWords: List<String>
        get() = words("adult_entry_words") ?: AdultNames.DEFAULT_ENTRY_WORDS
        set(v) = put("adult_entry_words", v.joinToString("\n"))

    private fun words(key: String): List<String>? =
        str(key)?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }

    /** Live channels: the provider's order (with any channels moved by hand on Android). */
    val liveSort: Sort get() = Sort.PROVIDER

    fun vodSort(type: ContentType): Sort =
        Sort.values().getOrNull(int("vod_sort_" + type.name, Sort.NEWEST.ordinal)) ?: Sort.NEWEST

    fun setVodSort(type: ContentType, sort: Sort) = put("vod_sort_" + type.name, sort.ordinal.toString())

    var adultScanVersion: Int
        get() = int("adult_scan_version", 0)
        set(v) = put("adult_scan_version", v.toString())

    // ---- subtitles ----

    var subsEnabled: Boolean
        get() = bool("subs_enabled", true)
        set(v) = put("subs_enabled", v.toString())

    /** Preferred subtitle language (ISO 639-1); empty = the system's. */
    var subsLanguage: String
        get() = str("subs_language").orEmpty()
        set(v) = put("subs_language", v)

    /** Subtitle size step: 0 small, 1 normal, 2 large, 3 huge. */
    var subsSize: Int
        get() = int("subs_size", 1)
        set(v) = put("subs_size", v.toString())

    // ---- channel check ----

    fun lastChannelCheck(id: Long): Long = long("channel_check_$id", 0)
    fun setLastChannelCheck(id: Long, at: Long) = put("channel_check_$id", at.toString())
    fun checkRoundStart(id: Long): Long = long("check_round_$id", 0)
    fun setCheckRoundStart(id: Long, at: Long) = put("check_round_$id", at.toString())
    fun checkRoundActive(id: Long): Long = long("check_active_$id", 0)
    fun setCheckRoundActive(id: Long, ms: Long) = put("check_active_$id", ms.toString())
    fun checkLastRoundStart(id: Long): Long = long("check_last_round_$id", 0)
    fun checkLastRoundActive(id: Long): Long = long("check_last_active_$id", 0)

    @Synchronized
    fun finishCheckRound(id: Long, started: Long, activeMs: Long) {
        p.setProperty("check_last_round_$id", started.toString())
        p.setProperty("check_last_active_$id", activeMs.toString())
        p.setProperty("check_round_$id", "0")
        p.setProperty("check_active_$id", "0")
        save()
    }

    var pendingChecks: Set<Long>
        get() = str("channel_check_pending").orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()
        set(v) = put("channel_check_pending", v.joinToString(","))

    // ---- home screen slider ----

    var spotlightPlaylist: Long
        get() = long("spotlight_playlist", -1)
        set(v) = put("spotlight_playlist", v.toString())

    var spotlightBuiltAt: Long
        get() = long("spotlight_built_at", 0)
        set(v) = put("spotlight_built_at", v.toString())

    var spotlightAttemptAt: Long
        get() = long("spotlight_attempt_at", 0)
        set(v) = put("spotlight_attempt_at", v.toString())

    /** Settings > Backdrop region: the slider's languages (SliderRegions ids); empty = all. */
    var spotlightRegions: Set<String>
        get() = str("spotlight_regions").orEmpty().split(',').filter { it.isNotBlank() }.toSet()
        set(v) = put("spotlight_regions", v.joinToString(","))

    /** Titles taken off the slider ("movie:123", "tv:456"). */
    var spotlightHidden: Set<String>
        get() = str("spotlight_hidden").orEmpty().split(',').filter { it.isNotBlank() }.toSet()
        set(v) = put("spotlight_hidden", v.joinToString(","))

    // ---- window ----

    var windowBounds: String?
        get() = str("window_bounds")
        set(v) = put("window_bounds", v)

    // ---- online accounts (TMDB, OpenSubtitles): read by the shared services ----

    fun accounts(service: OnlineService): List<OnlineAccount> =
        str("accounts_${service.id}")?.let { OnlineAccount.listFromJson(it) } ?: OnlineAccount.builtIns(service)

    fun setAccounts(service: OnlineService, list: List<OnlineAccount>) =
        put("accounts_${service.id}", OnlineAccount.listToJson(list))

    /** Reset app: every setting back to new. */
    @Synchronized
    fun resetAll() {
        p.clear()
        save()
    }
}
