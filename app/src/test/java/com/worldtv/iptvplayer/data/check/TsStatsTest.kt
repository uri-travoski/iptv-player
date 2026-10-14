package com.worldtv.iptvplayer.data.check

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class TsStatsTest {

    /** A TS stream: PAT, PMT (one stream of [streamType] on PID 0x100), then [frames] frames. */
    private fun stream(seconds: Double, kbps: Int, fps: Int = 25, streamType: Int = 0x1b): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(packet(0, true, psi(byteArrayOf(0, 1, (0xe0 or 0x10).toByte(), 0x00)))) // program 1 -> PMT PID 0x1000
        val pmt = byteArrayOf(
            0xe1.toByte(), 0x00, // PCR PID 0x100
            0xf0.toByte(), 0x00, // no program info
            streamType.toByte(), 0xe1.toByte(), 0x00, 0xf0.toByte(), 0x00, // stream on PID 0x100
        )
        out.write(packet(0x1000, true, psi(pmt, tableId = 2)))
        val frames = (seconds * fps).toInt()
        val bytesPerFrame = kbps * 1000 / 8 / fps
        for (f in 0..frames) {
            val pts = 90_000L * f / fps
            val pes = ByteArrayOutputStream()
            pes.write(byteArrayOf(0, 0, 1, 0xe0.toByte(), 0, 0, 0x80.toByte(), 0x80.toByte(), 5))
            pes.write(ptsBytes(pts))
            pes.write(ByteArray(bytesPerFrame))
            val data = pes.toByteArray()
            var i = 0
            var first = true
            while (i < data.size) {
                val n = minOf(184, data.size - i)
                out.write(packet(0x100, first, data.copyOfRange(i, i + n)))
                first = false
                i += n
            }
        }
        return out.toByteArray()
    }

    private fun psi(body: ByteArray, tableId: Int = 0): ByteArray {
        val len = 5 + body.size + 4
        val head = byteArrayOf(0, tableId.toByte(), (0xb0 or (len shr 8)).toByte(), len.toByte(), 0, 1, 0xc1.toByte(), 0, 0)
        return head + body + byteArrayOf(0, 0, 0, 0) // pointer field first; CRC not checked
    }

    private fun packet(pid: Int, start: Boolean, payload: ByteArray): ByteArray {
        val p = ByteArray(188) { 0xff.toByte() }
        p[0] = 0x47
        p[1] = ((if (start) 0x40 else 0) or (pid shr 8)).toByte()
        p[2] = pid.toByte()
        if (payload.size >= 184) {
            p[3] = 0x10
            System.arraycopy(payload, 0, p, 4, 184)
        } else {
            // Adaptation field stuffing so the payload ends the packet.
            val af = 183 - payload.size
            p[3] = 0x30
            p[4] = af.toByte()
            if (af > 0) p[5] = 0
            System.arraycopy(payload, 0, p, 188 - payload.size, payload.size)
        }
        return p
    }

    private fun ptsBytes(pts: Long) = byteArrayOf(
        (0x21 or ((pts shr 29) and 0x0e).toInt()).toByte(),
        (pts shr 22).toByte(),
        (((pts shr 14) and 0xfe) or 1).toInt().toByte(),
        (pts shr 7).toByte(),
        (((pts shl 1) and 0xfe) or 1).toInt().toByte(),
    )

    private fun stats(bytes: ByteArray, chunk: Int = 4096) = TsStats().also { s ->
        var i = 0
        while (i < bytes.size) {
            val n = minOf(chunk, bytes.size - i)
            s.feed(bytes.copyOfRange(i, i + n), n)
            i += n
        }
    }

    @Test
    fun liveVideoWorks() {
        val s = stats(stream(seconds = 3.0, kbps = 3000))
        assertTrue(s.isTs)
        assertTrue(s.hasVideo)
        assertEquals(3.0, s.seconds, 0.01)
        assertEquals(3000.0, s.kbps, 150.0)
        assertEquals(CheckResult.WORKING, CheckVerdict.of(s))
    }

    @Test
    fun blackFillerIsNotWorking() {
        // What dead event channels send: minutes of a black picture at ~70 kbit/s.
        val s = stats(stream(seconds = 60.0, kbps = 70))
        assertTrue(s.seconds > 50)
        assertEquals(CheckResult.NOT_WORKING, CheckVerdict.of(s))
    }

    @Test
    fun aStallIsNotWorking() {
        assertEquals(CheckResult.NOT_WORKING, CheckVerdict.of(stats(stream(seconds = 0.4, kbps = 3000))))
    }

    @Test
    fun notTsIsNotWorking() {
        val html = "<html><body>Max connections reached</body></html>".repeat(50).toByteArray()
        val s = stats(html)
        assertFalse(s.isTs)
        assertEquals(CheckResult.NOT_WORKING, CheckVerdict.of(s))
    }

    @Test
    fun radioIsMeasuredOnItsAudio() {
        val s = stats(stream(seconds = 3.0, kbps = 128, streamType = 0x0f))
        assertFalse(s.hasVideo)
        assertEquals(CheckResult.WORKING, CheckVerdict.of(s))
    }

    @Test
    fun anyChunkSizeGivesTheSameResult() {
        val bytes = stream(seconds = 2.0, kbps = 2000)
        // Leading junk before the first packet, too.
        val withJunk = ByteArray(77) { 1 } + bytes
        val a = stats(withJunk, chunk = 1)
        val b = stats(withJunk, chunk = 997)
        val c = stats(withJunk, chunk = withJunk.size)
        assertEquals(c.mediaBytes, a.mediaBytes)
        assertEquals(c.mediaBytes, b.mediaBytes)
        assertEquals(c.seconds, a.seconds, 0.0)
    }
}
