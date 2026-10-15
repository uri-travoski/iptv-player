package android.content

/** Desktop stand-in for Android's ContentValues: column name → value, in insertion order. */
class ContentValues {
    internal val values = LinkedHashMap<String, Any?>()

    fun put(key: String, value: String?) { values[key] = value }
    fun put(key: String, value: Long?) { values[key] = value }
    fun put(key: String, value: Int?) { values[key] = value?.toLong() }
    fun put(key: String, value: Short?) { values[key] = value?.toLong() }
    fun put(key: String, value: Boolean?) { values[key] = value?.let { if (it) 1L else 0L } }
    fun put(key: String, value: Double?) { values[key] = value }
    fun put(key: String, value: Float?) { values[key] = value?.toDouble() }
    fun put(key: String, value: ByteArray?) { values[key] = value }
    fun putNull(key: String) { values[key] = null }
    fun remove(key: String) { values.remove(key) }
    fun containsKey(key: String) = key in values
    fun get(key: String): Any? = values[key]
    fun size() = values.size
    fun keySet(): Set<String> = values.keys
}

/** Desktop stand-in: where the database file lives. */
open class Context(val dataDir: java.io.File) {
    fun getDatabasePath(name: String): java.io.File = java.io.File(dataDir, name)
}
