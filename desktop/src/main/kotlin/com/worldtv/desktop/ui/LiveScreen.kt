package com.worldtv.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.worldtv.desktop.check.DesktopChannelCheck
import com.worldtv.desktop.graph
import com.worldtv.iptvplayer.data.model.ContentType
import com.worldtv.iptvplayer.data.repo.CategoryRow
import com.worldtv.iptvplayer.data.repo.EntryRow
import com.worldtv.iptvplayer.data.repo.Group
import com.worldtv.iptvplayer.data.repo.Playlist
import com.worldtv.iptvplayer.data.repo.Repository
import com.worldtv.iptvplayer.data.source.EpgListing
import com.worldtv.iptvplayer.data.source.Guide
import com.worldtv.iptvplayer.data.source.XtreamCredentials
import com.worldtv.iptvplayer.data.source.XtreamUrls
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where a channel was picked from: its list and place in it (zapping stays in that list). */
private class Picked(val row: EntryRow, val key: String, val index: Int, val rows: PagedRows)

/**
 * Live TV: categories · channels · preview, like the Android live screen. A click plays a channel
 * in the preview; a double click (or Enter on the playing one, or F) goes full screen. Full
 * screen: Up/Down (or Page Up/Down) zap, Esc steps back. 0 / Backspace flips to the channel
 * watched before. Xtream playlists show now / next under the picture and catch-up where the
 * panel records. Right-click a channel for favourites, groups and catch-up.
 */
