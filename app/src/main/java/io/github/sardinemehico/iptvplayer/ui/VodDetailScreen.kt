package io.github.sardinemehico.iptvplayer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import io.github.sardinemehico.iptvplayer.MainActivity
import io.github.sardinemehico.iptvplayer.R
import io.github.sardinemehico.iptvplayer.data.model.ContentType
import io.github.sardinemehico.iptvplayer.data.repo.EntryDetails
import io.github.sardinemehico.iptvplayer.data.repo.EntryRow
import io.github.sardinemehico.iptvplayer.data.repo.Playlist
import io.github.sardinemehico.iptvplayer.data.repo.Progress
import io.github.sardinemehico.iptvplayer.data.source.Episode
import io.github.sardinemehico.iptvplayer.data.source.VodInfo
import io.github.sardinemehico.iptvplayer.data.online.CastPhotos
import io.github.sardinemehico.iptvplayer.data.online.SubQuery
import io.github.sardinemehico.iptvplayer.data.source.XtreamCredentials
import io.github.sardinemehico.iptvplayer.data.source.XtreamUrls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * A movie's or series' page: poster, details, Play and Favourite. Xtream series also get a
 * season row and an episode list (from get_series_info). M3U "series" entries are single
 * episode files, so they behave like movies.
 *
 * [onFavourite] tells the grid when the favourite state changed here.
 */
