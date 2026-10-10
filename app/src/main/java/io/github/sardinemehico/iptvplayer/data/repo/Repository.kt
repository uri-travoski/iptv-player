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

/** One of the viewer's own Live TV groups. */
data class Group(val id: Long, val name: String)

/** Order of a Movies / Series list (App: the Sort button). */
enum class Sort { NEWEST, RATING, NAME, PROVIDER }

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
/**
 * [io]: quick reads and writes for the screens. [scan]: long adult re-checks of a whole playlist,
 * on their own low-priority thread so they never hold up a screen or playback.
 */
class Repository(private val db: Db, private val io: CoroutineDispatcher, private val scan: CoroutineDispatcher = io) {

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
            w.delete("user_group_item", "group_id IN (SELECT id FROM user_group WHERE playlist_id = ?)", args)
            w.delete("user_group", "playlist_id = ?", args)
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
     * (and "Continue watching" for movies and series). Live TV: the viewer's own groups come
     * before those, newest first.
     */
    suspend fun categories(playlistId: Long, type: ContentType): List<CategoryRow> = withContext(io) {
        val out = ArrayList<CategoryRow>()
        if (type == ContentType.LIVE) groupsNow(playlistId).forEach { out += CategoryRow(groupKey(it.id), it.name) }
        out += CategoryRow(KEY_ALL, "All")
        out += CategoryRow(KEY_FAV, "Favourites")
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
        val r = db.readableDatabase
        val args = arrayOf(playlistId.toString(), type.ordinal.toString())
        // Hidden entries per category in one grouped pass (a sub-query per category took seconds on a box).
        val hiddenPerCat = HashMap<String, Int>()
        r.rawQuery(
            "SELECT e.category_id, COUNT(*) FROM hidden_item i JOIN entry e ON e.playlist_id = i.playlist_id " +
                "AND e.type = i.type AND e.item_id = i.item_id WHERE i.playlist_id = ? AND i.type = ? GROUP BY e.category_id",
            args,
        ).use { c -> while (c.moveToNext()) c.getString(0)?.let { hiddenPerCat[it] = c.getInt(1) } }
        r.rawQuery(
            "SELECT cat_id, name, EXISTS(SELECT 1 FROM hidden_category h WHERE h.playlist_id = c.playlist_id " +
                "AND h.type = c.type AND h.cat_id = c.cat_id) FROM category c WHERE playlist_id = ? AND type = ? ORDER BY sort",
            args,
        ).use { c ->
            val out = ArrayList<AdminCategory>(c.count)
            while (c.moveToNext()) {
                val id = c.getString(0)
                out += AdminCategory(CategoryRow(id, c.getString(1)), c.getInt(2) == 1, hiddenPerCat[id] ?: 0)
            }
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
    suspend fun autoHideAdult(playlistId: Long, names: AdultNames, adopt: Boolean = false) = withContext(scan) {
        val started = System.currentTimeMillis()
        val pl = playlistId.toString()
        if (adopt) {
            val w = db.writableDatabase
            w.beginTransaction()
            try {
                adoptHidden(w, playlistId, names)
                w.setTransactionSuccessful()
            } finally {
                w.endTransaction()
            }
        }
        // Work out what to hide with reads only (WAL: they never block anyone, and nobody waits
        // for them), then write it in one short transaction.
        val r = db.readableDatabase
        val hideCats = HashMap<ContentType, List<String>>()
        val hideItems = HashMap<ContentType, List<String>>()
        for (type in ContentType.values()) {
            val args = arrayOf(pl, type.ordinal.toString())
            fun ids(sql: String) = HashSet<String>().also { set -> r.rawQuery(sql, args).use { c -> while (c.moveToNext()) set += c.getString(0) } }
            val manualCats = ids("SELECT cat_id FROM hidden_category WHERE playlist_id = ? AND type = ? AND auto = 0")
            val shownCats = ids("SELECT cat_id FROM shown_category WHERE playlist_id = ? AND type = ?")
            val manualItems = ids("SELECT item_id FROM hidden_item WHERE playlist_id = ? AND type = ? AND auto = 0")
            val shownItems = ids("SELECT item_id FROM shown_item WHERE playlist_id = ? AND type = ?")
            val cats = ArrayList<String>()
            r.rawQuery("SELECT cat_id, name FROM category WHERE playlist_id = ? AND type = ?", args).use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    if (id !in shownCats && id !in manualCats && names.isAdultCategory(c.getString(1))) cats += id
                }
            }
            // Entries in a hidden category are hidden anyway; a category shown by hand is shown whole.
            val skipCats = HashSet<String>(cats).apply { addAll(manualCats); addAll(shownCats) }
            val items = ArrayList<String>()
            r.rawQuery("SELECT item_id, name, adult, category_id FROM entry WHERE playlist_id = ? AND type = ?", args).use { c ->
                while (c.moveToNext()) {
                    val cat = c.getString(3)
                    if (cat != null && cat in skipCats) continue
                    val id = c.getString(0)
                    if (id in manualItems || id in shownItems) continue
                    if (c.getInt(2) == 1 || names.isAdultEntry(c.getString(1))) items += id
                }
            }
            hideCats[type] = cats
            hideItems[type] = items
        }
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            w.delete("hidden_category", "playlist_id = ? AND auto = 1", arrayOf(pl))
            w.delete("hidden_item", "playlist_id = ? AND auto = 1", arrayOf(pl))
            for (type in ContentType.values()) {
                insertHidden(w, "hidden_category", "cat_id", playlistId, type, hideCats[type].orEmpty(), auto = true)
                insertHidden(w, "hidden_item", "item_id", playlistId, type, hideItems[type].orEmpty(), auto = true)
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
        forgetAdultProgress(playlistId, names)
        val shownByHand = android.database.DatabaseUtils.queryNumEntries(r, "shown_category", "playlist_id = ?", arrayOf(pl))
        android.util.Log.i(
            "WorldTV.Adult",
            "playlist $playlistId: auto-hid ${hideCats.values.sumOf { it.size }} categories, ${hideItems.values.sumOf { it.size }} entries; " +
                "$shownByHand categories shown by hand; ${System.currentTimeMillis() - started} ms",
        )
    }

    /**
     * The admin's "Hide adult": everything the adult rules match is hidden again, also what was
     * shown by hand. Adult categories and entries lose their "shown" choice and become automatic
     * hides; adult entries inside a normal category the admin showed by hand (shown whole) are
     * hidden by hand, as that category's automatic check never looks inside.
     */
    suspend fun hideAllAdult(playlistId: Long, names: AdultNames) = withContext(scan) {
        val pl = playlistId.toString()
        val r = db.readableDatabase
        val adultCats = HashMap<ContentType, List<String>>()
        val adultItems = HashMap<ContentType, List<String>>()
        val insideShown = HashMap<ContentType, List<String>>()
        for (type in ContentType.values()) {
            val args = arrayOf(pl, type.ordinal.toString())
            val cats = ArrayList<String>()
            r.rawQuery("SELECT cat_id, name FROM category WHERE playlist_id = ? AND type = ?", args).use { c ->
                while (c.moveToNext()) if (names.isAdultCategory(c.getString(1))) cats += c.getString(0)
            }
            val shownCats = HashSet<String>().also { set ->
                r.rawQuery("SELECT cat_id FROM shown_category WHERE playlist_id = ? AND type = ?", args).use { c -> while (c.moveToNext()) set += c.getString(0) }
            }
            shownCats.removeAll(cats.toSet())
            val items = ArrayList<String>()
            val inside = ArrayList<String>()
            r.rawQuery("SELECT item_id, name, adult, category_id FROM entry WHERE playlist_id = ? AND type = ?", args).use { c ->
                while (c.moveToNext()) {
                    if (c.getInt(2) != 1 && !names.isAdultEntry(c.getString(1))) continue
                    items += c.getString(0)
                    if (c.getString(3) in shownCats) inside += c.getString(0)
                }
            }
            adultCats[type] = cats
            adultItems[type] = items
            insideShown[type] = inside
        }
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            for (type in ContentType.values()) {
                val t = type.ordinal.toString()
                for (id in adultCats[type].orEmpty()) w.delete("shown_category", "playlist_id = ? AND type = ? AND cat_id = ?", arrayOf(pl, t, id))
                for (id in adultItems[type].orEmpty()) w.delete("shown_item", "playlist_id = ? AND type = ? AND item_id = ?", arrayOf(pl, t, id))
                insertHidden(w, "hidden_item", "item_id", playlistId, type, insideShown[type].orEmpty(), auto = false)
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
        autoHideAdult(playlistId, names)
    }

    /** True when an entry is adult: provider flag, its name, or its category's name. */
    private fun isAdult(playlistId: Long, type: ContentType, itemId: String, names: AdultNames): Boolean =
        db.readableDatabase.rawQuery(
            "SELECT e.name, e.adult, c.name FROM entry e LEFT JOIN category c ON c.playlist_id = e.playlist_id " +
                "AND c.type = e.type AND c.cat_id = e.category_id WHERE e.playlist_id = ? AND e.type = ? AND e.item_id = ?",
            arrayOf(playlistId.toString(), type.ordinal.toString(), itemId),
        ).use { c ->
            c.moveToFirst() && (c.getInt(1) == 1 || names.isAdultEntry(c.getString(0)) || (!c.isNull(2) && names.isAdultCategory(c.getString(2))))
        }

    /** Adult titles never stay in Continue watching, even when shown by hand and played. */
    private fun forgetAdultProgress(playlistId: Long, names: AdultNames) {
        val gone = ArrayList<Pair<Int, String>>()
        db.readableDatabase.rawQuery("SELECT type, item_id FROM progress WHERE playlist_id = ?", arrayOf(playlistId.toString())).use { c ->
            while (c.moveToNext()) {
                val type = ContentType.values().getOrNull(c.getInt(0)) ?: continue
                if (isAdult(playlistId, type, c.getString(1), names)) gone += c.getInt(0) to c.getString(1)
            }
        }
        for ((type, id) in gone) {
            db.writableDatabase.delete("progress", "playlist_id = ? AND type = ? AND item_id = ?", arrayOf(playlistId.toString(), type.toString(), id))
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
     * "Show all": back to the defaults for [ids] (categories, or entries with [entries]): the
     * admin's own hides and shows are cleared, so only automatic (adult) hiding applies. Run
     * [autoHideAdult] afterwards to hide adult ones again.
     */
    suspend fun resetToDefault(playlistId: Long, type: ContentType, ids: Collection<String>, entries: Boolean) = withContext(io) {
        val (hidden, shown, column) = if (entries) Triple("hidden_item", "shown_item", "item_id") else Triple("hidden_category", "shown_category", "cat_id")
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            for (id in ids) {
                val args = arrayOf(playlistId.toString(), type.ordinal.toString(), id)
                w.delete(hidden, "playlist_id = ? AND type = ? AND $column = ? AND auto = 0", args)
                w.delete(shown, "playlist_id = ? AND type = ? AND $column = ?", args)
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    /**
     * Before 0.1.28, "Show all" marked every category as shown by hand, which switched adult
     * hiding off for that library. A library where every category is marked that way gets the
     * marks cleared. Returns how many marks were removed.
     */
    suspend fun forgetBulkShown(playlistId: Long): Int = withContext(io) {
        val w = db.writableDatabase
        var removed = 0
        for (type in ContentType.values()) {
            val args = arrayOf(playlistId.toString(), type.ordinal.toString())
            val cats = android.database.DatabaseUtils.queryNumEntries(w, "category", "playlist_id = ? AND type = ?", args)
            val shown = android.database.DatabaseUtils.queryNumEntries(w, "shown_category", "playlist_id = ? AND type = ?", args)
            if (cats > 0 && shown >= cats) removed += w.delete("shown_category", "playlist_id = ? AND type = ?", args)
        }
        removed
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

    suspend fun page(playlistId: Long, type: ContentType, key: String, offset: Int, limit: Int, sort: Sort = Sort.PROVIDER): List<EntryRow> =
        withContext(io) {
            val (where, args) = filter(playlistId, type, key)
            db.readableDatabase.rawQuery(
                """SELECT e.item_id, e.name, e.logo, e.stream_url, e.ext, e.catchup_days,
                   EXISTS(SELECT 1 FROM favourite f WHERE f.playlist_id = e.playlist_id
                          AND f.type = e.type AND f.item_id = e.item_id)
                   FROM entry e $where ORDER BY ${order(key, sort)} LIMIT $limit OFFSET $offset""",
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

    // ---- own groups (Live TV) ----

    private fun groupsNow(playlistId: Long): List<Group> =
        db.readableDatabase.rawQuery("SELECT id, name FROM user_group WHERE playlist_id = ? ORDER BY created DESC, id DESC", arrayOf(playlistId.toString())).use { c ->
            val out = ArrayList<Group>(c.count)
            while (c.moveToNext()) out += Group(c.getLong(0), c.getString(1))
            out
        }

    /** The playlist's own groups, newest first. */
    suspend fun groups(playlistId: Long): List<Group> = withContext(io) { groupsNow(playlistId) }

    suspend fun addGroup(playlistId: Long, name: String): Long = withContext(io) {
        val v = ContentValues().apply {
            put("playlist_id", playlistId)
            put("name", name)
            put("created", System.currentTimeMillis())
        }
        db.writableDatabase.insertOrThrow("user_group", null, v)
    }

    suspend fun renameGroup(groupId: Long, name: String) = withContext(io) {
        db.writableDatabase.update("user_group", ContentValues().apply { put("name", name) }, "id = ?", arrayOf(groupId.toString()))
    }

    suspend fun deleteGroup(groupId: Long) = withContext(io) {
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            w.delete("user_group_item", "group_id = ?", arrayOf(groupId.toString()))
            w.delete("user_group", "id = ?", arrayOf(groupId.toString()))
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    /** Ids of the groups channel [itemId] is in. */
    suspend fun groupsOf(itemId: String, groupIds: Collection<Long>): Set<Long> = withContext(io) {
        if (groupIds.isEmpty()) return@withContext emptySet()
        db.readableDatabase.rawQuery(
            "SELECT group_id FROM user_group_item WHERE item_id = ? AND group_id IN (${groupIds.joinToString(",")})",
            arrayOf(itemId),
        ).use { c -> HashSet<Long>().also { while (c.moveToNext()) it += c.getLong(0) } }
    }

    /** Adds a channel to a group (at the end) or takes it out. */
    suspend fun setInGroup(groupId: Long, itemId: String, inGroup: Boolean) = withContext(io) {
        val w = db.writableDatabase
        if (inGroup) {
            val v = ContentValues().apply {
                put("group_id", groupId)
                put("item_id", itemId)
                put("added", System.currentTimeMillis())
            }
            w.insertWithOnConflict("user_group_item", null, v, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)
        } else {
            w.delete("user_group_item", "group_id = ? AND item_id = ?", arrayOf(groupId.toString(), itemId))
        }
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
     * finished (last [END_PERCENT]%) clears it instead, so finished titles leave the list. Adult
     * titles (by [names]) are never recorded.
     */
    suspend fun saveProgress(
        playlistId: Long,
        type: ContentType,
        itemId: String,
        episodeId: String?,
        positionMs: Long,
        durationMs: Long,
        names: AdultNames,
    ) = withContext(io) {
        val w = db.writableDatabase
        if (isAdult(playlistId, type, itemId, names)) {
            w.delete("progress", "playlist_id = ? AND type = ? AND item_id = ?", arrayOf(playlistId.toString(), type.ordinal.toString(), itemId))
            return@withContext
        }
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
            else -> if (groupId(key) != null) {
                ("WHERE e.playlist_id = ? AND e.type = ? AND EXISTS(SELECT 1 FROM user_group_item g " +
                    "WHERE g.group_id = ? AND g.item_id = e.item_id)") to (base + groupId(key).toString())
            } else if (key.startsWith(SEARCH_PREFIX)) {
                // Substring match on the name. A scan of one library: fine on the IO thread even for 50k rows.
                "WHERE e.playlist_id = ? AND e.type = ? AND e.name LIKE ? ESCAPE '\\'" to
                    (base + ("%" + likeEscape(key.removePrefix(SEARCH_PREFIX)) + "%"))
            } else {
                "WHERE e.playlist_id = ? AND e.type = ? AND e.category_id = ?" to (base + key)
            }
        }
    }

    /** Continue watching: most recent first. Favourites: their own (provider) order. Else [sort]; ties keep the provider order. */
    private fun order(key: String, sort: Sort) = when {
        key == KEY_CONTINUE -> "(SELECT p.updated FROM progress p WHERE p.playlist_id = e.playlist_id AND p.type = e.type " +
            "AND p.item_id = e.item_id) DESC"
        key == KEY_FAV -> "e.sort"
        groupId(key) != null -> "(SELECT g.added FROM user_group_item g WHERE g.group_id = ${groupId(key)} AND g.item_id = e.item_id)"
        else -> when (sort) {
            Sort.NEWEST -> "e.added DESC, e.sort"
            Sort.RATING -> "CAST(e.rating AS REAL) DESC, e.sort" // no rating counts as 0: last
            Sort.NAME -> "e.name COLLATE NOCASE, e.sort"
            Sort.PROVIDER -> "e.sort"
        }
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
        private const val GROUP_PREFIX = "\u0000group:"

        /** List key of one of the viewer's own groups. */
        fun groupKey(groupId: Long) = GROUP_PREFIX + groupId

        /** The group id in a group's list key, or null for any other key. */
        fun groupId(key: String?): Long? = key?.takeIf { it.startsWith(GROUP_PREFIX) }?.removePrefix(GROUP_PREFIX)?.toLongOrNull()

        /** List key for a name search, usable wherever a category key is. */
        fun searchKey(query: String) = SEARCH_PREFIX + query.trim()

        fun isSearch(key: String) = key.startsWith(SEARCH_PREFIX)

        /** All, Favourites, Continue watching and search lists: not provider categories. */
        fun isBuiltIn(key: String) = key.startsWith("\u0000")

        const val MIN_RESUME_MS = 30_000L
        const val END_PERCENT = 95
    }
}
