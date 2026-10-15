package com.worldtv.desktop

import android.content.Context
import com.sun.net.httpserver.HttpServer
import com.worldtv.iptvplayer.data.db.Db
import com.worldtv.iptvplayer.data.model.ContentType
import com.worldtv.iptvplayer.data.repo.Playlist
import com.worldtv.iptvplayer.data.repo.Repository
import com.worldtv.iptvplayer.data.source.AdultNames
import com.worldtv.iptvplayer.data.sync.Syncer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files

/** The Android data layer (Db, Repository, Syncer) on the desktop SQLite stand-ins. */
class DataLayerTest {

    private lateinit var dir: File
    private lateinit var server: HttpServer
    private lateinit var db: Db
    private lateinit var repo: Repository
    private lateinit var syncer: Syncer
    private var base = ""

    private val m3u = buildString {
        append("#EXTM3U url-tvg=\"http://example.invalid/epg.xml\"\n")
        for (i in 1..300) {
            val group = if (i % 3 == 0) "Sports" else "News"
            append("#EXTINF:-1 tvg-id=\"ch$i\" tvg-logo=\"http://example.invalid/$i.png\" group-title=\"$group\",Channel $i\n")
            append("http://example.invalid/live/$i.ts\n")
        }
        append("#EXTINF:-1 group-title=\"XXX Adult\",Hot channel\nhttp://example.invalid/live/x.ts\n")
    }

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("worldtv-test").toFile()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/list.m3u") { ex ->
            val body = m3u.toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
        db = Db(Context(dir))
        repo = Repository(db, Dispatchers.IO)
        syncer = Syncer(OkHttpClient(), db, repo, Dispatchers.IO, { AdultNames(AdultNames.DEFAULT_CATEGORY_WORDS, AdultNames.DEFAULT_ENTRY_WORDS) })
    }

    @After
    fun tearDown() {
        server.stop(0)
        db.close()
        dir.deleteRecursively()
    }

    @Test
    fun loadsAnM3uPlaylist(): Unit = runBlocking {
        val id = repo.addPlaylist("Test", Playlist.KIND_M3U, "$base/list.m3u", null, null)
        val p = repo.playlist(id)!!
        val lines = ArrayList<String>()
        syncer.sync(p) { lines += it }
        assertTrue(lines.isNotEmpty())

        val cats = repo.categories(id, ContentType.LIVE)
        val names = cats.map { it.name }
        assertTrue("News" in names)
        assertTrue("Sports" in names)
        assertFalse("adult category hidden", names.any { it.contains("Adult") })

        assertEquals(300, repo.count(id, ContentType.LIVE, Repository.KEY_ALL))
        assertEquals(100, repo.count(id, ContentType.LIVE, "Sports"))
        val page = repo.page(id, ContentType.LIVE, "News", 0, 10)
        assertEquals(10, page.size)
        assertEquals("Channel 1", page[0].name)

        // Search, favourites
        assertEquals(111, repo.count(id, ContentType.LIVE, Repository.searchKey("Channel 2"))) // 2, 20-29, 200-299
        val first = page[0].itemId
        assertTrue(repo.toggleFavourite(id, ContentType.LIVE, first))
        assertEquals(1, repo.count(id, ContentType.LIVE, Repository.KEY_FAV))
        assertTrue(repo.isFavourite(id, ContentType.LIVE, first))

        // A reload keeps favourites.
        syncer.sync(repo.playlist(id)!!) {}
        assertEquals(1, repo.count(id, ContentType.LIVE, Repository.KEY_FAV))
        assertTrue(repo.playlist(id)!!.lastSync > 0)
    }

    @Test
    fun readsGoOnWhileALoadWrites(): Unit = runBlocking {
        val id = repo.addPlaylist("Test", Playlist.KIND_M3U, "$base/list.m3u", null, null)
        syncer.sync(repo.playlist(id)!!) {}
        // Readers on other threads during reloads: always a whole library, never half of one.
        val jobs = (0 until 4).map {
            async(Dispatchers.IO) {
                repeat(20) { assertEquals(300, repo.count(id, ContentType.LIVE, Repository.KEY_ALL)) }
            }
        } + async(Dispatchers.IO) { repeat(3) { syncer.sync(repo.playlist(id)!!) {} } }
        jobs.awaitAll()
    }

    @Test
    fun schemaSurvivesReopen(): Unit = runBlocking {
        val id = repo.addPlaylist("Kept", Playlist.KIND_M3U, "$base/list.m3u", null, null)
        db.close()
        db = Db(Context(dir))
        repo = Repository(db, Dispatchers.IO)
        assertEquals("Kept", repo.playlist(id)!!.name)
    }
}
