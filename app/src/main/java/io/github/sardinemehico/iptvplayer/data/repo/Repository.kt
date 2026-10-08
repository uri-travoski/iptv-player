package io.github.sardinemehico.iptvplayer.data.repo

import android.content.ContentValues
import android.database.Cursor
import io.github.sardinemehico.iptvplayer.data.db.Db
import io.github.sardinemehico.iptvplayer.data.model.ContentType
import io.github.sardinemehico.iptvplayer.data.source.AdultNames
import io.github.sardinemehico.iptvplayer.data.source.XtreamAccount
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

data class Playlist(
    val id: Long,
    val name: String,
    val kind: String,
    val url: String,
    val username: String?,
    val password: String?,
    val epgUrl: String?,
    val lastSync: Long,
    val expires: Long?,
    val maxConnections: Int,
    val formats: List<String>,
    val serverTz: String?,
    val status: String?,
    /** Salted hash of the playlist's 6-digit PIN, or null if it has none. See [Pin]. */
    val pinHash: String?,
) {
    val isXtream get() = kind == KIND_XTREAM
    val hasPin get() = pinHash != null

    companion object {
        const val KIND_XTREAM = "xtream"
        const val KIND_M3U = "m3u"
    }
}

data class CategoryRow(val key: String, val name: String)

/** A category as the admin sees it: hidden or not, and how many of its entries are hidden. */
data class AdminCategory(val row: CategoryRow, val hidden: Boolean, val hiddenEntries: Int)

/** An entry as the admin sees it. */
data class AdminEntry(val itemId: String, val name: String, val hidden: Boolean)

/** What a list row needs, nothing more: keeps paged memory small. */
data class EntryRow(
    val itemId: String,
    val name: String,
    val logo: String?,
    val streamUrl: String?,
    val ext: String?,
    val catchupDays: Int,
    val favourite: Boolean,
)

/** Saved playback point. [episodeId] is set for series. */
data class Progress(val episodeId: String?, val positionMs: Long, val durationMs: Long)

/** The stored fields a movie or series page shows before (or without) the panel's info call. */
data class EntryDetails(val rating: String?, val plot: String?, val ext: String?)

/**
 * All database reads and writes. Every call runs on [io]; nothing here may be called
 * from the UI thread directly.
 */
class Repository(private val db: Db, private val io: CoroutineDispatcher) {

    // ---- playlists ----

    suspend fun playlists(): List<Playlist> = withContext(io) {
        db.readableDatabase.rawQuery("SELECT * FROM playlist ORDER BY id", null).use { c ->
            val out = ArrayList<Playlist>()
            while (c.moveToNext()) out += c.toPlaylist()
            out
        }
    }

    suspend fun playlist(id: Long): Playlist? = withContext(io) {
        db.readableDatabase.rawQuery("SELECT * FROM playlist WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) c.toPlaylist() else null
        }
    }

    suspend fun addPlaylist(
        name: String,
        kind: String,
        url: String,
        username: String?,
        password: String?,
        pinHash: String? = null,
    ): Long = withContext(io) {
        val v = ContentValues().apply {
            put("name", name)
            put("kind", kind)
            put("url", url)
            put("username", username)
            put("password", password)
            put("pin_hash", pinHash ?: Pin.hash(Pin.DEFAULT))
        }
        db.writableDatabase.insertOrThrow("playlist", null, v)
    }

    /** Sets a playlist's PIN; null puts back the default 000000 (every playlist has a PIN). */
    suspend fun setPin(id: Long, pinHash: String?) = withContext(io) {
        val v = ContentValues().apply { put("pin_hash", pinHash ?: Pin.hash(Pin.DEFAULT)) }
        db.writableDatabase.update("playlist", v, "id = ?", arrayOf(id.toString()))
    }

