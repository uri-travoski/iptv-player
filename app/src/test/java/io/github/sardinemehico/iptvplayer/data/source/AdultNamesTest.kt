package io.github.sardinemehico.iptvplayer.data.source

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real names from provider catalogues (Oct 2026). */
class AdultNamesTest {

    @Test
    fun adultCategories() {
        listOf(
            "|+18| ✪ ADULTS", "XXX | ✪ FOR ADULTS Brazzers", "|+18| ✪ HANIME TV", "FOR Adults", "ULLU 18+",
            "ATRANGII 18+", "XXX | ✪ FOR FREE PORNIVEOS", "Philippines Movies (18+)",
        ).forEach { assertTrue(it, AdultNames.isAdultCategory(it)) }
    }

    @Test
    fun normalCategories() {
        listOf(
            "Adult Swim", "SPORTS | CRICKET", "|EN| ✪ COMEDY", "AFRICA | CARIBBEAN", "EUROPE | RUSSIA", "Essex & Sussex",
            "UK | ENTERTAINMENT", "Kids",
        ).forEach { assertFalse(it, AdultNames.isAdultCategory(it)) }
    }

    @Test
    fun adultEntriesInNormalCategories() {
        listOf(
            "Carib HustlerTV", "Carib PlayboyTV", "Carib Fab Anal", "Carib RedLight", "RU: Для Взрослых, Blue Hustler,Blue Hustler",
            "RU: Для Взрослых, Babes TV HD,Babes TV HD", "Brazzers TV Europe", "Horny MILF fucked hard", "XXX | Some Scene",
            "Busty Babe Gets Creampie",
        ).forEach { assertTrue(it, AdultNames.isAdultEntry(it)) }
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
        ).forEach { assertFalse(it, AdultNames.isAdultEntry(it)) }
    }
}
