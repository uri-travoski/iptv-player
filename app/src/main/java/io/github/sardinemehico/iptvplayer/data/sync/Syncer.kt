package io.github.sardinemehico.iptvplayer.data.sync

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import io.github.sardinemehico.iptvplayer.data.db.Db
import io.github.sardinemehico.iptvplayer.data.model.Category
import io.github.sardinemehico.iptvplayer.data.model.ContentType
import io.github.sardinemehico.iptvplayer.data.model.Entry
import io.github.sardinemehico.iptvplayer.data.repo.Playlist
import io.github.sardinemehico.iptvplayer.data.repo.Repository
import io.github.sardinemehico.iptvplayer.data.source.AdultNames
import io.github.sardinemehico.iptvplayer.data.source.Episode
import io.github.sardinemehico.iptvplayer.data.source.M3uParser
import io.github.sardinemehico.iptvplayer.data.source.VodInfo
import io.github.sardinemehico.iptvplayer.data.source.XtreamAccount
import io.github.sardinemehico.iptvplayer.data.source.XtreamAction
import io.github.sardinemehico.iptvplayer.data.source.XtreamCredentials
import io.github.sardinemehico.iptvplayer.data.source.XtreamParser
import io.github.sardinemehico.iptvplayer.data.source.XtreamUrls
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.Reader

class SyncException(message: String) : IOException(message)

/**
 * Downloads a playlist and rewrites its rows. Each library is replaced inside one transaction:
 * with WAL, screens keep seeing the old rows until the commit, so browsing never stalls.
 */