    suspend fun deletePlaylist(id: Long) = withContext(io) {
        val w = db.writableDatabase
        val args = arrayOf(id.toString())
        w.beginTransaction()
        try {
            w.delete("entry", "playlist_id = ?", args)
            w.delete("category", "playlist_id = ?", args)
            w.delete("favourite", "playlist_id = ?", args)
            w.delete("progress", "playlist_id = ?", args)
            w.delete("hidden_category", "playlist_id = ?", args)
            w.delete("hidden_item", "playlist_id = ?", args)
            w.delete("shown_category", "playlist_id = ?", args)
            w.delete("shown_item", "playlist_id = ?", args)
            w.delete("playlist", "id = ?", args)
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    suspend fun saveAccount(id: Long, a: XtreamAccount) = withContext(io) {
        val v = ContentValues().apply {
            if (a.expiresAt != null) put("expires", a.expiresAt) else putNull("expires")
            put("max_connections", a.maxConnections)
            put("formats", a.allowedFormats.joinToString(","))
            put("server_tz", a.serverTimezone)
            put("status", a.status)
        }
        db.writableDatabase.update("playlist", v, "id = ?", arrayOf(id.toString()))
    }

    suspend fun markSynced(id: Long, epgUrl: String?) = withContext(io) {
        val v = ContentValues().apply {
            put("last_sync", System.currentTimeMillis())
            if (epgUrl != null) put("epg_url", epgUrl)
        }
        db.writableDatabase.update("playlist", v, "id = ?", arrayOf(id.toString()))
    }

    // ---- browsing ----

    /**
     * Categories of one library, with the virtual "All" and "Favourites" entries first
     * (and "Continue watching" for movies and series).
     */
    suspend fun categories(playlistId: Long, type: ContentType): List<CategoryRow> = withContext(io) {
        val out = arrayListOf(CategoryRow(KEY_ALL, "All"), CategoryRow(KEY_FAV, "Favourites"))
        if (type != ContentType.LIVE) out += CategoryRow(KEY_CONTINUE, "Continue watching")
        db.readableDatabase.rawQuery(
            "SELECT cat_id, name FROM category c WHERE playlist_id = ? AND type = ? AND NOT EXISTS(" +
                "SELECT 1 FROM hidden_category h WHERE h.playlist_id = c.playlist_id AND h.type = c.type " +
                "AND h.cat_id = c.cat_id) ORDER BY sort",
            arrayOf(playlistId.toString(), type.ordinal.toString()),
        ).use { c ->
            while (c.moveToNext()) out += CategoryRow(c.getString(0), c.getString(1))
        }
        out
    }

    /** Every provider category, whether it is hidden and how many of its entries are: for the admin. */
    suspend fun categoriesForAdmin(playlistId: Long, type: ContentType): List<AdminCategory> = withContext(io) {
        db.readableDatabase.rawQuery(
            "SELECT cat_id, name, EXISTS(SELECT 1 FROM hidden_category h WHERE h.playlist_id = c.playlist_id " +
                "AND h.type = c.type AND h.cat_id = c.cat_id), " +
                "(SELECT COUNT(*) FROM hidden_item i JOIN entry e ON e.playlist_id = i.playlist_id AND e.type = i.type " +
                "AND e.item_id = i.item_id WHERE i.playlist_id = c.playlist_id AND i.type = c.type AND e.category_id = c.cat_id) " +
                "FROM category c WHERE playlist_id = ? AND type = ? ORDER BY sort",
            arrayOf(playlistId.toString(), type.ordinal.toString()),
        ).use { c ->
            val out = ArrayList<AdminCategory>(c.count)
            while (c.moveToNext()) out += AdminCategory(CategoryRow(c.getString(0), c.getString(1)), c.getInt(2) == 1, c.getInt(3))
            out
        }
    }

    /** Every entry of one category with whether it is hidden: for the admin. */
    suspend fun entriesForAdmin(playlistId: Long, type: ContentType, catId: String): List<AdminEntry> = withContext(io) {
        db.readableDatabase.rawQuery(
            "SELECT item_id, name, EXISTS(SELECT 1 FROM hidden_item i WHERE i.playlist_id = e.playlist_id " +
                "AND i.type = e.type AND i.item_id = e.item_id) FROM entry e " +
                "WHERE playlist_id = ? AND type = ? AND category_id = ? ORDER BY sort",
            arrayOf(playlistId.toString(), type.ordinal.toString(), catId),
        ).use { c ->
            val out = ArrayList<AdminEntry>(c.count)
            while (c.moveToNext()) out += AdminEntry(c.getString(0), c.getString(1), c.getInt(2) == 1)
            out
        }
    }

    /** Hides or shows single entries [itemIds] of one library: the admin's choice, kept over adult auto-hiding. */
    suspend fun setEntriesHidden(playlistId: Long, type: ContentType, itemIds: Collection<String>, hidden: Boolean) =
        setRows("hidden_item", "shown_item", "item_id", playlistId, type, itemIds, hidden)

    /**
     * Hides adult categories and entries: names matching [names] (the admin's word lists) and
     * entries the provider flags `is_adult`. Runs after every load and refresh, and when the word
     * lists change. Automatic hides are worked out again each time (so removing a word un-hides
     * what only it hid); the admin's own choices, shown or hidden, are never touched. Entries in
     * a hidden category, or in one the admin showed by hand, are skipped.
     *
     * [adopt]: once, after the update that added the `auto` flag: rows hidden before it that the
     * rules explain become automatic; the rest stay as hand-made hides.
     */
    suspend fun autoHideAdult(playlistId: Long, names: AdultNames, adopt: Boolean = false) = withContext(io) {
        val w = db.writableDatabase
        val pl = playlistId.toString()
        w.beginTransaction()
        try {
            if (adopt) adoptHidden(w, playlistId, names)
            w.delete("hidden_category", "playlist_id = ? AND auto = 1", arrayOf(pl))
            w.delete("hidden_item", "playlist_id = ? AND auto = 1", arrayOf(pl))
            for (type in ContentType.values()) {
                val args = arrayOf(pl, type.ordinal.toString())
                val cats = ArrayList<String>()
                w.rawQuery(
                    "SELECT cat_id, name FROM category c WHERE playlist_id = ? AND type = ? " +
                        "AND NOT EXISTS(SELECT 1 FROM shown_category s WHERE s.playlist_id = c.playlist_id AND s.type = c.type AND s.cat_id = c.cat_id) " +
                        "AND NOT EXISTS(SELECT 1 FROM hidden_category h WHERE h.playlist_id = c.playlist_id AND h.type = c.type AND h.cat_id = c.cat_id)",
                    args,
                ).use { c -> while (c.moveToNext()) if (names.isAdultCategory(c.getString(1))) cats += c.getString(0) }
                insertHidden(w, "hidden_category", "cat_id", playlistId, type, cats, auto = true)
                val items = ArrayList<String>()
                w.rawQuery(
                    "SELECT item_id, name, adult FROM entry e WHERE playlist_id = ? AND type = ? " +
                        "AND NOT EXISTS(SELECT 1 FROM hidden_category h WHERE h.playlist_id = e.playlist_id AND h.type = e.type AND h.cat_id = e.category_id) " +
                        // A category the admin showed by hand is shown whole.
                        "AND NOT EXISTS(SELECT 1 FROM shown_category sc WHERE sc.playlist_id = e.playlist_id AND sc.type = e.type AND sc.cat_id = e.category_id) " +
                        "AND NOT EXISTS(SELECT 1 FROM hidden_item i WHERE i.playlist_id = e.playlist_id AND i.type = e.type AND i.item_id = e.item_id) " +
                        "AND NOT EXISTS(SELECT 1 FROM shown_item s WHERE s.playlist_id = e.playlist_id AND s.type = e.type AND s.item_id = e.item_id)",
                    args,
                ).use { c ->
                    while (c.moveToNext()) if (c.getInt(2) == 1 || names.isAdultEntry(c.getString(1))) items += c.getString(0)
                }
                insertHidden(w, "hidden_item", "item_id", playlistId, type, items, auto = true)
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    private fun adoptHidden(w: android.database.sqlite.SQLiteDatabase, playlistId: Long, names: AdultNames) {
        val pl = playlistId.toString()
        w.rawQuery(
            "SELECT h.type, h.cat_id, c.name FROM hidden_category h JOIN category c ON c.playlist_id = h.playlist_id " +
                "AND c.type = h.type AND c.cat_id = h.cat_id WHERE h.playlist_id = ? AND h.auto = 0",
            arrayOf(pl),
        ).use { c ->
            while (c.moveToNext()) {
                if (names.isAdultCategory(c.getString(2))) {
                    w.execSQL("UPDATE hidden_category SET auto = 1 WHERE playlist_id = ? AND type = ? AND cat_id = ?", arrayOf<Any>(playlistId, c.getInt(0), c.getString(1)))
                }
            }
        }
        w.rawQuery(
            "SELECT h.type, h.item_id, e.name, e.adult FROM hidden_item h JOIN entry e ON e.playlist_id = h.playlist_id " +
                "AND e.type = h.type AND e.item_id = h.item_id WHERE h.playlist_id = ? AND h.auto = 0",
            arrayOf(pl),
        ).use { c ->
            while (c.moveToNext()) {
                if (c.getInt(3) == 1 || names.isAdultEntry(c.getString(2))) {
                    w.execSQL("UPDATE hidden_item SET auto = 1 WHERE playlist_id = ? AND type = ? AND item_id = ?", arrayOf<Any>(playlistId, c.getInt(0), c.getString(1)))
                }
            }
        }
    }

    /** Adds rows to a hidden table: automatic ones never replace a hand-made hide; hand-made ones replace automatic ones. */
    private fun insertHidden(w: android.database.sqlite.SQLiteDatabase, table: String, column: String, playlistId: Long, type: ContentType, ids: Collection<String>, auto: Boolean) {
        for (id in ids) {
            val v = ContentValues().apply {
                put("playlist_id", playlistId)
                put("type", type.ordinal)
                put(column, id)
                put("auto", if (auto) 1 else 0)
            }
            w.insertWithOnConflict(
                table, null, v,
                if (auto) android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE else android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
    }

    private fun insertShown(w: android.database.sqlite.SQLiteDatabase, table: String, column: String, playlistId: Long, type: ContentType, ids: Collection<String>) {
        for (id in ids) {
            val v = ContentValues().apply {
                put("playlist_id", playlistId)
                put("type", type.ordinal)
                put(column, id)
            }
            w.insertWithOnConflict(table, null, v, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    /**
     * Hides or shows categories [catIds] of one library: the admin's choice, kept over adult
     * auto-hiding. Showing a category shows it whole: entries hidden inside it are shown again
     * (single ones can be hidden afterwards).
     */
    suspend fun setHidden(playlistId: Long, type: ContentType, catIds: Collection<String>, hidden: Boolean) {
        setRows("hidden_category", "shown_category", "cat_id", playlistId, type, catIds, hidden)
        if (hidden) return
        withContext(io) {
            val w = db.writableDatabase
            for (id in catIds) {
                w.execSQL(
                    "DELETE FROM hidden_item WHERE playlist_id = ? AND type = ? AND item_id IN " +
                        "(SELECT item_id FROM entry WHERE playlist_id = ? AND type = ? AND category_id = ?)",
                    arrayOf<Any>(playlistId, type.ordinal, playlistId, type.ordinal, id),
                )
            }
        }
    }

    /** Hiding adds a hand-made row to [hiddenTable]; showing removes it and records the choice in [shownTable]. */
    private suspend fun setRows(
        hiddenTable: String,
        shownTable: String,
        column: String,
        playlistId: Long,
        type: ContentType,
        ids: Collection<String>,
        hidden: Boolean,
    ) = withContext(io) {
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            if (hidden) insertHidden(w, hiddenTable, column, playlistId, type, ids, auto = false)
            else insertShown(w, shownTable, column, playlistId, type, ids)
            val remove = if (hidden) shownTable else hiddenTable
            for (id in ids) {
                w.delete(remove, "playlist_id = ? AND type = ? AND $column = ?", arrayOf(playlistId.toString(), type.ordinal.toString(), id))
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    suspend fun count(playlistId: Long, type: ContentType, key: String): Int = withContext(io) {
        val (where, args) = filter(playlistId, type, key)
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM entry e $where", args).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    suspend fun page(playlistId: Long, type: ContentType, key: String, offset: Int, limit: Int): List<EntryRow> =
        withContext(io) {
            val (where, args) = filter(playlistId, type, key)
            db.readableDatabase.rawQuery(
                """SELECT e.item_id, e.name, e.logo, e.stream_url, e.ext, e.catchup_days,
                   EXISTS(SELECT 1 FROM favourite f WHERE f.playlist_id = e.playlist_id
                          AND f.type = e.type AND f.item_id = e.item_id)
                   FROM entry e $where ORDER BY ${order(key)} LIMIT $limit OFFSET $offset""",
                args,
            ).use { c ->
                val out = ArrayList<EntryRow>(c.count)
                while (c.moveToNext()) {
                    out += EntryRow(
                        itemId = c.getString(0),
                        name = c.getString(1),
                        logo = c.getStringOrNull(2),
                        streamUrl = c.getStringOrNull(3),
                        ext = c.getStringOrNull(4),
                        catchupDays = c.getInt(5),
                        favourite = c.getInt(6) != 0,
                    )
                }
                out
            }
        }

    /** Position of [itemId] inside the list for [key], or -1. Used to restore the last channel. */
    suspend fun indexOf(playlistId: Long, type: ContentType, key: String, itemId: String): Int = withContext(io) {
        val r = db.readableDatabase
        val sort = r.rawQuery(
            "SELECT sort FROM entry WHERE playlist_id = ? AND type = ? AND item_id = ?",
            arrayOf(playlistId.toString(), type.ordinal.toString(), itemId),
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else return@withContext -1 }
        val (where, args) = filter(playlistId, type, key)
        // Not in this list (another category, or a hidden one): no position.
        val inList = r.rawQuery("SELECT 1 FROM entry e $where AND e.item_id = ?", args + itemId).use { it.moveToFirst() }
        if (!inList) return@withContext -1
        r.rawQuery("SELECT COUNT(*) FROM entry e $where AND e.sort < $sort", args).use { c ->
            if (c.moveToFirst()) c.getInt(0) else -1
        }
    }

    suspend fun details(playlistId: Long, type: ContentType, itemId: String): EntryDetails? = withContext(io) {
        db.readableDatabase.rawQuery(
            "SELECT rating, plot, ext FROM entry WHERE playlist_id = ? AND type = ? AND item_id = ?",
            arrayOf(playlistId.toString(), type.ordinal.toString(), itemId),
        ).use { c -> if (c.moveToFirst()) EntryDetails(c.getStringOrNull(0), c.getStringOrNull(1), c.getStringOrNull(2)) else null }
    }

    suspend fun isFavourite(playlistId: Long, type: ContentType, itemId: String): Boolean = withContext(io) {
        db.readableDatabase.rawQuery(
            "SELECT 1 FROM favourite WHERE playlist_id = ? AND type = ? AND item_id = ?",
            arrayOf(playlistId.toString(), type.ordinal.toString(), itemId),
        ).use { it.moveToFirst() }
    }

    // ---- continue watching ----

    suspend fun progress(playlistId: Long, type: ContentType, itemId: String): Progress? = withContext(io) {
        db.readableDatabase.rawQuery(
            "SELECT episode_id, position_ms, duration_ms FROM progress WHERE playlist_id = ? AND type = ? AND item_id = ?",
            arrayOf(playlistId.toString(), type.ordinal.toString(), itemId),
        ).use { c -> if (c.moveToFirst()) Progress(c.getStringOrNull(0), c.getLong(1), c.getLong(2)) else null }
    }

    /**
     * Records where playback stopped. Too early to matter (under [MIN_RESUME_MS]) or nearly
     * finished (last [END_PERCENT]%) clears it instead, so finished titles leave the list.
     */
    suspend fun saveProgress(
        playlistId: Long,
        type: ContentType,
        itemId: String,
        episodeId: String?,
        positionMs: Long,
        durationMs: Long,
    ) = withContext(io) {
        val w = db.writableDatabase
        val nearEnd = durationMs > 0 && positionMs >= durationMs * END_PERCENT / 100
        if (positionMs < MIN_RESUME_MS || nearEnd) {
            // An early stop in a *different* episode shouldn't wipe the series' saved point.
            val where = if (episodeId == null) "" else " AND (episode_id = ? OR ? = 1)"
            val args = arrayListOf(playlistId.toString(), type.ordinal.toString(), itemId)
            if (episodeId != null) { args += episodeId; args += if (nearEnd) "1" else "0" }
            w.delete("progress", "playlist_id = ? AND type = ? AND item_id = ?$where", args.toTypedArray())
            return@withContext
        }
        val v = ContentValues().apply {
            put("playlist_id", playlistId)
            put("type", type.ordinal)
            put("item_id", itemId)
            put("episode_id", episodeId)
            put("position_ms", positionMs)
            put("duration_ms", durationMs)
            put("updated", System.currentTimeMillis())
        }
        w.insertWithOnConflict("progress", null, v, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Toggles a favourite and returns the new state. */
    suspend fun toggleFavourite(playlistId: Long, type: ContentType, itemId: String): Boolean = withContext(io) {
        val w = db.writableDatabase
        val args = arrayOf(playlistId.toString(), type.ordinal.toString(), itemId)
        val removed = w.delete("favourite", "playlist_id = ? AND type = ? AND item_id = ?", args)
        if (removed > 0) {
            false
        } else {
            val v = ContentValues().apply {
                put("playlist_id", playlistId)
                put("type", type.ordinal)
                put("item_id", itemId)
            }
            w.insertWithOnConflict("favourite", null, v, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)
            true
        }
    }

    /**
     * WHERE clause for list [key]. Every list leaves out hidden entries and entries of hidden
     * categories, so they don't come back through All, Favourites, Continue watching or a search.
     */
    private fun filter(playlistId: Long, type: ContentType, key: String): Pair<String, Array<String>> {
        val (where, args) = listFilter(playlistId, type, key)
        return "$where AND NOT EXISTS(SELECT 1 FROM hidden_category h WHERE h.playlist_id = e.playlist_id " +
            "AND h.type = e.type AND h.cat_id = e.category_id) AND NOT EXISTS(SELECT 1 FROM hidden_item i " +
            "WHERE i.playlist_id = e.playlist_id AND i.type = e.type AND i.item_id = e.item_id)" to args
    }

    private fun listFilter(playlistId: Long, type: ContentType, key: String): Pair<String, Array<String>> {
        val base = arrayOf(playlistId.toString(), type.ordinal.toString())
        return when (key) {
            KEY_ALL -> "WHERE e.playlist_id = ? AND e.type = ?" to base
            KEY_FAV -> ("WHERE e.playlist_id = ? AND e.type = ? AND EXISTS(SELECT 1 FROM favourite f " +
                "WHERE f.playlist_id = e.playlist_id AND f.type = e.type AND f.item_id = e.item_id)") to base
            KEY_CONTINUE -> ("WHERE e.playlist_id = ? AND e.type = ? AND EXISTS(SELECT 1 FROM progress p " +
                "WHERE p.playlist_id = e.playlist_id AND p.type = e.type AND p.item_id = e.item_id)") to base
            else -> if (key.startsWith(SEARCH_PREFIX)) {
                // Substring match on the name. A scan of one library: fine on the IO thread even for 50k rows.
                "WHERE e.playlist_id = ? AND e.type = ? AND e.name LIKE ? ESCAPE '\\'" to
                    (base + ("%" + likeEscape(key.removePrefix(SEARCH_PREFIX)) + "%"))
            } else {
                "WHERE e.playlist_id = ? AND e.type = ? AND e.category_id = ?" to (base + key)
            }
        }
    }

    private fun order(key: String) = if (key == KEY_CONTINUE) {
        "(SELECT p.updated FROM progress p WHERE p.playlist_id = e.playlist_id AND p.type = e.type " +
            "AND p.item_id = e.item_id) DESC"
    } else {
        "e.sort"
    }

    private fun likeEscape(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun Cursor.getStringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)

    private fun Cursor.toPlaylist(): Playlist {
        fun s(name: String) = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }
        fun l(name: String) = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getLong(it) }
        return Playlist(
            id = l("id")!!,
            name = s("name").orEmpty(),
            kind = s("kind").orEmpty(),
            url = s("url").orEmpty(),
            username = s("username"),
            password = s("password"),
            epgUrl = s("epg_url"),
            lastSync = l("last_sync") ?: 0,
            expires = l("expires"),
            maxConnections = (l("max_connections") ?: 0).toInt(),
            formats = s("formats")?.split(',')?.filter { it.isNotBlank() }.orEmpty(),
            serverTz = s("server_tz"),
            status = s("status"),
            pinHash = s("pin_hash"),
        )
    }

    companion object {
        const val KEY_ALL = "\u0000all"
        const val KEY_FAV = "\u0000fav"
        const val KEY_CONTINUE = "\u0000continue"
        private const val SEARCH_PREFIX = "\u0000search:"

        /** List key for a name search, usable wherever a category key is. */
        fun searchKey(query: String) = SEARCH_PREFIX + query.trim()

        fun isSearch(key: String) = key.startsWith(SEARCH_PREFIX)

        /** All, Favourites, Continue watching and search lists: not provider categories. */
        fun isBuiltIn(key: String) = key.startsWith("\u0000")

        const val MIN_RESUME_MS = 30_000L
        const val END_PERCENT = 95
    }
}
