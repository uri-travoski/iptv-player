package com.worldtv.iptvplayer.data.repo

import android.content.ContentValues
import android.database.Cursor
import com.worldtv.iptvplayer.data.db.Db
import com.worldtv.iptvplayer.data.model.ContentType
import com.worldtv.iptvplayer.data.source.AdultNames
import com.worldtv.iptvplayer.data.source.TitleMatch
import com.worldtv.iptvplayer.data.source.XtreamAccount
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
        /** The built-in sample playlist (Reset app): no server, made up locally. */
        const val KIND_DEMO = "demo"
    }
}

data class CategoryRow(val key: String, val name: String)

/** A live channel to check: its id, and its own link (M3U) when it has one. */
data class CheckTarget(val itemId: String, val streamUrl: String?)

/** One of the viewer's own Live TV groups. */
data class Group(val id: Long, val name: String)

/** Order of a Movies / Series list (App: the Sort button). */
enum class Sort { NEWEST, RATING, NAME, NAME_DESC, PROVIDER }

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
    /** The provider's rating, as given (e.g. "7.3", "8.338"); null when there is none. */
    val rating: String? = null,
    /** Movies/series: release year, when known (only filled where it is needed). */
    val year: String? = null,
    /** Movies and series started: how far (ms) of how long; 0 when not started or unknown. */
    val watchedMs: Long = 0,
    val durationMs: Long = 0,
    /** Series started: the episode it is at ("S1 E6"). */
    val resumeEpisode: String? = null,
)

/** Saved playback point. [episodeId] is set for series. */
data class Progress(val episodeId: String?, val positionMs: Long, val durationMs: Long) {
    /** Watched to the end (as Continue watching counts it). */
    val finished get() = durationMs > 0 && positionMs >= durationMs * Repository.END_PERCENT / 100
}

