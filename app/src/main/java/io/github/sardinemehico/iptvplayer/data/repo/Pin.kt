package io.github.sardinemehico.iptvplayer.data.repo

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 6-digit playlist PINs. Only a salted hash is stored, so the PIN itself can't be read back
 * from the database. This guards the playlist details in the UI; it is not encryption.
 */
object Pin {

    const val LENGTH = 6

    /** Every playlist has a PIN; this is the one it starts with (and goes back to when removed). */
    const val DEFAULT = "000000"

    fun isValid(pin: String) = pin.length == LENGTH && pin.all { it in '0'..'9' }

    /** "salt:hash", both hex. */
    fun hash(pin: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return salt.hex() + ":" + digest(salt, pin).hex()
    }

    fun matches(pin: String, stored: String): Boolean {
        val parts = stored.split(':')
        if (parts.size != 2) return false
        val salt = parts[0].unhex() ?: return false
        val expected = parts[1].unhex() ?: return false
        return MessageDigest.isEqual(digest(salt, pin), expected)
    }

    private fun digest(salt: ByteArray, pin: String): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        return md.digest(pin.toByteArray(Charsets.UTF_8))
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private fun String.unhex(): ByteArray? {
        if (length % 2 != 0) return null
        return try {
            ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } catch (e: NumberFormatException) {
            null
        }
    }
}
