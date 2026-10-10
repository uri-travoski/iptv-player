package com.worldtv.iptvplayer.data.telemetry

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** One queued write: a target table and the row's columns. */
class TelemetryEntry(val table: String, val fields: Map<String, Any?>)

/** Batched rows for one table, ready to be joined into a PostgREST array. */
class TelemetryBatch(val table: String, val rows: List<String>)

/**
 * The in-memory hand-off between the app and the telemetry thread. [offer] is cheap and
 * lock-free (a map build plus a queue add), so callers — including the UI thread — never block.
 * The queue is bounded: at [capacity] the oldest entry is dropped, so a broken network can never
 * grow it without limit.
 */
class TelemetryQueue(private val capacity: Int = DEFAULT_CAPACITY) {

    private val queue = ConcurrentLinkedQueue<TelemetryEntry>()
    private val size = AtomicInteger()

    fun offer(table: String, fields: Map<String, Any?>) {
        if (size.incrementAndGet() > capacity) {
            if (queue.poll() != null) size.decrementAndGet()
        }
        queue.add(TelemetryEntry(table, fields))
    }

    /** Removes and returns everything queued right now. */
    fun drain(): List<TelemetryEntry> {
        val out = ArrayList<TelemetryEntry>()
        while (true) {
            val e = queue.poll() ?: break
            size.decrementAndGet()
            out.add(e)
        }
        return out
    }

    val depth: Int get() = size.get()

    companion object {
        const val DEFAULT_CAPACITY = 4000
    }
}

/** Turns queued entries into one serialized row list per table. Pure Kotlin, unit-tested. */
object TelemetrySerializer {

    fun batches(entries: List<TelemetryEntry>): List<TelemetryBatch> {
        if (entries.isEmpty()) return emptyList()
        val byTable = LinkedHashMap<String, MutableList<String>>()
        for (e in entries) byTable.getOrPut(e.table) { ArrayList() }.add(Json.row(e.fields))
        return byTable.map { (table, rows) -> TelemetryBatch(table, rows) }
    }
}

/** A stream URL's host:port, for grouping playback problems without logging credentials. */
fun streamHost(url: String?): String? {
    val u = url ?: return null
    val schemeEnd = u.indexOf("://")
    val start = if (schemeEnd >= 0) schemeEnd + 3 else 0
    val end = u.indexOf('/', start).let { if (it < 0) u.length else it }
    return u.substring(start, end).substringAfter('@').ifEmpty { null }
}

/** The container extension of a stream URL ("ts", "m3u8"), or null. */
fun streamExt(url: String?): String? {
    val u = url ?: return null
    val name = u.substringAfterLast('/').substringBefore('?')
    val dot = name.lastIndexOf('.')
    return if (dot in 0 until name.length - 1) name.substring(dot + 1).lowercase() else null
}
