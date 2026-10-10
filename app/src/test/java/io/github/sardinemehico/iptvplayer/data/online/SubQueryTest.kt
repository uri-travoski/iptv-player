package io.github.sardinemehico.iptvplayer.data.online

import io.github.sardinemehico.iptvplayer.data.source.Ratings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Provider titles as they come (Oct 2026 catalogues). */
class SubQueryTest {

    @Test
    fun cleansProviderTitles() {
        SubQuery.fromName("EN - Sex Tape  (2014)").let { assertEquals("Sex Tape", it.title); assertEquals("2014", it.year) }
        SubQuery.fromName("Spider-Man: Brand New Day (2026) FHD").let { assertEquals("Spider-Man: Brand New Day", it.title); assertEquals("2026", it.year) }
        SubQuery.fromName("|EN| Avengers : Endgame (2019) 4K").let { assertEquals("Avengers : Endgame", it.title); assertEquals("2019", it.year) }
        SubQuery.fromName("NF - Money Shot: The Pornhub Story - 2023").let { assertEquals("Money Shot: The Pornhub Story", it.title); assertEquals("2023", it.year) }
        assertEquals("2010", SubQuery.fromName("Inception", year = "2010-07-16").year)
    }

    @Test
    fun ratings() {
        assertEquals("8.3", Ratings.badge("8.338"))
        assertEquals("7.0", Ratings.badge("7"))
        assertEquals("7.2", Ratings.badge("72"))
        assertNull(Ratings.badge("0"))
        assertNull(Ratings.badge(""))
        assertNull(Ratings.badge(null))
        assertEquals("8.0", Ratings.fromPanel(null, "4"))
        assertEquals("6.1", Ratings.fromPanel("6.1", "3"))
        assertNull(Ratings.fromPanel("0", "0"))
    }

    @Test
    fun castNames() {
        assertEquals(listOf("Tom Holland", "Zendaya", "Jacob Batalon"), CastPhotos.split("Tom Holland, Zendaya,  Jacob Batalon, "))
        assertEquals(2, CastPhotos.split("A B, C D, E F", max = 2).size)
    }
}
