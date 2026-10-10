package io.github.sardinemehico.iptvplayer.ui

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import io.github.sardinemehico.iptvplayer.MainActivity
import io.github.sardinemehico.iptvplayer.R
import io.github.sardinemehico.iptvplayer.data.model.ContentType
import io.github.sardinemehico.iptvplayer.data.online.Tmdb
import io.github.sardinemehico.iptvplayer.data.repo.EntryRow
import io.github.sardinemehico.iptvplayer.data.repo.Playlist
import io.github.sardinemehico.iptvplayer.data.source.TitleMatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * An actor's movies and series that this playlist has (opened from a cast photo on a movie or
 * series page). With a TMDB key: their whole filmography, matched to the playlist by title and
 * year. Always: titles whose provider cast lists them (series lists carry cast; movies once
 * their page was opened). Movies first, newest first; then series.
 */
class ActorScreen(
    activity: MainActivity,
    private val playlist: Playlist,
    private val name: String,
    /** TMDB person id when the cast came from TMDB. */
    private val personId: Long?,
    private val photo: String?,
) : Screen(activity) {

    override val root: View = inflater.inflate(R.layout.screen_actor, null)

    private val status: TextView = root.findViewById(R.id.actor_status)
    private val busy: View = root.findViewById(R.id.busy)
    private val grid: RecyclerView = root.findViewById(R.id.grid)
    private val adapter = PagedEntryAdapter(scope, ::onPosterClicked, R.layout.row_poster)
    private val mobile = graph.prefs.isMobile
    private val columns = if (mobile) (activity.resources.configuration.screenWidthDp / 120).coerceAtLeast(2) else COLUMNS

    private var movies: List<EntryRow> = emptyList()
    private var series: List<EntryRow> = emptyList()
    private var loaded = false

    init {
        root.findViewById<TextView>(R.id.actor_name).text = name
        root.findViewById<TextView>(R.id.cast_initials).text =
            name.split(' ').filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().uppercase() }
        showPhoto(photo)
        if (mobile) {
            val side = (16 * activity.resources.displayMetrics.density).toInt()
            root.setPadding(side, root.paddingTop, side, root.paddingBottom)
        }
        grid.layoutManager = GridLayoutManager(activity, columns)
        grid.adapter = adapter
        grid.itemAnimator = null
        grid.setHasFixedSize(true)
        status.text = activity.getString(R.string.actor_looking, name)
    }

    override fun onShown() {
        activity.hideVideo()
        graph.player.stop()
        if (loaded) return
        loaded = true
        root.requestFocus()
        scope.launch { load() }
    }

    private fun showPhoto(url: String?) {
        if (url == null) return
        root.findViewById<ImageView>(R.id.cast_photo).load(url) {
            listener(onSuccess = { _, _ -> root.findViewById<View>(R.id.cast_initials).visibility = View.GONE })
        }
    }

    private suspend fun load() {
        busy.visibility = View.VISIBLE
        val t0 = System.currentTimeMillis()
        // TMDB's filmography, when the user has a key; else (or when TMDB fails) only the cast lists.
        var person: Tmdb.Person? = null
        var tmdbFailed = false
        if (graph.tmdb.isSetUp) {
            try {
                person = graph.tmdb.person(personId, name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.i("WorldTV.Online", "TMDB person: ${e.message}")
                tmdbFailed = true
            }
            if (photo == null) showPhoto(person?.photo)
        }
        val id = playlist.id
        movies = merge(
            person?.let { graph.repo.matchTitles(id, ContentType.MOVIE, index(it, series = false)) { y -> y } }.orEmpty(),
            graph.repo.byCast(id, ContentType.MOVIE, name),
        )
        series = merge(
            person?.let { graph.repo.matchTitles(id, ContentType.SERIES, index(it, series = true)) { y -> y } }.orEmpty(),
            graph.repo.byCast(id, ContentType.SERIES, name),
        )
        android.util.Log.i("WorldTV.Actor", "$name: ${person?.credits?.size ?: "no"} credits, ${movies.size} movies, ${series.size} series in ${System.currentTimeMillis() - t0}ms")
        busy.visibility = View.GONE
        val all = movies + series
        val found = if (all.isEmpty()) {
            activity.getString(R.string.actor_none, name)
        } else {
            listOfNotNull(
                movies.size.takeIf { it > 0 }?.let { activity.resources.getQuantityString(R.plurals.movies_n, it, it) },
                series.size.takeIf { it > 0 }?.let { activity.resources.getQuantityString(R.plurals.series_n, it, it) },
            ).joinToString(" · ") + " " + activity.getString(R.string.actor_on_playlist)
        }
        status.text = when {
            person != null -> found
            tmdbFailed -> found + "\n" + activity.getString(R.string.actor_tmdb_failed)
            else -> found + "\n" + activity.getString(R.string.actor_needs_tmdb)
        }
        adapter.reset(all.size) { offset, limit -> all.subList(offset.coerceAtMost(all.size), (offset + limit).coerceAtMost(all.size)) }
        if (all.isNotEmpty()) focusPoster(0)
    }

    /** The actor's films (or series) as titles to look for; each tagged with its year for ordering. */
    private fun index(p: Tmdb.Person, series: Boolean): TitleMatch.Index<Int> {
        val wanted = ArrayList<TitleMatch.Wanted<Int>>()
        for (c in p.credits) {
            if (c.series != series) continue
            val y = c.year?.toIntOrNull() ?: 0
            wanted += TitleMatch.Wanted(c.title, c.year, y)
            c.originalTitle?.let { wanted += TitleMatch.Wanted(it, c.year, y) }
        }
        return TitleMatch.Index(wanted)
    }

    /** TMDB matches first (already newest first), then cast-list finds not among them; each name once. */
    private fun merge(a: List<EntryRow>, b: List<EntryRow>): List<EntryRow> {
        val seen = HashSet<String>()
        return (a + b).filter { seen.add(it.itemId) && seen.add("\u0000" + it.name.trim().lowercase()) }
    }

    private fun focusPoster(index: Int) {
        var tries = 0
        fun attempt() {
            val v = grid.findViewHolderForAdapterPosition(index)?.itemView
            // The poster pages load a moment after the list is set: keep trying for about a second.
            if (v != null) v.requestFocus() else if (++tries < 12) grid.postDelayed({ attempt() }, 80)
        }
        grid.post { attempt() }
    }

    private fun onPosterClicked(index: Int, row: EntryRow) {
        val type = if (index < movies.size) ContentType.MOVIE else ContentType.SERIES
        activity.push(VodDetailScreen(activity, playlist, type, row) {})
    }

    private companion object {
        const val COLUMNS = 7
    }
}
