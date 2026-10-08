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
import io.github.sardinemehico.iptvplayer.data.net.AppDns
import io.github.sardinemehico.iptvplayer.data.repo.Repository
import io.github.sardinemehico.iptvplayer.data.sync.Syncer
import io.github.sardinemehico.iptvplayer.player.PlayerController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    val repo: Repository by lazy { Repository(db, io) }
    val syncer: Syncer by lazy { Syncer(http, db, repo, io) }
    val player: PlayerController by lazy { PlayerController(app, http) }
    val prefs: Prefs by lazy { Prefs(app.getSharedPreferences("app", Context.MODE_PRIVATE)) }
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

    /** AdultNames.VERSION the playlists were last scanned with (0 = never). */
    var adultScanVersion: Int
        get() = sp.getInt("adult_scan_version", 0)
        set(v) = sp.edit().putInt("adult_scan_version", v).apply()

    /** PlayerView resize mode (AspectRatioFrameLayout.RESIZE_MODE_*), 0 = fit. */
    var resizeMode: Int
        get() = sp.getInt("resize_mode", 0)
        set(v) = sp.edit().putInt("resize_mode", v).apply()
}
