package com.worldtv.iptvplayer.data.check

/**
 * Reads an MPEG-TS byte stream (as it arrives, in any chunk sizes) and measures what a channel
 * check needs: how much video arrived and how many seconds of it (from the PES timestamps).
 * Nothing is decoded. Pure Kotlin, so it runs as a JVM unit test.
 *
 * Finds the video stream through the PAT and PMT; a stream with no video (radio) is measured on
 * its first audio stream instead.
 */
class TsStats {

    /** Payload bytes of the measured (video, else audio) stream. */
    var mediaBytes = 0L
        private set

    /** Bytes accepted as TS packets (after the first sync). */
    var tsBytes = 0L
        private set

    var hasVideo = false
        private set

    private var synced = false
    private val carry = ByteArray(PACKET)
    private var carryLen = 0
    private var pmtPid = -1
    private var mediaPid = -1
    private var minPts = Long.MAX_VALUE
    private var maxPts = Long.MIN_VALUE

    /** Seconds of media between the first and last timestamp seen (0 until two are seen). */
    val seconds: Double
        get() = if (maxPts > minPts) (maxPts - minPts) / 90_000.0 else 0.0

    /** Media bit rate in kbit/s over [seconds]; 0 until measurable. */
    val kbps: Double
        get() = if (seconds > 0) mediaBytes * 8 / seconds / 1000 else 0.0

    val isTs: Boolean get() = synced

    /** Bytes before the first sync is found, which may come in small pieces. */
    private var head: java.io.ByteArrayOutputStream? = java.io.ByteArrayOutputStream()

    fun feed(data: ByteArray, len: Int = data.size) {
        val h = head
        if (h != null) {
            if (h.size() >= MAX_HEAD) return // not a TS stream (an error page, HTML): ignored
            h.write(data, 0, len)
            val buf = h.toByteArray()
            var i = 0
            // Lock on to three sync bytes 188 apart; the bytes before are dropped.
            while (i + 2 * PACKET < buf.size) {
                if (buf[i] == SYNC && buf[i + PACKET] == SYNC && buf[i + 2 * PACKET] == SYNC) {
                    synced = true
                    head = null
                    packets(buf, i, buf.size)
                    return
                }
                i++
            }
            return
        }
        packets(data, 0, len)
    }

    private fun packets(data: ByteArray, from: Int, len: Int) {
        var i = from
        if (carryLen > 0) {
            val need = PACKET - carryLen
            val n = minOf(need, len - i)
            System.arraycopy(data, i, carry, carryLen, n)
            carryLen += n
            i += n
            if (carryLen < PACKET) return
            packet(carry, 0)
            carryLen = 0
        }
        while (i + PACKET <= len) {
            if (data[i] != SYNC) {
                // Lost sync (a broken stream): find it again.
                var j = i + 1
                while (j + PACKET <= len && data[j] != SYNC) j++
                i = j
                continue
            }
            packet(data, i)
            i += PACKET
        }
        if (i < len) {
            carryLen = len - i
            System.arraycopy(data, i, carry, 0, carryLen)
        }
    }

    private fun packet(b: ByteArray, o: Int) {
        tsBytes += PACKET
        val pusi = b[o + 1].toInt() and 0x40 != 0
        val pid = ((b[o + 1].toInt() and 0x1f) shl 8) or (b[o + 2].toInt() and 0xff)
        val afc = (b[o + 3].toInt() shr 4) and 0x3
        if (afc == 0 || afc == 2) return // no payload
        var p = o + 4
        if (afc == 3) p += 1 + (b[o + 4].toInt() and 0xff)
        val end = o + PACKET
        if (p >= end) return
        when {
            pid == 0 && pusi -> parsePat(b, p, end)
            pid == pmtPid && pusi -> parsePmt(b, p, end)
            pid == mediaPid -> {
                if (pusi) pes(b, p, end) else mediaBytes += end - p
            }
        }
    }

    private fun section(b: ByteArray, p: Int, end: Int): Int? {
        val start = p + 1 + (b[p].toInt() and 0xff) // pointer field
        return if (start + 8 < end) start else null
    }

