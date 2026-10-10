package io.github.sardinemehico.iptvplayer

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.allowRgb565
import coil3.request.crossfade
import io.github.sardinemehico.iptvplayer.data.db.Db
import io.github.sardinemehico.iptvplayer.data.model.ContentType
import io.github.sardinemehico.iptvplayer.data.net.AppDns
import io.github.sardinemehico.iptvplayer.data.online.CastPhotos
import io.github.sardinemehico.iptvplayer.data.online.OpenSubtitles
import io.github.sardinemehico.iptvplayer.data.repo.Repository
import io.github.sardinemehico.iptvplayer.data.repo.Sort
import io.github.sardinemehico.iptvplayer.data.source.AdultNames
import io.github.sardinemehico.iptvplayer.data.sync.Syncer
import io.github.sardinemehico.iptvplayer.player.PlayerController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import java.util.concurrent.TimeUnit

class App : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val lowRam = (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
        val memoryBytes = if (lowRam) 8L * 1024 * 1024 else 16L * 1024 * 1024
        return ImageLoader.Builder(context)
            .memoryCache { MemoryCache.Builder().maxSizeBytes(memoryBytes).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("images").toOkioPath())
                    .maxSizeBytes(64L * 1024 * 1024)
                    .build()
            }
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { graph.imageHttp })) }
            .allowRgb565(true)
            .crossfade(false)
            .build()
    }

    companion object {
        lateinit var graph: AppGraph
            private set
    }
}

/** Manual dependency graph: everything is created lazily, nothing on startup. */
class AppGraph(private val app: Application) {

    @OptIn(ExperimentalCoroutinesApi::class)
    val io: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(2)

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            // System DNS, or Cloudflare / Google encrypted DNS (App Settings).
            .dns(AppDns { prefs.dnsMode })
            .build()
    }

    /**
     * Same connection pool as [http], but with a descriptive User-Agent (some logo hosts, e.g.
     * Wikimedia, answer 403 to clients that don't identify themselves) and at most 4 logo
     * downloads at a time (2 per host), so scrolling never competes hard with a starting stream.
     */
    val imageHttp: OkHttpClient by lazy {
        val version = app.packageManager.getPackageInfo(app.packageName, 0).versionName
        val agent = "WorldTV/$version (Android TV; https://github.com/uri-travoski/iptv-player)"
        http.newBuilder()
            .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 4; maxRequestsPerHost = 2 })
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", agent).build()) }
            .build()
    }

    val db: Db by lazy { Db(app) }
    /**
     * One low-priority thread for long adult re-checks (a big playlist has 300k titles): screens
     * and playback keep their own threads and the CPU comes first to them.
     */
    val scanDispatcher: CoroutineDispatcher by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "adult-scan")
        }.asCoroutineDispatcher()
    }

    /** Work that outlives the screen that started it (a re-check after the admin leaves). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val repo: Repository by lazy { Repository(db, io, scanDispatcher) }

    /**
     * Re-checks [playlistIds] (all when null) for adult content in the background. Callers never
     * wait for it; [done] runs on the main thread afterwards. Failures are logged, never thrown.
     */
    fun rescanAdult(playlistIds: List<Long>? = null, done: () -> Unit = {}): Job = appScope.launch {
        try {
            val names = adultNames()
            for (id in playlistIds ?: repo.playlists().map { it.id }) repo.autoHideAdult(id, names)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("WorldTV.Adult", "re-check failed", e)
        }
        done()
    }
    val syncer: Syncer by lazy { Syncer(http, db, repo, io) { adultNames() } }

    /** Online subtitles (the user's own OpenSubtitles key). */
    val openSubs by lazy {
        val version = app.packageManager.getPackageInfo(app.packageName, 0).versionName
        OpenSubtitles(http, io, prefs, app.cacheDir, "WorldTV v$version")
    }

    /** Cast photos for movie and series pages (Wikipedia; needs a descriptive User-Agent). */
    val castPhotos by lazy { CastPhotos(imageHttp, io) }

    /** Playlists reloaded automatically since the app started (Settings > Automatic refresh). */
    val autoRefreshedThisRun = HashSet<Long>()

    /** True while a playlist is being downloaded (manual Reload or automatic refresh). */
    @Volatile var syncing = false
    val player: PlayerController by lazy { PlayerController(app, http) }
    val prefs: Prefs by lazy { Prefs(app.getSharedPreferences("app", Context.MODE_PRIVATE)) }

    /** The admin's adult word lists, compiled; rebuilt only when they change. */
    fun adultNames(): AdultNames {
        val cats = prefs.adultCategoryWords
        val entries = prefs.adultEntryWords
        val cached = adultCache
        if (cached != null && cached.first == cats to entries) return cached.second
        return AdultNames(cats, entries).also { adultCache = (cats to entries) to it }
    }

    @Volatile
    private var adultCache: Pair<Pair<List<String>, List<String>>, AdultNames>? = null
}

