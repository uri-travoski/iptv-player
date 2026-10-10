package com.worldtv.iptvplayer.data.source

import com.worldtv.iptvplayer.data.model.Category
import com.worldtv.iptvplayer.data.model.ContentType
import com.worldtv.iptvplayer.data.model.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

class XtreamParserTest {

    @Test
    fun normalisesServerAndBuildsUrls() {
        val c = XtreamCredentials(" host.example:8080/player_api.php ", "us er", "p@ss")
        assertEquals("http://host.example:8080", c.baseUrl)
        val u = XtreamUrls(c)
        assertEquals(
            "http://host.example:8080/player_api.php?username=us+er&password=p%40ss&action=get_live_streams&category_id=5",
            u.api(XtreamAction.LIVE_STREAMS, "category_id" to "5"),
        )
        assertEquals("http://host.example:8080/live/us%20er/p%40ss/101.ts", u.live("101"))
        assertEquals("http://host.example:8080/movie/us%20er/p%40ss/9.mkv", u.movie("9", "mkv"))
    }

    @Test
    fun parsesAccount() {
        val a = XtreamParser.parseAccount(
            StringReader(
                """{"user_info":{"username":"a","auth":1,"status":"Active","exp_date":"1767225600","is_trial":"0",
                "active_cons":"0","max_connections":"2","allowed_output_formats":["m3u8","ts"]},
                "server_info":{"url":"h","port":"80","timezone":"Europe/London","timestamp_now":1}}""",
            ),
        )
        assertTrue(a.authenticated)
        assertEquals("Active", a.status)
        assertEquals(1767225600L, a.expiresAt)
        assertEquals(2, a.maxConnections)
        assertEquals(listOf("m3u8", "ts"), a.allowedFormats)
        assertEquals("Europe/London", a.serverTimezone)
        assertFalse(XtreamParser.parseAccount(StringReader("""{"user_info":{"auth":0}}""")).authenticated)
    }

    @Test
    fun parsesCategoriesLeniently() {
        val cats = ArrayList<Category>()
        XtreamParser.parseCategories(
            StringReader("""[{"category_id":"1","category_name":"UK"},{"category_id":2,"category_name":" Sports "},null,"x"]"""),
            ContentType.LIVE,
        ) { cats += it }
        assertEquals(2, cats.size)
        assertEquals("2", cats[1].id)
        assertEquals("Sports", cats[1].name)

        val none = ArrayList<Category>()
        XtreamParser.parseCategories(StringReader("""{"error":"x"}"""), ContentType.LIVE) { none += it }
        assertTrue(none.isEmpty())
    }

    @Test
    fun parsesLiveStreamsWithEscapesAndMissingFields() {
        val live = ArrayList<Entry>()
        XtreamParser.parseLiveStreams(
            StringReader(
                """[{"num":1,"name":"BBC One \"HD\" é","stream_id":101,"stream_icon":"http:\/\/l\/a.png",
                "epg_channel_id":"bbc1.uk","added":"1600000000","category_id":"1","category_ids":[1,2],
                "custom_sid":null,"tv_archive":1,"tv_archive_duration":"7"},
                {"name":"No id"},
                {"stream_id":"102","name":"Two","tv_archive":0,"tv_archive_duration":0,"stream_icon":""}]""",
            ),
        ) { live += it }
        assertEquals(2, live.size)
        assertEquals("BBC One \"HD\" é", live[0].name)
        assertEquals("http://l/a.png", live[0].logo)
        assertEquals(7, live[0].catchupDays)
        assertEquals(1600000000L, live[0].added)
        assertNull(live[1].logo)
        assertEquals(0, live[1].catchupDays)
        assertEquals(1, live[1].order)
    }

    @Test
    fun readsTheAdultFlag() {
        val live = ArrayList<Entry>()
        XtreamParser.parseLiveStreams(
            StringReader("""[{"stream_id":1,"name":"A","is_adult":"1"},{"stream_id":2,"name":"B","is_adult":0},{"stream_id":3,"name":"C"},{"stream_id":4,"name":"D","is_adult":1}]"""),
        ) { live += it }
        assertEquals(listOf(true, false, false, true), live.map { it.adult })
    }

    @Test
    fun parsesVodAndSeries() {
        val vod = ArrayList<Entry>()
        XtreamParser.parseVodStreams(
            StringReader("""[{"stream_id":555,"name":"Movie A","rating":"7.4","container_extension":"mkv","category_id":"9"}]"""),
        ) { vod += it }
        assertEquals("mkv", vod.single().containerExt)
        assertEquals(ContentType.MOVIE, vod.single().type)

        val series = ArrayList<Entry>()
        XtreamParser.parseSeries(
            StringReader("""[{"series_id":77,"name":"Show","cover":"http://c.jpg","plot":"Plot","last_modified":"1700000000","rating":"0"}]"""),
        ) { series += it }
        assertEquals("77", series.single().id)
        assertEquals("http://c.jpg", series.single().logo)
        assertNull(series.single().rating)
    }

    @Test
    fun decodesBase64EpgButKeepsPlainText() {
        val epg = ArrayList<EpgListing>()
        XtreamParser.parseEpg(
            StringReader(
                """{"epg_listings":[{"title":"TmV3cyBhdCBTaXg=","description":"VGhlIGxhdGVzdCBuZXdz",
                "start_timestamp":"1700000000","stop_timestamp":"1700001800"},
                {"title":"News","start_timestamp":"1700001800","stop_timestamp":"1700003600","has_archive":1}]}""",
            ),
        ) { epg += it }
        assertEquals(2, epg.size)
        assertEquals("News at Six", epg[0].title)
        assertEquals("The latest news", epg[0].description)
        assertEquals("News", epg[1].title)
        assertTrue(epg[1].hasArchive)
    }

    @Test
    fun parsesEpisodesInBothShapes() {
        val eps = ArrayList<Episode>()
        XtreamParser.parseSeriesEpisodes(
            StringReader(
                """{"seasons":[],"info":{"name":"Show"},"episodes":{
                "1":[{"id":"9001","episode_num":1,"title":"Pilot","container_extension":"mp4","info":{"plot":"P","duration_secs":3000},"season":1}],
                "2":[{"id":"9002","episode_num":"1","title":"S2E1","container_extension":"mkv","info":[]}]}}""",
            ),
        ) { eps += it }
        assertEquals(2, eps.size)
        assertEquals(3000, eps[0].durationSecs)
        assertEquals(2, eps[1].season)

        val eps2 = ArrayList<Episode>()
        XtreamParser.parseSeriesEpisodes(
            StringReader("""{"episodes":[[{"id":"1","season":3,"episode_num":4,"title":"x"}]]}"""),
        ) { eps2 += it }
        assertEquals(3, eps2.single().season)
        assertEquals(4, eps2.single().number)
    }

    @Test
    fun streamsFiftyThousandItems() {
        val sb = StringBuilder("[")
        for (i in 0 until 50_000) {
            if (i > 0) sb.append(',')
            sb.append("""{"stream_id":$i,"name":"Ch $i","category_id":"1","stream_icon":"http://x/$i.png"}""")
        }
        sb.append(']')
        var count = 0
        XtreamParser.parseLiveStreams(StringReader(sb.toString())) { count++ }
        assertEquals(50_000, count)
    }
}