class Syncer(
    private val http: OkHttpClient,
    private val db: Db,
    private val repo: Repository,
    private val io: CoroutineDispatcher,
    /** The admin's adult word lists, read at the end of each load. */
    private val adultNames: () -> AdultNames,
) {

    /** Checks an Xtream login without touching the database. */
    suspend fun login(c: XtreamCredentials): XtreamAccount = withContext(io) {
        val account = get(XtreamUrls(c).api()) { XtreamParser.parseAccount(it) }
        if (!account.authenticated) throw SyncException("Login failed. Check the server, username and password.")
        account
    }

    /** Movie page details (get_vod_info). Not stored: fetched when the page opens. */
    suspend fun vodInfo(p: Playlist, streamId: String): VodInfo = withContext(io) {
        get(xtreamUrls(p).api(XtreamAction.VOD_INFO, "vod_id" to streamId)) { XtreamParser.parseVodInfo(it) }
    }

    /** Series page details and every episode (get_series_info). A series has at most a few hundred. */
    suspend fun seriesInfo(p: Playlist, seriesId: String): Pair<VodInfo, List<Episode>> = withContext(io) {
        val episodes = ArrayList<Episode>()
        val info = get(xtreamUrls(p).api(XtreamAction.SERIES_INFO, "series_id" to seriesId)) { r ->
            XtreamParser.parseSeriesInfo(r) { episodes += it }
        }
        episodes.sortWith(compareBy({ it.season }, { it.number }))
        info to episodes
    }

    private fun xtreamUrls(p: Playlist) = XtreamUrls(XtreamCredentials(p.url, p.username.orEmpty(), p.password.orEmpty()))

    suspend fun sync(p: Playlist, progress: (String) -> Unit) {
        when (p.kind) {
            Playlist.KIND_XTREAM -> syncXtream(p, progress)
            Playlist.KIND_DEMO -> syncDemo(p)
            else -> syncM3u(p, progress)
        }
        // New adult categories/entries are hidden on every load; the admin's own choices stay.
        // Silently: the last progress line stays up while it runs.
        try {
            repo.autoHideAdult(p.id, adultNames())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never fail a load over this; the admin can still hide things by hand.
            android.util.Log.w("WorldTV", "adult auto-hide failed", e)
        }
    }

    private suspend fun syncXtream(p: Playlist, progress: (String) -> Unit) = withContext(io) {
        val c = XtreamCredentials(p.url, p.username.orEmpty(), p.password.orEmpty())
        val urls = XtreamUrls(c)
        progress("Signing in…")
        val account = login(c)
        repo.saveAccount(p.id, account)

        for (type in listOf(ContentType.LIVE, ContentType.MOVIE, ContentType.SERIES)) {
            val label = when (type) {
                ContentType.LIVE -> "Live channels"
                ContentType.MOVIE -> "Movies"
                ContentType.SERIES -> "Series"
            }
            progress("$label: categories…")
            val catAction = when (type) {
                ContentType.LIVE -> XtreamAction.LIVE_CATEGORIES
                ContentType.MOVIE -> XtreamAction.VOD_CATEGORIES
                ContentType.SERIES -> XtreamAction.SERIES_CATEGORIES
            }
            val categories = ArrayList<Category>()
            get(urls.api(catAction)) { r -> XtreamParser.parseCategories(r, type) { categories += it } }

            val listAction = when (type) {
                ContentType.LIVE -> XtreamAction.LIVE_STREAMS
                ContentType.MOVIE -> XtreamAction.VOD_STREAMS
                ContentType.SERIES -> XtreamAction.SERIES
            }
            get(urls.api(listAction)) { reader ->
                replaceLibrary(p.id, type, categories) { insert ->
                    var n = 0
                    val onEach: (Entry) -> Unit = { e ->
                        insert(e)
                        if (++n % 2000 == 0) progress("$label: $n")
                    }
                    when (type) {
                        ContentType.LIVE -> XtreamParser.parseLiveStreams(reader, onEach)
                        ContentType.MOVIE -> XtreamParser.parseVodStreams(reader, onEach)
                        ContentType.SERIES -> XtreamParser.parseSeries(reader, onEach)
                    }
                    progress("$label: $n")
                }
            }
        }
        repo.markSynced(p.id, urls.xmltv())
    }

    private suspend fun syncM3u(p: Playlist, progress: (String) -> Unit) = withContext(io) {
        progress("Downloading playlist…")
        var epgUrl: String? = null
        get(p.url) { reader ->
            val w = db.writableDatabase
            w.beginTransactionNonExclusive()
            try {
                deleteLibrary(w, p.id, null)
                val insertEntry = w.compileStatement(INSERT_ENTRY)
                val insertCategory = w.compileStatement(INSERT_CATEGORY)
                val seenGroups = HashMap<String, Int>() // "type|group" -> sort
                var n = 0
                M3uParser.parse(reader, onEpgUrl = { epgUrl = it }) { m ->
                    val group = m.group?.trim().orEmpty().ifEmpty { "Uncategorised" }
                    val key = "${m.type.ordinal}|$group"
                    if (key !in seenGroups) {
                        seenGroups[key] = seenGroups.size
                        bindCategory(insertCategory, p.id, Category(group, group, m.type), seenGroups.size)
                    }
                    bindEntry(
                        insertEntry,
                        p.id,
                        Entry(
                            id = m.url,
                            type = m.type,
                            name = m.name,
                            categoryId = group,
                            logo = m.logo,
                            streamUrl = m.url,
                            epgId = m.tvgId,
                            catchupDays = m.catchupDays,
                            order = n,
                        ),
                    )
                    if (++n % 2000 == 0) progress("Entries: $n")
                }
                progress("Entries: $n")
                w.setTransactionSuccessful()
            } finally {
                w.endTransaction()
            }
        }
        repo.markSynced(p.id, epgUrl)
    }

    /** The sample playlist: rebuilt from [Demo], nothing downloaded. */
    private suspend fun syncDemo(p: Playlist) = withContext(io) {
        val entries = Demo.entries(System.currentTimeMillis() / 1000)
        for (type in ContentType.values()) {
            replaceLibrary(p.id, type, Demo.categories().filter { it.type == type }) { insert ->
                entries.filter { it.type == type }.forEach(insert)
            }
        }
        repo.markSynced(p.id, null)
    }

    /** Replaces one library (categories + entries) in a single transaction. */
    private fun replaceLibrary(
        playlistId: Long,
        type: ContentType,
        categories: List<Category>,
        fill: ((Entry) -> Unit) -> Unit,
    ) {
        val w = db.writableDatabase
        w.beginTransactionNonExclusive()
        try {
            deleteLibrary(w, playlistId, type)
            val insertCategory = w.compileStatement(INSERT_CATEGORY)
            categories.forEachIndexed { i, cat -> bindCategory(insertCategory, playlistId, cat, i) }
            val insertEntry = w.compileStatement(INSERT_ENTRY)
            fill { e -> bindEntry(insertEntry, playlistId, e) }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    private fun deleteLibrary(w: SQLiteDatabase, playlistId: Long, type: ContentType?) {
        if (type == null) {
            val args = arrayOf(playlistId.toString())
            w.delete("entry", "playlist_id = ?", args)
            w.delete("category", "playlist_id = ?", args)
        } else {
            val args = arrayOf(playlistId.toString(), type.ordinal.toString())
            w.delete("entry", "playlist_id = ? AND type = ?", args)
            w.delete("category", "playlist_id = ? AND type = ?", args)
        }
    }

    private fun bindCategory(s: SQLiteStatement, playlistId: Long, c: Category, sort: Int) {
        s.clearBindings()
        s.bindLong(1, playlistId)
        s.bindLong(2, c.type.ordinal.toLong())
        s.bindString(3, c.id)
        s.bindString(4, c.name)
        s.bindLong(5, sort.toLong())
        s.executeInsert()
    }

    private fun bindEntry(s: SQLiteStatement, playlistId: Long, e: Entry) {
        s.clearBindings()
        s.bindLong(1, playlistId)
        s.bindLong(2, e.type.ordinal.toLong())
        s.bindString(3, e.id)
        s.bindString(4, e.name)
        s.bindNullable(5, e.categoryId)
        s.bindNullable(6, e.logo)
        s.bindNullable(7, e.streamUrl)
        s.bindNullable(8, e.epgId)
        s.bindLong(9, e.catchupDays.toLong())
        s.bindNullable(10, e.containerExt)
        s.bindNullable(11, e.rating)
        s.bindNullable(12, e.plot)
        s.bindLong(13, e.added)
        s.bindLong(14, e.order.toLong())
        s.bindLong(15, if (e.adult) 1 else 0)
        s.executeInsert()
    }

    private fun SQLiteStatement.bindNullable(i: Int, v: String?) {
        if (v == null) bindNull(i) else bindString(i, v)
    }

    private fun <T> get(url: String, block: (Reader) -> T): T {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw SyncException("Server returned HTTP ${response.code}")
            val body = response.body ?: throw SyncException("Empty response")
            return block(body.charStream())
        }
    }

    companion object {
        const val USER_AGENT = "IPTVPlayer/0.1 (Linux; Android)"

        private const val INSERT_CATEGORY =
            "INSERT OR REPLACE INTO category(playlist_id, type, cat_id, name, sort) VALUES (?,?,?,?,?)"

        private const val INSERT_ENTRY =
            """INSERT OR REPLACE INTO entry(playlist_id, type, item_id, name, category_id, logo, stream_url,
               epg_id, catchup_days, ext, rating, plot, added, sort, adult) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"""
    }
}
