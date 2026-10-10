package com.worldtv.iptvplayer.data.source

import java.io.Closeable
import java.io.IOException
import java.io.Reader

/**
 * Minimal, lenient, streaming JSON pull parser.
 *
 * Why not org.json or android.util.JsonReader: Xtream responses can be tens of MB, so they
 * must be read as a stream, and the parsers need to run in plain JVM unit tests.
 * This reader never builds a tree and never holds more than one value in memory.
 *
 * Lenient where Xtream panels are sloppy: values can be read as strings whatever their JSON
 * type ([nextStringOrNull]), and numbers stored as strings can be read as numbers ([nextLongOrNull]).
 */
class JsonPull(private val reader: Reader) : Closeable {

    enum class Token { BEGIN_OBJECT, END_OBJECT, BEGIN_ARRAY, END_ARRAY, NAME, STRING, NUMBER, BOOLEAN, NULL, END_DOCUMENT }

    private val buffer = CharArray(16 * 1024)
    private var pos = 0
    private var limit = 0

    private var stack = IntArray(32)
    private var depth = 0
    private var peeked: Token? = null

    init {
        push(EMPTY_DOCUMENT)
    }

    fun peek(): Token {
        peeked?.let { return it }
        val token = when (stack[depth - 1]) {
            EMPTY_ARRAY -> {
                stack[depth - 1] = NONEMPTY_ARRAY
                val c = nextNonWhitespace()
                if (c == ']'.code) Token.END_ARRAY else { unread(); readValueToken() }
            }
            NONEMPTY_ARRAY -> {
                when (val c = nextNonWhitespace()) {
                    ']'.code -> Token.END_ARRAY
                    ','.code -> readValueToken()
                    -1 -> throw IOException("Unterminated array")
                    else -> { unread(); if (c == -1) Token.END_DOCUMENT else readValueToken() }
                }
            }
            EMPTY_OBJECT, NONEMPTY_OBJECT -> {
                val wasEmpty = stack[depth - 1] == EMPTY_OBJECT
                stack[depth - 1] = DANGLING_NAME
                var c = nextNonWhitespace()
                if (!wasEmpty && c == ','.code) c = nextNonWhitespace()
                when (c) {
                    '}'.code -> Token.END_OBJECT
                    '"'.code -> Token.NAME
                    -1 -> throw IOException("Unterminated object")
                    else -> throw IOException("Expected a name at offset $pos")
                }
            }
            DANGLING_NAME -> {
                stack[depth - 1] = NONEMPTY_OBJECT
                val c = nextNonWhitespace()
                if (c != ':'.code) throw IOException("Expected ':'")
                readValueToken()
            }
            EMPTY_DOCUMENT -> {
                stack[depth - 1] = NONEMPTY_DOCUMENT
                val c = nextNonWhitespace()
                if (c == -1) Token.END_DOCUMENT else { unread(); readValueToken() }
            }
            else -> { // NONEMPTY_DOCUMENT
                val c = nextNonWhitespace()
                if (c == -1) Token.END_DOCUMENT else { unread(); readValueToken() }
            }
        }
        peeked = token
        return token
    }

    fun hasNext(): Boolean {
        val t = peek()
        return t != Token.END_OBJECT && t != Token.END_ARRAY && t != Token.END_DOCUMENT
    }

    fun beginObject() {
        expect(Token.BEGIN_OBJECT)
        push(EMPTY_OBJECT)
    }

    fun endObject() {
        expect(Token.END_OBJECT)
        depth--
    }

    fun beginArray() {
        expect(Token.BEGIN_ARRAY)
        push(EMPTY_ARRAY)
    }

    fun endArray() {
        expect(Token.END_ARRAY)
        depth--
    }

    fun nextName(): String {
        expect(Token.NAME)
        return readQuoted()
    }

    /** Any scalar as text; null for JSON null. Objects and arrays are skipped and return null. */
    fun nextStringOrNull(): String? {
        return when (peek()) {
            Token.STRING -> { peeked = null; readQuoted() }
            Token.NUMBER, Token.BOOLEAN -> { peeked = null; readLiteral() }
            Token.NULL -> { peeked = null; readLiteral(); null }
            Token.BEGIN_OBJECT, Token.BEGIN_ARRAY -> { skipValue(); null }
            else -> throw IOException("Expected a value but was ${peek()}")
        }
    }

