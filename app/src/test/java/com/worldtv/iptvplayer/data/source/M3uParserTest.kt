package com.worldtv.iptvplayer.data.source

import com.worldtv.iptvplayer.data.model.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.StringReader

class M3uParserTest {

    private val sample = "﻿#EXTM3U url-tvg=\"http://epg.example/guide.xml.gz,http://b\"\n" +
        "#EXTINF:-1 tvg-id=\"bbc1.uk\" tvg-name=\"BBC One\" tvg-logo=\"http://l/bbc.png\" group-title=\"UK, News\" catchup=\"default\" catchup-days=\"7\",BBC One HD\n" +
        "http://host:8080/live/u/p/101.ts\n\n" +
        "#EXTINF:-1,Plain Channel\n#EXTGRP:Misc\n#EXTVLCOPT:http-user-agent=x\nhttp://a/b.m3u8\n" +
        "#EXTINF:-1 tvg-name=\"Movie A\" group-title=\"Films\",Movie A (2020)\nhttp://host:8080/movie/u/p/555.mkv\n" +
        "http://bare/url/stream.ts\n"

    private fun parse(): Pair<List<M3uEntry>, String?> {
        val entries = ArrayList<M3uEntry>()
        var epg: String? = null
        M3uParser.parse(StringReader(sample), onEpgUrl = { epg = it }) { entries += it }
        return entries to epg
    }

    @Test
    fun parsesAllEntriesAndHeaderEpg() {
        val (entries, epg) = parse()
        assertEquals(4, entries.size)
        assertEquals("http://epg.example/guide.xml.gz", epg)
    }

    @Test
    fun readsAttributesIncludingCommaInsideQuotes() {
        val e = parse().first[0]
        assertEquals("BBC One HD", e.name)
        assertEquals("UK, News", e.group)
        assertEquals("bbc1.uk", e.tvgId)
        assertEquals("http://l/bbc.png", e.logo)
        assertEquals(7, e.catchupDays)
        assertEquals(ContentType.LIVE, e.type)
    }

    @Test
    fun usesExtGrpAndIgnoresOtherDirectives() {
        val e = parse().first[1]
        assertEquals("Plain Channel", e.name)
        assertEquals("Misc", e.group)
        assertNull(e.logo)
    }

    @Test
    fun classifiesXtreamStyleUrls() {
        val entries = parse().first
        assertEquals(ContentType.MOVIE, entries[2].type)
        assertEquals(ContentType.SERIES, M3uParser.classify("http://h/series/u/p/1.mp4"))
    }

    @Test
    fun bareUrlGetsNameFromPath() {
        assertEquals("stream.ts", parse().first[3].name)
    }

    @Test
    fun detectsXtreamGeneratedM3uLink() {
        val c = XtreamCredentials.fromM3uUrl("http://h:80/get.php?username=a&password=b&type=m3u_plus&output=ts")
        assertNotNull(c)
        assertEquals("http://h:80", c!!.baseUrl)
        assertEquals("a", c.username)
        assertEquals("b", c.password)
        assertNull(XtreamCredentials.fromM3uUrl("http://h/playlist.m3u"))
    }
}
