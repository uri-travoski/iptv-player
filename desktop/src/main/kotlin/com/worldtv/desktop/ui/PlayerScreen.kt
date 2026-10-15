package com.worldtv.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.worldtv.desktop.graph
import com.worldtv.desktop.player.VlcPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The movie player (films, episodes, catch-up): the whole window, controls over the picture while
 * the mouse moves. Space pauses, Left / Right jump 10 s (Shift: 1 min), Page Up / Down go to the
 * previous / next episode. The position is saved as it plays, so the page offers Resume.
 */
@Composable
fun PlayerScreen(route: Route.Player) {
    val nav = LocalNav.current
    val wt = LocalWt.current
    val toaster = LocalToaster.current
    val window = LocalWindowControls.current
    val player = graph.player
    var index by remember { mutableIntStateOf(route.index) }
    val item = route.items[index]
    var aspect by remember { mutableIntStateOf(graph.prefs.aspect) }
    val overlay = remember { OverlayTimer() }
    val focus = remember { FocusRequester() }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var full by remember { mutableStateOf(window.isFullscreen) }

    fun save() {
        val it = route.items[index]
        val type = it.type ?: return
        val id = it.itemId ?: return
        val pos = player.positionMs
        val dur = player.lengthMs
        if (dur <= 0 && pos <= 0) return
        // In the app's scope: leaving the player must not cancel the last save.
        graph.appScope.launch {
            graph.repo.saveProgress(route.playlistId, type, id, it.episodeId, pos, dur, graph.adultNames(), it.season, it.number)
            graph.progressSaved.value = graph.progressSaved.value + 1
        }
    }

    LaunchedEffect(index) {
        val start = if (index == route.index) route.startMs else 0
        player.play(item.url, isLive = false, startMs = start)
    }
    // The position is kept every 15 s while playing.
    LaunchedEffect(index) {
        while (true) {
            delay(15_000)
            if (player.state == VlcPlayer.State.PLAYING) save()
        }
    }
    // The end: the next episode, else back to the page.
    LaunchedEffect(player.state) {
        if (player.state == VlcPlayer.State.ENDED) {
            save()
            if (index < route.items.lastIndex) index++ else nav.back()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            save()
            player.stop()
            window.setCursorHidden(false)
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(overlay.visible) { window.setCursorHidden(!overlay.visible) }

    fun step(delta: Int) {
        val next = index + delta
        if (next !in route.items.indices) return
        save()
        index = next
        overlay.poke()
    }

    fun seek(deltaMs: Long) {
        player.seekBy(deltaMs)
        overlay.poke()
    }

    fun toggleFull() {
        full = !full
        window.setFullscreen(full)
    }

    fun addSubtitleFile() {
        val d = java.awt.FileDialog(null as java.awt.Frame?, "Subtitle file", java.awt.FileDialog.LOAD)
        d.setFilenameFilter { _, name -> name.substringAfterLast('.').lowercase() in listOf("srt", "ass", "ssa", "vtt", "sub") }
        d.isVisible = true
        val f = d.files.firstOrNull() ?: return
        player.addSubtitleFile(f)
        toaster.show("Subtitles: ${f.name}")
    }

    fun onKey(e: KeyEvent): Boolean {
        if (e.type != KeyEventType.KeyDown) return false
        when (e.key) {
            Key.Spacebar, Key.Enter, Key.NumPadEnter, Key.K, Key.MediaPlayPause -> { player.togglePause(); overlay.poke() }
            Key.DirectionLeft, Key.J -> seek(if (e.isShiftPressed) -60_000 else -10_000)
            Key.DirectionRight, Key.L -> seek(if (e.isShiftPressed) 60_000 else 10_000)
            Key.DirectionUp -> toaster.show(volumeStep(player, true))
            Key.DirectionDown -> toaster.show(volumeStep(player, false))
            Key.Plus, Key.NumPadAdd, Key.Equals -> toaster.show(volumeStep(player, true))
            Key.Minus, Key.NumPadSubtract -> toaster.show(volumeStep(player, false))
            Key.PageUp, Key.MediaPrevious -> step(-1)
            Key.PageDown, Key.MediaNext -> step(1)
            Key.M -> { toggleMute(player); toaster.show(if (player.muted) "Muted" else "Sound on") }
            Key.F -> toggleFull()
            Key.A -> cycleAudio(player)?.let { toaster.show(it) }
            Key.S -> cycleSubtitles(player)?.let { toaster.show(it) }
            Key.Z -> {
                aspect = (aspect + 1) % Aspect.LABELS.size
                graph.prefs.aspect = aspect
                toaster.show("Picture: ${Aspect.LABELS[aspect]}")
            }
            else -> return false
        }
        return true
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .onPreviewKeyEvent(::onKey).focusRequester(focus).focusable()
            .onPointerEvent(PointerEventType.Move) { overlay.poke() }
            .onPointerEvent(PointerEventType.Scroll) { ev ->
                val dy = ev.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                if (dy != 0f) toaster.show(volumeStep(player, dy < 0))
            }
            .clicks(remember { MutableInteractionSource() }, onClick = { player.togglePause(); overlay.poke() }, onDoubleClick = { toggleFull() }),
    ) {
        VideoView(player, aspect, Modifier.fillMaxSize())
        overlay.Run(hold = player.isPaused || dragging != null)
        if (overlay.visible) {
            // Top: back and title.
            Row(
                Modifier.align(Alignment.TopStart).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconAction(Icons.AutoMirrored.Filled.ArrowBack, "Back (Esc)", { nav.back() }, tint = Color.White)
                Spacer(Modifier.width(10.dp))
                Text(item.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // Bottom: position and controls.
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000), Color(0xEE000000))))
                    .padding(horizontal = 24.dp, vertical = 14.dp),
            ) {
                val len = player.lengthMs
                val pos = dragging?.let { (it * len).toLong() } ?: player.positionMs
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Dates.clock(pos), color = Color.White, fontSize = 14.sp)
                    Slider(
                        value = if (len > 0) (pos.toFloat() / len).coerceIn(0f, 1f) else 0f,
                        onValueChange = { dragging = it; overlay.poke() },
                        onValueChangeFinished = {
                            dragging?.let { player.seekTo((it * len).toLong()) }
                            dragging = null
                        },
                        enabled = len > 0,
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = wt.accent, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
                    )
                    Text(if (len > 0) Dates.clock(len) else "--:--", color = Color.White, fontSize = 14.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (route.items.size > 1) IconAction(Icons.Default.SkipPrevious, "Previous (Page Up)", { step(-1) }, tint = Color.White)
                    IconAction(Icons.Default.Replay10, "Back 10 s (Left)", { seek(-10_000) }, tint = Color.White)
                    IconAction(if (player.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause, if (player.isPaused) "Play (Space)" else "Pause (Space)", { player.togglePause() }, size = 52.dp, tint = Color.White)
                    IconAction(Icons.Default.Forward10, "Forward 10 s (Right)", { seek(10_000) }, tint = Color.White)
                    if (route.items.size > 1) IconAction(Icons.Default.SkipNext, "Next (Page Down)", { step(1) }, tint = Color.White)
                    Spacer(Modifier.weight(1f))
                    TrackButton(player, audio = true, onOpen = { overlay.poke() })
                    TrackButton(player, audio = false, onOpen = { overlay.poke() })
                    IconAction(Icons.Default.Subtitles, "Subtitle file…", { addSubtitleFile() }, tint = Color.White)
                    AspectButton(aspect) { aspect = it; graph.prefs.aspect = it }
                    VolumeControl(player)
                    IconAction(if (full) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, "Full screen (F)", { toggleFull() }, tint = Color.White)
                }
            }
        }
    }
}
