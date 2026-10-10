package com.worldtv.iptvplayer.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

class UpdatesTest {

    @Test
    fun comparesVersionsNumerically() {
        assertTrue(Updates.isNewer("0.1.10", "0.1.9"))
        assertTrue(Updates.isNewer("0.2", "0.1.16"))
        assertTrue(Updates.isNewer("v1.0.0", "0.9.9"))
        assertFalse(Updates.isNewer("0.1.16", "0.1.16"))
        assertFalse(Updates.isNewer("0.1", "0.1.0"))
        assertFalse(Updates.isNewer("0.1.15", "0.1.16"))
        assertEquals(0, Updates.compare("0.1.16-beta", "0.1.16"))
    }

    @Test
    fun picksNewestReleaseWithApkAndSkipsDrafts() {
        val json = """[
          {"tag_name":"0.1.9","draft":false,"body":"old","assets":[{"name":"worldtv-0.1.9.apk","size":10,"browser_download_url":"https://x/9.apk"}]},
          {"tag_name":"0.1.20","draft":true,"assets":[{"name":"worldtv-0.1.20.apk","size":10,"browser_download_url":"https://x/20.apk"}]},
          {"tag_name":"0.1.16","draft":false,"body":"Changes","assets":[
             {"name":"notes.txt","size":1,"browser_download_url":"https://x/n.txt"},
             {"name":"worldtv-0.1.16.apk","size":4125890,"digest":"sha256:abc","browser_download_url":"https://x/16.apk"}]},
          {"tag_name":"0.1","draft":false,"prerelease":true,"assets":[]}
        ]"""
        val r = Updates.newest(StringReader(json))!!
        assertEquals("0.1.16", r.version)
        assertEquals("https://x/16.apk", r.apkUrl)
        assertEquals(4125890L, r.apkSize)
        assertEquals("sha256:abc", r.digest)
        assertEquals("Changes", r.notes)
    }

    @Test
    fun handlesErrorsAndEmptyLists() {
        assertNull(Updates.newest(StringReader("[]")))
        assertNull(Updates.newest(StringReader("""{"message":"API rate limit exceeded"}""")))
    }
}