/** The stored fields a movie or series page shows before (or without) the panel's info call. */
data class EntryDetails(val rating: String?, val plot: String?, val ext: String?, val added: Long = 0)

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

    /** Edit: new name, kind, address and login; the PIN only when [pinHash] is given (else kept). */
    suspend fun updatePlaylist(id: Long, name: String, kind: String, url: String, username: String?, password: String?, pinHash: String?) = withContext(io) {
        val v = ContentValues().apply {
            put("name", name)
            put("kind", kind)
            put("url", url)
            put("username", username)
            put("password", password)
            if (pinHash != null) put("pin_hash", pinHash)
        }
        db.writableDatabase.update("playlist", v, "id = ?", arrayOf(id.toString()))
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
            w.delete("episode_progress", "playlist_id = ?", args)
            w.delete("hidden_category", "playlist_id = ?", args)
            w.delete("hidden_item", "playlist_id = ?", args)
            w.delete("shown_category", "playlist_id = ?", args)
            w.delete("shown_item", "playlist_id = ?", args)
            w.delete("live_watch", "playlist_id = ?", args)
            w.delete("most_watched", "playlist_id = ?", args)
            w.delete("live_order", "playlist_id = ?", args)
            w.delete("category_order", "playlist_id = ?", args)
            w.delete("check_category", "playlist_id = ?", args)
            w.delete("channel_check", "playlist_id = ?", args)
            liveOrdered.remove(id)
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
                "AND h.cat_id = c.cat_id) ORDER BY $CAT_CHECKED_FIRST, $CAT_POS",
            arrayOf(playlistId.toString(), type.ordinal.toString()),
        ).use { c ->
            while (c.moveToNext()) out += CategoryRow(c.getString(0), c.getString(1))
        }
        out
    }

    // ---- channel check (Settings > Channel check) ----

    /** The Live TV categories chosen for checking. */
    suspend fun checkCategories(playlistId: Long): Set<String> = withContext(io) {
        db.readableDatabase.rawQuery("SELECT cat_id FROM check_category WHERE playlist_id = ?", arrayOf(playlistId.toString())).use { c ->
            val out = HashSet<String>()
            while (c.moveToNext()) out += c.getString(0)
            out
        }
    }

    suspend fun setCheckCategories(playlistId: Long, catIds: Collection<String>) = withContext(io) {
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            w.delete("check_category", "playlist_id = ?", arrayOf(playlistId.toString()))
            for (id in catIds) w.execSQL("INSERT OR IGNORE INTO check_category(playlist_id, cat_id) VALUES (?, ?)", arrayOf<Any>(playlistId, id))
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    /** The channels to check: every live channel in the chosen categories, in list order. */
    suspend fun channelsToCheck(playlistId: Long): List<CheckTarget> = withContext(io) {
        db.readableDatabase.rawQuery(
            """SELECT e.item_id, e.stream_url FROM entry e
               JOIN check_category k ON k.playlist_id = e.playlist_id AND k.cat_id = e.category_id
               JOIN category c ON c.playlist_id = e.playlist_id AND c.type = e.type AND c.cat_id = e.category_id
               WHERE e.playlist_id = ? AND e.type = ? ORDER BY $CAT_CHECKED_FIRST, $CAT_POS, e.sort""",
            arrayOf(playlistId.toString(), ContentType.LIVE.ordinal.toString()),
        ).use { c ->
            val out = ArrayList<CheckTarget>(c.count)
            val seen = HashSet<String>()
            while (c.moveToNext()) if (seen.add(c.getString(0))) out += CheckTarget(c.getString(0), c.getStringOrNull(1))
            out
        }
    }

    suspend fun saveChecks(playlistId: Long, results: Map<String, Boolean>, at: Long) = withContext(io) {
        if (results.isEmpty()) return@withContext
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            for ((id, ok) in results) {
                w.execSQL(
                    "INSERT OR REPLACE INTO channel_check(playlist_id, item_id, ok, checked) VALUES (?, ?, ?, ?)",
                    arrayOf<Any>(playlistId, id, if (ok) 1 else 0, at),
                )
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    /** Check results newer than [since] (ms): item id to working. Older ones no longer count. */
    suspend fun checkResults(playlistId: Long, since: Long): Map<String, Boolean> = withContext(io) {
        db.readableDatabase.rawQuery(
            "SELECT item_id, ok FROM channel_check WHERE playlist_id = ? AND checked >= ?",
            arrayOf(playlistId.toString(), since.toString()),
        ).use { c ->
            val out = HashMap<String, Boolean>(c.count)
            while (c.moveToNext()) out[c.getString(0)] = c.getInt(1) != 0
            out
        }
    }

    // ---- category order (Settings > Categories order) ----

    /** The shown categories of a library, in the order the library lists them. */
    suspend fun categoryOrder(playlistId: Long, type: ContentType): List<CategoryRow> =
        categories(playlistId, type).filterNot { isBuiltIn(it.key) }

    /**
     * Moves category [from] of a library to place [to] (indexes in [categoryOrder]). The
     * categories in between share out the same positions in the new order. Reloads keep it.
     */
    suspend fun moveCategory(playlistId: Long, type: ContentType, from: Int, to: Int) = withContext(io) {
        val w = db.writableDatabase
        val rows = ArrayList<Pair<String, Long>>()
        w.rawQuery(
            "SELECT cat_id, $CAT_POS FROM category c WHERE playlist_id = ? AND type = ? AND NOT EXISTS(" +
                "SELECT 1 FROM hidden_category h WHERE h.playlist_id = c.playlist_id AND h.type = c.type " +
                "AND h.cat_id = c.cat_id) ORDER BY $CAT_CHECKED_FIRST, $CAT_POS",
            arrayOf(playlistId.toString(), type.ordinal.toString()),
        ).use { c -> while (c.moveToNext()) rows += c.getString(0) to c.getLong(1) }
        if (from !in rows.indices || to !in rows.indices || from == to) return@withContext
        // The first move by hand keeps the whole order as it is shown (e.g. checked categories
        // first), from then on it is the user's.
        val first = w.rawQuery(
            "SELECT 1 FROM category_order WHERE playlist_id = ? AND type = ? LIMIT 1",
            arrayOf(playlistId.toString(), type.ordinal.toString()),
        ).use { !it.moveToFirst() }
        val positions = if (first) rows.indices.map { it.toLong() } else rows.map { it.second }
        val ids = rows.map { it.first }.toMutableList()
        ids.add(to, ids.removeAt(from))
        w.beginTransaction()
        try {
            for (i in if (first) rows.indices else minOf(from, to)..maxOf(from, to)) {
                w.execSQL(
                    "INSERT OR REPLACE INTO category_order (playlist_id, type, cat_id, pos) VALUES (?, ?, ?, ?)",
                    arrayOf<Any>(playlistId, type.ordinal, ids[i], positions[i]),
                )
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    /** Back to the provider's category order for one library. */
    suspend fun resetCategoryOrder(playlistId: Long, type: ContentType) = withContext(io) {
        db.writableDatabase.delete("category_order", "playlist_id = ? AND type = ?", arrayOf(playlistId.toString(), type.ordinal.toString()))
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
                "AND h.type = c.type AND h.cat_id = c.cat_id) FROM category c WHERE playlist_id = ? AND type = ? ORDER BY $CAT_CHECKED_FIRST, $CAT_POS",
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
                          AND f.type = e.type AND f.item_id = e.item_id), e.rating,
                   p.position_ms, p.duration_ms, ep.season, ep.number
                   FROM entry e
                   LEFT JOIN progress p ON p.playlist_id = e.playlist_id AND p.type = e.type AND p.item_id = e.item_id
                   LEFT JOIN episode_progress ep ON ep.playlist_id = e.playlist_id AND ep.series_id = e.item_id AND ep.episode_id = p.episode_id
                   $where ORDER BY ${order(key, sort, hasLiveOrder(playlistId, type))} LIMIT $limit OFFSET $offset""",
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
                        rating = c.getStringOrNull(7),
                        watchedMs = c.getLong(8),
                        durationMs = c.getLong(9),
                        resumeEpisode = if (c.isNull(11)) null else
                            (if (c.isNull(10) || c.getInt(10) <= 0) "" else "S${c.getInt(10)} ") + "E${c.getInt(11)}",
                    )
                }
                out
            }
        }

    /** Position of [itemId] inside the list for [key], or -1. Used to restore the last channel. */
    suspend fun indexOf(playlistId: Long, type: ContentType, key: String, itemId: String, sort: Sort = Sort.PROVIDER): Int = withContext(io) {
        val r = db.readableDatabase
        val (pos, name) = r.rawQuery(
            "SELECT sort, name FROM entry WHERE playlist_id = ? AND type = ? AND item_id = ?",
            arrayOf(playlistId.toString(), type.ordinal.toString(), itemId),
        ).use { c -> if (c.moveToFirst()) c.getInt(0) to c.getString(1) else return@withContext -1 }
        val (where, args) = filter(playlistId, type, key)
        // Not in this list (another category, or a hidden one): no position.
        val inList = r.rawQuery("SELECT 1 FROM entry e $where AND e.item_id = ?", args + itemId).use { it.moveToFirst() }
        if (!inList) return@withContext -1
        // Rows before it in the list's own order (as in [order]).
        val group = groupId(key)
        val (before, extra) = when {
            group != null -> "(SELECT g.added FROM user_group_item g WHERE g.group_id = $group AND g.item_id = e.item_id) < " +
                "(SELECT g.added FROM user_group_item g WHERE g.group_id = $group AND g.item_id = ?)" to arrayOf(itemId)
            key == KEY_MOST_WATCHED -> "$WATCH_RANK < (SELECT m.rank FROM most_watched m WHERE m.playlist_id = ? AND m.item_id = ?)" to
                arrayOf(playlistId.toString(), itemId)
            key == KEY_FAV -> "e.sort < $pos" to emptyArray()
            sort == Sort.PROVIDER && hasLiveOrder(playlistId, type) ->
                "$LIVE_POS < (SELECT COALESCE((SELECT o.pos FROM live_order o WHERE o.playlist_id = ? AND o.item_id = ?), $pos))" to
                    arrayOf(playlistId.toString(), itemId)
            sort == Sort.PROVIDER -> "e.sort < $pos" to emptyArray()
            sort == Sort.NAME -> "(e.name < ? COLLATE NOCASE OR (e.name = ? COLLATE NOCASE AND e.sort < $pos))" to arrayOf(name, name)
            sort == Sort.NAME_DESC -> "(e.name > ? COLLATE NOCASE OR (e.name = ? COLLATE NOCASE AND e.sort < $pos))" to arrayOf(name, name)
            else -> "e.sort < $pos" to emptyArray() // Movies / Series don't restore a position
        }
        r.rawQuery("SELECT COUNT(*) FROM entry e $where AND $before", args + extra).use { c ->
            if (c.moveToFirst()) c.getInt(0) else -1
        }
    }

    suspend fun details(playlistId: Long, type: ContentType, itemId: String): EntryDetails? = withContext(io) {
        db.readableDatabase.rawQuery(
            "SELECT rating, plot, ext, added FROM entry WHERE playlist_id = ? AND type = ? AND item_id = ?",
            arrayOf(playlistId.toString(), type.ordinal.toString(), itemId),
        ).use { c -> if (c.moveToFirst()) EntryDetails(c.getStringOrNull(0), c.getStringOrNull(1), c.getStringOrNull(2), c.getLong(3)) else null }
    }

    /**
     * Keeps a rating the movie/series page found (get_vod_info) for a title the list came without
     * one, so its poster shows it from now on. A reload brings the provider's own value back.
     */
    suspend fun saveRating(playlistId: Long, type: ContentType, itemId: String, rating: String) = withContext(io) {
        db.writableDatabase.execSQL(
            "UPDATE entry SET rating = ? WHERE playlist_id = ? AND type = ? AND item_id = ? AND (rating IS NULL OR rating = '' OR rating = '0')",
            arrayOf<Any>(rating, playlistId, type.ordinal, itemId),
        )
    }

    /** A movie's or series' actors from its page (the list had none): for finding it by actor later. */
    suspend fun saveCast(playlistId: Long, type: ContentType, itemId: String, cast: String) = withContext(io) {
        db.writableDatabase.execSQL(
            "UPDATE entry SET cast_names = ? WHERE playlist_id = ? AND type = ? AND item_id = ? AND (cast_names IS NULL OR cast_names = '')",
            arrayOf<Any>(cast, playlistId, type.ordinal, itemId),
        )
    }

    // ---- an actor's titles ----

    /**
     * The movies or series of [type] whose name matches a title in [index] (an actor's films),
     * newest film first. One scan of the library's names; hidden ones are left out.
     */
    suspend fun <T> matchTitles(playlistId: Long, type: ContentType, index: TitleMatch.Index<T>, yearOf: (T) -> Int): List<EntryRow> =
        withContext(io) {
            if (index.isEmpty) return@withContext emptyList()
            val (where, args) = filter(playlistId, type, KEY_ALL)
            val found = ArrayList<Pair<String, Int>>()
            // Providers often list a film several times under the same name: show it once.
            val names = HashSet<String>()
            db.readableDatabase.rawQuery("SELECT e.item_id, e.name, e.year FROM entry e $where ORDER BY e.sort", args).use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1)
                    val tag = index.match(name, c.getStringOrNull(2)) ?: continue
                    if (!names.add(name.trim().lowercase())) continue
                    found += c.getString(0) to yearOf(tag)
                    if (found.size >= MAX_ACTOR_TITLES) break
                }
            }
            val rows = rowsById(playlistId, type, found.map { it.first })
            val year = found.toMap()
            rows.sortedByDescending { year[it.itemId] ?: 0 }
        }

    /**
     * Each wanted title in [index] (e.g. TMDB's recent releases) that is in the library, with the
     * library entry it matched: the first (provider order) of each, so a title listed several times
     * comes back once. Hidden and adult entries are left out. One scan of the names.
     */
    suspend fun <T> matchEach(playlistId: Long, type: ContentType, index: TitleMatch.Index<T>): List<Pair<EntryRow, T>> =
        withContext(io) {
            if (index.isEmpty) return@withContext emptyList()
            val (where, args) = filter(playlistId, type, KEY_ALL)
            val found = LinkedHashMap<T, String>()
            db.readableDatabase.rawQuery("SELECT e.item_id, e.name, e.year FROM entry e $where ORDER BY e.sort", args).use { c ->
                while (c.moveToNext()) {
                    val tag = index.match(c.getString(1), c.getStringOrNull(2)) ?: continue
                    if (tag !in found) found[tag] = c.getString(0)
                }
            }
            val rows = rowsById(playlistId, type, found.values.toList()).associateBy { it.itemId }
            found.mapNotNull { (tag, id) -> rows[id]?.let { it to tag } }
        }

    /** One entry as lists show it, or null when it is gone from the playlist. */
    suspend fun entryRow(playlistId: Long, type: ContentType, itemId: String): EntryRow? = withContext(io) {
        rowsById(playlistId, type, listOf(itemId)).firstOrNull()
    }

    /** Movies or series of [type] whose stored cast names [actor] (the provider's lists; no TMDB). */
    suspend fun byCast(playlistId: Long, type: ContentType, actor: String): List<EntryRow> = withContext(io) {
        val (where, args) = filter(playlistId, type, KEY_ALL)
        val ids = db.readableDatabase.rawQuery(
            "SELECT e.item_id FROM entry e $where AND e.cast_names LIKE ? ESCAPE '\\' LIMIT $MAX_ACTOR_TITLES",
            args + ("%" + likeEscape(actor.trim()) + "%"),
        ).use { c -> ArrayList<String>().apply { while (c.moveToNext()) add(c.getString(0)) } }
        rowsById(playlistId, type, ids).sortedByDescending { it.year?.toIntOrNull() ?: 0 }
    }

    private fun rowsById(playlistId: Long, type: ContentType, ids: List<String>): List<EntryRow> {
        if (ids.isEmpty()) return emptyList()
        val out = ArrayList<EntryRow>(ids.size)
        for (chunk in ids.chunked(500)) {
            db.readableDatabase.rawQuery(
                """SELECT e.item_id, e.name, e.logo, e.stream_url, e.ext, e.catchup_days,
                   EXISTS(SELECT 1 FROM favourite f WHERE f.playlist_id = e.playlist_id
                          AND f.type = e.type AND f.item_id = e.item_id), e.rating, e.year
                   FROM entry e WHERE e.playlist_id = ? AND e.type = ? AND e.item_id IN (${chunk.joinToString(",") { "?" }})""",
                arrayOf(playlistId.toString(), type.ordinal.toString()) + chunk,
            ).use { c ->
                while (c.moveToNext()) {
                    out += EntryRow(
                        itemId = c.getString(0),
                        name = c.getString(1),
                        logo = c.getStringOrNull(2),
                        streamUrl = c.getStringOrNull(3),
                        ext = c.getStringOrNull(4),
                        catchupDays = c.getInt(5),
                        favourite = c.getInt(6) != 0,
                        rating = c.getStringOrNull(7),
                        year = c.getStringOrNull(8) ?: TitleMatch.year(c.getString(1)),
                    )
                }
            }
        }
        return out
    }

    // ---- history and reset ----

    /**
     * Clear history: Continue watching of [types] in every playlist, and with Live TV the viewing
     * behind Most watched. Favourites and groups stay.
     */
    suspend fun clearHistory(types: Collection<ContentType>) = withContext(io) {
        if (types.isEmpty()) return@withContext
        val w = db.writableDatabase
        w.delete("progress", "type IN (${types.joinToString(",") { it.ordinal.toString() }})", null)
        if (ContentType.SERIES in types) w.delete("episode_progress", null, null)
        if (ContentType.LIVE in types) {
            w.delete("live_watch", null, null)
            w.delete("most_watched", null, null)
        }
    }

    // ---- Most watched (Live TV) ----

    /** Stores one view of a channel (see MostWatched). Adult channels are never kept, even when shown by hand. */
    suspend fun saveView(playlistId: Long, itemId: String, name: String, started: Long, watchedMs: Long, names: AdultNames) = withContext(io) {
        if (isAdult(playlistId, ContentType.LIVE, itemId, names)) return@withContext
        val v = ContentValues().apply {
            put("playlist_id", playlistId)
            put("item_id", itemId)
            put("name", name)
            put("started", started)
            put("watched_ms", watchedMs)
        }
        db.writableDatabase.insertWithOnConflict("live_watch", null, v, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** A view that ran past 4 hours (a box left on): it doesn't count. */
    suspend fun deleteView(playlistId: Long, itemId: String, started: Long) = withContext(io) {
        db.writableDatabase.delete(
            "live_watch", "playlist_id = ? AND item_id = ? AND started = ?",
            arrayOf(playlistId.toString(), itemId, started.toString()),
        )
    }

    /** "Remove from Most watched": its views go, so it comes back only after 3 new ones. */
    suspend fun forgetWatched(playlistId: Long, itemId: String) = withContext(io) {
        val args = arrayOf(playlistId.toString(), itemId)
        db.writableDatabase.delete("live_watch", "playlist_id = ? AND item_id = ?", args)
        db.writableDatabase.delete("most_watched", "playlist_id = ? AND item_id = ?", args)
    }

    /**
     * Works the Most watched list out again from the stored views (a few hundred rows at most).
     * Run when Live TV opens. A channel whose id changed in a reload is found again by its name;
     * hidden and adult channels are left out.
     */
    suspend fun refreshMostWatched(playlistId: Long, names: AdultNames, now: Long = System.currentTimeMillis()) = withContext(io) {
        val w = db.writableDatabase
        val pl = playlistId.toString()
        w.delete("live_watch", "playlist_id = ? AND started < ?", arrayOf(pl, (now - MostWatched.KEEP_MS).toString()))
        val views = ArrayList<MostWatched.View>()
        val nameOf = HashMap<String, String>()
        w.rawQuery("SELECT item_id, name, started, watched_ms FROM live_watch WHERE playlist_id = ?", arrayOf(pl)).use { c ->
            while (c.moveToNext()) {
                views += MostWatched.View(c.getString(0), c.getLong(2), c.getLong(3))
                nameOf[c.getString(0)] = c.getString(1)
            }
        }
        // Ids gone after a reload: the channel under the same name, if there is one.
        val live = ContentType.LIVE.ordinal.toString()
        val moved = HashMap<String, String>()
        for ((id, name) in nameOf) {
            val exists = w.rawQuery("SELECT 1 FROM entry WHERE playlist_id = ? AND type = ? AND item_id = ?", arrayOf(pl, live, id)).use { it.moveToFirst() }
            if (exists) continue
            val found = w.rawQuery(
                "SELECT item_id FROM entry WHERE playlist_id = ? AND type = ? AND name = ? COLLATE NOCASE ORDER BY sort LIMIT 1",
                arrayOf(pl, live, name),
            ).use { if (it.moveToFirst()) it.getString(0) else null } ?: continue
            w.execSQL("UPDATE OR IGNORE live_watch SET item_id = ? WHERE playlist_id = ? AND item_id = ?", arrayOf<Any>(found, playlistId, id))
            moved[id] = found
        }
        val ranked = MostWatched.rank(views.map { if (it.itemId in moved) MostWatched.View(moved.getValue(it.itemId), it.started, it.watchedMs) else it }, now)
        val (where, args) = filter(playlistId, ContentType.LIVE, KEY_ALL)
        val list = ranked.asSequence()
            .filter { id -> w.rawQuery("SELECT 1 FROM entry e $where AND e.item_id = ?", args + id).use { it.moveToFirst() } }
            .filter { id -> !isAdult(playlistId, ContentType.LIVE, id, names) }
            .take(MostWatched.SIZE)
            .toList()
        w.beginTransaction()
        try {
            w.delete("most_watched", "playlist_id = ?", arrayOf(pl))
            list.forEachIndexed { i, id ->
                w.execSQL("INSERT OR IGNORE INTO most_watched (playlist_id, item_id, rank) VALUES (?, ?, ?)", arrayOf<Any>(playlistId, id, i))
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    /** Reset app: every playlist and everything kept about it, as on a fresh install. */
    suspend fun resetAll() = withContext(io) {
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            for (t in RESET_TABLES) w.delete(t, null, null)
            liveOrdered.clear()
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
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

    /** How far each watched episode of series [seriesId] got, by episode id. */
    suspend fun episodeProgress(playlistId: Long, seriesId: String): Map<String, Progress> = withContext(io) {
        db.readableDatabase.rawQuery(
            "SELECT episode_id, position_ms, duration_ms FROM episode_progress WHERE playlist_id = ? AND series_id = ?",
            arrayOf(playlistId.toString(), seriesId),
        ).use { c ->
            val out = HashMap<String, Progress>(c.count)
            while (c.moveToNext()) out[c.getString(0)] = Progress(c.getString(0), c.getLong(1), c.getLong(2))
            out
        }
    }

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
        /** Episodes: season and episode number, for "S1 E6" on the poster. */
        season: Int? = null,
        number: Int? = null,
    ) = withContext(io) {
        val w = db.writableDatabase
        if (isAdult(playlistId, type, itemId, names)) {
            w.delete("progress", "playlist_id = ? AND type = ? AND item_id = ?", arrayOf(playlistId.toString(), type.ordinal.toString(), itemId))
            w.delete("episode_progress", "playlist_id = ? AND series_id = ?", arrayOf(playlistId.toString(), itemId))
            return@withContext
        }
        val nearEnd = durationMs > 0 && positionMs >= durationMs * END_PERCENT / 100
        // Every episode keeps its own point (✓ when finished); a few seconds' look doesn't count.
        if (episodeId != null && durationMs > 0 && (positionMs >= MIN_RESUME_MS || nearEnd)) {
            val v = ContentValues().apply {
                put("playlist_id", playlistId)
                put("series_id", itemId)
                put("episode_id", episodeId)
                put("season", season)
                put("number", number)
                put("position_ms", if (nearEnd) durationMs else positionMs)
                put("duration_ms", durationMs)
                put("updated", System.currentTimeMillis())
            }
            w.insertWithOnConflict("episode_progress", null, v, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
        }
        if (positionMs < MIN_RESUME_MS || nearEnd) {
            // A stop or finish in a *different* episode leaves the series' saved point alone.
            val where = if (episodeId == null) "" else " AND episode_id = ?"
            val args = arrayListOf(playlistId.toString(), type.ordinal.toString(), itemId)
            if (episodeId != null) args += episodeId
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
            KEY_MOST_WATCHED -> ("WHERE e.playlist_id = ? AND e.type = ? AND EXISTS(SELECT 1 FROM most_watched m " +
                "WHERE m.playlist_id = e.playlist_id AND m.item_id = e.item_id)") to base
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

    /**
     * Continue watching: most recent first. Favourites: their own (provider) order. Else [sort];
     * ties keep the provider order. [livePos]: Live TV's order set by hand, when there is one.
     */
    private fun order(key: String, sort: Sort, livePos: Boolean = false) = when {
        key == KEY_CONTINUE -> "(SELECT p.updated FROM progress p WHERE p.playlist_id = e.playlist_id AND p.type = e.type " +
            "AND p.item_id = e.item_id) DESC"
        key == KEY_FAV -> "e.sort"
        key == KEY_MOST_WATCHED -> WATCH_RANK
        groupId(key) != null -> "(SELECT g.added FROM user_group_item g WHERE g.group_id = ${groupId(key)} AND g.item_id = e.item_id)"
        else -> when (sort) {
            Sort.NEWEST -> "e.added DESC, e.sort"
            Sort.RATING -> "CAST(e.rating AS REAL) DESC, e.sort" // no rating counts as 0: last
            Sort.NAME -> "e.name COLLATE NOCASE, e.sort"
            Sort.NAME_DESC -> "e.name COLLATE NOCASE DESC, e.sort"
            Sort.PROVIDER -> if (livePos) LIVE_POS else "e.sort"
        }
    }

    // ---- live channel order (Settings > Live channel order) ----

    /** Playlists with a live order set by hand (cached: most have none, and then lists cost nothing extra). */
    private val liveOrdered = java.util.concurrent.ConcurrentHashMap<Long, Boolean>()

    private fun hasLiveOrder(playlistId: Long, type: ContentType): Boolean =
        type == ContentType.LIVE && liveOrdered.getOrPut(playlistId) {
            db.readableDatabase.rawQuery("SELECT 1 FROM live_order WHERE playlist_id = ? LIMIT 1", arrayOf(playlistId.toString()))
                .use { it.moveToFirst() }
        }

    /** A category's channels (hidden ones too, so they keep their place), in the order shown. */
    suspend fun liveOrder(playlistId: Long, catId: String): List<AdminEntry> = withContext(io) {
        val ordered = hasLiveOrder(playlistId, ContentType.LIVE)
        db.readableDatabase.rawQuery(
            "SELECT e.item_id, e.name FROM entry e WHERE e.playlist_id = ? AND e.type = ? AND e.category_id = ? " +
                "ORDER BY ${if (ordered) LIVE_POS else "e.sort"}",
            arrayOf(playlistId.toString(), ContentType.LIVE.ordinal.toString(), catId),
        ).use { c -> ArrayList<AdminEntry>(c.count).apply { while (c.moveToNext()) add(AdminEntry(c.getString(0), c.getString(1), false)) } }
    }

    /**
     * Moves channel [from] of category [catId] to place [to] (indexes in [liveOrder]). The
     * category's channels share out the same positions in the new order, so the category keeps
     * its place among the others in All. Reloads keep it (positions are per channel id).
     */
    suspend fun moveLive(playlistId: Long, catId: String, from: Int, to: Int) = withContext(io) {
        val w = db.writableDatabase
        val ordered = hasLiveOrder(playlistId, ContentType.LIVE)
        val rows = ArrayList<Pair<String, Long>>()
        w.rawQuery(
            "SELECT e.item_id, ${if (ordered) LIVE_POS else "e.sort"} FROM entry e WHERE e.playlist_id = ? AND e.type = ? AND e.category_id = ? " +
                "ORDER BY ${if (ordered) LIVE_POS else "e.sort"}",
            arrayOf(playlistId.toString(), ContentType.LIVE.ordinal.toString(), catId),
        ).use { c -> while (c.moveToNext()) rows += c.getString(0) to c.getLong(1) }
        if (from !in rows.indices || to !in rows.indices || from == to) return@withContext
        val positions = rows.map { it.second }
        val ids = rows.map { it.first }.toMutableList()
        ids.add(to, ids.removeAt(from))
        w.beginTransaction()
        try {
            for (i in minOf(from, to)..maxOf(from, to)) {
                w.execSQL("INSERT OR REPLACE INTO live_order (playlist_id, item_id, pos) VALUES (?, ?, ?)", arrayOf<Any>(playlistId, ids[i], positions[i]))
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
        liveOrdered[playlistId] = true
    }

    /** Back to the provider's order for category [catId]. */
    suspend fun resetLiveOrder(playlistId: Long, catId: String) = withContext(io) {
        db.writableDatabase.execSQL(
            "DELETE FROM live_order WHERE playlist_id = ? AND item_id IN (SELECT item_id FROM entry WHERE playlist_id = ? AND type = ? AND category_id = ?)",
            arrayOf<Any>(playlistId, playlistId, ContentType.LIVE.ordinal, catId),
        )
        liveOrdered.remove(playlistId)
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
        /** Live TV's Most watched list (see MostWatched). */
        const val KEY_MOST_WATCHED = "\u0000mostwatched"
        /** A row's place in Most watched (0 = top). */
        /**
         * Live TV: until the categories are put in order by hand, the ones in the channel check
         * list come first (just under Favourites). 0 = up there, 1 = the rest.
         */
        private const val CAT_CHECKED_FIRST = "CASE WHEN c.type = 0 AND NOT EXISTS(SELECT 1 FROM category_order o " +
            "WHERE o.playlist_id = c.playlist_id AND o.type = c.type) AND EXISTS(SELECT 1 FROM check_category k " +
            "WHERE k.playlist_id = c.playlist_id AND k.cat_id = c.cat_id) THEN 0 ELSE 1 END"
        /** A category's place: set by hand (category_order), else the provider's. */
        private const val CAT_POS = "COALESCE((SELECT o.pos FROM category_order o WHERE o.playlist_id = c.playlist_id AND o.type = c.type AND o.cat_id = c.cat_id), c.sort)"
        /** A live channel's place: set by hand (live_order), else the provider's. */
        private const val LIVE_POS = "COALESCE((SELECT o.pos FROM live_order o WHERE o.playlist_id = e.playlist_id AND o.item_id = e.item_id), e.sort)"
        private const val WATCH_RANK = "(SELECT m.rank FROM most_watched m WHERE m.playlist_id = e.playlist_id AND m.item_id = e.item_id)"
        private const val SEARCH_PREFIX = "\u0000search:"
        private const val GROUP_PREFIX = "\u0000group:"

        /** List key of one of the viewer's own groups. */
        fun groupKey(groupId: Long) = GROUP_PREFIX + groupId

        /** The group id in a group's list key, or null for any other key. */
        fun groupId(key: String?): Long? = key?.takeIf { it.startsWith(GROUP_PREFIX) }?.removePrefix(GROUP_PREFIX)?.toLongOrNull()

        /** List key for a name search, usable wherever a category key is. */
        /** At most this many titles on an actor's page. */
        const val MAX_ACTOR_TITLES = 300

        fun searchKey(query: String) = SEARCH_PREFIX + query.trim()

        fun isSearch(key: String) = key.startsWith(SEARCH_PREFIX)

        /** All, Favourites, Continue watching and search lists: not provider categories. */
        fun isBuiltIn(key: String) = key.startsWith("\u0000")

        private val RESET_TABLES = listOf(
            "entry", "category", "favourite", "progress", "episode_progress", "hidden_category", "hidden_item", "shown_category", "shown_item",
            "user_group_item", "user_group", "live_watch", "most_watched", "live_order", "category_order", "check_category", "channel_check", "playlist",
        )

        const val MIN_RESUME_MS = 30_000L
        const val END_PERCENT = 95
    }
}