/** Small settings and "last used" state. */
class Prefs(private val sp: SharedPreferences) {

    companion object {
        const val MIN_SLOTS = 1
        const val DEFAULT_SLOTS = 2
        const val MAX_SLOTS = 14
        const val UI_TV = "tv"
        const val UI_MOBILE = "mobile"
    }

    /** UI_TV (remote, D-pad) or UI_MOBILE (touch, portrait); null until picked on first start. */
    var uiMode: String?
        get() = sp.getString("ui_mode", null)
        set(v) = sp.edit().putString("ui_mode", v).apply()

    val isMobile: Boolean get() = uiMode == UI_MOBILE

    var activePlaylist: Long
        get() = sp.getLong("active_playlist", -1)
        set(v) = sp.edit().putLong("active_playlist", v).apply()

    var lastLiveCategory: String?
        get() = sp.getString("last_live_cat", null)
        set(v) = sp.edit().putString("last_live_cat", v).apply()

    var lastLiveItem: String?
        get() = sp.getString("last_live_item", null)
        set(v) = sp.edit().putString("last_live_item", v).apply()

    /** Open the app when the box boots. */
    var autoStart: Boolean
        get() = sp.getBoolean("auto_start", false)
        set(v) = sp.edit().putBoolean("auto_start", v).apply()

    /** When the boot broadcast last reached the app (ms), 0 = never. Shown in Settings. */
    var lastBootAt: Long
        get() = sp.getLong("last_boot_at", 0)
        set(v) = sp.edit().putLong("last_boot_at", v).apply()

    /** Whether the app had the permission Android 10+ needs to open itself at that boot. */
    var lastBootAllowed: Boolean
        get() = sp.getBoolean("last_boot_allowed", false)
        set(v) = sp.edit().putBoolean("last_boot_allowed", v).apply()

    /**
     * How many app slots the home screen shows (1..14, rows of 5). New installs get 2. An update
     * from a version without this setting keeps every slot that already holds an app (up to 5),
     * so nobody's apps disappear.
     */
    var appSlotCount: Int
        get() {
            val stored = sp.getInt("app_slot_count", -1)
            if (stored != -1) return stored.coerceIn(MIN_SLOTS, MAX_SLOTS)
            val highestUsed = (0 until 5).lastOrNull { appSlot(it) != null }
            val n = if (highestUsed == null) DEFAULT_SLOTS else (highestUsed + 1).coerceIn(DEFAULT_SLOTS, 5)
            return n.also { appSlotCount = it }
        }
        set(v) = sp.edit().putInt("app_slot_count", v.coerceIn(MIN_SLOTS, MAX_SLOTS)).apply()

    /** Check GitHub for a newer WorldTV when the app starts (App Settings). */
    var autoUpdate: Boolean
        get() = sp.getBoolean("auto_update", true)
        set(v) = sp.edit().putBoolean("auto_update", v).apply()

    /** When the last update check ran (ms), to check at most every few hours. */
    var lastUpdateCheck: Long
        get() = sp.getLong("last_update_check", 0)
        set(v) = sp.edit().putLong("last_update_check", v).apply()

    /** A version the user chose to skip; it is not offered again. */
    var skippedUpdate: String?
        get() = sp.getString("skipped_update", null)
        set(v) = sp.edit().putString("skipped_update", v).apply()

    /** Package shown in home-screen app slot [index] (0..13), or null if the slot is empty. */
    fun appSlot(index: Int): String? = sp.getString("app_slot_$index", null)

    fun setAppSlot(index: Int, pkg: String?) = sp.edit().putString("app_slot_$index", pkg).apply()

    /** AppDns.MODE_*: which DNS the app's own connections use. */
    var dnsMode: Int
        get() = sp.getInt("dns_mode", AppDns.MODE_SYSTEM)
        set(v) = sp.edit().putInt("dns_mode", v).apply()

    /** Words that hide a category (App Settings > playlist > Categories > Adult words). */
    var adultCategoryWords: List<String>
        get() = words("adult_category_words") ?: AdultNames.DEFAULT_CATEGORY_WORDS
        set(v) = sp.edit().putString("adult_category_words", v.joinToString("\n")).apply()

    /** Words that hide a channel, movie or series. */
    var adultEntryWords: List<String>
        get() = words("adult_entry_words") ?: AdultNames.DEFAULT_ENTRY_WORDS
        set(v) = sp.edit().putString("adult_entry_words", v.joinToString("\n")).apply()

    /** New default category words for an edited list (an unedited one has them already). */
    fun addAdultCategoryWords(added: List<String>) {
        val current = words("adult_category_words") ?: return
        val missing = added.filter { w -> current.none { it.equals(w, ignoreCase = true) } }
        if (missing.isNotEmpty()) adultCategoryWords = current + missing
    }

