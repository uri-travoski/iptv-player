package android.database

/** The rows of one query, held in memory. Values are what JDBC returned (Long, Double, String, ByteArray, null). */
class MemoryCursor(private val names: Array<String>, private val rows: List<Array<Any?>>) : Cursor {

    private var position = -1

    override fun getPosition() = position
    override fun getCount() = rows.size
    override fun getColumnCount() = names.size
    override fun getColumnNames() = names
    override fun isAfterLast() = position >= rows.size

    override fun moveToNext(): Boolean = moveToPosition(position + 1)

    override fun moveToFirst(): Boolean = moveToPosition(0)

    override fun moveToPosition(position: Int): Boolean {
        this.position = position.coerceIn(-1, rows.size)
        return position in rows.indices
    }

    override fun getColumnIndex(name: String): Int = names.indexOfFirst { it.equals(name, ignoreCase = true) }

    override fun getColumnIndexOrThrow(name: String): Int =
        getColumnIndex(name).takeIf { it >= 0 } ?: throw IllegalArgumentException("column '$name' does not exist")

    override fun getColumnName(index: Int): String = names[index]

    private fun value(index: Int): Any? {
        check(position in rows.indices) { "cursor at $position of ${rows.size}" }
        return rows[position][index]
    }

    override fun isNull(index: Int) = value(index) == null

    override fun getString(index: Int): String? = when (val v = value(index)) {
        null -> null
        is ByteArray -> String(v, Charsets.UTF_8)
        is Double -> if (v == Math.floor(v) && !v.isInfinite() && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()
        else -> v.toString()
    }

    // Like SQLite's own conversion: text reads as its leading number, 0 if none.
    override fun getLong(index: Int): Long = when (val v = value(index)) {
        null -> 0
        is Number -> v.toLong()
        is String -> v.trim().toLongOrNull() ?: v.trim().toDoubleOrNull()?.toLong() ?: leadingNumber(v).toLong()
        else -> 0
    }

    override fun getInt(index: Int): Int = getLong(index).toInt()

    override fun getDouble(index: Int): Double = when (val v = value(index)) {
        null -> 0.0
        is Number -> v.toDouble()
        is String -> v.trim().toDoubleOrNull() ?: leadingNumber(v)
        else -> 0.0
    }

    override fun getFloat(index: Int): Float = getDouble(index).toFloat()

    override fun getBlob(index: Int): ByteArray? = when (val v = value(index)) {
        null -> null
        is ByteArray -> v
        else -> v.toString().toByteArray()
    }

    private fun leadingNumber(s: String): Double =
        Regex("""^\s*[+-]?(\d+(\.\d*)?|\.\d+)([eE][+-]?\d+)?""").find(s)?.value?.trim()?.toDoubleOrNull() ?: 0.0

    override fun close() {}
}