class VodDetailScreen(
    activity: MainActivity,
    private val playlist: Playlist,
    private val type: ContentType,
    private val row: EntryRow,
    private val onFavourite: (Boolean) -> Unit,
) : Screen(activity) {

    override val root: View = inflater.inflate(if (graph.prefs.isMobile) R.layout.screen_vod_detail_mobile else R.layout.screen_vod_detail, null)

    private val poster: ImageView = root.findViewById(R.id.poster)
    private val meta: TextView = root.findViewById(R.id.meta)
    private val plot: TextView = root.findViewById(R.id.plot)
    private val people: TextView = root.findViewById(R.id.people)
    private val play: TextView = root.findViewById(R.id.play)
    private val favourite: TextView = root.findViewById(R.id.favourite)
    private val fromStart: TextView = root.findViewById(R.id.from_start)
    private val busy: View = root.findViewById(R.id.busy)
    private val message: TextView = root.findViewById(R.id.message)
    private val seasonsScroll: View = root.findViewById(R.id.seasons_scroll)
    private val seasonsRow: LinearLayout = root.findViewById(R.id.seasons)
    private val episodesView: RecyclerView = root.findViewById(R.id.episodes)
    private val trailer: TextView = root.findViewById(R.id.trailer)
    private val castScroll: View = root.findViewById(R.id.cast_scroll)
    private val castRow: LinearLayout = root.findViewById(R.id.cast_row)
    /** TV series: cast under the poster (TV layout only). */
    private val castSide: LinearLayout? = root.findViewById(R.id.cast_side)

    private val urls = if (playlist.isXtream) {
        XtreamUrls(XtreamCredentials(playlist.url, playlist.username.orEmpty(), playlist.password.orEmpty()))
    } else {
        null
    }

    /** Xtream series have episodes to fetch; everything else is one playable file. */
    private val isEpisodic = type == ContentType.SERIES && row.streamUrl == null && urls != null

    private var details: EntryDetails? = null
    /** TMDB's cast, rating and trailer (the user's own keys), when set up and found. */
    private var extras: io.github.sardinemehico.iptvplayer.data.online.Tmdb.Extras? = null
    private var info: VodInfo? = null
    private var isFav = row.favourite
    private var episodes: List<Episode> = emptyList()
    private var seasons: List<Int> = emptyList()
    private var season = -1
    private val episodeAdapter = EpisodeAdapter(::playEpisode)
    private var loaded = false
    /** Saved position, for Resume. */
    private var progress: Progress? = null

    init {
        root.findViewById<TextView>(R.id.title).text = row.name
        poster.load(row.logo)
        play.setOnClickListener { onPlay(resume = true) }
        fromStart.setOnClickListener { onPlay(resume = false) }
        favourite.setOnClickListener { toggleFavourite() }
        trailer.setOnClickListener { Trailers.open(activity, info?.trailer ?: extras?.trailer, row.name, info?.released) }
        episodesView.layoutManager = LinearLayoutManager(activity)
        episodesView.adapter = episodeAdapter
        episodesView.itemAnimator = null
        updateFavLabel()
    }

    override fun onShown() {
        activity.hideVideo()
        graph.player.stop()
        if (!loaded) {
            loaded = true
            play.requestFocus()
            scope.launch { load() }
        } else {
            // Back from the player: the saved position moved (or was cleared).
            scope.launch { loadProgress() }
        }
    }

    private suspend fun loadProgress() {
        progress = graph.repo.progress(playlist.id, type, row.itemId)
        updatePlayLabel()
    }

    /** "Play", "Resume 12:34", or for series "Resume S2 E5 · 12:34". */
    private fun updatePlayLabel() {
        val p = progress
        if (p == null) {
            play.setText(R.string.play)
            fromStart.visibility = View.GONE
            return
        }
        val at = PlayerUi.time(p.positionMs)
        if (isEpisodic) {
            val ep = episodes.firstOrNull { it.id == p.episodeId }
            if (ep == null) {
                play.setText(R.string.play)
                fromStart.visibility = View.GONE
                return
            }
            val label = (if (ep.season > 0) "S${ep.season} " else "") + "E${ep.number}"
            play.text = activity.getString(R.string.resume_episode, label, at)
        } else {
            play.text = activity.getString(R.string.resume_at, at)
        }
        fromStart.visibility = View.VISIBLE
    }

    private suspend fun load() {
        details = graph.repo.details(playlist.id, type, row.itemId)
        isFav = graph.repo.isFavourite(playlist.id, type, row.itemId)
        updateFavLabel()
        loadProgress()
        showInfo()
        if (urls == null) return enrich() // M3U: nothing more to fetch from the provider.

        busy.visibility = View.VISIBLE
        try {
            if (isEpisodic) {
                val (i, eps) = graph.syncer.seriesInfo(playlist, row.itemId)
                info = i
                episodes = eps
                showSeasons()
                updatePlayLabel()
            } else {
                info = graph.syncer.vodInfo(playlist, row.itemId)
            }
            showInfo()
            // A rating the list came without: keep it, so the poster shows it from now on.
            val r = info?.rating
            if (r != null && details?.rating == null) graph.repo.saveRating(playlist.id, type, row.itemId, r)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Details are a nice-to-have for movies; for series the episodes are needed.
            if (isEpisodic) message.text = e.message ?: activity.getString(R.string.no_episodes)
        } finally {
            busy.visibility = View.GONE
        }
        enrich()
    }

    /**
     * TMDB (when the user has set up a key): cast with photos, and the rating and trailer the
     * provider lacks; its id also finds subtitles better. Without TMDB, or when it fails or
     * doesn't know the title: the provider's cast names with Wikipedia photos.
     */
    private suspend fun enrich() {
        val i = info
        if (graph.tmdb.isSetUp) {
            val q = SubQuery.fromName(row.name, i?.released)
            extras = try {
                graph.tmdb.extras(type == ContentType.SERIES, i?.tmdbId, q.title, q.year)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.i("WorldTV.Online", "TMDB: ${e.message}; using the provider's details")
                null
            }
        }
        val ex = extras
        val tmdbRating = ex?.rating
        if (tmdbRating != null && i?.rating == null && details?.rating == null) {
            info = (i ?: VodInfo()).copy(rating = tmdbRating)
            showInfo()
            graph.repo.saveRating(playlist.id, type, row.itemId, tmdbRating)
        }
        if (ex != null && ex.cast.isNotEmpty()) {
            showCast(ex.cast.map { it.name }, ex.cast.associate { it.name to it.photo })
        } else {
            showCast(CastPhotos.split(i?.cast), null)
        }
    }

    private fun showInfo() {
        val i = info
        val d = details
        val rating = io.github.sardinemehico.iptvplayer.data.source.Ratings.badge(i?.rating ?: d?.rating)?.let { "★ $it" }
        val year = i?.released?.take(4)?.takeIf { it.all(Char::isDigit) }
        meta.text = listOfNotNull(rating, year, i?.duration, i?.genre).joinToString("   ·   ")
        plot.text = i?.plot ?: d?.plot ?: ""
        // The cast has its own row of photos (showCast).
        people.text = listOfNotNull(
            i?.director?.let { "Director: $it" },
            d?.added?.takeIf { it > 0 }?.let { activity.getString(R.string.date_added, Dates.day(it * 1000)) },
        ).joinToString("\n")
        if (row.logo == null && i?.image != null) poster.load(i.image)
    }

    // ---- cast ----

    /**
     * The cast as photos with names (from TMDB with the cast, else Wikipedia, looked up once per page;
     * a name with no photo shows its initials). Movies: a row under the buttons. TV series: 3 across under the
     * poster, so the episode list keeps its room.
     */
    private fun showCast(all: List<String>, known: Map<String, String?>?) {
        val names = all.take(if (isEpisodic && castSide != null) 6 else 12)
        if (names.isEmpty()) return
        val side = if (isEpisodic) castSide else null
        val views = names.associateWith { castItem(it, small = side != null) }
        if (side != null) {
            side.removeAllViews()
            names.chunked(3).forEach { chunk ->
                val r = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                chunk.forEach { r.addView(views.getValue(it)) }
                side.addView(r)
            }
            side.visibility = View.VISIBLE
        } else {
            castRow.removeAllViews()
            names.forEach { castRow.addView(views.getValue(it)) }
            castScroll.visibility = View.VISIBLE
        }
        scope.launch {
            // TMDB's photos when it gave the cast; else Wikipedia's.
            val photos = known ?: graph.castPhotos.lookup(names)
            for ((name, url) in photos) {
                if (url == null) continue
                val v = views[name] ?: continue
                v.findViewById<ImageView>(R.id.cast_photo).load(url) {
                    listener(onSuccess = { _, _ -> v.findViewById<View>(R.id.cast_initials).visibility = View.GONE })
                }
            }
        }
    }

    private fun castItem(name: String, small: Boolean): View {
        val v = LayoutInflater.from(activity).inflate(if (small) R.layout.row_cast_small else R.layout.row_cast, castRow, false)
        v.findViewById<TextView>(R.id.cast_name).text = name
        v.findViewById<TextView>(R.id.cast_initials).text =
            name.split(' ').filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().uppercase() }
        return v
    }

    // ---- series ----

    private fun showSeasons() {
        if (episodes.isEmpty()) {
            message.setText(R.string.no_episodes)
            return
        }
        seasons = episodes.map { it.season }.distinct()
        seasonsRow.removeAllViews()
        val li = LayoutInflater.from(activity)
        seasons.forEachIndexed { i, number ->
            val v = li.inflate(R.layout.row_season, seasonsRow, false) as TextView
            v.text = if (number > 0) activity.getString(R.string.season_n, number) else activity.getString(R.string.series)
            // Moving onto a season shows its episodes; seasons are few, so no debounce needed.
            v.setOnFocusChangeListener { _, has -> if (has) selectSeason(i) }
            v.setOnClickListener { selectSeason(i); focusFirstEpisode() }
            seasonsRow.addView(v)
        }
        seasonsScroll.visibility = View.VISIBLE
        episodesView.visibility = View.VISIBLE
        // Open on the season being watched, if any.
        val watching = episodes.firstOrNull { it.id == progress?.episodeId }?.season
        selectSeason(seasons.indexOf(watching).coerceAtLeast(0))
    }

    private fun selectSeason(i: Int) {
        if (i == season) return
        season = i
        for (k in 0 until seasonsRow.childCount) seasonsRow.getChildAt(k).isActivated = k == i
        episodeAdapter.items = seasonEpisodes()
        episodesView.scrollToPosition(0)
    }

    private fun seasonEpisodes(): List<Episode> {
        val number = seasons.getOrNull(season) ?: return emptyList()
        return episodes.filter { it.season == number }
    }

    private fun focusFirstEpisode() {
        episodesView.post { episodesView.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
    }

    /** Plays one episode of the shown season, queueing the rest. The saved episode resumes. */
    private fun playEpisode(index: Int, resume: Boolean = true) {
        val u = urls ?: return
        val list = seasonEpisodes()
        val prefix = seasons.getOrNull(season)?.takeIf { it > 0 }?.let { "S$it " } ?: ""
        val items = list.map {
            PlayItem(
                url = u.episode(it.id, it.containerExt ?: "mp4"),
                title = "${row.name} · ${prefix}E${it.number} ${it.title}",
                type = type,
                itemId = row.itemId,
                episodeId = it.id,
                subs = SubQuery.fromName(row.name, info?.released, info?.tmdbId ?: extras?.tmdbId, info?.imdbId, season = it.season.takeIf { s -> s > 0 } ?: 1, episode = it.number),
            )
        }
        val p = progress
        val startMs = if (resume && p != null && list.getOrNull(index)?.id == p.episodeId) p.positionMs else 0
        activity.push(PlayerScreen(activity, items, index, startMs))
    }

    // ---- actions ----

    private fun onPlay(resume: Boolean) {
        val p = progress
        if (isEpisodic) {
            if (episodes.isEmpty()) return activity.toast(activity.getString(R.string.loading))
            // Resume goes to the saved episode (switching season if needed); else the season's first.
            val saved = episodes.firstOrNull { it.id == p?.episodeId }
            if (saved != null) {
                selectSeason(seasons.indexOf(saved.season))
                playEpisode(seasonEpisodes().indexOfFirst { it.id == saved.id }, resume)
            } else {
                playEpisode(0)
            }
            return
        }
        val url = row.streamUrl
            ?: urls?.movie(row.itemId, info?.containerExt ?: details?.ext ?: row.ext ?: "mp4")
            ?: return
        val item = PlayItem(url, row.name, type, row.itemId, subs = SubQuery.fromName(row.name, info?.released, info?.tmdbId ?: extras?.tmdbId, info?.imdbId))
        activity.push(PlayerScreen(activity, listOf(item), 0, if (resume) p?.positionMs ?: 0 else 0))
    }

    private fun toggleFavourite() {
        scope.launch {
            isFav = graph.repo.toggleFavourite(playlist.id, type, row.itemId)
            updateFavLabel()
            onFavourite(isFav)
            activity.toast(activity.getString(if (isFav) R.string.favourite_added else R.string.favourite_removed))
        }
    }

    private fun updateFavLabel() {
        favourite.setText(if (isFav) R.string.vod_fav_remove else R.string.vod_fav_add)
    }

    /** Season's episodes: few enough to hold in memory. */
    private class EpisodeAdapter(private val onClick: (Int) -> Unit) : RecyclerView.Adapter<EpisodeAdapter.VH>() {

        var items: List<Episode> = emptyList()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        init {
            setHasStableIds(true)
        }

        override fun getItemCount() = items.size
        override fun getItemId(position: Int) = position.toLong()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.row_category, parent, false) as TextView)

        override fun onBindViewHolder(holder: VH, position: Int) {
            val e = items[position]
            val mins = e.durationSecs / 60
            holder.text.text = buildString {
                append("E").append(e.number).append("   ").append(e.title)
                if (mins > 0) append("   ·   ").append(mins).append(" min")
            }
        }

        inner class VH(val text: TextView) : RecyclerView.ViewHolder(text) {
            init {
                text.setOnClickListener {
                    if (bindingAdapterPosition != RecyclerView.NO_POSITION) onClick(bindingAdapterPosition)
                }
            }
        }
    }
}
