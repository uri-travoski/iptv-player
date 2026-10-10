package com.worldtv.iptvplayer.data.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryCoreTest {

    @Test
    fun jsonEscapesAndTypes() {
        val row = Json.row(
            linkedMapOf(
                "s" to "a\"b\\c\nd\te",
                "i" to 42,
                "l" to 42L,
                "b" to true,
                "n" to null,
                "d" to 1.5,
                "arr" to listOf("x", "y"),
                "obj" to mapOf("k" to "v"),
            ),
        )
        assertEquals(
            """{"s":"a\"b\\c\nd\te","i":42,"l":42,"b":true,"n":null,"d":1.5,"arr":["x","y"],"obj":{"k":"v"}}""",
            row,
        )
    }

    @Test
    fun jsonSkipsNonFinite() {
        val row = Json.row(linkedMapOf<String, Any?>("a" to Double.NaN, "b" to Double.POSITIVE_INFINITY))
        assertEquals("""{"a":null,"b":null}""", row)
    }

    @Test
    fun serializerGroupsByTableAndBuildsArrays() {
        val entries = listOf(
            TelemetryEntry("events", linkedMapOf("a" to 1)),
            TelemetryEntry("sessions", linkedMapOf("b" to 2)),
            TelemetryEntry("events", linkedMapOf("c" to 3)),
        )
        val batches = TelemetrySerializer.batches(entries)
        assertEquals(2, batches.size)
        val events = batches.first { it.table == "events" }
        assertEquals(2, events.rows.size)
        assertEquals("""[{"a":1},{"c":3}]""", Json.arrayOfRows(events.rows))
        assertEquals("""[{"b":2}]""", Json.arrayOfRows(batches.first { it.table == "sessions" }.rows))
    }

    @Test
    fun queueIsBoundedAndDrains() {
        val q = TelemetryQueue(capacity = 3)
        for (i in 1..5) q.offer("events", mapOf("i" to i))
        assertEquals(3, q.depth)
        val drained = q.drain()
        assertEquals(3, drained.size)
        // Oldest (1, 2) were dropped; 3, 4, 5 remain, in order.
        assertEquals(listOf(3, 4, 5), drained.map { it.fields["i"] })
        assertEquals(0, q.depth)
        assertTrue(q.drain().isEmpty())
    }

    @Test
    fun streamHostAndExt() {
        assertEquals("host:8080", streamHost("http://host:8080/live/user/pass/1.ts"))
        assertEquals("host", streamHost("https://host/a/b.m3u8?x=1"))
        assertEquals("host", streamHost("http://user:pass@host/x"))
        assertNull(streamHost(null))
        assertEquals("ts", streamExt("http://h/live/u/p/1.ts"))
        assertEquals("m3u8", streamExt("http://h/a/b.M3U8?x=1"))
        assertNull(streamExt("http://h/noext"))
    }

    /**
     * Not a correctness test: measures the cost the app pays on the calling thread — building a
     * map and offering it to the queue. Serialization happens later on the telemetry thread.
     */
    @Test
    fun enqueueBenchmark() {
        val q = TelemetryQueue(capacity = 1_000_000)
        val rounds = 200_000
        // warm up
        repeat(20_000) { q.offer("events", mapOf("installation_id" to "x", "kind" to "playback", "action" to "play", "ts" to "2026-01-01T00:00:00.000Z")) }
        q.drain()
        val start = System.nanoTime()
        for (i in 0 until rounds) {
            q.offer("events", mapOf("installation_id" to "x", "kind" to "playback", "action" to "play", "ts" to "2026-01-01T00:00:00.000Z", "i" to i))
        }
        val elapsed = System.nanoTime() - start
        val perEventNs = elapsed.toDouble() / rounds
        println("enqueueBenchmark: %.2f ns/event (%.4f ms for %d events)"
            .format(perEventNs, elapsed / 1_000_000.0, rounds))
        q.drain()
        assertTrue("enqueue must be well under a microsecond", perEventNs < 5_000)
    }

    @Test
    fun serializeBenchmark() {
        val entries = (0 until 10_000).map {
            TelemetryEntry("events", mapOf("installation_id" to "abc", "ts" to "2026-01-01T00:00:00.000Z", "kind" to "playback", "action" to "watch", "i" to it))
        }
        val start = System.nanoTime()
        val batches = TelemetrySerializer.batches(entries)
        val elapsed = System.nanoTime() - start
        println("serializeBenchmark: %.2f us/row (%d rows, %d batches)"
            .format(elapsed / 1000.0 / entries.size, entries.size, batches.size))
        assertEquals(1, batches.size)
        assertEquals(10_000, batches[0].rows.size)
    }
}
