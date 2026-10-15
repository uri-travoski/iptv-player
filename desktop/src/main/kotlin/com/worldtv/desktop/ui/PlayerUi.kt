package com.worldtv.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.worldtv.desktop.graph
import com.worldtv.desktop.player.VlcPlayer
import kotlinx.coroutines.delay

/** Audio language or subtitle menu of the playing stream (VLC's tracks); hidden with one track or less. */
@Composable
fun TrackButton(player: VlcPlayer, audio: Boolean, onOpen: () -> Unit = {}) {
    var open by remember { mutableStateOf(false) }
    var tracks by remember { mutableStateOf<List<VlcPlayer.Track>>(emptyList()) }
    LaunchedEffect(player.tracksVersion, player.state) { tracks = if (audio) player.audioTracks() else player.subtitleTracks() }
    // Subtitles always list "Disable" (VLC's -1); audio needs a real choice.
    val real = tracks.filter { it.id >= 0 }
    if (audio && real.size < 2) return
    if (!audio && real.isEmpty()) return
    Box {
        IconAction(if (audio) Icons.Default.Audiotrack else Icons.Default.ClosedCaption, if (audio) "Audio language (A)" else "Subtitles (S)", {
            open = true
            onOpen()
        }, tint = Color.White)
        DropdownMenu(open, onDismissRequest = { open = false }) {
            tracks.forEach { t ->
                DropdownMenuItem(
                    text = { Text(if (t.id < 0) "Off" else t.label) },
                    leadingIcon = { if (t.selected) Icon(Icons.Default.Check, null) else Box(Modifier.size(24.dp)) },
                    onClick = {
                        open = false
                        if (audio) player.selectAudio(t.id) else player.selectSubtitle(t.id)
                    },
                )
            }
        }
    }
}

/** Steps through the next audio language (A key). */
fun cycleAudio(player: VlcPlayer): String? {
    val list = player.audioTracks().filter { it.id >= 0 }
    if (list.size < 2) return null
    val i = list.indexOfFirst { it.selected }
    val next = list[(i + 1) % list.size]
    player.selectAudio(next.id)
    return "Audio: ${next.label}"
}

/** Steps through the subtitles, then off (S key). */
fun cycleSubtitles(player: VlcPlayer): String? {
    val list = player.subtitleTracks()
    if (list.none { it.id >= 0 }) return null
    val i = list.indexOfFirst { it.selected }
    val next = list[(i + 1) % list.size]
    player.selectSubtitle(next.id)
    return if (next.id < 0) "Subtitles off" else "Subtitles: ${next.label}"
}

/** Mute button and volume slider; kept in Settings for the next start. */
@Composable
fun VolumeControl(player: VlcPlayer) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconAction(
            if (player.muted || player.volume == 0) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
            "Mute (M)",
            { toggleMute(player) },
            tint = Color.White,
        )
        Slider(
            value = if (player.muted) 0f else player.volume.toFloat(),
            onValueChange = { setVolume(player, it.toInt()) },
            valueRange = 0f..150f,
            modifier = Modifier.width(120.dp),
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = LocalWt.current.accent, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
        )
    }
}

fun setVolume(player: VlcPlayer, v: Int) {
    player.changeVolume(v)
    graph.prefs.volume = player.volume
    graph.prefs.muted = player.muted
}

fun toggleMute(player: VlcPlayer) {
    player.toggleMute()
    graph.prefs.muted = player.muted
}

/** Volume keys and the mouse wheel: 10 at a time. */
fun volumeStep(player: VlcPlayer, up: Boolean): String {
    setVolume(player, player.volume + if (up) 10 else -10)
    return "Volume ${player.volume}%"
}

/** The picture shape menu (Z steps through it). */
@Composable
fun AspectButton(aspect: Int, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconAction(Icons.Default.AspectRatio, "Picture: ${Aspect.LABELS[aspect]} (Z)", { open = true }, tint = Color.White)
        DropdownMenu(open, onDismissRequest = { open = false }) {
            Aspect.LABELS.forEachIndexed { i, label ->
                DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = { if (i == aspect) Icon(Icons.Default.Check, null) else Box(Modifier.size(24.dp)) },
                    onClick = {
                        open = false
                        onChange(i)
                    },
                )
            }
        }
    }
}

/**
 * Overlay visibility over full-screen video: shown on any mouse move or key, hidden (with the
 * pointer) after [hideMs] of nothing; held while [hold] (a menu is open, paused).
 */
class OverlayTimer(private val hideMs: Long = 3500) {
    var visible by mutableStateOf(true)
        private set
    private var touched by mutableLongStateOf(System.currentTimeMillis())

    fun poke() {
        touched = System.currentTimeMillis()
        visible = true
    }

    fun hide() {
        visible = false
    }

    @Composable
    fun Run(hold: Boolean) {
        LaunchedEffect(touched, hold) {
            if (hold) return@LaunchedEffect
            delay(hideMs)
            visible = false
        }
    }
}
