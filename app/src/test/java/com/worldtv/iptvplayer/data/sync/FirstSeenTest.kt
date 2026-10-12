package com.worldtv.iptvplayer.data.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstSeenTest {

    @Test
    fun firstLoadDatesNothing() {
        assertFalse(FirstSeen.shouldStamp(known = 0, fresh = 5000))
    }

    @Test
    fun newTitlesOnAReloadAreDated() {
        assertTrue(FirstSeen.shouldStamp(known = 5000, fresh = 40))
        assertTrue(FirstSeen.shouldStamp(known = 100, fresh = 50))
    }

    @Test
    fun mostlyNewMeansChangedLinksNotNewTitles() {
        assertFalse(FirstSeen.shouldStamp(known = 5000, fresh = 4990))
        assertFalse(FirstSeen.shouldStamp(known = 100, fresh = 51))
    }

    @Test
    fun nothingNewNothingToDate() {
        assertFalse(FirstSeen.shouldStamp(known = 5000, fresh = 0))
    }
}
