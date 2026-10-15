package com.worldtv.desktop.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.worldtv.desktop.Updates
import com.worldtv.desktop.graph
import com.worldtv.iptvplayer.data.model.ContentType
import com.worldtv.iptvplayer.data.repo.Playlist
import com.worldtv.iptvplayer.data.spotlight.Slide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val shadow = Shadow(Color(0x99000000), blurRadius = 12f)

/**
 * Home: the slider (5 recent movies then 3 series of the playlist, from the shared picker) fills
 * the window behind Live TV · Movies · Series, as on the TV. More info opens the title's page;
 * Left / Right on it (or a dot) change the slide; right-click it to hide a title (playlist PIN).
 * Slides change by themselves every 8 s unless the pointer or the keyboard is on them.
 */
@Composable
fun HomeScreen() {
    val nav = LocalNav.current
    val wt = LocalWt.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var active by remember { mutableStateOf<Playlist?>(null) }
    var loaded by remember { mutableStateOf(false) }
    val syncing by graph.syncing.collectAsState()
    val progress by graph.syncProgress.collectAsState()
    val synced by graph.synced.collectAsState()
    val slides by graph.spotlight.slides.collectAsState()
    var index by remember { mutableIntStateOf(0) }
    var hideMenu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    val images = remember { mutableStateMapOf<String, ImageBitmap>() }
    val first = remember { FocusRequester() }
    val moreFocus = remember { FocusRequester() }
    val moreSource = remember { MutableInteractionSource() }
    val moreFocused by moreSource.collectIsFocusedAsState()
    val heroSource = remember { MutableInteractionSource() }
    val heroHovered by heroSource.collectIsHoveredAsState()

    LaunchedEffect(synced, syncing) {
        active = graph.activePlaylist()
        loaded = true
        graph.spotlight.refreshIfDue()
    }

    // Backdrops: decoded once each, off the UI thread.
    LaunchedEffect(slides) {
        if (index >= slides.size) index = 0
        for (s in slides) {
            if (s.image in images) continue
            val bmp = withContext(Dispatchers.IO) {
                runCatching { org.jetbrains.skia.Image.makeFromEncoded(graph.spotlight.imageFile(s).readBytes()).toComposeImageBitmap() }.getOrNull()
            }
            if (bmp != null) images[s.image] = bmp
        }
    }

    // The next slide every 8 s, unless the user is on the slider.
    LaunchedEffect(slides, index, moreFocused || heroHovered) {
        if (slides.size < 2 || moreFocused || heroHovered) return@LaunchedEffect
        delay(8_000)
        index = (index + 1) % slides.size
    }

    fun step(d: Int) {
        if (slides.isEmpty()) return
        index = ((index + d) % slides.size + slides.size) % slides.size
    }

    fun reload() {
        val p = active ?: return
        scope.launch {
            val err = graph.reload(p)
            toaster.show(err?.let { "Couldn't load the playlist: $it" } ?: "Playlist is up to date")
        }
    }

    fun openSlide(s: Slide) {
        val p = active ?: return
        scope.launch {
            val row = graph.repo.entryRow(p.id, s.type, s.itemId) ?: return@launch toaster.show("This title is no longer in the playlist")
            nav.push(Route.Detail(p, s.type, row))
        }
    }

    val slide = slides.getOrNull(index)
    Box(Modifier.fillMaxSize().background(wt.background)) {
        // Backdrop and its shades.
        if (slide != null) {
            Box(Modifier.fillMaxSize().hoverable(heroSource)) {
                Crossfade(images[slide.image], animationSpec = tween(700)) { bmp ->
                    if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0xE00C0306), 0.35f to Color(0x700C0306), 0.62f to Color.Transparent)))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 0.72f to wt.heroFade.copy(alpha = 0.65f), 1f to wt.heroFade)))
            }
        }
        Box(Modifier.fillMaxWidth().height(140.dp).background(Brush.verticalGradient(listOf(Color(0x73000000), Color.Transparent))))

        Column(Modifier.fillMaxSize().padding(start = 64.dp, end = 64.dp, top = 36.dp, bottom = 48.dp)) {
            // WorldTV · Valid Till · Reload · Settings
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    androidx.compose.ui.text.buildAnnotatedString {
                        append("World")
                        pushStyle(androidx.compose.ui.text.SpanStyle(color = wt.pressed))
                        append("TV")
                        pop()
                    },
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    style = TextStyle(shadow = shadow),
                )
                Spacer(Modifier.weight(1f))
                active?.expires?.takeIf { it > 0 }?.let {
                    Text(
                        "Valid Till: " + SimpleDateFormat("dd-MMM-yyyy", Locale.ENGLISH).format(Date(it * 1000)),
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 15.sp,
                        style = TextStyle(shadow = shadow),
                    )
                    Spacer(Modifier.width(24.dp))
                }
                if (active != null) RoundGlass(Icons.Default.Refresh, if (syncing) "Loading…" else "Reload playlist") { if (!syncing) reload() }
                Spacer(Modifier.width(14.dp))
                RoundGlass(Icons.Default.Settings, "Settings") { nav.push(Route.Settings) }
            }
            if (syncing) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(Modifier.width(140.dp).height(3.dp), color = wt.pressed, trackColor = Color.White.copy(alpha = 0.2f))
                    Spacer(Modifier.width(10.dp))
                    Text(progress, color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                }
            }
            if (!graph.player.available) {
                Text(
                    "VLC isn't installed, so nothing can play. Install it (sudo apt install vlc, or sudo dnf install vlc) and start WorldTV again.",
                    color = wt.error, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp),
                )
            }
            Updates.available?.let { r ->
                Text(
                    "WorldTV ${r.version} is out. Click to download it.",
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 10.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x33FFFFFF))
                        .clickable { Updates.open(r.page) }.pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }

            Spacer(Modifier.weight(1f))

            if (loaded && active == null) {
                Column(Modifier.align(Alignment.CenterHorizontally), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No playlist yet", color = wt.textPrimary, fontSize = 22.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("Add the playlist your provider gave you (Xtream login or M3U link).", color = wt.textSecondary, fontSize = 15.sp)
                    Spacer(Modifier.height(20.dp))
                    WtButton("Add playlist", { nav.push(Route.EditPlaylist(null)) }, primary = true)
                }
                Spacer(Modifier.weight(1f))
                return@Column
            }

            if (slide != null) {
                Text(slide.title, color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(shadow = shadow), modifier = Modifier.widthIn(max = 1100.dp))
                Text(
                    listOfNotNull(slide.year, slide.genre, slide.rating?.let { "★ $it" }).joinToString("  ·  "),
                    color = Color.White.copy(alpha = 0.78f), fontSize = 16.sp, style = TextStyle(shadow = shadow), modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    slide.overview, color = Color.White.copy(alpha = 0.78f), fontSize = 16.sp, lineHeight = 23.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(shadow = shadow), modifier = Modifier.padding(top = 10.dp).width(640.dp),
                )
                Box(Modifier.padding(top = 18.dp)) {
                    MoreInfoButton(
                        source = moreSource,
                        modifier = Modifier.focusRequester(moreFocus).onKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                            when (e.key) {
                                Key.DirectionLeft -> { step(-1); true }
                                Key.DirectionRight -> { step(1); true }
                                Key.Menu -> { hideMenu = true; true }
                                Key.DirectionDown -> { runCatching { first.requestFocus() }; true }
                                else -> false
                            }
                        },
                        onClick = { openSlide(slide) },
                        onSecondary = { hideMenu = true },
                    )
                    DropdownMenu(hideMenu, onDismissRequest = { hideMenu = false }) {
                        DropdownMenuItem(text = { Text("Hide from the home screen") }, onClick = {
                            hideMenu = false
                            val p = active ?: return@DropdownMenuItem
                            dialog = {
                                PinDialog(p, "Hiding a title", onDismiss = { dialog = null }) {
                                    dialog = null
                                    graph.spotlight.hide(slide)
                                    toaster.show("Hidden from the home screen")
                                }
                            }
                        })
                    }
                }
                // Dots: 5 movies, a gap, 3 series. A click shows that one.
                Row(Modifier.padding(top = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                    slides.forEachIndexed { i, s ->
                        if (i > 0) Spacer(Modifier.width(if (s.type != slides[i - 1].type) 16.dp else 8.dp))
                        Box(
                            Modifier.height(8.dp).width(if (i == index) 30.dp else 8.dp).clip(RoundedCornerShape(4.dp))
                                .background(if (i == index) Color.White.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.32f))
                                .clickable { index = i }.pointerHoverIcon(PointerIcon.Hand),
                        )
                    }
                }
                Spacer(Modifier.height(36.dp))
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                HomeTile("Live TV", Icons.Default.LiveTv, Modifier.weight(1f).focusRequester(first), up = slide != null, onUp = { runCatching { moreFocus.requestFocus() } }) { nav.push(Route.Live) }
                HomeTile("Movies", Icons.Default.Movie, Modifier.weight(1f), up = slide != null, onUp = { runCatching { moreFocus.requestFocus() } }) { nav.push(Route.Vod(ContentType.MOVIE)) }
                HomeTile("Series", Icons.Default.Tv, Modifier.weight(1f), up = slide != null, onUp = { runCatching { moreFocus.requestFocus() } }) { nav.push(Route.Vod(ContentType.SERIES)) }
            }
            // No slides: the tiles in the middle.
            if (slide == null) Spacer(Modifier.weight(1f))
        }
        dialog?.invoke()
    }
    LaunchedEffect(loaded, active) { if (loaded && active != null) runCatching { first.requestFocus() } }
}

