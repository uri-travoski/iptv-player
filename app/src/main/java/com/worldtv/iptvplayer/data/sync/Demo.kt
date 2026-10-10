package com.worldtv.iptvplayer.data.sync

import com.worldtv.iptvplayer.data.model.Category
import com.worldtv.iptvplayer.data.model.ContentType
import com.worldtv.iptvplayer.data.model.Entry

/**
 * The sample playlist Reset app puts in: a few categories, channels, movies and series so a new
 * user sees what WorldTV looks like before adding a real playlist. Built here, no server: the
 * videos are public test streams (Mux, Apple, Google, Unified Streaming) and the posters are the
 * Blender Foundation's open films on Wikimedia Commons (CC BY). Some may stop working one day;
 * it is only a sample.
 */
object Demo {

    const val NAME = "Sample playlist (demo)"

    private const val BUNNY = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
    private const val BIPBOP = "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_4x3/bipbop_4x3_variant.m3u8"
    private const val LIVE = "https://demo.unified-streaming.com/k8s/live/stable/live.isml/.m3u8"
    private const val STEEL = "https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8"
    private const val STEEL2 = "https://test-streams.mux.dev/tos_ismc/main.m3u8"
    private const val PTS = "https://test-streams.mux.dev/pts_shift/master.m3u8"
    private const val ANGEL = "https://storage.googleapis.com/shaka-demo-assets/angel-one-hls/hls.m3u8"

    private const val THUMB = "https://thumb.wikimedia.org/wikipedia/commons/thumb"
    private const val P_BUNNY = "$THUMB/c/c5/Big_buck_bunny_poster_big.jpg/500px-Big_buck_bunny_poster_big.jpg"
    private const val P_STEEL = "$THUMB/7/70/Tos-poster.png/500px-Tos-poster.png"
    private const val P_DREAM = "$THUMB/0/0c/ElephantsDreamPoster.jpg/500px-ElephantsDreamPoster.jpg"
    private const val P_COSMOS = "$THUMB/c/c5/CosmosLaundromatPoster.jpg/500px-CosmosLaundromatPoster.jpg"
    private const val P_SPRING = "$THUMB/0/05/Spring2019PillarPosterBlender.jpg/500px-Spring2019PillarPosterBlender.jpg"
    private const val P_LLAMA = "$THUMB/6/61/Pablo_Vazquez_-_Caminandes_-_Episode_1_-_Llama_Drama_-_Cover_thumbnail.png/" +
        "500px-Pablo_Vazquez_-_Caminandes_-_Episode_1_-_Llama_Drama_-_Cover_thumbnail.png"

    /** Categories of each library, in order. */
    fun categories(): List<Category> = listOf(
        Category("news", "Sample | News", ContentType.LIVE),
        Category("sports", "Sample | Sports", ContentType.LIVE),
        Category("movies247", "Sample | Movies 24/7", ContentType.LIVE),
        Category("kids", "Sample | Kids", ContentType.LIVE),
        Category("films", "Sample | Open movies", ContentType.MOVIE),
        Category("shows", "Sample | Open series", ContentType.SERIES),
    )

    /** Every channel, movie and series. Days ago for "added", so Newest sorts sensibly. */
    fun entries(nowSecs: Long): List<Entry> {
        val out = ArrayList<Entry>()
        fun live(cat: String, name: String, url: String) {
            out += Entry(id = "live-${out.size}", type = ContentType.LIVE, name = name, categoryId = cat, logo = null, streamUrl = url, order = out.size)
        }
        live("news", "Sample News 24", LIVE)
        live("news", "Sample World News", BIPBOP)
        live("news", "Sample Business", PTS)
        live("sports", "Sample Sports 1", LIVE)
        live("sports", "Sample Sports 2", ANGEL)
        live("sports", "Sample Racing", BIPBOP)
        live("movies247", "Sample Cinema 24/7", STEEL)
        live("movies247", "Sample Classics", BUNNY)
        live("movies247", "Sample Action", STEEL2)
        live("kids", "Sample Kids", BUNNY)
        live("kids", "Sample Cartoons", ANGEL)
        live("kids", "Sample Family", PTS)

        fun vod(type: ContentType, cat: String, name: String, url: String, poster: String, rating: String, daysAgo: Int, plot: String) {
            out += Entry(
                id = "${type.name.lowercase()}-${out.size}", type = type, name = name, categoryId = cat, logo = poster,
                streamUrl = url, rating = rating, plot = plot, added = nowSecs - daysAgo * 86_400L, order = out.size,
            )
        }
        val m = ContentType.MOVIE
        vod(m, "films", "Big Buck Bunny (2008)", BUNNY, P_BUNNY, "7.4", 1, "A giant rabbit takes revenge on three bullying rodents. Blender Foundation open movie.")
        vod(m, "films", "Tears of Steel (2012)", STEEL, P_STEEL, "6.6", 2, "Warriors and scientists try to save the world from robots in a future Amsterdam.")
        vod(m, "films", "Elephants Dream (2006)", STEEL2, P_DREAM, "6.4", 3, "Two men explore a strange, ever-changing machine world.")
        vod(m, "films", "Cosmos Laundromat (2015)", ANGEL, P_COSMOS, "7.1", 4, "A suicidal sheep is offered a second chance by a mysterious salesman.")
        vod(m, "films", "Spring (2019)", BIPBOP, P_SPRING, "7.8", 5, "A shepherd girl and her dog face ancient spirits to bring back spring.")
        val s = ContentType.SERIES
        vod(s, "shows", "Caminandes (2013)", BUNNY, P_LLAMA, "7.2", 1, "A llama in Patagonia tries to cross a fence. Short series by the Blender Foundation.")
        vod(s, "shows", "Open Studio Stories", STEEL, P_STEEL, "6.9", 2, "Behind the scenes of open movie making.")
        vod(s, "shows", "Dream Machines", STEEL2, P_DREAM, "6.2", 3, "Episodes from a machine world.")
        vod(s, "shows", "Laundromat Tales", ANGEL, P_COSMOS, "7.0", 4, "Second chances, one episode at a time.")
        vod(s, "shows", "Seasons", BIPBOP, P_SPRING, "7.5", 5, "Stories of spring.")
        return out
    }
}
