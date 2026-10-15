package com.worldtv.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.worldtv.desktop.graph
import com.worldtv.iptvplayer.data.model.ContentType
import com.worldtv.iptvplayer.data.repo.CategoryRow
import com.worldtv.iptvplayer.data.repo.Playlist
import com.worldtv.iptvplayer.data.repo.Repository
import com.worldtv.iptvplayer.data.repo.Sort
import com.worldtv.iptvplayer.data.source.Ratings

/** Where Movies / Series were left (category, search, scroll), for coming back from a title's page. */
private object VodMemory {
    class State(val cat: String, val search: String, val first: Int, val offset: Int)
    val byType = HashMap<ContentType, State>()
}

private val SORT_LABELS = mapOf(
    Sort.NEWEST to "Newest first",
    Sort.RATING to "Top rated",
    Sort.NAME to "A–Z",
    Sort.NAME_DESC to "Z–A",
    Sort.PROVIDER to "Provider's order",
)

/** Movies or Series: categories on the left, posters on the right, as in IBO. */
@Composable
fun VodScreen(type: ContentType) {
    val nav = LocalNav.current
    val wt = LocalWt.current
    val scope = rememberCoroutineScope()
    val memory = remember { VodMemory.byType[type] }
    var playlist by remember { mutableStateOf<Playlist?>(null) }
    var categories by remember { mutableStateOf<List<CategoryRow>>(emptyList()) }
    var catKey by remember { mutableStateOf(memory?.cat ?: Repository.KEY_ALL) }
    val search = remember { mutableStateOf(memory?.search ?: "") }
    var listKey by remember { mutableStateOf(if (memory != null && memory.search.isNotBlank()) Repository.searchKey(memory.search) else catKey) }
    var sort by remember { mutableStateOf(graph.prefs.vodSort(type)) }
    var sortOpen by remember { mutableStateOf(false) }
    var rows by remember { mutableStateOf<PagedRows?>(null) }
    val synced by graph.synced.collectAsState()
    val grid = rememberLazyGridState(memory?.first ?: 0, memory?.offset ?: 0)
    val searchFocus = remember { FocusRequester() }
    var firstLoad by remember { mutableStateOf(true) }
    val title = if (type == ContentType.MOVIE) "Movies" else "Series"

    LaunchedEffect(synced) {
        val p = graph.activePlaylist() ?: return@LaunchedEffect
        playlist = p
        categories = graph.repo.categories(p.id, type)
        if (categories.none { it.key == catKey }) catKey = Repository.KEY_ALL
    }
    LaunchedEffect(search.value, catKey) {
        val q = search.value.trim()
        if (q.isNotEmpty() && !firstLoad) kotlinx.coroutines.delay(300)
        listKey = if (q.isNotEmpty()) Repository.searchKey(q) else catKey
    }
    LaunchedEffect(playlist, listKey, sort, synced) {
        val p = playlist ?: return@LaunchedEffect
        val key = listKey
        val s = sort
        val count = graph.repo.count(p.id, type, key)
        rows = PagedRows(scope, count) { off, lim -> graph.repo.page(p.id, type, key, off, lim, s) }
        if (!firstLoad) grid.scrollToItem(0)
        firstLoad = false
    }
    DisposableEffect(Unit) {
        onDispose {
            VodMemory.byType[type] = VodMemory.State(catKey, search.value, grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset)
        }
    }
    BackHandler {
        if (search.value.isNotEmpty()) {
            search.value = ""
            true
        } else {
            false
        }
    }

    ThemedBackground(
        Modifier.onPreviewKeyEvent { e ->
            if (e.type == KeyEventType.KeyDown && e.isCtrlPressed && e.key == Key.F) {
                runCatching { searchFocus.requestFocus() }
                true
            } else {
                false
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            TopBar(title, { nav.back() }, subtitle = playlist?.name) {
                SearchField(search, "Search ${title.lowercase()} (Ctrl+F)", Modifier.width(320.dp), searchFocus)
                Box {
                    WtButton(SORT_LABELS[sort] ?: "", { sortOpen = true }, icon = Icons.AutoMirrored.Filled.Sort)
                    DropdownMenu(sortOpen, onDismissRequest = { sortOpen = false }) {
                        listOf(Sort.NEWEST, Sort.RATING, Sort.NAME, Sort.NAME_DESC, Sort.PROVIDER).forEach { s ->
                            DropdownMenuItem(
                                text = { Text(SORT_LABELS[s] ?: "") },
                                leadingIcon = { if (s == sort) Icon(Icons.Default.Check, null) else Box(Modifier.size(24.dp)) },
                                onClick = {
                                    sortOpen = false
                                    sort = s
                                    graph.prefs.setVodSort(type, s)
                                },
                            )
                        }
                    }
                }
            }
            Row(Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
                LazyColumn(Modifier.width(250.dp).fillMaxHeight()) {
                    itemsIndexed(categories, key = { _, c -> c.key }) { _, c ->
                        ListRow(selected = c.key == catKey && search.value.isBlank(), onClick = {
                            search.value = ""
                            catKey = c.key
                        }) {
                            Text(c.name, color = wt.textPrimary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                val r = rows
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when {
                        r == null -> {}
                        r.count == 0 -> CenterNote(
                            when {
                                Repository.isSearch(listKey) -> "Nothing by that name"
                                listKey == Repository.KEY_FAV -> "No favourites yet"
                                listKey == Repository.KEY_CONTINUE -> "Nothing started yet"
                                else -> "Nothing here"
                            },
                            Modifier.fillMaxSize(),
                        )
                        else -> LazyVerticalGrid(
                            GridCells.Adaptive(165.dp),
                            state = grid,
                            contentPadding = PaddingValues(8.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(r.count) { i ->
                                val row = r[i]
                                PosterCard(row?.name ?: "", row?.logo, row?.rating, row?.favourite == true) {
                                    val p = playlist
                                    if (row != null && p != null) nav.push(Route.Detail(p, type, row))
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
fun PosterCard(name: String, image: String?, rating: String?, favourite: Boolean, onClick: () -> Unit) {
    val wt = LocalWt.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Column(
        Modifier.padding(8.dp).hoverable(source).clicks(source, onClick),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(wt.surface)
                .border(if (hovered) 3.dp else 0.dp, if (hovered) wt.accent else Color.Transparent, RoundedCornerShape(10.dp)),
        ) {
            Icon(Icons.Default.Movie, null, tint = wt.textSecondary.copy(alpha = 0.4f), modifier = Modifier.size(48.dp).align(Alignment.Center))
            if (image != null) AsyncImage(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Ratings.badge(rating)?.let { Badge("★ $it", Color(0xCC000000), Modifier.align(Alignment.TopEnd).padding(6.dp)) }
            if (favourite) Icon(Icons.Default.Star, "Favourite", tint = wt.accent, modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(20.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(name, color = if (hovered) wt.textPrimary else wt.textPrimary.copy(alpha = 0.9f), fontSize = 14.sp, fontWeight = if (hovered) FontWeight.Medium else FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(38.dp))
    }
}
