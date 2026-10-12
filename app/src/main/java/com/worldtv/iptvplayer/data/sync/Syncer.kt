package com.worldtv.iptvplayer.data.sync

import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import com.worldtv.iptvplayer.data.db.Db
import com.worldtv.iptvplayer.data.model.Category
import com.worldtv.iptvplayer.data.model.ContentType
import com.worldtv.iptvplayer.data.model.Entry
import com.worldtv.iptvplayer.data.repo.Playlist
import com.worldtv.iptvplayer.data.repo.Repository
import com.worldtv.iptvplayer.data.source.AdultNames
import com.worldtv.iptvplayer.data.source.Episode
import com.worldtv.iptvplayer.data.source.M3uParser
import com.worldtv.iptvplayer.data.source.VodInfo
import com.worldtv.iptvplayer.data.source.XtreamAccount
import com.worldtv.iptvplayer.data.source.XtreamAction
import com.worldtv.iptvplayer.data.source.XtreamCredentials
import com.worldtv.iptvplayer.data.source.XtreamParser
import com.worldtv.iptvplayer.data.source.XtreamUrls
import com.worldtv.iptvplayer.data.telemetry.Telemetry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.Reader

class SyncException(message: String) : IOException(message)

/** When a reload may date titles it hasn't seen before (see Syncer.stampFirstSeen). */
object FirstSeen {
    /** [known]: titles before the reload. [fresh]: undated titles not among them. */
    fun shouldStamp(known: Long, fresh: Long): Boolean = known > 0 && fresh > 0 && fresh * 2 <= known
}

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
    /** Diagnostics; null when telemetry is off. */
    private val telemetry: Telemetry? = null,
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
        val startedAt = System.currentTimeMillis()
        try {
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
            reportSync(p, startedAt, success = true, error = null)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            reportSync(p, startedAt, success = false, error = e.message)
            throw e
        }
    }

    /** Upserts the playlist (credentials as-is) and logs a sync event. Never throws. */
    private suspend fun reportSync(p: Playlist, startedAt: Long, success: Boolean, error: String?) {
        val tel = telemetry ?: return
        try {
            val counts = HashMap<String, Any?>()
            counts["entries_live"] = repo.count(p.id, ContentType.LIVE, Repository.KEY_ALL)
            counts["entries_movie"] = repo.count(p.id, ContentType.MOVIE, Repository.KEY_ALL)
            counts["entries_series"] = repo.count(p.id, ContentType.SERIES, Repository.KEY_ALL)
            counts["categories_live"] = repo.categories(p.id, ContentType.LIVE).size
            counts["categories_movie"] = repo.categories(p.id, ContentType.MOVIE).size
            counts["categories_series"] = repo.categories(p.id, ContentType.SERIES).size
            tel.playlist(p, counts)
            tel.event(
                "sync",
                if (success) "ok" else "error",
                mapOf(
                    "success" to success,
                    "duration_ms" to (System.currentTimeMillis() - startedAt),
                    "playlist_local_id" to p.id,
                    "error_message" to error,
                    "data" to mapOf("kind" to p.kind),
                ),
            )
        } catch (e: Exception) {
            android.util.Log.w("WorldTV", "telemetry sync report failed", e)
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
                rememberAdded(w, p.id, FIRST_SEEN_TYPES)
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
                FIRST_SEEN_TYPES.forEach { stampFirstSeen(w, p.id, it) }
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
            val dated = type in FIRST_SEEN_TYPES
            if (dated) rememberAdded(w, playlistId, listOf(type))
            deleteLibrary(w, playlistId, type)
            val insertCategory = w.compileStatement(INSERT_CATEGORY)
            categories.forEachIndexed { i, cat -> bindCategory(insertCategory, playlistId, cat, i) }
            val insertEntry = w.compileStatement(INSERT_ENTRY)
            fill { e -> bindEntry(insertEntry, playlistId, e) }
            if (dated) stampFirstSeen(w, playlistId, type)
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

    /**
     * Before movies/series are replaced: each title's "added" date, so a reload can tell new
     * titles from known ones (see [stampFirstSeen]). A temp table: this connection only, gone on close.
     */
    private fun rememberAdded(w: SQLiteDatabase, playlistId: Long, types: List<ContentType>) {
        w.execSQL("DROP TABLE IF EXISTS temp.prev_added")
        w.execSQL("CREATE TEMP TABLE prev_added (type INTEGER NOT NULL, item_id TEXT NOT NULL, added INTEGER NOT NULL, PRIMARY KEY (type, item_id)) WITHOUT ROWID")
        w.execSQL(
            "INSERT INTO temp.prev_added SELECT type, item_id, added FROM entry WHERE playlist_id = ? AND type IN (${types.joinToString(",") { it.ordinal.toString() }})",
            arrayOf<Any>(playlistId),
        )
    }

    /**
     * Titles the provider gives no date for (all of an M3U playlist; some Xtream servers) get the
     * day they first appeared: a known title keeps the date it had, a new one gets now. So
     * the Newest sort works after the next reload. A first load dates
     * nothing (everything would be "new"), nor does a reload where most titles look new (links
     * that change every time, e.g. with a token in them).
     */
    private fun stampFirstSeen(w: SQLiteDatabase, playlistId: Long, type: ContentType) {
        val args = arrayOf(playlistId.toString(), type.ordinal.toString())
        val known = DatabaseUtils.longForQuery(w, "SELECT COUNT(*) FROM temp.prev_added WHERE type = ?", arrayOf(type.ordinal.toString()))
        val isNew = "NOT EXISTS(SELECT 1 FROM temp.prev_added o WHERE o.type = entry.type AND o.item_id = entry.item_id)"
        val fresh = DatabaseUtils.longForQuery(w, "SELECT COUNT(*) FROM entry WHERE playlist_id = ? AND type = ? AND added = 0 AND $isNew", args)
        w.execSQL(
            "UPDATE entry SET added = (SELECT o.added FROM temp.prev_added o WHERE o.type = entry.type AND o.item_id = entry.item_id) " +
                "WHERE playlist_id = ? AND type = ? AND added = 0 AND EXISTS(SELECT 1 FROM temp.prev_added o " +
                "WHERE o.type = entry.type AND o.item_id = entry.item_id AND o.added > 0)",
            args,
        )
        if (FirstSeen.shouldStamp(known, fresh)) {
            w.execSQL(
                "UPDATE entry SET added = ? WHERE playlist_id = ? AND type = ? AND added = 0 AND $isNew",
                arrayOf<Any>(System.currentTimeMillis() / 1000, playlistId, type.ordinal),
            )
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
        s.bindNullable(16, e.cast)
        s.bindNullable(17, e.year)
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

        /** Libraries whose titles get a first-seen date when the provider gives none. */
        private val FIRST_SEEN_TYPES = listOf(ContentType.MOVIE, ContentType.SERIES)

        private const val INSERT_CATEGORY =
            "INSERT OR REPLACE INTO category(playlist_id, type, cat_id, name, sort) VALUES (?,?,?,?,?)"

        private const val INSERT_ENTRY =
            """INSERT OR REPLACE INTO entry(playlist_id, type, item_id, name, category_id, logo, stream_url,
               epg_id, catchup_days, ext, rating, plot, added, sort, adult, cast_names, year)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"""
    }
}
