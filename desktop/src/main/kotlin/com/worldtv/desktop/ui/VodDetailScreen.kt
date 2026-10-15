package com.worldtv.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.worldtv.desktop.Updates
import com.worldtv.desktop.graph
import com.worldtv.iptvplayer.data.model.ContentType
import com.worldtv.iptvplayer.data.online.CastPhotos
import com.worldtv.iptvplayer.data.online.SubQuery
import com.worldtv.iptvplayer.data.online.Tmdb
import com.worldtv.iptvplayer.data.repo.EntryDetails
import com.worldtv.iptvplayer.data.repo.Progress
import com.worldtv.iptvplayer.data.source.Episode
import com.worldtv.iptvplayer.data.source.Ratings
import com.worldtv.iptvplayer.data.source.VodInfo
import com.worldtv.iptvplayer.data.source.XtreamCredentials
import com.worldtv.iptvplayer.data.source.XtreamUrls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.net.URLEncoder

/**
 * A movie's or series' page: poster, details, cast, Play / Resume and Favourite. Xtream series
 * also get their seasons and episodes (get_series_info). M3U "series" entries are single files,
 * so they behave like movies.
 */
@Composable
fun VodDetailScreen(route: Route.Detail) {
    val nav = LocalNav.current
    val wt = LocalWt.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val playlist = route.playlist
    val type = route.type
    val row = route.row
    val urls = remember(playlist) { if (playlist.isXtream) XtreamUrls(XtreamCredentials(playlist.url, playlist.username.orEmpty(), playlist.password.orEmpty())) else null }
    val episodic = type == ContentType.SERIES && row.streamUrl == null && urls != null

    var details by remember { mutableStateOf<EntryDetails?>(null) }
    var info by remember { mutableStateOf<VodInfo?>(null) }
    var extras by remember { mutableStateOf<Tmdb.Extras?>(null) }
    var favourite by remember { mutableStateOf(row.favourite) }
    var progress by remember { mutableStateOf<Progress?>(null) }
    var episodes by remember { mutableStateOf<List<Episode>>(emptyList()) }
    var season by remember { mutableIntStateOf(-1) }
    var busy by remember { mutableStateOf(urls != null) }
    var message by remember { mutableStateOf<String?>(null) }
    var cast by remember { mutableStateOf<List<Pair<String, String?>>>(emptyList()) }

    LaunchedEffect(row.itemId) {
        details = graph.repo.details(playlist.id, type, row.itemId)
        favourite = graph.repo.isFavourite(playlist.id, type, row.itemId)
        progress = graph.repo.progress(playlist.id, type, row.itemId)
        if (urls != null) {
            try {
                if (episodic) {
                    val (i, eps) = graph.syncer.seriesInfo(playlist, row.itemId)
                    info = i
                    episodes = eps
                    val seasons = eps.map { it.season }.distinct()
                    val saved = eps.firstOrNull { it.id == progress?.episodeId }
                    season = saved?.season ?: seasons.firstOrNull() ?: -1
                } else {
                    info = graph.syncer.vodInfo(playlist, row.itemId)
                }
                info?.cast?.takeIf { it.isNotBlank() }?.let { graph.repo.saveCast(playlist.id, type, row.itemId, it) }
                val r = info?.rating
                if (r != null && details?.rating == null) graph.repo.saveRating(playlist.id, type, row.itemId, r)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (episodic) message = e.message ?: "Couldn't load the episodes"
            } finally {
                busy = false
            }
        }
        // TMDB (cast with photos, rating, trailer) when set up; else the provider's cast with Wikipedia photos.
        val i = info
        if (graph.tmdb.isSetUp) {
            val q = SubQuery.fromName(row.name, i?.released)
            extras = runCatching { graph.tmdb.extras(type == ContentType.SERIES, i?.tmdbId, q.title, q.year) }.getOrNull()
        }
        val ex = extras
        if (ex != null && ex.cast.isNotEmpty()) {
            cast = ex.cast.map { it.name to it.photo }
        } else {
            val names = CastPhotos.split(i?.cast)
            cast = names.map { it to null }
            if (names.isNotEmpty()) {
                val photos = runCatching { graph.castPhotos.lookup(names) }.getOrDefault(emptyMap())
                cast = names.map { it to photos[it] }
            }
        }
    }

    var watched by remember { mutableStateOf<Map<String, Progress>>(emptyMap()) }
    val saved by graph.progressSaved.collectAsState()
    LaunchedEffect(saved) {
        if (saved > 0) progress = graph.repo.progress(playlist.id, type, row.itemId)
        if (episodic) watched = graph.repo.episodeProgress(playlist.id, row.itemId)
    }

    val seasons = episodes.map { it.season }.distinct()
    val seasonEpisodes = episodes.filter { it.season == season }

    fun playEpisodes(list: List<Episode>, index: Int, startMs: Long) {
        val u = urls ?: return
        val items = list.map {
            val prefix = it.season.takeIf { s -> s > 0 }?.let { s -> "S$s " } ?: ""
            PlayItem(u.episode(it.id, it.containerExt ?: "mp4"), "${row.name} · ${prefix}E${it.number} ${it.title}", type, row.itemId, it.id, it.season, it.number)
        }
        nav.push(Route.Player(playlist.id, items, index, startMs))
    }

    fun play(resume: Boolean) {
        val p = progress
        if (episodic) {
            if (episodes.isEmpty()) return toaster.show(if (busy) "Loading…" else "No episodes")
            val saved = episodes.firstOrNull { it.id == p?.episodeId }
            if (saved != null) {
                season = saved.season
                val list = episodes.filter { it.season == saved.season }
                playEpisodes(list, list.indexOfFirst { it.id == saved.id }, if (resume) p!!.positionMs else 0)
            } else {
                playEpisodes(seasonEpisodes, 0, 0)
            }
            return
        }
        val url = row.streamUrl ?: urls?.movie(row.itemId, info?.containerExt ?: details?.ext ?: row.ext ?: "mp4") ?: return
        nav.push(Route.Player(playlist.id, listOf(PlayItem(url, row.name, type, row.itemId)), 0, if (resume) p?.positionMs ?: 0 else 0))
    }

    val resumeLabel = progress?.let { p ->
        if (episodic) {
            episodes.firstOrNull { it.id == p.episodeId }?.let { ep ->
                "Resume " + (if (ep.season > 0) "S${ep.season} " else "") + "E${ep.number} · " + Dates.clock(p.positionMs)
            }
        } else {
            "Resume " + Dates.clock(p.positionMs)
        }
    }

    ThemedBackground {
        Column(Modifier.fillMaxSize()) {
            TopBar("", { nav.back() })
            Row(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 8.dp)) {
                // Poster
                Column(Modifier.width(260.dp)) {
                    Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)).background(wt.surface)) {
                        Icon(Icons.Default.Movie, null, tint = wt.textSecondary.copy(alpha = 0.4f), modifier = Modifier.size(64.dp).align(Alignment.Center))
                        val img = row.logo ?: info?.image
                        if (img != null) AsyncImage(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
                Spacer(Modifier.width(32.dp))
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    val i = info
                    val d = details
                    val rating = Ratings.badge(i?.rating ?: d?.rating ?: extras?.rating)?.let { "★ $it" }
                    val year = i?.released?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }
                    Text(row.name, color = wt.textPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val meta = listOfNotNull(rating, year, i?.duration?.takeIf { it.isNotBlank() }, i?.genre?.takeIf { it.isNotBlank() }).joinToString("   ·   ")
                    if (meta.isNotEmpty()) Text(meta, color = wt.textSecondary, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        WtButton(resumeLabel ?: "Play", { play(resume = true) }, primary = true, icon = Icons.Default.PlayArrow)
                        if (resumeLabel != null) WtButton("From the start", { play(resume = false) }, icon = Icons.Default.Replay)
                        WtButton(if (favourite) "Favourite" else "Add to favourites", {
                            scope.launch {
                                favourite = graph.repo.toggleFavourite(playlist.id, type, row.itemId)
                                toaster.show(if (favourite) "Added to favourites" else "Removed from favourites")
                            }
                        }, icon = if (favourite) Icons.Default.Star else Icons.Default.StarBorder)
                        WtButton("Trailer", { openTrailer(i?.trailer ?: extras?.trailer, row.name, i?.released) }, icon = Icons.Default.Theaters)
                    }
                    Spacer(Modifier.height(16.dp))
                    val scroll = rememberScrollState()
                    Column(Modifier.weight(1f).fillMaxWidth().then(if (episodic) Modifier else Modifier.verticalScroll(scroll))) {
                        val plot = i?.plot ?: d?.plot
                        if (!plot.isNullOrBlank()) Text(plot, color = wt.textPrimary, fontSize = 15.sp, lineHeight = 22.sp, maxLines = if (episodic) 4 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
                        val people = listOfNotNull(
                            i?.director?.takeIf { it.isNotBlank() }?.let { "Director: $it" },
                            d?.added?.takeIf { it > 0 }?.let { "Added " + Dates.day(it * 1000) },
                        )
                        if (people.isNotEmpty()) Text(people.joinToString("\n"), color = wt.textSecondary, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
                        if (cast.isNotEmpty()) {
                            Spacer(Modifier.height(14.dp))
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                cast.forEach { (name, photo) -> CastItem(name, photo) }
                            }
                        }
                        if (episodic) {
                            Spacer(Modifier.height(14.dp))
                            if (busy) CircularProgressIndicator(color = wt.accent)
                            message?.let { Text(it, color = wt.error, fontSize = 14.sp) }
                            if (seasons.isNotEmpty()) {
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    seasons.forEach { s ->
                                        WtButton(if (s > 0) "Season $s" else "Episodes", { season = s }, primary = s == season)
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                                LazyColumn(Modifier.fillMaxSize()) {
                                    itemsIndexed(seasonEpisodes, key = { _, e -> e.id }) { idx, e ->
                                        EpisodeRow(e, watched[e.id] ?: progress?.takeIf { it.episodeId == e.id }) {
                                            val p = progress
                                            playEpisodes(seasonEpisodes, idx, if (p != null && p.episodeId == e.id) p.positionMs else 0)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CastItem(name: String, photo: String?) {
    val wt = LocalWt.current
    Column(Modifier.width(92.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(76.dp).clip(CircleShape).background(wt.surfaceActive), contentAlignment = Alignment.Center) {
            Text(name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }, color = wt.textPrimary, fontSize = 20.sp)
            if (photo != null) AsyncImage(photo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Text(name, color = wt.textSecondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun EpisodeRow(e: Episode, progress: Progress?, onClick: () -> Unit) {
    val wt = LocalWt.current
    ListRow(selected = false, current = progress != null, onClick = onClick, height = 84.dp) {
        Box(Modifier.size(width = 128.dp, height = 72.dp).clip(RoundedCornerShape(6.dp)).background(wt.surface), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.PlayArrow, null, tint = wt.textSecondary)
            if (e.image != null) AsyncImage(e.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                (if (progress?.finished == true) "✓  " else "") + "${e.number}. ${e.title}",
                color = if (progress?.finished == true) wt.textSecondary else wt.textPrimary,
                fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val line = listOfNotNull(e.durationSecs.takeIf { it > 0 }?.let { "${(it + 59) / 60} min" }, e.plot?.takeIf { it.isNotBlank() }).joinToString(" · ")
            if (line.isNotEmpty()) Text(line, color = wt.textSecondary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (progress != null && progress.durationMs > 0 && !progress.finished) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { (progress.positionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f) },
                    modifier = Modifier.width(200.dp).height(3.dp),
                    color = wt.accent,
                    trackColor = Color.White.copy(alpha = 0.2f),
                )
            }
        }
    }
}

/** The provider's YouTube trailer, else a YouTube search for "<title> <year> trailer", in the browser. */
private fun openTrailer(trailer: String?, title: String, released: String?) {
    val id = trailer?.trim()?.let { t ->
        Regex("(?:v=|youtu\\.be/|embed/|shorts/)([A-Za-z0-9_-]{11})").find(t)?.groupValues?.get(1) ?: t.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
    }
    graph.player.stop()
    if (id != null) {
        Updates.open("https://www.youtube.com/watch?v=$id")
    } else {
        val year = released?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }
        val q = SubQuery.fromName(title, year).let { listOfNotNull(it.title, it.year).joinToString(" ") }
        Updates.open("https://www.youtube.com/results?search_query=" + URLEncoder.encode("$q trailer", "UTF-8"))
    }
}
