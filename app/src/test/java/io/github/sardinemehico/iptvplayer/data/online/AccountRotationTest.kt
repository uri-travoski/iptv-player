package io.github.sardinemehico.iptvplayer.data.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class AccountRotationTest {

    private val a = OnlineAccount("key-a-1234567890")
    private val b = OnlineAccount("key-b-1234567890")
    private val c = OnlineAccount("key-c-1234567890")

    @Test
    fun nextAccountWhenOneFails() {
        val r = AccountRotation(OnlineService.TMDB, log = {}) { listOf(a, b, c) }
        val tried = ArrayList<String>()
        val result = r.run { acc ->
            tried += acc.key
            when (acc) {
                a -> throw OnlineFailure("key refused", tryNext = true)
                b -> throw IOException("timeout")
                else -> "ok from ${acc.key}"
            }
        }
        assertEquals("ok from ${c.key}", result)
        assertEquals(listOf(a.key, b.key, c.key), tried)
        assertEquals("key refused", r.status[a.key])
        assertEquals("OK", r.status[c.key])
    }

    @Test
    fun startsWithTheOneThatWorkedLast() {
        val r = AccountRotation(OnlineService.TMDB, log = {}) { listOf(a, b, c) }
        r.run { acc -> if (acc == b) "ok" else throw OnlineFailure("limit", tryNext = true) }
        val tried = ArrayList<OnlineAccount>()
        r.run { acc -> tried += acc; "ok" }
        assertEquals(listOf(b), tried)
    }

    @Test
    fun stopsWhenNoAccountCanHelp() {
        val r = AccountRotation(OnlineService.TMDB, log = {}) { listOf(a, b) }
        var calls = 0
        try {
            r.run<String> { calls++; throw OnlineFailure("bad request", tryNext = false) }
            fail()
        } catch (e: OnlineFailure) {
            assertEquals(1, calls)
        }
        try {
            r.run<String> { throw OnlineFailure("refused", tryNext = true) }
            fail()
        } catch (e: OnlineFailure) {
            assertEquals("refused", e.message)
        }
        try {
            AccountRotation(OnlineService.TMDB, log = {}) { emptyList() }.run { "x" }
            fail()
        } catch (e: OnlineFailure) {
            assertTrue(e.message!!.contains("No TMDB account"))
        }
    }

    @Test
    fun labelHidesTheKey() {
        assertEquals("abcd…wxyz · me", OnlineAccount("abcdEFGHIJKLwxyz", "me").label)
    }
}
