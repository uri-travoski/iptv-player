package android.database.sqlite

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MemoryCursor
import java.io.File
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.Types
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

open class SQLiteException(message: String?, cause: Throwable? = null) : RuntimeException(message, cause)

class SQLiteConstraintException(message: String?, cause: Throwable? = null) : SQLiteException(message, cause)

/**
 * Desktop stand-in for Android's SQLiteDatabase on sqlite-jdbc (the same SQLite engine), with
 * Android's threading: one writer at a time, and a transaction belongs to the thread that began
 * it. Reads outside a transaction use a small pool of read connections (WAL), so screens keep
 * reading committed rows while a playlist load rewrites them, as on Android.
 */
class SQLiteDatabase internal constructor(private val file: File) {

    /** A connection and its compiled statements; used by one thread at a time. */
    private class Conn(val c: Connection) {
        val cache = object : LinkedHashMap<String, PreparedStatement>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PreparedStatement>): Boolean {
                if (size <= 64) return false
                runCatching { eldest.value.close() }
                return true
            }
        }

        fun prepare(sql: String): PreparedStatement = cache.getOrPut(sql) { c.prepareStatement(sql) }.also { it.clearParameters() }
    }

    private val writer = Conn(open(readOnly = false))
    private val lock = ReentrantLock()
    private var depth = 0
    private var allSuccessful = true
    private var levelSuccessful = BooleanArray(16)

    private val readers = ArrayBlockingQueue<Conn>(READERS)
    @Volatile private var readersMade = 0

    private fun open(readOnly: Boolean): Connection {
        val config = org.sqlite.SQLiteConfig().apply {
            setBusyTimeout(15_000)
            if (readOnly) setReadOnly(true)
            setSynchronous(org.sqlite.SQLiteConfig.SynchronousMode.NORMAL)
        }
        return config.createConnection("jdbc:sqlite:" + file.absolutePath)
    }

    private fun reader(): Conn {
        readers.poll()?.let { return it }
        synchronized(this) {
            if (readersMade < READERS) {
                readersMade++
                return Conn(open(readOnly = true))
            }
        }
        return readers.poll(60, TimeUnit.SECONDS) ?: throw SQLiteException("database busy")
    }

    /** Runs [block] on the writer (inside this thread's transaction, or alone) or a reader. */
    private fun <T> on(write: Boolean, block: (Conn) -> T): T {
        try {
            if (lock.isHeldByCurrentThread) return block(writer)
            if (write) {
                lock.lock()
                try {
                    return block(writer)
                } finally {
                    lock.unlock()
                }
            }
            val r = reader()
            try {
                return block(r)
            } finally {
                readers.offer(r)
            }
        } catch (e: SQLException) {
            throw if (e.message?.contains("CONSTRAINT", ignoreCase = true) == true) SQLiteConstraintException(e.message, e) else SQLiteException(e.message, e)
        }
    }

    private fun isRead(sql: String): Boolean {
        val s = sql.trimStart().take(8).uppercase()
        return (s.startsWith("SELECT") || s.startsWith("WITH")) && !sql.contains("temp.", ignoreCase = true)
    }

    private fun bind(ps: PreparedStatement, args: Array<out Any?>?) {
        args?.forEachIndexed { i, v ->
            val n = i + 1
            when (v) {
                null -> ps.setNull(n, Types.NULL)
                is String -> ps.setString(n, v)
                is Long -> ps.setLong(n, v)
                is Int -> ps.setLong(n, v.toLong())
                is Short -> ps.setLong(n, v.toLong())
                is Byte -> ps.setLong(n, v.toLong())
                is Boolean -> ps.setLong(n, if (v) 1 else 0)
                is Double -> ps.setDouble(n, v)
                is Float -> ps.setDouble(n, v.toDouble())
                is ByteArray -> ps.setBytes(n, v)
                else -> ps.setString(n, v.toString())
            }
        }
    }

    // ---- queries ----

    fun rawQuery(sql: String, selectionArgs: Array<out String?>?): Cursor = on(write = !isRead(sql)) { conn ->
        val ps = conn.prepare(sql)
        bind(ps, selectionArgs)
        ps.executeQuery().use { rs ->
            val md = rs.metaData
            val n = md.columnCount
            val names = Array(n) { md.getColumnLabel(it + 1) }
            val rows = ArrayList<Array<Any?>>()
            while (rs.next()) {
                rows += Array(n) { i ->
                    when (val v = rs.getObject(i + 1)) {
                        is Int -> v.toLong()
                        else -> v
                    }
                }
            }
            MemoryCursor(names, rows)
        }
    }

    fun execSQL(sql: String) = execSQL(sql, null)

    fun execSQL(sql: String, bindArgs: Array<out Any?>?) {
        on(write = true) { conn ->
            val ps = conn.prepare(sql)
            bind(ps, bindArgs)
            ps.execute()
        }
    }

    internal fun executeInsert(sql: String, args: Array<out Any?>?): Long = on(write = true) { conn ->
        val ps = conn.prepare(sql)
        bind(ps, args)
        val changed = ps.executeUpdate()
        if (changed > 0) lastRowId(conn) else -1L
    }

    internal fun executeUpdateDelete(sql: String, args: Array<out Any?>?): Int = on(write = true) { conn ->
        val ps = conn.prepare(sql)
        bind(ps, args)
        ps.executeUpdate()
    }

    private fun lastRowId(conn: Conn): Long = conn.prepare("SELECT last_insert_rowid()").executeQuery().use { if (it.next()) it.getLong(1) else -1L }

    // ---- inserts, updates, deletes ----

    fun insert(table: String, nullColumnHack: String?, values: ContentValues): Long = try {
        insertWithOnConflict(table, nullColumnHack, values, CONFLICT_NONE)
    } catch (e: SQLiteException) {
        -1
    }

    fun insertOrThrow(table: String, nullColumnHack: String?, values: ContentValues): Long =
        insertWithOnConflict(table, nullColumnHack, values, CONFLICT_NONE)

    fun replace(table: String, nullColumnHack: String?, values: ContentValues): Long = try {
        insertWithOnConflict(table, nullColumnHack, values, CONFLICT_REPLACE)
    } catch (e: SQLiteException) {
        -1
    }

    fun insertWithOnConflict(table: String, nullColumnHack: String?, values: ContentValues, conflictAlgorithm: Int): Long {
        val cols = values.values.keys.toList()
        val sql = StringBuilder("INSERT").append(CONFLICT[conflictAlgorithm]).append(" INTO ").append(table)
        if (cols.isEmpty()) {
            sql.append('(').append(nullColumnHack ?: throw SQLiteException("empty insert")).append(") VALUES (NULL)")
        } else {
            sql.append('(').append(cols.joinToString(",")).append(") VALUES (").append(cols.joinToString(",") { "?" }).append(')')
        }
        return executeInsert(sql.toString(), values.values.values.toTypedArray())
    }

    fun update(table: String, values: ContentValues, whereClause: String?, whereArgs: Array<out String?>?): Int =
        updateWithOnConflict(table, values, whereClause, whereArgs, CONFLICT_NONE)

    fun updateWithOnConflict(table: String, values: ContentValues, whereClause: String?, whereArgs: Array<out String?>?, conflictAlgorithm: Int): Int {
        require(values.size() > 0) { "Empty values" }
        val sql = StringBuilder("UPDATE").append(CONFLICT[conflictAlgorithm]).append(' ').append(table).append(" SET ")
            .append(values.values.keys.joinToString(",") { "$it=?" })
        if (!whereClause.isNullOrEmpty()) sql.append(" WHERE ").append(whereClause)
        val args = ArrayList<Any?>(values.values.values)
        whereArgs?.let { args.addAll(it) }
        return executeUpdateDelete(sql.toString(), args.toTypedArray())
    }

    fun delete(table: String, whereClause: String?, whereArgs: Array<out String?>?): Int {
        val sql = "DELETE FROM $table" + if (whereClause.isNullOrEmpty()) "" else " WHERE $whereClause"
        return executeUpdateDelete(sql, whereArgs)
    }

    fun compileStatement(sql: String): SQLiteStatement = SQLiteStatement(this, sql)

    // ---- transactions (per thread, nestable, as on Android) ----

    fun beginTransaction() = begin()

    fun beginTransactionNonExclusive() = begin()

    private fun begin() {
        lock.lock()
        if (depth == 0) {
            try {
                writer.c.createStatement().use { it.execute("BEGIN IMMEDIATE") }
            } catch (e: SQLException) {
                lock.unlock()
                throw SQLiteException(e.message, e)
            }
            allSuccessful = true
        }
        if (depth == levelSuccessful.size) levelSuccessful = levelSuccessful.copyOf(depth * 2)
        levelSuccessful[depth] = false
        depth++
    }

    fun setTransactionSuccessful() {
        check(lock.isHeldByCurrentThread && depth > 0) { "no transaction" }
        levelSuccessful[depth - 1] = true
    }

    fun endTransaction() {
        check(lock.isHeldByCurrentThread && depth > 0) { "no transaction" }
        depth--
        if (!levelSuccessful[depth]) allSuccessful = false
        try {
            if (depth == 0) {
                writer.c.createStatement().use { it.execute(if (allSuccessful) "COMMIT" else "ROLLBACK") }
            }
        } catch (e: SQLException) {
            throw SQLiteException(e.message, e)
        } finally {
            lock.unlock()
        }
    }

    fun inTransaction(): Boolean = lock.isHeldByCurrentThread && depth > 0

    // ---- settings ----

    fun enableWriteAheadLogging(): Boolean {
        on(write = true) { conn -> conn.c.createStatement().use { it.executeQuery("PRAGMA journal_mode=WAL").close() } }
        return true
    }

    fun setForeignKeyConstraintsEnabled(enable: Boolean) {
        on(write = true) { conn -> conn.c.createStatement().use { it.execute("PRAGMA foreign_keys=" + if (enable) "ON" else "OFF") } }
    }

    var version: Int
        get() = on(write = true) { conn -> conn.c.createStatement().use { s -> s.executeQuery("PRAGMA user_version").use { if (it.next()) it.getInt(1) else 0 } } }
        set(v) {
            on(write = true) { conn -> conn.c.createStatement().use { it.execute("PRAGMA user_version = $v") } }
        }

    val path: String get() = file.absolutePath

    val isOpen: Boolean get() = !writer.c.isClosed

    fun close() {
        lock.lock()
        try {
            while (true) {
                val r = readers.poll() ?: break
                runCatching { r.c.close() }
            }
            runCatching { writer.c.close() }
        } finally {
            lock.unlock()
        }
    }

    companion object {
        const val CONFLICT_NONE = 0
        const val CONFLICT_ROLLBACK = 1
        const val CONFLICT_ABORT = 2
        const val CONFLICT_FAIL = 3
        const val CONFLICT_IGNORE = 4
        const val CONFLICT_REPLACE = 5
        private val CONFLICT = arrayOf("", " OR ROLLBACK", " OR ABORT", " OR FAIL", " OR IGNORE", " OR REPLACE")
        private const val READERS = 4

        fun openOrCreate(file: File): SQLiteDatabase {
            file.parentFile?.mkdirs()
            return SQLiteDatabase(file)
        }
    }
}

