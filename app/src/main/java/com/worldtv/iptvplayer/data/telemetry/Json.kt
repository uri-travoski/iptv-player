package com.worldtv.iptvplayer.data.telemetry

/**
 * Tiny JSON writer, pure Kotlin (no Android, no org.json) so the telemetry core runs as a JVM
 * unit test and on cheap boxes without pulling in a dependency. Only what PostgREST rows need:
 * objects, arrays, strings (escaped), numbers, booleans and null.
 */
object Json {

    /** One row: `{"k":v,...}`. Values may be String/Number/Boolean/null/Map/Iterable. */
    fun row(fields: Map<String, Any?>): String = StringBuilder(128).also { writeObject(it, fields) }.toString()

    /** A PostgREST request body from already-serialized rows: `[row,row,...]`. */
    fun arrayOfRows(rows: List<String>): String = rows.joinToString(separator = ",", prefix = "[", postfix = "]")

    private fun writeValue(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (v) "true" else "false")
            is Double -> if (v.isFinite()) sb.append(v) else sb.append("null")
            is Float -> if (v.isFinite()) sb.append(v) else sb.append("null")
            is Number -> sb.append(v.toString())
            is Map<*, *> -> writeObject(sb, v)
            is Iterable<*> -> writeArray(sb, v)
            else -> writeString(sb, v.toString())
        }
    }

    private fun writeObject(sb: StringBuilder, map: Map<*, *>) {
        sb.append('{')
        var first = true
        for ((k, v) in map) {
            if (k == null) continue
            if (!first) sb.append(',')
            first = false
            writeString(sb, k.toString())
            sb.append(':')
            writeValue(sb, v)
        }
        sb.append('}')
    }

    private fun writeArray(sb: StringBuilder, list: Iterable<*>) {
        sb.append('[')
        var first = true
        for (v in list) {
            if (!first) sb.append(',')
            first = false
            writeValue(sb, v)
        }
        sb.append(']')
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append("\\u").append(c.code.toString(16).padStart(4, '0')) else sb.append(c)
            }
        }
        sb.append('"')
    }
}
