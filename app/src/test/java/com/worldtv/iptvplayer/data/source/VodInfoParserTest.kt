package com.worldtv.iptvplayer.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.StringReader

class VodInfoParserTest {

    @Test
    fun parsesVodInfoLeniently() {
        val info = XtreamParser.parseVodInfo(
            StringReader(
                """{"info":{"movie_image":"http://i/p.jpg","plot":"A plot.","genre":"Drama","releasedate":"2020-01-02",
                "duration":"01:45:00","cast":"A, B","director":"D","rating":"7.2","backdrop_path":["x"]},
                "movie_data":{"stream_id":9,"container_extension":"mkv"}}""",
            ),
        )
        assertEquals("A plot.", info.plot)
        assertEquals("Drama", info.genre)
        assertEquals("2020-01-02", info.released)
        assertEquals("01:45:00", info.duration)
        assertEquals("7.2", info.rating)
        assertEquals("http://i/p.jpg", info.image)
        assertEquals("mkv", info.containerExt)
    }

    @Test
    fun emptyOrBrokenVodInfoGivesBlanks() {
        val info = XtreamParser.parseVodInfo(StringReader("""{"info":[],"movie_data":{}}"""))
        assertNull(info.plot)
        assertNull(info.containerExt)
        assertNull(XtreamParser.parseVodInfo(StringReader("[]")).plot)
    }

    @Test
    fun parsesSeriesInfoAndEpisodes() {
        val episodes = ArrayList<Episode>()
        val info = XtreamParser.parseSeriesInfo(
            StringReader(
                """{"seasons":[],"info":{"name":"S","cover":"http://c","plot":"P","rating":"0","episode_run_time":"45"},
                "episodes":{"1":[{"id":"11","episode_num":1,"title":"Pilot","container_extension":"mp4","info":{"duration_secs":2700}}],
                "2":[{"id":"21","episode_num":"1","title":"","container_extension":"mkv"}]}}""",
            ),
        ) { episodes += it }
        assertEquals("P", info.plot)
        assertEquals("http://c", info.image)
        assertNull(info.rating)
        assertEquals("45m", info.duration)
        assertEquals(listOf("11", "21"), episodes.map { it.id })
        assertEquals(listOf(1, 2), episodes.map { it.season })
        assertEquals("Episode 1", episodes[1].title)
    }
}