@Composable
private fun RoundGlass(icon: ImageVector, label: String, onClick: () -> Unit) {
    val wt = LocalWt.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    androidx.compose.foundation.TooltipArea(
        tooltip = {
            Box(Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xEE111111)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                Text(label, color = Color.White, fontSize = 13.sp)
            }
        },
        delayMillis = 500,
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape)
                .background(if (hovered || focused) wt.accent else Color(0x26FFFFFF))
                .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else Color(0x38FFFFFF), CircleShape)
                .hoverable(source)
                .clickable(interactionSource = source, indication = null, onClick = onClick)
                .pointerHoverIcon(PointerIcon.Hand),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, tint = Color.White, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun MoreInfoButton(source: MutableInteractionSource, modifier: Modifier, onClick: () -> Unit, onSecondary: () -> Unit) {
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val on = hovered || focused
    Row(
        modifier.clip(RoundedCornerShape(10.dp))
            .background(if (on) Color(0xF2FFFFFF) else Color(0x29FFFFFF))
            .border(1.dp, if (on) Color.Transparent else Color(0x4DFFFFFF), RoundedCornerShape(10.dp))
            .hoverable(source)
            .focusable(interactionSource = source)
            .onKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter || e.key == Key.Spacebar)) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .clicks(source, onClick, onSecondary = onSecondary)
            .padding(start = 16.dp, end = 22.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val c = if (on) Color(0xFF1A0A0E) else Color.White
        Icon(Icons.Default.PlayArrow, null, tint = c, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text("More info", color = c, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HomeTile(title: String, icon: ImageVector, modifier: Modifier, up: Boolean, onUp: () -> Unit, onClick: () -> Unit) {
    val wt = LocalWt.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val focus = LocalFocusManager.current
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, tween(150))
    Row(
        modifier.scale(scale).height(112.dp).clip(RoundedCornerShape(20.dp))
            .background(if (focused) wt.accent else if (hovered) wt.surfaceActive else wt.slot)
            .border(if (focused) 2.dp else 1.dp, if (focused) Color(0xD9FFFFFF) else Color(0x24FFFFFF), RoundedCornerShape(20.dp))
            .hoverable(source)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.Enter, Key.NumPadEnter, Key.Spacebar -> { onClick(); true }
                    Key.DirectionRight -> focus.moveFocus(FocusDirection.Next)
                    Key.DirectionLeft -> focus.moveFocus(FocusDirection.Previous)
                    Key.DirectionUp -> if (up) { onUp(); true } else false
                    else -> false
                }
            }
            .focusable(interactionSource = source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(40.dp))
        Spacer(Modifier.width(18.dp))
        Text(title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
    }
}
