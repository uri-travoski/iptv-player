package com.worldtv.iptvplayer.data.source

/**
 * Xtream panels often base64-encode EPG titles and descriptions, but not always.
 * Decodes when the text is clearly base64 of readable UTF-8, otherwise returns it unchanged.
 * (java.util.Base64 needs API 26 and android.util.Base64 isn't available in JVM tests.)
 */
object Base64Text {

    fun maybeDecode(input: String?): String? {
        if (input.isNullOrEmpty()) return input
        val s = input.trim()
        if (s.length < 4 || s.any { !isBase64Char(it) }) return input
        val bytes = decode(s) ?: return input
        val text = try {
            val decoder = Charsets.UTF_8.newDecoder()
            decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            return input
        }
        // Plain words like "News" are valid base64 but decode to binary noise: reject those.
        val controls = text.count { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }
        return if (controls == 0 && text.isNotBlank()) text else input
    }

    private fun isBase64Char(c: Char) =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '+' || c == '/' || c == '=' || c == '-' || c == '_'

    private fun value(c: Char): Int = when (c) {
        in 'A'..'Z' -> c - 'A'
        in 'a'..'z' -> c - 'a' + 26
        in '0'..'9' -> c - '0' + 52
        '+', '-' -> 62
        '/', '_' -> 63
        else -> -1
    }

    /** Standard or URL-safe alphabet, padded or not. Returns null on malformed input. */
    fun decode(s: String): ByteArray? {
        val clean = s.trimEnd('=')
        if (clean.length % 4 == 1) return null
        val out = java.io.ByteArrayOutputStream(clean.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val v = value(c)
            if (v < 0) return null
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}
