package com.worldtv.iptvplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

class StreamErrorsTest {

    @Test
    fun swapsXtreamLiveFormat() {
        assertEquals("http://h:8080/live/u/p/66236.m3u8", StreamErrors.alternateFormat("http://h:8080/live/u/p/66236.ts"))
        assertEquals("http://h/live/u/p/1.ts", StreamErrors.alternateFormat("http://h/live/u/p/1.m3u8"))
        assertEquals("http://h/live/u/p/1.m3u8?token=x", StreamErrors.alternateFormat("http://h/live/u/p/1.ts?token=x"))
    }

    @Test
    fun leavesOtherUrlsAlone() {
        assertNull(StreamErrors.alternateFormat("http://h/movie/u/p/9.mkv"))
        assertNull(StreamErrors.alternateFormat("http://h/some/playlist/channel.ts"))
        assertNull(StreamErrors.alternateFormat("http://h/live/u/p/1.mp4"))
    }

    @Test
    fun recognisesDeadServerInCauseChain() {
        assertTrue(StreamErrors.isDeadServer(IOException(RuntimeException(UnknownHostException("ddown.example")))))
        assertFalse(StreamErrors.isDeadServer(IOException("reset")))
        assertFalse(StreamErrors.isDeadServer(null))
    }
}