/** Desktop stand-in for Android's SQLiteStatement: SQL plus bound arguments, run on [db]. */
class SQLiteStatement internal constructor(private val db: SQLiteDatabase, private val sql: String) : java.io.Closeable {
    private var args = arrayOfNulls<Any?>(8)
    private var count = 0

    private fun set(index: Int, v: Any?) {
        if (index > args.size) args = args.copyOf(maxOf(index, args.size * 2))
        args[index - 1] = v
        if (index > count) count = index
    }

    fun bindLong(index: Int, value: Long) = set(index, value)
    fun bindDouble(index: Int, value: Double) = set(index, value)
    fun bindString(index: Int, value: String) = set(index, value)
    fun bindBlob(index: Int, value: ByteArray) = set(index, value)
    fun bindNull(index: Int) = set(index, null)
    fun bindAllArgsAsStrings(bindArgs: Array<String>?) {
        clearBindings()
        bindArgs?.forEachIndexed { i, s -> set(i + 1, s) }
    }

    fun clearBindings() {
        java.util.Arrays.fill(args, null)
        count = 0
    }

    private fun bound() = args.copyOf(count)

    fun execute() {
        db.execSQL(sql, bound())
    }

    fun executeInsert(): Long = db.executeInsert(sql, bound())

    fun executeUpdateDelete(): Int = db.executeUpdateDelete(sql, bound())