    /** null until the admin edits the list: the defaults (which may grow with app updates) apply. */
    private fun words(key: String): List<String>? =
        sp.getString(key, null)?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }

    /** Sort order of Movies / Series lists; newest first by default. */
    fun vodSort(type: ContentType): Sort =
        Sort.values().getOrNull(sp.getInt("vod_sort_" + type.name, Sort.NEWEST.ordinal)) ?: Sort.NEWEST

    fun setVodSort(type: ContentType, sort: Sort) = sp.edit().putInt("vod_sort_" + type.name, sort.ordinal).apply()

    /** AdultNames.VERSION the playlists were last scanned with (0 = never). */
    var adultScanVersion: Int
        get() = sp.getInt("adult_scan_version", 0)
        set(v) = sp.edit().putInt("adult_scan_version", v).apply()

    /** PlayerView resize mode (AspectRatioFrameLayout.RESIZE_MODE_*), 0 = fit. */
    var resizeMode: Int
        get() = sp.getInt("resize_mode", 0)
        set(v) = sp.edit().putInt("resize_mode", v).apply()

    /**
     * Live channel order (Settings > Live channel sort): Sort.PROVIDER (the provider's order),
     * NAME (A–Z) or NAME_DESC (Z–A). A setting, not part of the playlist: reloads keep it.
     */
    var liveSort: Sort
        get() = sp.getString("live_sort", null)?.let { s -> Sort.values().firstOrNull { it.name == s } }
            ?.takeIf { it == Sort.NAME || it == Sort.NAME_DESC } ?: Sort.PROVIDER
        set(v) = sp.edit().putString("live_sort", v.name).apply()

    /** Settings > Automatic refresh: AutoRefresh.EVERY_START, DAILY or EVERY_2_DAYS (default). */
    var autoRefresh: String
        get() = sp.getString("auto_refresh", null)?.takeIf { it in AutoRefresh.MODES } ?: AutoRefresh.EVERY_2_DAYS
        set(v) = sp.edit().putString("auto_refresh", v).apply()

    // ---- subtitles (Settings > Subtitle settings) ----

    /** Show subtitles automatically when a stream has them in [subsLanguage] (else only when picked). */
    var subsEnabled: Boolean
        get() = sp.getBoolean("subs_enabled", true)
        set(v) = sp.edit().putBoolean("subs_enabled", v).apply()

    /** Size step, Subtitles.SIZES index (1 = normal). */
    var subsSize: Int
        get() = sp.getInt("subs_size", 1)
        set(v) = sp.edit().putInt("subs_size", v).apply()

    /** Text colour, Subtitles.COLORS index (0 = white). */
    var subsColor: Int
        get() = sp.getInt("subs_color", 0)
        set(v) = sp.edit().putInt("subs_color", v).apply()

    /** Background, Subtitles.BACKGROUNDS index (0 = outline only). */
    var subsBackground: Int
        get() = sp.getInt("subs_background", 0)
        set(v) = sp.edit().putInt("subs_background", v).apply()

    /** Preferred subtitle language (ISO 639-1, e.g. "en"); empty = the box's language. */
    var subsLanguage: String
        get() = sp.getString("subs_language", "").orEmpty()
        set(v) = sp.edit().putString("subs_language", v).apply()

    /** OpenSubtitles.com API key for online subtitles (free, the user's own); empty = off. */
    var openSubsKey: String
        get() = sp.getString("opensubs_key", "").orEmpty()
        set(v) = sp.edit().putString("opensubs_key", v.trim()).apply()

    /** Optional OpenSubtitles account: more downloads per day than without one. */
    var openSubsUser: String
        get() = sp.getString("opensubs_user", "").orEmpty()
        set(v) = sp.edit().putString("opensubs_user", v.trim()).apply()

    var openSubsPassword: String
        get() = sp.getString("opensubs_password", "").orEmpty()
        set(v) = sp.edit().putString("opensubs_password", v).apply()

    /** Reset app: everything back to a fresh install, except the TV / Mobile layout choice. */
    fun resetAll() {
        val mode = uiMode
        sp.edit().clear().putString("ui_mode", mode).apply()
    }
}

/** Settings > Automatic refresh: when the active playlist is downloaded again by itself. */
object AutoRefresh {
    const val EVERY_START = "start"
    const val DAILY = "daily"
    const val EVERY_2_DAYS = "2days"
    val MODES = listOf(EVERY_START, DAILY, EVERY_2_DAYS)

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * True when a playlist last loaded at [lastSyncMs] should load again now. [refreshedThisRun]:
     * already reloaded since the app started (EVERY_START does it once per start, not on every
     * return to the home screen). A clock set back counts as due rather than never.
     */
    fun isDue(mode: String, lastSyncMs: Long, nowMs: Long, refreshedThisRun: Boolean): Boolean {
        if (refreshedThisRun) return false
        if (lastSyncMs <= 0 || nowMs < lastSyncMs) return true
        return when (mode) {
            EVERY_START -> true
            DAILY -> nowMs - lastSyncMs >= DAY_MS
            else -> nowMs - lastSyncMs >= 2 * DAY_MS
        }
    }
}
