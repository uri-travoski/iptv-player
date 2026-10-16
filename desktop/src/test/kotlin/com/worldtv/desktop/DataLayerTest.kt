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
    fun checkedCategoriesComeFirstUntilOrderedByHand(): Unit = runBlocking {
        val id = repo.addPlaylist("Test", Playlist.KIND_M3U, "$base/list.m3u", null, null)
        syncer.sync(repo.playlist(id)!!) {}
        fun names() = runBlocking { repo.categoryOrder(id, ContentType.LIVE).map { it.name } }
        assertEquals(listOf("News", "Sports"), names())

        val sports = repo.categories(id, ContentType.LIVE).first { it.name == "Sports" }.key
        repo.setCheckCategories(id, listOf(sports))
        assertEquals(listOf("News", "Sports").reversed(), names())
        // Just under Favourites in the Live TV list.
        val all = repo.categories(id, ContentType.LIVE).map { it.name }
        assertEquals(all.indexOf("Favourites") + 1, all.indexOf("Sports"))

        // Put in order by hand: that order wins, checked or not.
        repo.moveCategory(id, ContentType.LIVE, 0, 1)
        assertEquals(listOf("News", "Sports"), names())
        repo.setCheckCategories(id, listOf(sports))
        assertEquals(listOf("News", "Sports"), names())

        // Default order: checked first again.
        repo.resetCategoryOrder(id, ContentType.LIVE)
        assertEquals(listOf("Sports", "News"), names())
    }

    @Test
    fun episodesKeepTheirOwnProgress(): Unit = runBlocking {
        val id = repo.addPlaylist("Test", Playlist.KIND_M3U, "$base/list.m3u", null, null)
        val names = AdultNames(AdultNames.DEFAULT_CATEGORY_WORDS, AdultNames.DEFAULT_ENTRY_WORDS)
        val s = ContentType.SERIES
        repo.saveProgress(id, s, "show", "e2", 40_000, 100_000, names, 1, 2)
        assertEquals("e2", repo.progress(id, s, "show")?.episodeId)

        // Finishing another episode ticks it off but keeps the series' resume point.
        repo.saveProgress(id, s, "show", "e1", 97_000, 100_000, names, 1, 1)
        var eps = repo.episodeProgress(id, "show")
        assertTrue(eps.getValue("e1").finished)
        assertFalse(eps.getValue("e2").finished)
        assertEquals("e2", repo.progress(id, s, "show")?.episodeId)

        // A few seconds' look doesn't count.
        repo.saveProgress(id, s, "show", "e3", 10_000, 100_000, names, 1, 3)
        eps = repo.episodeProgress(id, "show")
        assertFalse("e3" in eps)
        assertEquals("e2", repo.progress(id, s, "show")?.episodeId)

        // Finishing the resume episode clears the resume point.
        repo.saveProgress(id, s, "show", "e2", 99_000, 100_000, names, 1, 2)
        assertEquals(null, repo.progress(id, s, "show"))
        assertTrue(repo.episodeProgress(id, "show").getValue("e2").finished)
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