    /** Numbers, numeric strings ("12"), and decimals ("4.5" -> 4) as Long; anything else null. */
    fun nextLongOrNull(): Long? {
        val s = nextStringOrNull()?.trim() ?: return null
        return s.toLongOrNull() ?: s.toDoubleOrNull()?.toLong()
    }

    fun skipValue() {
        when (peek()) {
            Token.BEGIN_OBJECT -> {
                beginObject()
                while (hasNext()) { nextName(); skipValue() }
                endObject()
            }
            Token.BEGIN_ARRAY -> {
                beginArray()
                while (hasNext()) skipValue()
                endArray()
            }
            Token.NAME -> { nextName(); skipValue() }
            Token.END_OBJECT, Token.END_ARRAY, Token.END_DOCUMENT -> Unit
            else -> nextStringOrNull()
        }
    }

    override fun close() = reader.close()

    // ---- internals ----

    private fun expect(token: Token) {
        val actual = peek()
        if (actual != token) throw IOException("Expected $token but was $actual")
        peeked = null
    }

    private fun push(scope: Int) {
        if (depth == stack.size) stack = stack.copyOf(depth * 2)
        stack[depth++] = scope
    }

    private fun readValueToken(): Token {
        return when (nextNonWhitespace()) {
            '{'.code -> Token.BEGIN_OBJECT
            '['.code -> Token.BEGIN_ARRAY
            '"'.code -> Token.STRING
            't'.code, 'f'.code -> { unread(); Token.BOOLEAN }
            'n'.code -> { unread(); Token.NULL }
            -1 -> throw IOException("Unexpected end of input")
            else -> { unread(); Token.NUMBER }
        }
    }

    private fun read(): Int {
        if (pos == limit) {
            val n = reader.read(buffer, 0, buffer.size)
            if (n <= 0) return -1
            pos = 0
            limit = n
        }
        return buffer[pos++].code
    }

    /** Valid only directly after a successful [read]. */
    private fun unread() {
        if (pos > 0) pos--
    }

    private fun nextNonWhitespace(): Int {
        while (true) {
            val c = read()
            if (c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code || c == 0xFEFF) continue
            return c
        }
    }

    /** Reads a string body; the opening quote has already been consumed. */
    private fun readQuoted(): String {
        val sb = StringBuilder()
        while (true) {
            val c = read()
            when (c) {
                -1 -> throw IOException("Unterminated string")
                '"'.code -> return sb.toString()
                '\\'.code -> {
                    when (val e = read()) {
                        'n'.code -> sb.append('\n')
                        't'.code -> sb.append('\t')
                        'r'.code -> sb.append('\r')
                        'b'.code -> sb.append('\b')
                        'f'.code -> sb.append('\u000C')
                        'u'.code -> {
                            var v = 0
                            repeat(4) {
                                val h = read()
                                v = (v shl 4) or hexValue(h)
                            }
                            sb.append(v.toChar())
                        }
                        -1 -> throw IOException("Unterminated escape")
                        else -> sb.append(e.toChar()) // \" \\ \/ and anything else literally
                    }
                }
                else -> sb.append(c.toChar())
            }
        }
    }

    private fun readLiteral(): String {
        val sb = StringBuilder()
        while (true) {
            val c = read()
            if (c == -1) return sb.toString()
            if (c == ','.code || c == '}'.code || c == ']'.code || c == ':'.code ||
                c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code
            ) {
                unread()
                return sb.toString()
            }
            sb.append(c.toChar())
        }
    }

    private fun hexValue(c: Int): Int = when (c) {
        in '0'.code..'9'.code -> c - '0'.code
        in 'a'.code..'f'.code -> c - 'a'.code + 10
        in 'A'.code..'F'.code -> c - 'A'.code + 10
        else -> throw IOException("Bad \\u escape")
    }

    private companion object {
        const val EMPTY_ARRAY = 1
        const val NONEMPTY_ARRAY = 2
        const val EMPTY_OBJECT = 3
        const val DANGLING_NAME = 4
        const val NONEMPTY_OBJECT = 5
        const val EMPTY_DOCUMENT = 6
        const val NONEMPTY_DOCUMENT = 7
    }
}