    private fun parsePat(b: ByteArray, p: Int, end: Int) {
        val s = section(b, p, end) ?: return
        val len = ((b[s + 1].toInt() and 0x0f) shl 8) or (b[s + 2].toInt() and 0xff)
        val last = minOf(s + 3 + len - 4, end) // before the CRC
        var i = s + 8
        while (i + 4 <= last) {
            val program = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)
            val pid = ((b[i + 2].toInt() and 0x1f) shl 8) or (b[i + 3].toInt() and 0xff)
            if (program != 0) {
                pmtPid = pid
                return
            }
            i += 4
        }
    }

    private fun parsePmt(b: ByteArray, p: Int, end: Int) {
        if (mediaPid >= 0) return
        val s = section(b, p, end) ?: return
        if (s + 12 > end) return
        val len = ((b[s + 1].toInt() and 0x0f) shl 8) or (b[s + 2].toInt() and 0xff)
        val last = minOf(s + 3 + len - 4, end)
        val infoLen = ((b[s + 10].toInt() and 0x0f) shl 8) or (b[s + 11].toInt() and 0xff)
        var i = s + 12 + infoLen
        var audio = -1
        while (i + 5 <= last) {
            val type = b[i].toInt() and 0xff
            val pid = ((b[i + 1].toInt() and 0x1f) shl 8) or (b[i + 2].toInt() and 0xff)
            val esLen = ((b[i + 3].toInt() and 0x0f) shl 8) or (b[i + 4].toInt() and 0xff)
            if (type in VIDEO_TYPES) {
                mediaPid = pid
                hasVideo = true
                return
            }
            if (audio < 0 && type in AUDIO_TYPES) audio = pid
            i += 5 + esLen
        }
        if (audio >= 0) mediaPid = audio
    }

    private fun pes(b: ByteArray, p: Int, end: Int) {
        if (p + 9 > end || b[p].toInt() != 0 || b[p + 1].toInt() != 0 || b[p + 2].toInt() != 1) {
            mediaBytes += end - p
            return
        }
        val headerLen = b[p + 8].toInt() and 0xff
        val ptsFlag = b[p + 7].toInt() and 0x80 != 0
        if (ptsFlag && p + 14 <= end) {
            val pts = ((b[p + 9].toLong() and 0x0e) shl 29) or
                ((b[p + 10].toLong() and 0xff) shl 22) or
                ((b[p + 11].toLong() and 0xfe) shl 14) or
                ((b[p + 12].toLong() and 0xff) shl 7) or
                ((b[p + 13].toLong() and 0xfe) shr 1)
            if (pts < minPts) minPts = pts
            if (pts > maxPts) maxPts = pts
        }
        val payload = p + 9 + headerLen
        if (payload < end) mediaBytes += end - payload
    }

    companion object {
        const val PACKET = 188
        private const val MAX_HEAD = 64 * 1024
        private const val SYNC = 0x47.toByte()
        /** MPEG-1/2, MPEG-4 part 2, H.264, H.265, AVS2/AVS3 and VVC. */
        private val VIDEO_TYPES = setOf(0x01, 0x02, 0x10, 0x1b, 0x24, 0x42, 0xd2, 0xd4, 0x33)
        /** MPEG audio, AAC (ADTS / LATM), AC-3, E-AC-3 and DTS. */
        private val AUDIO_TYPES = setOf(0x03, 0x04, 0x0f, 0x11, 0x81, 0x87, 0x82)
    }
}

/** What a channel check found. */
enum class CheckResult {
    /** Video arrived, live and not a black / still filler. */
    WORKING,

    /** The panel answered but no usable picture came: an error, no data, a stall or black filler. */
    NOT_WORKING,

    /** Couldn't tell (no network, the box offline, the check stopped): nothing is recorded. */
    UNKNOWN,
}

/** The decision, from what one check measured. Pure, for unit tests. */
object CheckVerdict {

    /** Below this many kbit/s of video, a picture is black or a still card (real TV: 500+). */
    const val MIN_VIDEO_KBPS = 120.0

    /** At least this much media must arrive inside the check's time. */
    const val MIN_SECONDS = 1.0

    fun of(stats: TsStats): CheckResult {
        if (!stats.isTs) return CheckResult.NOT_WORKING
        if (stats.seconds < MIN_SECONDS) return CheckResult.NOT_WORKING
        if (stats.hasVideo && stats.kbps < MIN_VIDEO_KBPS) return CheckResult.NOT_WORKING
        return CheckResult.WORKING
    }
}