    fun simpleQueryForLong(): Long = db.rawQuery(sql, bound().map { it?.toString() }.toTypedArray()).use { c ->
        if (!c.moveToFirst()) throw SQLiteException("no rows") else c.getLong(0)
    }

    fun simpleQueryForString(): String? = db.rawQuery(sql, bound().map { it?.toString() }.toTypedArray()).use { c ->
        if (!c.moveToFirst()) throw SQLiteException("no rows") else c.getString(0)
    }

    override fun close() {}
}

/**
 * Desktop stand-in for Android's SQLiteOpenHelper: opens the file in the context's data folder,
 * creates or upgrades the schema by `PRAGMA user_version`, as Android does.
 */
abstract class SQLiteOpenHelper(private val context: Context?, private val name: String?, factory: Any?, private val version: Int) {

    @Volatile private var db: SQLiteDatabase? = null

    open fun onConfigure(db: SQLiteDatabase) {}
    abstract fun onCreate(db: SQLiteDatabase)
    abstract fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int)
    open fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw SQLiteException("Can't downgrade database from version $oldVersion to $newVersion")
    }
    open fun onOpen(db: SQLiteDatabase) {}

    val writableDatabase: SQLiteDatabase get() = db ?: open()
    val readableDatabase: SQLiteDatabase get() = db ?: open()

    val databaseName: String? get() = name

    @Synchronized
    private fun open(): SQLiteDatabase {
        db?.let { return it }
        val file = context?.getDatabasePath(name ?: "db.sqlite") ?: File(name ?: "db.sqlite")
        val d = SQLiteDatabase.openOrCreate(file)
        onConfigure(d)
        val current = d.version
        if (current != version) {
            d.beginTransaction()
            try {
                when {
                    current == 0 -> onCreate(d)
                    current < version -> onUpgrade(d, current, version)
                    else -> onDowngrade(d, current, version)
                }
                d.version = version
                d.setTransactionSuccessful()
            } finally {
                d.endTransaction()
            }
        }
        onOpen(d)
        db = d
        return d
    }

    @Synchronized
    fun close() {
        db?.close()
        db = null
    }
}
