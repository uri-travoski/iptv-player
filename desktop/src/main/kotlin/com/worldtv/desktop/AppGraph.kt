package com.worldtv.desktop

import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.worldtv.desktop.check.DesktopChannelCheck
import com.worldtv.desktop.player.VlcPlayer
import com.worldtv.iptvplayer.AutoRefresh
import com.worldtv.iptvplayer.Prefs
import com.worldtv.iptvplayer.data.db.Db
import com.worldtv.iptvplayer.data.net.AppDns
import com.worldtv.iptvplayer.data.online.CastPhotos
import com.worldtv.iptvplayer.data.online.Tmdb
import com.worldtv.iptvplayer.data.repo.Playlist
import com.worldtv.iptvplayer.data.repo.Repository
import com.worldtv.iptvplayer.data.source.AdultNames
import com.worldtv.iptvplayer.data.sync.DefaultPlaylist
import com.worldtv.iptvplayer.data.sync.Syncer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import java.io.File
import java.util.concurrent.TimeUnit

/** Where the app keeps its files (XDG base directories; inside Flatpak they point into the sandbox). */
object Paths {
    private val home = File(System.getProperty("user.home"))

    private fun xdg(env: String, fallback: String) =
        File(System.getenv(env)?.takeIf { it.isNotBlank() } ?: File(home, fallback).path, "worldtv")

    val data: File = xdg("XDG_DATA_HOME", ".local/share")
    val config: File = xdg("XDG_CONFIG_HOME", ".config")
    val cache: File = xdg("XDG_CACHE_HOME", ".cache")
}

/** Manual dependency graph, like the Android AppGraph: one of everything for the app's lifetime. */
class AppGraph {

    @OptIn(ExperimentalCoroutinesApi::class)
    val io: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(4)

    /** Work that outlives a screen (a reload started there). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val prefs = Prefs(File(Paths.config, "settings.properties"))

    val userAgent = "WorldTV/${BuildInfo.VERSION} (Linux)"

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        // System DNS, or Cloudflare / Google encrypted DNS (Settings).
        .dns(AppDns { prefs.dnsMode })
        .build()

    /** Logos and posters: a descriptive User-Agent, at most 6 at a time. */
    val imageHttp: OkHttpClient = http.newBuilder()
        .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 6; maxRequestsPerHost = 3 })
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build()) }
        .build()

    val db = Db(Context(Paths.data))

    val repo = Repository(db, io)

    val syncer = Syncer(http, db, repo, io, { adultNames() }, null, onSynced = {
        channelCheck.request(it, fresh = true)
        spotlight.onSynced(it)
    })

    val player = VlcPlayer().also {
        it.initVolume(prefs.volume, prefs.muted)
    }

    val channelCheck = DesktopChannelCheck(this)

    val tmdb = Tmdb(http, io, prefs)

    val castPhotos = CastPhotos(imageHttp, io)

    /** The home screen slider: recent family-safe movies and series of the playlist. */
    val spotlight by lazy { com.worldtv.desktop.spotlight.DesktopSpotlight(this) }

    /** True while a playlist is being downloaded. */
    val syncing = MutableStateFlow(false)

    /** Progress line of the running load ("Movies: 12000"). */
    val syncProgress = MutableStateFlow("")

    /** Bumped after every successful load: screens read their lists again. */
    val synced = MutableStateFlow(0L)

    /** Bumped when a resume point is saved: film and series pages read theirs again. */
    val progressSaved = MutableStateFlow(0L)

    /** Playlists reloaded since the app started (Automatic refresh "every start"). */
    private val refreshedThisRun = HashSet<Long>()

    init {
        player.beforePlay = { url -> channelCheck.beforePlay(url) }
        player.afterStop = { channelCheck.afterStop() }
        SingletonImageLoader.setSafe { ctx -> imageLoader(ctx) }
    }

    private fun imageLoader(ctx: PlatformContext) = ImageLoader.Builder(ctx)
        .memoryCache { MemoryCache.Builder().maxSizeBytes(96L * 1024 * 1024).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(File(Paths.cache, "images").toOkioPath())
                .maxSizeBytes(256L * 1024 * 1024)
                .build()
        }
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { imageHttp })) }
        .build()

    /** The admin's adult word lists, compiled; rebuilt only when they change. */
    fun adultNames(): AdultNames {
        val cats = prefs.adultCategoryWords
        val entries = prefs.adultEntryWords
        val cached = adultCache
        if (cached != null && cached.first == cats to entries) return cached.second
        return AdultNames(cats, entries).also { adultCache = (cats to entries) to it }
    }

    @Volatile private var adultCache: Pair<Pair<List<String>, List<String>>, AdultNames>? = null

    /** First start: the free default playlist (Free TV), so there is something to watch. */
    suspend fun addDefaultPlaylistOnce() {
        if (prefs.defaultPlaylistAdded) return
        prefs.defaultPlaylistAdded = true
        if (repo.playlists().isNotEmpty()) return
        val id = repo.addPlaylist(DefaultPlaylist.NAME, Playlist.KIND_M3U, DefaultPlaylist.URL, null, null)
        prefs.activePlaylist = id
    }

    /** The active playlist, or the first one when the saved one is gone. */
    suspend fun activePlaylist(): Playlist? {
        val all = repo.playlists()
        val p = all.firstOrNull { it.id == prefs.activePlaylist } ?: all.firstOrNull()
        if (p != null && p.id != prefs.activePlaylist) prefs.activePlaylist = p.id
        return p
    }

    /**
     * Downloads [p] again. One load at a time; the result is the error message, or null when it
     * worked. Runs in [appScope]: leaving the screen doesn't stop it.
     */
    suspend fun reload(p: Playlist): String? {
        if (syncing.value) return "A playlist is loading already"
        syncing.value = true
        syncProgress.value = "Starting…"
        return try {
            syncer.sync(p) { syncProgress.value = it }
            refreshedThisRun += p.id
            synced.value = synced.value + 1
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        } finally {
            syncing.value = false
            syncProgress.value = ""
        }
    }

    /** Settings > Automatic refresh: the active playlist loads again when due (at start, and at 3 am while open). */
    fun reloadIfDue() {
        appScope.launch {
            val p = activePlaylist() ?: return@launch
            if (AutoRefresh.isDue(prefs.autoRefresh, p.lastSync, System.currentTimeMillis(), p.id in refreshedThisRun)) reload(p)
        }
    }

    fun shutdown() {
        runCatching { channelCheck.stop() }
        runCatching { player.release() }
        runCatching { db.close() }
    }
}
