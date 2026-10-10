package io.github.sardinemehico.iptvplayer.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TitleMatchTest {

    private val index = TitleMatch.Index(
        listOf(
            TitleMatch.Wanted("The Batman", "2022", "batman22"),
            TitleMatch.Wanted("Batman", "1989", "batman89"),
            TitleMatch.Wanted("Heat", "1995", "heat"),
            TitleMatch.Wanted("Inception", "2010", "inception"),
            TitleMatch.Wanted("Ocean's Eleven", "2001", "oceans"),
            TitleMatch.Wanted("Mission: Impossible", "1996", "mi"),
            TitleMatch.Wanted("Fast & Furious", "2009", "ff"),
            TitleMatch.Wanted("Peaky Blinders", "2013", "peaky"),
        ),
    )

    @Test fun providerPrefixesAndTags() {
        assertEquals("batman22", index.match("EN - The Batman (2022) 4K"))
        assertEquals("inception", index.match("|IN| Inception [2010]"))
        assertEquals("inception", index.match("4K-NF - Inception"))
        assertEquals("oceans", index.match("Oceans Eleven (2001)"))
        assertEquals("mi", index.match("EN: Mission Impossible 1996 FHD"))
        assertEquals("ff", index.match("Fast and Furious (2009)"))
        assertEquals("peaky", index.match("NF | Peaky Blinders"))
    }

    @Test fun yearFromTheProviderField() {
        assertEquals("heat", index.match("Heat", "1995"))
        assertNull(index.match("Heat", "2013"))
        assertEquals("batman89", index.match("Batman", "1990")) // ±1
    }

    @Test fun otherFilmsAreNotMatched() {
        assertNull(index.match("The LEGO Batman Movie (2017)"))
        assertNull(index.match("Batman Begins (2005)"))
        assertNull(index.match("Heat (2013)"))
        assertNull(index.match("Inception of Evil"))
        assertNull(index.match("Dead Heat"))
    }

    @Test fun separatedSuffixNeedsAgreeingYears() {
        assertEquals("inception", index.match("Inception (2010) - Hindi Dubbed"))
        assertNull(index.match("Inception - The Cobol Job"))
    }

    @Test fun year() {
        assertEquals("2014", TitleMatch.year("2014-05-01"))
        assertEquals("1999", TitleMatch.year("The Matrix (1999)"))
        assertNull(TitleMatch.year("12345"))
        assertNull(TitleMatch.year(null))
    }
}
