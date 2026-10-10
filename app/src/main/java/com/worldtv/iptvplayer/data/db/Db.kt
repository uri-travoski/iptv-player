package com.worldtv.iptvplayer.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.worldtv.iptvplayer.data.repo.Pin

/**
 * Raw SQLite (no Room): no annotation processing, no generated code, smaller APK.
 * WAL mode lets screens keep reading the old rows while a sync rewrites them in one transaction.
 */
class Db(context: Context) : SQLiteOpenHelper(context, "iptv.db", null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
        db.setForeignKeyConstraintsEnabled(false)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE playlist (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                kind TEXT NOT NULL,
                url TEXT NOT NULL,
                username TEXT,
                password TEXT,
                epg_url TEXT,
                last_sync INTEGER NOT NULL DEFAULT 0,
                expires INTEGER,
                max_connections INTEGER NOT NULL DEFAULT 0,
                formats TEXT,
                server_tz TEXT,
                status TEXT,
                pin_hash TEXT
            )""",
        )
        db.execSQL(
            """CREATE TABLE category (
                playlist_id INTEGER NOT NULL,
                type INTEGER NOT NULL,
                cat_id TEXT NOT NULL,
                name TEXT NOT NULL,
                sort INTEGER NOT NULL,
                PRIMARY KEY (playlist_id, type, cat_id)
            )""",
        )
        db.execSQL(
            """CREATE TABLE entry (
                playlist_id INTEGER NOT NULL,
                type INTEGER NOT NULL,
                item_id TEXT NOT NULL,
                name TEXT NOT NULL,
                category_id TEXT,
                logo TEXT,
                stream_url TEXT,
                epg_id TEXT,
                catchup_days INTEGER NOT NULL DEFAULT 0,
                ext TEXT,
                rating TEXT,
                plot TEXT,
                added INTEGER NOT NULL DEFAULT 0,
                sort INTEGER NOT NULL,
                adult INTEGER NOT NULL DEFAULT 0,
                cast_names TEXT,
                year TEXT
            )""",
        )
        db.execSQL("CREATE UNIQUE INDEX entry_key ON entry(playlist_id, type, item_id)")
        db.execSQL("CREATE INDEX entry_all ON entry(playlist_id, type, sort)")
        db.execSQL("CREATE INDEX entry_cat ON entry(playlist_id, type, category_id, sort)")
        createSortIndexes(db)
        // Favourites live outside `entry` so they survive a re-sync.
        db.execSQL(
            """CREATE TABLE favourite (
                playlist_id INTEGER NOT NULL,
                type INTEGER NOT NULL,
                item_id TEXT NOT NULL,
                PRIMARY KEY (playlist_id, type, item_id)
            )""",
        )
        createProgress(db)
        createHiddenCategories(db)
        createGroups(db)
    }

    /** The viewer's own Live TV groups ("Add group") and their channels, kept over re-syncs like favourites. */
    private fun createGroups(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS user_group (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                playlist_id INTEGER NOT NULL,
                name TEXT NOT NULL,
                created INTEGER NOT NULL
            )""",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS user_group_item (
                group_id INTEGER NOT NULL,
                item_id TEXT NOT NULL,
                added INTEGER NOT NULL,
                PRIMARY KEY (group_id, item_id)
            )""",
        )
    }

    /**
     * Categories and single entries an admin hid for a playlist (App Settings > playlist >
     * Categories, behind its PIN). Kept outside `category`/`entry`, like favourites, so they stay
     * hidden after a re-sync.
     */
    private fun createHiddenCategories(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS hidden_category (
                playlist_id INTEGER NOT NULL,
                type INTEGER NOT NULL,
                cat_id TEXT NOT NULL,
                auto INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (playlist_id, type, cat_id)
            )""",
        )
        // What the admin chose to show by hand: adult auto-hiding (on every load) leaves these alone.
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS shown_category (
                playlist_id INTEGER NOT NULL,
                type INTEGER NOT NULL,
                cat_id TEXT NOT NULL,
                PRIMARY KEY (playlist_id, type, cat_id)
            )""",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS shown_item (
                playlist_id INTEGER NOT NULL,
                type INTEGER NOT NULL,
                item_id TEXT NOT NULL,
                PRIMARY KEY (playlist_id, type, item_id)
            )""",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS hidden_item (
                playlist_id INTEGER NOT NULL,
                type INTEGER NOT NULL,
                item_id TEXT NOT NULL,
                auto INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (playlist_id, type, item_id)
            )""",
        )
    }

    /** Movies and Series open newest first: these keep that order fast in 245k-title libraries. */
    private fun createSortIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS entry_added ON entry(playlist_id, type, added)")
        db.execSQL("CREATE INDEX IF NOT EXISTS entry_cat_added ON entry(playlist_id, type, category_id, added)")
    }

    /**
     * Where a movie or series was left. One row per movie / per series (its last episode).
     * Kept outside `entry`, like favourites, so it survives a re-sync.
     */
    private fun createProgress(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS progress (
                playlist_id INTEGER NOT NULL,
                type INTEGER NOT NULL,
                item_id TEXT NOT NULL,
                episode_id TEXT,
                position_ms INTEGER NOT NULL,
                duration_ms INTEGER NOT NULL,
                updated INTEGER NOT NULL,
                PRIMARY KEY (playlist_id, type, item_id)
            )""",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE playlist ADD COLUMN pin_hash TEXT")
        if (oldVersion < 3) createProgress(db)
        if (oldVersion < 5) createHiddenCategories(db) // v4 had the hidden tables; v5 adds the shown ones
        if (oldVersion == 5) {
            // v6: automatic (adult words / is_adult) hides are told apart from the admin's own.
            db.execSQL("ALTER TABLE hidden_category ADD COLUMN auto INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE hidden_item ADD COLUMN auto INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 7) createSortIndexes(db)
        if (oldVersion < 8) createGroups(db)
        if (oldVersion < 9) {
            // Actors (series lists carry them; movies get theirs when their page opens) and the
            // year: for "Movies & series with <actor>". Filled by the next playlist load.
            db.execSQL("ALTER TABLE entry ADD COLUMN cast_names TEXT")
            db.execSQL("ALTER TABLE entry ADD COLUMN year TEXT")
            // Due for Automatic refresh at the next start, which fills them in.
            db.execSQL("UPDATE playlist SET last_sync = 0")
        }
        if (oldVersion < 6) {
            // The provider's is_adult flag; and every playlist gets a PIN, 000000 by default.
            db.execSQL("ALTER TABLE entry ADD COLUMN adult INTEGER NOT NULL DEFAULT 0")
            db.rawQuery("SELECT id FROM playlist WHERE pin_hash IS NULL OR pin_hash = ''", null).use { c ->
                while (c.moveToNext()) {
                    db.execSQL("UPDATE playlist SET pin_hash = ? WHERE id = ?", arrayOf<Any>(Pin.hash(Pin.DEFAULT), c.getLong(0)))
                }
            }
        }
    }

    companion object {
        const val VERSION = 9
    }
}
