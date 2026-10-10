package com.worldtv.iptvplayer.data.telemetry

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * The telemetry outbox, in its own database file so it can never contend with the app's main
 * `iptv.db` (a playlist sync holds a big write there). One row per queued event; the body is a
 * single serialized JSON row, joined into a PostgREST array at flush time.
 */
class TelemetryDb(context: Context) : SQLiteOpenHelper(context, "telemetry.db", null, 1) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS outbox (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                table_name TEXT NOT NULL,
                body TEXT NOT NULL,
                created INTEGER NOT NULL
            )""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS outbox_table ON outbox(table_name, id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** Writes [rows] (already-serialized single-row JSON) for [table] in one transaction. */
    fun enqueue(table: String, rows: List<String>, now: Long) {
        if (rows.isEmpty()) return
        val w = writableDatabase
        w.beginTransaction()
        try {
            for (row in rows) {
                w.insertOrThrow("outbox", null, ContentValues().apply {
                    put("table_name", table)
                    put("body", row)
                    put("created", now)
                })
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    /** Up to [limit] pending rows for [table], oldest first, with their ids. */
    fun peek(table: String, limit: Int): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>()
        readableDatabase.rawQuery(
            "SELECT id, body FROM outbox WHERE table_name = ? ORDER BY id LIMIT ?",
            arrayOf(table, limit.toString()),
        ).use { c ->
            while (c.moveToNext()) out.add(c.getLong(0) to c.getString(1))
        }
        return out
    }

    fun deleteUpTo(lastId: Long) {
        writableDatabase.delete("outbox", "id <= ?", arrayOf(lastId.toString()))
    }

    /** Readable tables that currently have something queued. */
    fun tables(): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery("SELECT DISTINCT table_name FROM outbox", null).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    fun count(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM outbox", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /** Drops the oldest rows past [max], so a long outage cannot fill the disk. */
    fun prune(max: Int) {
        val n = count()
        if (n <= max) return
        writableDatabase.execSQL(
            "DELETE FROM outbox WHERE id IN (SELECT id FROM outbox ORDER BY id ASC LIMIT ?)",
            arrayOf(n - max),
        )
    }

    fun clear() = writableDatabase.delete("outbox", null, null)
}
