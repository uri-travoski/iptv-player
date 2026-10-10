package com.worldtv.iptvplayer.data.source

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real names from provider catalogues (Oct 2026). */
class AdultNamesTest {

    private val names = AdultNames.DEFAULT

    @Test
    fun adultCategories() {
        listOf(
            "|+18| ✪ ADULTS", "XXX | ✪ FOR ADULTS Brazzers", "|+18| ✪ HANIME TV", "FOR Adults", "ULLU 18+",
            "ATRANGII 18+", "XXX | ✪ FOR FREE PORNIVEOS", "Philippines Movies (18+)",
            "DASI MUJ", "XX | HINDI XX", "XX|HINDI XX",
        ).forEach { assertTrue(it, names.isAdultCategory(it)) }
    }

    @Test
    fun normalCategories() {
        listOf(
            "Adult Swim", "SPORTS | CRICKET", "|EN| ✪ COMEDY", "AFRICA | CARIBBEAN", "EUROPE | RUSSIA", "Essex & Sussex",
            "UK | ENTERTAINMENT", "Kids", "HINDI | MOVIES 24/7", "XX Factor",
        ).forEach { assertFalse(it, names.isAdultCategory(it)) }
    }

    @Test
    fun adultEntriesInNormalCategories() {
        listOf(
            "Carib HustlerTV", "Carib PlayboyTV", "Carib Fab Anal", "Carib RedLight", "RU: Для Взрослых, Blue Hustler,Blue Hustler",
            "RU: Для Взрослых, Babes TV HD,Babes TV HD", "Brazzers TV Europe", "Horny MILF fucked hard", "XXX | Some Scene",
            "Busty Babe Gets Creampie",
        ).forEach { assertTrue(it, names.isAdultEntry(it)) }
    }

    @Test
    fun mainstreamTitlesStayVisible() {
        listOf(
            "Sex Education (2019)", "EN - Sex Tape  (2014)", "NF - Money Shot: The Pornhub Story - 2023",
            "EN - Zack and Miri Make a Porno - 2008", "ES - xXx", "DE - xXx - Die Rückkehr des Xander Cage (2017)",
            "IT - XXx 2 The Next Level", "WWE NXT TakeOver XXX 2020.8.24", "Teenage Sex and Death at Camp Miasma (2026)",
            "Horny Teenagers Must Die! (2024)", "FR - Zero Fucks Given (2022)", "CA - Stepmom - 1998", "EN - The Hustler - 1961",
            "Evil Angel (2009)", "PK: Venus TV", "The Hentai Prince and the Stony Cat-DE", "EN - A Cock and Bull Story - 2005",
            "Sex, Uncut – L’amore e il sesso fuori copione-it", "Никто.не.знает.про.секс.2006.1080p", "Canal+ Sport",
            "Essex County News", "Analysis of the Game", "Adult Swim", "Pussy Riot: A Punk Prayer (2013)", "The Penthouse (2021) S02",
            "Attack of the 50 Foot Camgirl - 2022", "Hücum (2013)",
        ).forEach { assertFalse(it, names.isAdultEntry(it)) }
    }

    @Test
    fun adminWords() {
        val custom = AdultNames(listOf("vip 2"), listOf("red lips", "babes tv", "hotclub*"))
        assertTrue(custom.isAdultCategory("VIP 2"))
        assertTrue(custom.isAdultCategory("VIP2"))
        assertFalse(custom.isAdultCategory("VIP 22"))
        assertTrue(custom.isAdultEntry("RU: Red Lips"))
        assertTrue(custom.isAdultEntry("REDLIPS HD"))
        assertFalse(custom.isAdultEntry("Red Lipstick Show"))
        assertTrue(custom.isAdultEntry("BabesTV HD"))
        assertTrue(custom.isAdultEntry("HotClubXL"))
        assertFalse(custom.isAdultEntry("The Hot Club"))
        // A removed default word no longer hides on its own.
        assertFalse(AdultNames(emptyList(), emptyList()).isAdultEntry("Carib Fab Anal"))
        assertFalse(AdultNames(emptyList(), emptyList()).isAdultCategory("FOR Adults"))
    }

    /** The fast pre-check must never skip a name the full rules would hide. */
    @Test
    fun everyDefaultWordStillHides() {
        AdultNames.DEFAULT_ENTRY_WORDS.forEach { w ->
            val sample = "UK: " + w.removeSuffix("*").uppercase() + (if (w.endsWith("*")) "X" else "") + " HD"
            assertTrue(sample, names.isAdultEntry(sample))
        }
    }
}