@Composable
fun LiveScreen() {
    val nav = LocalNav.current
    val wt = LocalWt.current
    val toaster = LocalToaster.current
    val window = LocalWindowControls.current
    val scope = rememberCoroutineScope()
    val player = graph.player
    val prefs = graph.prefs

    var playlist by remember { mutableStateOf<Playlist?>(null) }
    var categories by remember { mutableStateOf<List<CategoryRow>>(emptyList()) }
    var catKey by remember { mutableStateOf(prefs.lastLiveCategory ?: Repository.KEY_ALL) }
    val search = remember { mutableStateOf("") }
    var listKey by remember { mutableStateOf(catKey) }
    var rows by remember { mutableStateOf<PagedRows?>(null) }
    var cursor by remember { mutableIntStateOf(0) }
    var catCursor by remember { mutableIntStateOf(0) }
    var paneCats by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<Picked?>(null) }
    var previous by remember { mutableStateOf<Picked?>(null) }
    var fullscreen by remember { mutableStateOf(false) }
    var aspect by remember { mutableIntStateOf(prefs.aspect) }
    var checks by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var groups by remember { mutableStateOf<List<Group>>(emptyList()) }
    var guide by remember { mutableStateOf<List<EpgListing>>(emptyList()) }
    val guideCache = remember { mutableStateMapOf<String, Pair<Long, List<EpgListing>>>() }
    var menuFor by remember { mutableStateOf<Pair<Int, EntryRow>?>(null) }
    /** Where the right click was, across the row: the menu opens there. */
    var menuX by remember { mutableStateOf(0.dp) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    var catMenu by remember { mutableStateOf<CategoryRow?>(null) }
    var dialog by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    var reloadList by remember { mutableIntStateOf(0) }
    val synced by graph.synced.collectAsState()
    val checkUpdates by graph.channelCheck.updates.collectAsState()
    val overlay = remember { OverlayTimer() }
    val listState = rememberLazyListState()
    val catState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    var restored by remember { mutableStateOf(false) }
    /** Typing in the search box: letter shortcuts (M, A, S, F…) are off. */
    var typing by remember { mutableStateOf(false) }
    /** Filter above the categories (their names). */
    val catSearch = remember { mutableStateOf("") }
    val shownCats = catSearch.value.trim().let { q -> if (q.isEmpty()) categories else categories.filter { it.name.contains(q, ignoreCase = true) } }

    val urls = playlist?.takeIf { it.isXtream }?.let { XtreamUrls(XtreamCredentials(it.url, it.username.orEmpty(), it.password.orEmpty())) }
    val liveExt = playlist?.formats?.let { if (it.isEmpty() || "ts" in it) "ts" else "m3u8" } ?: "ts"

    // Playlist and categories (again after a reload).
    LaunchedEffect(synced) {
        val p = graph.activePlaylist() ?: return@LaunchedEffect
        playlist = p
        categories = graph.repo.categories(p.id, ContentType.LIVE)
        groups = graph.repo.groups(p.id)
        if (categories.none { it.key == catKey }) catKey = Repository.KEY_ALL
        catCursor = categories.indexOfFirst { it.key == catKey }.coerceAtLeast(0)
        reloadList++
    }

    // The list on screen: a category, or the search (a short pause after typing).
    LaunchedEffect(search.value, catKey) {
        val q = search.value.trim()
        if (q.isNotEmpty()) delay(300)
        listKey = if (q.isNotEmpty()) Repository.searchKey(q) else catKey
    }

    LaunchedEffect(listKey, reloadList) {
        val p = playlist ?: return@LaunchedEffect
        val key = listKey
        val count = graph.repo.count(p.id, ContentType.LIVE, key)
        val r = PagedRows(scope, count) { off, lim -> graph.repo.page(p.id, ContentType.LIVE, key, off, lim, prefs.liveSort) }
        rows = r
        // The playing channel's place in this list, else the top.
        val playingHere = picked?.let { pk -> runCatching { graph.repo.indexOf(p.id, ContentType.LIVE, key, pk.row.itemId, prefs.liveSort) }.getOrDefault(-1) } ?: -1
        cursor = playingHere.coerceAtLeast(0)
        if (!restored) {
            restored = true
            // The cursor goes back to the channel watched last time; nothing plays until one is picked.
            val last = prefs.lastLiveItem
            if (last != null && !Repository.isSearch(key)) {
                val i = graph.repo.indexOf(p.id, ContentType.LIVE, key, last, prefs.liveSort)
                if (i >= 0) cursor = i
            }
        }
        listState.scrollToItem((cursor - 5).coerceAtLeast(0))
    }

    // Channel check marks: working (green dot) / not (dimmed).
    LaunchedEffect(playlist, checkUpdates, synced) {
        val p = playlist ?: return@LaunchedEffect
        checks = graph.repo.checkResults(p.id, System.currentTimeMillis() - DesktopChannelCheck.VALID_MS)
    }

    // Play whatever is picked; the guide a moment later (zapping through asks for nothing).
    LaunchedEffect(picked?.row?.itemId) {
        val pk = picked ?: return@LaunchedEffect
        val p = playlist ?: return@LaunchedEffect
        val url = pk.row.streamUrl ?: urls?.live(pk.row.itemId, liveExt) ?: return@LaunchedEffect
        if (player.url != url) player.play(url, isLive = true)
        prefs.lastLiveItem = pk.row.itemId
        if (!Repository.isSearch(pk.key)) prefs.lastLiveCategory = pk.key
        guide = emptyList()
        if (urls == null) return@LaunchedEffect
        val cached = guideCache[pk.row.itemId]
        val now = System.currentTimeMillis() / 1000
        if (cached != null && now - cached.first < 600) {
            guide = cached.second
            return@LaunchedEffect
        }
        delay(400)
        val list = runCatching {
            if (pk.row.catchupDays > 0) graph.syncer.fullEpg(p, pk.row.itemId) else graph.syncer.shortEpg(p, pk.row.itemId)
        }.getOrDefault(emptyList())
        guideCache[pk.row.itemId] = now to list
        if (picked?.row?.itemId == pk.row.itemId) guide = list
    }

    // Guide progress moves on.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            tick++
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            player.stop()
            window.setCursorHidden(false)
            if (window.isFullscreen) window.setFullscreen(false)
        }
    }

    fun play(index: Int, r: PagedRows, key: String) {
        scope.launch {
            val row = r.fetch(index) ?: return@launch
            val cur = picked
            if (cur?.row?.itemId == row.itemId) return@launch
            if (cur != null) previous = cur
            picked = Picked(row, key, index, r)
        }
    }

    fun zap(delta: Int) {
        val pk = picked ?: return
        val n = pk.rows.count
        if (n == 0) return
        val i = ((pk.index + delta) % n + n) % n
        play(i, pk.rows, pk.key)
        if (pk.key == listKey) cursor = i
        overlay.poke()
    }

    fun recall() {
        val prev = previous ?: return toaster.show("No channel before this one")
        previous = picked
        picked = prev
        if (prev.key == listKey) cursor = prev.index
        overlay.poke()
    }

    fun setFull(on: Boolean) {
        fullscreen = on
        window.setFullscreen(on)
        window.setCursorHidden(false)
        overlay.poke()
    }

    fun toggleFavourite(row: EntryRow, index: Int?, r: PagedRows?) {
        val p = playlist ?: return
        scope.launch {
            val on = graph.repo.toggleFavourite(p.id, ContentType.LIVE, row.itemId)
            toaster.show(if (on) "Added to favourites" else "Removed from favourites")
            val updated = row.copy(favourite = on)
            if (index != null && r != null) r.update(index, updated)
            picked?.let { if (it.row.itemId == row.itemId) picked = Picked(updated, it.key, it.index, it.rows) }
            if (listKey == Repository.KEY_FAV) reloadList++
        }
    }

    fun openCatchup(row: EntryRow) {
        val p = playlist ?: return
        val u = urls ?: return
        scope.launch {
            val listings = runCatching { graph.syncer.fullEpg(p, row.itemId) }.getOrDefault(emptyList())
            val list = Guide.catchup(listings, System.currentTimeMillis() / 1000, row.catchupDays)
            if (list.isEmpty()) return@launch toaster.show("Nothing to watch again on ${row.name} right now")
            dialog = {
                ListDialog("Catch-up: ${row.name}", list.map { "${Dates.weekday(it.startEpoch)}  ${Dates.time(it.startEpoch)}   ${it.title}" }, onDismiss = { dialog = null }) { i ->
                    dialog = null
                    val e = list[i]
                    val url = u.timeshift(row.itemId, Guide.timeshiftStart(e.startEpoch, p.serverTz), Guide.minutes(e), liveExt)
                    if (fullscreen) setFull(false)
                    nav.push(Route.Player(p.id, listOf(PlayItem(url, "${row.name} · ${e.title}")), 0))
                }
            }
        }
    }

    fun newGroup(addItem: String?) {
        val p = playlist ?: return
        dialog = {
            TextDialog("New group", "Name", "", "Create", onDismiss = { dialog = null }) { name ->
                dialog = null
                scope.launch {
                    val id = graph.repo.addGroup(p.id, name)
                    if (addItem != null) graph.repo.setInGroup(id, addItem, true)
                    groups = graph.repo.groups(p.id)
                    categories = graph.repo.categories(p.id, ContentType.LIVE)
                    toaster.show(if (addItem != null) "Added to $name" else "Group $name made")
                }
            }
        }
    }

    fun onKey(e: KeyEvent): Boolean {
        if (e.type != KeyEventType.KeyDown) return false
        if (dialog != null || menuFor != null || catMenu != null) return false
        if (e.isCtrlPressed && e.key == Key.F) {
            if (fullscreen) setFull(false)
            runCatching { searchFocus.requestFocus() }
            return true
        }
        if (typing) {
            // Down / Enter leave the box for the results.
            if (e.key == Key.DirectionDown || e.key == Key.Enter || e.key == Key.NumPadEnter) {
                runCatching { focus.requestFocus() }
                return true
            }
            return false
        }
        if (e.isCtrlPressed || e.isAltPressed) return false
        when (e.key) {
            Key.M -> { toggleMute(player); toaster.show(if (player.muted) "Muted" else "Sound on"); return true }
            Key.Plus, Key.NumPadAdd, Key.Equals -> { toaster.show(volumeStep(player, true)); return true }
            Key.Minus, Key.NumPadSubtract -> { toaster.show(volumeStep(player, false)); return true }
            Key.Zero, Key.NumPad0, Key.Backspace -> { recall(); return true }
            Key.A -> { cycleAudio(player)?.let { toaster.show(it) }; return true }
            Key.S -> { cycleSubtitles(player)?.let { toaster.show(it) }; return true }
            Key.Z -> {
                aspect = (aspect + 1) % Aspect.LABELS.size
                prefs.aspect = aspect
                toaster.show("Picture: ${Aspect.LABELS[aspect]}")
                return true
            }
            Key.F -> { if (picked != null) setFull(!fullscreen); return true }
            else -> {}
        }
        if (fullscreen) {
            when (e.key) {
                Key.DirectionUp, Key.PageUp, Key.ChannelUp -> zap(-1)
                Key.DirectionDown, Key.PageDown, Key.ChannelDown -> zap(1)
                Key.Enter, Key.NumPadEnter, Key.Spacebar, Key.I -> if (overlay.visible) overlay.hide() else overlay.poke()
                else -> return false
            }
            return true
        }
        val r = rows ?: return false
        if (paneCats) {
            when (e.key) {
                Key.DirectionUp -> catCursor = (catCursor - 1).coerceAtLeast(0)
                Key.DirectionDown -> catCursor = (catCursor + 1).coerceAtMost(shownCats.lastIndex)
                Key.DirectionRight, Key.Enter, Key.NumPadEnter -> {
                    shownCats.getOrNull(catCursor)?.let { search.value = ""; catKey = it.key }
                    paneCats = false
                }
                else -> return false
            }
            return true
        }
        when (e.key) {
            Key.DirectionUp -> cursor = (cursor - 1).coerceAtLeast(0)
            Key.DirectionDown -> cursor = (cursor + 1).coerceAtMost(r.count - 1)
            Key.PageUp -> cursor = (cursor - 10).coerceAtLeast(0)
            Key.PageDown -> cursor = (cursor + 10).coerceAtMost(r.count - 1)
            Key.MoveHome -> cursor = 0
            Key.MoveEnd -> cursor = r.count - 1
            Key.DirectionLeft -> paneCats = true
            Key.Enter, Key.NumPadEnter -> {
                if (picked?.key == listKey && picked?.index == cursor) setFull(true) else play(cursor, r, listKey)
            }
            Key.Menu -> r[cursor]?.let { menuFor = cursor to it }
            else -> return false
        }
        return true
    }

    // Keep the cursor rows in view.
    LaunchedEffect(cursor) { scrollIntoView(listState, cursor) }
    LaunchedEffect(catCursor) { scrollIntoView(catState, catCursor) }
    LaunchedEffect(catSearch.value) { catCursor = 0 }

    BackHandler {
        when {
            fullscreen -> { setFull(false); true }
            paneCats -> { paneCats = false; true }
            search.value.isNotEmpty() -> { search.value = ""; true }
            else -> false
        }
    }

    LaunchedEffect(fullscreen) { runCatching { focus.requestFocus() } }
    // The pointer hides over full-screen video with the overlay.
    LaunchedEffect(fullscreen, overlay.visible) { window.setCursorHidden(fullscreen && !overlay.visible) }

    val pk = picked
    val now = System.currentTimeMillis() / 1000 + tick * 0
    val (on, next) = Guide.nowNext(guide, now)

    Box(
        Modifier.fillMaxSize().onPreviewKeyEvent(::onKey).focusRequester(focus).focusable(),
    ) {
        if (!fullscreen) {
            ThemedBackground {
                Column(Modifier.fillMaxSize()) {
                    TopBar("Live TV", { nav.back() }, subtitle = playlist?.name)
                    Row(Modifier.fillMaxSize().padding(start = 8.dp, end = 16.dp, bottom = 12.dp)) {
                        // Categories, with their own search on top.
                        Column(Modifier.width(250.dp).fillMaxHeight()) {
                        SearchField(catSearch, "Search categories", Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 6.dp), onFocus = { typing = it })
                        if (shownCats.isEmpty()) CenterNote("No category by that name", Modifier.fillMaxWidth())
                        LazyColumn(Modifier.fillMaxSize(), state = catState) {
                            itemsIndexed(shownCats, key = { _, c -> c.key }) { i, c ->
                                Box {
                                    ListRow(
                                        selected = c.key == catKey && search.value.isBlank(),
                                        current = paneCats && i == catCursor,
                                        onClick = {
                                            search.value = ""
                                            catKey = c.key
                                            catCursor = i
                                            paneCats = false
                                            runCatching { focus.requestFocus() }
                                        },
                                        onSecondary = { if (Repository.groupId(c.key) != null) catMenu = c },
                                    ) {
                                        Text(c.name, color = wt.textPrimary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (catMenu == c) GroupMenu(c, onDismiss = { catMenu = null }, onRename = {
                                        catMenu = null
                                        dialog = {
                                            TextDialog("Rename group", "Name", c.name, "Save", onDismiss = { dialog = null }) { name ->
                                                dialog = null
                                                scope.launch {
                                                    graph.repo.renameGroup(Repository.groupId(c.key)!!, name)
                                                    categories = graph.repo.categories(playlist!!.id, ContentType.LIVE)
                                                    groups = graph.repo.groups(playlist!!.id)
                                                }
                                            }
                                        }
                                    }, onDelete = {
                                        catMenu = null
                                        dialog = {
                                            ConfirmDialog("Delete group ${c.name}?", "The channels stay in their categories.", "Delete", onDismiss = { dialog = null }) {
                                                dialog = null
                                                scope.launch {
                                                    graph.repo.deleteGroup(Repository.groupId(c.key)!!)
                                                    if (catKey == c.key) catKey = Repository.KEY_ALL
                                                    categories = graph.repo.categories(playlist!!.id, ContentType.LIVE)
                                                    groups = graph.repo.groups(playlist!!.id)
                                                }
                                            }
                                        }
                                    })
                                }
                            }
                            if (catSearch.value.isBlank()) item {
                                ListRow(selected = false, onClick = { newGroup(null) }) {
                                    Text("+ New group", color = wt.textSecondary, fontSize = 14.sp)
                                }
                            }
                        }
                        }
                        Spacer(Modifier.width(8.dp))
                        // Channels
                        Column(Modifier.width(380.dp).fillMaxHeight()) {
                        SearchField(search, "Search channels (Ctrl+F)", Modifier.fillMaxWidth().padding(bottom = 6.dp), searchFocus, onFocus = { typing = it })
                        Column(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(wt.slot)) {
                            val r = rows
                            val title = if (Repository.isSearch(listKey)) "Results for “${search.value.trim()}”" else categories.firstOrNull { it.key == catKey }?.name ?: ""
                            Text(
                                "$title" + (r?.let { " · ${it.count}" } ?: ""),
                                color = wt.textSecondary,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                            if (r != null && r.count == 0) {
                                CenterNote(if (Repository.isSearch(listKey)) "No channel by that name" else if (listKey == Repository.KEY_FAV) "No favourites yet: right-click a channel to add it" else "Nothing here", Modifier.fillMaxSize())
                            }
                            if (r != null && r.count > 0) {
                                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                                    items(r.count) { i ->
                                        val row = r[i]
                                        Box(
                                            Modifier.onPointerEvent(PointerEventType.Press) { ev ->
                                                if (ev.buttons.isSecondaryPressed) menuX = with(density) { (ev.changes.firstOrNull()?.position?.x ?: 0f).toDp() }
                                            },
                                        ) {
                                            ChannelRow(
                                                number = i + 1,
                                                row = row,
                                                selected = i == cursor && !paneCats,
                                                playing = row != null && pk?.row?.itemId == row.itemId,
                                                check = row?.let { checks[it.itemId] },
                                                onClick = {
                                                    cursor = i
                                                    paneCats = false
                                                    runCatching { focus.requestFocus() }
                                                    play(i, r, listKey)
                                                },
                                                onDoubleClick = {
                                                    play(i, r, listKey)
                                                    setFull(true)
                                                },
                                                onSecondary = { if (row != null) menuFor = i to row },
                                            )
                                            val m = menuFor
                                            if (m != null && m.first == i && row != null) {
                                                ChannelMenu(
                                                    offsetX = menuX,
                                                    row = m.second,
                                                    groups = groups,
                                                    catchup = urls != null && m.second.catchupDays > 0,
                                                    playlistId = playlist?.id ?: -1,
                                                    onDismiss = { menuFor = null },
                                                    onPlay = { menuFor = null; play(i, r, listKey) },
                                                    onFull = { menuFor = null; play(i, r, listKey); setFull(true) },
                                                    onFavourite = { menuFor = null; toggleFavourite(m.second, i, r) },
                                                    onCatchup = { menuFor = null; openCatchup(m.second) },
                                                    onNewGroup = { menuFor = null; newGroup(m.second.itemId) },
                                                    onGroupsChanged = { if (Repository.groupId(listKey) != null) reloadList++ },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        }
                        Spacer(Modifier.width(16.dp))
                        // Preview and guide
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            Box(
                                Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))
                                    .clicks(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, onClick = {}, onDoubleClick = { if (pk != null) setFull(true) }),
                            ) {
                                if (pk != null) {
                                    VideoView(player, aspect, Modifier.fillMaxSize())
                                } else {
                                    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                                        Text("Pick a channel", color = Color.White.copy(alpha = 0.6f), fontSize = 16.sp)
                                    }
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            if (pk != null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(pk.row.name, color = wt.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (player.state == com.worldtv.desktop.player.VlcPlayer.State.ERROR) Text(player.error.orEmpty(), color = wt.error, fontSize = 13.sp)
                                    }
                                    IconAction(if (pk.row.favourite) Icons.Default.Star else Icons.Default.StarBorder, if (pk.row.favourite) "Remove from favourites" else "Add to favourites", { toggleFavourite(pk.row, null, null) })
                                    if (urls != null && pk.row.catchupDays > 0) IconAction(Icons.Default.History, "Catch-up", { openCatchup(pk.row) })
                                    TrackButton(player, audio = true)
                                    TrackButton(player, audio = false)
                                    IconAction(Icons.Default.SwapVert, "Previous channel (0)", { recall() })
                                    IconAction(Icons.Default.Fullscreen, "Full screen (F)", { setFull(true) })
                                }
                                GuideLines(on, next, now)
                            }
                        }
                    }
                }
            }
        } else if (pk != null) {
            // Full screen: the picture, and the banner while the mouse moves or a key is pressed.
            Box(
                Modifier.fillMaxSize().background(Color.Black)
                    .onPointerEvent(PointerEventType.Move) { overlay.poke() }
                    .onPointerEvent(PointerEventType.Scroll) { ev ->
                        val dy = ev.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                        if (dy != 0f) toaster.show(volumeStep(player, dy < 0))
                    }
                    .clicks(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, onClick = { overlay.poke() }, onDoubleClick = { setFull(false) }),
            ) {
                VideoView(player, aspect, Modifier.fillMaxSize())
                overlay.Run(hold = false)
                if (overlay.visible) {
                    Column(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000), Color(0xEE000000))))
                            .padding(horizontal = 32.dp, vertical = 20.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (pk.row.logo != null) {
                                AsyncImage(pk.row.logo, null, Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)))
                                Spacer(Modifier.width(14.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text("${pk.index + 1}  ${pk.row.name}", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (on != null) Text("Now ${Dates.time(on.startEpoch)}–${Dates.time(on.stopEpoch)}  ${on.title}", color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (next != null) Text("Next ${Dates.time(next.startEpoch)}  ${next.title}", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconAction(Icons.Default.KeyboardArrowUp, "Previous channel (Up)", { zap(-1) }, tint = Color.White)
                            IconAction(Icons.Default.KeyboardArrowDown, "Next channel (Down)", { zap(1) }, tint = Color.White)
                            IconAction(if (pk.row.favourite) Icons.Default.Star else Icons.Default.StarBorder, "Favourite", { toggleFavourite(pk.row, null, null) }, tint = Color.White)
                            if (urls != null && pk.row.catchupDays > 0) IconAction(Icons.Default.History, "Catch-up", { openCatchup(pk.row) }, tint = Color.White)
                            TrackButton(player, audio = true, onOpen = { overlay.poke() })
                            TrackButton(player, audio = false, onOpen = { overlay.poke() })
                            AspectButton(aspect) { aspect = it; prefs.aspect = it }
                            VolumeControl(player)
                            IconAction(Icons.Default.FullscreenExit, "Leave full screen (Esc)", { setFull(false) }, tint = Color.White)
                        }
                        if (on != null) {
                            Spacer(Modifier.height(10.dp))
                            LinearProgressIndicator(
                                progress = { ((now - on.startEpoch).toFloat() / (on.stopEpoch - on.startEpoch).coerceAtLeast(1)).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(4.dp),
                                color = wt.accent,
                                trackColor = Color.White.copy(alpha = 0.25f),
                            )
                        }
                    }
                }
            }
        }
        dialog?.invoke()
    }
}

private suspend fun scrollIntoView(state: LazyListState, index: Int) {
    val info = state.layoutInfo
    val visible = info.visibleItemsInfo
    if (visible.isEmpty()) return
    val first = visible.first().index
    val last = visible.last().index
    if (index <= first) state.scrollToItem(index.coerceAtLeast(0))
    else if (index >= last) state.scrollToItem((index - (last - first) + 2).coerceAtLeast(0))
}

@Composable
private fun ChannelRow(
    number: Int,
    row: EntryRow?,
    selected: Boolean,
    playing: Boolean,
    check: Boolean?,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
    onSecondary: () -> Unit,
) {
    val wt = LocalWt.current
    ListRow(selected = selected, current = playing, onClick = onClick, onDoubleClick = onDoubleClick, onSecondary = onSecondary, height = 52.dp) {
        Row(Modifier.fillMaxWidth().alpha(if (check == false) 0.4f else 1f), verticalAlignment = Alignment.CenterVertically) {
            Text("$number", color = wt.textSecondary, fontSize = 13.sp, modifier = Modifier.width(40.dp))
            Box(Modifier.size(width = 54.dp, height = 36.dp), contentAlignment = Alignment.Center) {
                if (row?.logo != null) AsyncImage(row.logo, null, Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(10.dp))
            Text(row?.name ?: "", color = wt.textPrimary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (row != null && row.catchupDays > 0) Icon(Icons.Default.History, "Catch-up", tint = wt.textSecondary, modifier = Modifier.size(16.dp))
            if (row?.favourite == true) Icon(Icons.Default.Star, "Favourite", tint = wt.accent, modifier = Modifier.size(18.dp))
            if (check == true) {
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(9.dp).clip(CircleShape).background(wt.good))
            }
        }
    }
}

@Composable
private fun GuideLines(on: EpgListing?, next: EpgListing?, now: Long) {
    val wt = LocalWt.current
    if (on == null && next == null) return
    Column(Modifier.padding(top = 10.dp).clip(RoundedCornerShape(10.dp)).background(wt.slot).fillMaxWidth().padding(14.dp)) {
        if (on != null) {
            Text("Now  ${Dates.time(on.startEpoch)}–${Dates.time(on.stopEpoch)}", color = wt.textSecondary, fontSize = 13.sp)
            Text(on.title, color = wt.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { ((now - on.startEpoch).toFloat() / (on.stopEpoch - on.startEpoch).coerceAtLeast(1)).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = wt.accent,
                trackColor = wt.surface,
            )
            on.description?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = wt.textSecondary, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        if (next != null) {
            if (on != null) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = wt.divider)
                Spacer(Modifier.height(8.dp))
            }
            Text("Next  ${Dates.time(next.startEpoch)}", color = wt.textSecondary, fontSize = 13.sp)
            Text(next.title, color = wt.textPrimary, fontSize = 15.sp)
        }
    }
}

@Composable
private fun ChannelMenu(
    offsetX: androidx.compose.ui.unit.Dp,
    row: EntryRow,
    groups: List<Group>,
    catchup: Boolean,
    playlistId: Long,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onFull: () -> Unit,
    onFavourite: () -> Unit,
    onCatchup: () -> Unit,
    onNewGroup: () -> Unit,
    onGroupsChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    var inGroups by remember { mutableStateOf<Set<Long>>(emptySet()) }
    LaunchedEffect(row.itemId, groups) { if (groups.isNotEmpty()) inGroups = graph.repo.groupsOf(row.itemId, groups.map { it.id }) }
    DropdownMenu(true, onDismissRequest = onDismiss, offset = androidx.compose.ui.unit.DpOffset(offsetX, 0.dp)) {
        DropdownMenuItem(text = { Text("Play") }, onClick = onPlay)
        DropdownMenuItem(text = { Text("Play full screen") }, onClick = onFull)
        DropdownMenuItem(text = { Text(if (row.favourite) "Remove from favourites" else "Add to favourites") }, onClick = onFavourite)
        if (catchup) DropdownMenuItem(text = { Text("Catch-up…") }, onClick = onCatchup)
        HorizontalDivider()
        groups.forEach { g ->
            val inside = g.id in inGroups
            DropdownMenuItem(
                text = { Text(g.name) },
                leadingIcon = { if (inside) Icon(Icons.Default.Check, null) else Box(Modifier.size(24.dp)) },
                onClick = {
                    scope.launch {
                        graph.repo.setInGroup(g.id, row.itemId, !inside)
                        inGroups = graph.repo.groupsOf(row.itemId, groups.map { it.id })
                        toaster.show(if (inside) "Removed from ${g.name}" else "Added to ${g.name}")
                        onGroupsChanged()
                    }
                },
            )
        }
        DropdownMenuItem(text = { Text("Add to a new group…") }, onClick = onNewGroup)
    }
}

@Composable
private fun GroupMenu(c: CategoryRow, onDismiss: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    DropdownMenu(true, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("Rename “${c.name}”…") }, onClick = onRename)
        DropdownMenuItem(text = { Text("Delete group") }, onClick = onDelete)
    }
}

/** A dialog with one text field ([confirm] runs with the trimmed text, which must not be empty). */
@Composable
fun TextDialog(title: String, label: String, initial: String, confirmLabel: String, onDismiss: () -> Unit, confirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val f = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                text, { text = it }, label = { Text(label) }, singleLine = true,
                modifier = Modifier.focusRequester(f).onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter) && text.isNotBlank()) {
                        confirm(text.trim())
                        true
                    } else {
                        false
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) confirm(text.trim()) }),
            )
            LaunchedEffect(Unit) { runCatching { f.requestFocus() } }
        },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) confirm(text.trim()) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirmLabel: String, onDismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = confirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A dialog listing [items]; a click picks one. */
@Composable
fun ListDialog(title: String, items: List<String>, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                items(items.size) { i ->
                    Text(
                        items[i],
                        modifier = Modifier.fillMaxWidth().clickable { onPick(i) }.padding(vertical = 10.dp, horizontal = 4.dp),
                        fontSize = 15.sp,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
