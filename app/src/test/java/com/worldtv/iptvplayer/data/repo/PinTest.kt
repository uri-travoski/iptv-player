package com.worldtv.iptvplayer.data.repo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinTest {

    @Test
    fun validatesSixDigits() {
        assertTrue(Pin.isValid("012345"))
        assertFalse(Pin.isValid("12345"))
        assertFalse(Pin.isValid("1234567"))
        assertFalse(Pin.isValid("12a456"))
        assertFalse(Pin.isValid(""))
    }

    @Test
    fun hashMatchesOnlyTheSamePin() {
        val stored = Pin.hash("482913")
        assertTrue(Pin.matches("482913", stored))
        assertFalse(Pin.matches("482914", stored))
        assertFalse(stored.contains("482913"))
    }

    @Test
    fun hashesAreSalted() {
        assertNotEquals(Pin.hash("000000"), Pin.hash("000000"))
    }

    @Test
    fun malformedStoredValueNeverMatches() {
        assertFalse(Pin.matches("123456", "garbage"))
        assertFalse(Pin.matches("123456", "zz:yy"))
    }
}
