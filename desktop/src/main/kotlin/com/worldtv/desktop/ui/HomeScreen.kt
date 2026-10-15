package com.worldtv.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.worldtv.desktop.Updates
import com.worldtv.desktop.graph
import com.worldtv.iptvplayer.data.model.ContentType
import com.worldtv.iptvplayer.data.repo.Playlist
import com.worldtv.iptvplayer.data.repo.Repository
import kotlinx.coroutines.launch
import java.text.NumberFormat

/** Home: Live TV, Movies, Series, as in IBO; the playlist in use, Reload and Settings on top. */
@Composable
fun HomeScreen() {
    val nav = LocalNav.current
    val wt = LocalWt.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var playlists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var active by remember { mutableStateOf<Playlist?>(null) }
    var counts by remember { mutableStateOf<Map<ContentType, Int>>(emptyMap()) }
    var loaded by remember { mutableStateOf(false) }
    val syncing by graph.syncing.collectAsState()
    val progress by graph.syncProgress.collectAsState()
    val synced by graph.synced.collectAsState()
    var pick by remember { mutableStateOf(false) }

    LaunchedEffect(synced, syncing) {
        playlists = graph.repo.playlists()
        val p = graph.activePlaylist()
        active = p
        counts = if (p == null) emptyMap() else ContentType.values().associateWith { graph.repo.count(p.id, it, Repository.KEY_ALL) }
        loaded = true
    }

    fun reload() {
        val p = active ?: return
        scope.launch {
            val err = graph.reload(p)
            toaster.show(err?.let { "Couldn't load ${p.name}: $it" } ?: "${p.name} is up to date")
        }
    }

    val first = remember { FocusRequester() }
    ThemedBackground {
        Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("WorldTV", color = wt.textPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(24.dp))
                if (active != null) {
                    Box {
                        Row(
                            Modifier.clip(RoundedCornerShape(8.dp)).clickable { pick = true }.pointerHoverIcon(PointerIcon.Hand)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(active!!.name, color = wt.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                                Text(playlistLine(active!!), color = wt.textSecondary, fontSize = 13.sp)
                            }
                            Icon(Icons.Default.ArrowDropDown, null, tint = wt.textSecondary)
                        }
                        DropdownMenu(pick, onDismissRequest = { pick = false }) {
                            playlists.forEach { p ->
                                DropdownMenuItem(
                                    text = { Text(p.name) },
                                    leadingIcon = { if (p.id == active?.id) Icon(Icons.Default.Check, null) else Spacer(Modifier.size(24.dp)) },
                                    onClick = {
                                        pick = false
                                        graph.prefs.activePlaylist = p.id
                                        graph.prefs.lastLiveCategory = null
                                        graph.prefs.lastLiveItem = null
                                        active = p
                                        scope.launch {
                                            counts = ContentType.values().associateWith { graph.repo.count(p.id, it, Repository.KEY_ALL) }
                                            if (p.lastSync <= 0) reload()
                                        }
                                    },
                                )
                            }
                            DropdownMenuItem(text = { Text("Add playlist…") }, onClick = {
                                pick = false
                                nav.push(Route.EditPlaylist(null))
                            })
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                if (active != null) IconAction(Icons.Default.Refresh, if (syncing) "Loading…" else "Reload playlist", { if (!syncing) reload() })
                IconAction(Icons.Default.Settings, "Settings", { nav.push(Route.Settings) })
            }
            if (syncing) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = wt.accent, trackColor = wt.surface)
                Text(progress, color = wt.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }
            if (!graph.player.available) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "VLC isn't installed, so nothing can play. Install it (e.g. sudo apt install vlc, or sudo dnf install vlc) and start WorldTV again.",
                    color = wt.error,
                    fontSize = 14.sp,
                )
            }
            Updates.available?.let { r ->
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.clip(RoundedCornerShape(8.dp)).background(wt.surface).clickable { Updates.open(r.page) }.pointerHoverIcon(PointerIcon.Hand)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text("WorldTV ${r.version} is out. Click to download it.", color = wt.textPrimary, fontSize = 14.sp)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when {
                    !loaded -> {}
                    active == null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No playlist yet", color = wt.textPrimary, fontSize = 22.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("Add the playlist your provider gave you (Xtream login or M3U link).", color = wt.textSecondary, fontSize = 15.sp)
                        Spacer(Modifier.height(20.dp))
                        WtButton("Add playlist", { nav.push(Route.EditPlaylist(null)) }, primary = true)
                    }
                    else -> Row(
                        Modifier.widthIn(max = 1100.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                    ) {
                        val fmt = NumberFormat.getIntegerInstance()
                        Tile("Live TV", Icons.Default.LiveTv, counts[ContentType.LIVE]?.let { "${fmt.format(it)} channels" }, Modifier.weight(1f).focusRequester(first)) { nav.push(Route.Live) }
                        Tile("Movies", Icons.Default.Movie, counts[ContentType.MOVIE]?.let { "${fmt.format(it)} movies" }, Modifier.weight(1f)) { nav.push(Route.Vod(ContentType.MOVIE)) }
                        Tile("Series", Icons.Default.Tv, counts[ContentType.SERIES]?.let { "${fmt.format(it)} series" }, Modifier.weight(1f)) { nav.push(Route.Vod(ContentType.SERIES)) }
                    }
                }
            }
            Text(
                "Esc goes back · F11 full screen",
                color = wt.textSecondary.copy(alpha = 0.7f),
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
    LaunchedEffect(loaded, active) { if (loaded && active != null) runCatching { first.requestFocus() } }
}

/** "Expires 7 Mar 2027 · loaded 2 h ago" style line under the playlist name. */
private fun playlistLine(p: Playlist): String {
    val parts = ArrayList<String>()
    p.expires?.takeIf { it > 0 }?.let { parts += "Expires " + Dates.day(it * 1000) }
    parts += if (p.lastSync > 0) "loaded " + Dates.ago(p.lastSync) else "not loaded yet"
    return parts.joinToString(" · ")
}

@Composable
private fun Tile(title: String, icon: ImageVector, line: String?, modifier: Modifier, onClick: () -> Unit) {
    val wt = LocalWt.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    Column(
        modifier.aspectRatio(1.1f).clip(RoundedCornerShape(18.dp))
            .background(if (focused) wt.accent else if (hovered) wt.surfaceActive else wt.slot)
            .border(if (focused) 3.dp else 1.dp, if (focused) wt.pressed else if (wt.stroke.alpha > 0f) wt.stroke else Color.Transparent, RoundedCornerShape(18.dp))
            .hoverable(source)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.Enter, Key.NumPadEnter, Key.Spacebar -> { onClick(); true }
                    Key.DirectionRight -> focus.moveFocus(androidx.compose.ui.focus.FocusDirection.Next)
                    Key.DirectionLeft -> focus.moveFocus(androidx.compose.ui.focus.FocusDirection.Previous)
                    else -> false
                }
            }
            .focusable(interactionSource = source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = wt.textPrimary, modifier = Modifier.size(96.dp))
        Spacer(Modifier.height(14.dp))
        Text(title, color = wt.textPrimary, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        if (line != null) Text(line, color = wt.textPrimary.copy(alpha = 0.75f), fontSize = 14.sp)
    }
}
