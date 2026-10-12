package com.worldtv.iptvplayer.data.source

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleLanguageTest {

    private fun en(title: String, category: String?) = TitleLanguage.isEnglish(title, category)

    @Test
    fun titleCodeDecides() {
        assertTrue(en("EN - The Florist (2026)", "|EN| NEW RELEASED"))
        assertTrue(en("4K-EN - Dune (2021)", null))
        assertFalse(en("4K-IN - Toy Story 5 (2026)", "|EN| 4K MOVIES"))
        assertFalse(en("IN - Bigg Boss 20", null))
        assertFalse(en("AR| Some Film", "|EN| DRAMA"))
        assertFalse(en("PL 4K - Film", null))
        assertFalse(en("EX - Film", "|EXYU| STRANI FILMOVI 1950-2022"))
    }

    @Test
    fun neutralTitleCodeFallsBackToCategory() {
        assertTrue(en("NF - Some Show", "|EN| NETFLIX"))
        assertFalse(en("NF - Some Show", "|MULTI| NETFLIX SERIES"))
        assertFalse(en("A+ - Matchbox The Movie (2026) (MULTI SUB)", "|MULTI| APPLE+ SERIES"))
        assertTrue(en("Animals (2026)", "UK| FILMS"))
        assertTrue(en("Animals (2026)", "[US] Movies"))
    }

    @Test
    fun noCodesUseCategoryWords() {
        assertTrue(en("Animals (2026)", "ENGLISH MOVIES"))
        assertTrue(en("Animals (2026)", "Hollywood 2026"))
        assertFalse(en("Maremma (2026)", "BOLLYWOOD (2025-2026)"))
        assertFalse(en("Film (2026)", "TAMIL MOVIES"))
    }

    @Test
    fun unknownIsNotEnglish() {
        assertFalse(en("Maremma (2026)", "4K ULTRA HD MOVIES"))
        assertFalse(en("Maremma (2026)", null))
        assertFalse(en("Maremma (2026)", "NETFLIX MOVIES"))
    }

    @Test
    fun titlesThatMerelyStartWithCapitalsAreNotCodes() {
        // "WWE Friday Night" has no separator after WWE: not a code.
        assertFalse(en("WWE Friday Night SmackDown (9 October 2026)", null))
        assertTrue(en("WWE Friday Night SmackDown", "|EN| SPORTS EVENTS"))
        assertTrue(en("TRON: Ares (2025)", "|EN| NEW RELEASED"))
        assertTrue(en("WALL-E (2008)", "ENGLISH KIDS"))
    }
}
