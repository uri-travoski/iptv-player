package android.database

import android.database.sqlite.SQLiteDatabase

/** Desktop stand-in for Android's DatabaseUtils: the helpers the shared code uses. */
object DatabaseUtils {

    @JvmStatic
    fun queryNumEntries(db: SQLiteDatabase, table: String): Long = queryNumEntries(db, table, null, null)

    @JvmStatic
    fun queryNumEntries(db: SQLiteDatabase, table: String, selection: String?, selectionArgs: Array<out String?>? = null): Long {
        val where = if (selection.isNullOrEmpty()) "" else " WHERE $selection"
        return longForQuery(db, "SELECT COUNT(*) FROM $table$where", selectionArgs)
    }

    @JvmStatic
    fun longForQuery(db: SQLiteDatabase, query: String, selectionArgs: Array<out String?>?): Long =
        db.rawQuery(query, selectionArgs).use { c -> if (c.moveToFirst()) c.getLong(0) else 0 }

    @JvmStatic
    fun stringForQuery(db: SQLiteDatabase, query: String, selectionArgs: Array<out String?>?): String? =
        db.rawQuery(query, selectionArgs).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    @JvmStatic
    fun sqlEscapeString(value: String): String = "'" + value.replace("'", "''") + "'"
}
