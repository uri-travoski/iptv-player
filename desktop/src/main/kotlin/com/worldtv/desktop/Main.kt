package com.worldtv.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.worldtv.desktop.ui.HomeScreen
import com.worldtv.desktop.ui.LiveScreen
import com.worldtv.desktop.ui.LocalNav
import com.worldtv.desktop.ui.LocalToaster
import com.worldtv.desktop.ui.LocalWindowControls
import com.worldtv.desktop.ui.Navigator
import com.worldtv.desktop.ui.PlayerScreen
import com.worldtv.desktop.ui.Route
import com.worldtv.desktop.ui.SettingsScreens
import com.worldtv.desktop.ui.Themes
import com.worldtv.desktop.ui.ToastHost
import com.worldtv.desktop.ui.Toaster
import com.worldtv.desktop.ui.VodDetailScreen
import com.worldtv.desktop.ui.VodScreen
import com.worldtv.desktop.ui.WindowControls
import com.worldtv.desktop.ui.WorldTheme
import kotlinx.coroutines.launch
import java.awt.Cursor
import java.awt.Point
import java.awt.Toolkit
import java.awt.image.BufferedImage

lateinit var graph: AppGraph
    private set

/** Theme chosen in Settings; the whole window follows it. */
var themeId by mutableStateOf("classic")

fun main() {
    graph = AppGraph()
    themeId = graph.prefs.theme

    val icon = runCatching {
        val bytes = AppGraph::class.java.getResourceAsStream("/worldtv.png")!!.readBytes()
        BitmapPainter(org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap())
    }.getOrNull()

    application {
        val bounds = remember { graph.prefs.windowBounds?.split(',')?.mapNotNull { it.toIntOrNull() } }
        val state = rememberWindowState(
            size = if (bounds?.size == 4) DpSize(bounds[2].dp, bounds[3].dp) else DpSize(1280.dp, 800.dp),
            position = if (bounds?.size == 4) WindowPosition(bounds[0].dp, bounds[1].dp) else WindowPosition(Alignment.Center),
        )
        val nav = remember { Navigator() }
        Window(
            onCloseRequest = {
                if (state.placement == WindowPlacement.Floating) {
                    graph.prefs.windowBounds = listOf(state.position.x.value, state.position.y.value, state.size.width.value, state.size.height.value)
                        .joinToString(",") { it.toInt().toString() }
                }
                graph.shutdown()
                exitApplication()
            },
            title = "WorldTV",
            icon = icon,
            state = state,
            onKeyEvent = { e ->
                if (e.type != KeyEventType.KeyDown) return@Window false
                when (e.key) {
                    Key.Escape -> {
                        if (!nav.onBack() && state.placement == WindowPlacement.Fullscreen) state.placement = WindowPlacement.Floating
                        true
                    }
                    Key.F11 -> {
                        state.placement = if (state.placement == WindowPlacement.Fullscreen) WindowPlacement.Floating else WindowPlacement.Fullscreen
                        true
                    }
                    else -> false
                }
            },
        ) {
            window.minimumSize = java.awt.Dimension(900, 560)
            val awtWindow = window
            val controls = remember {
                object : WindowControls {
                    private val blank: Cursor = Toolkit.getDefaultToolkit().createCustomCursor(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), Point(0, 0), "none")
                    override val isFullscreen get() = state.placement == WindowPlacement.Fullscreen
                    override fun setFullscreen(on: Boolean) {
                        state.placement = if (on) WindowPlacement.Fullscreen else WindowPlacement.Floating
                    }
                    override fun setCursorHidden(hidden: Boolean) {
                        awtWindow.cursor = if (hidden) blank else Cursor.getDefaultCursor()
                    }
                }
            }
            val scope = rememberCoroutineScope()
            val toaster = remember { Toaster(scope) }
            CompositionLocalProvider(LocalNav provides nav, LocalToaster provides toaster, LocalWindowControls provides controls) {
                WorldTheme(Themes.of(themeId)) {
                    Box(Modifier.fillMaxSize()) {
                        Screens(nav)
                        ToastHost(toaster, Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
            LaunchedEffect(Unit) {
                graph.addDefaultPlaylistOnce()
                graph.reloadIfDue()
                graph.channelCheck.start()
                scope.launch { Updates.checkAtStart(graph, toaster) }
            }
        }
    }
}

@Composable
private fun Screens(nav: Navigator) {
    when (val r = nav.top) {
        Route.Home -> HomeScreen()
        Route.Live -> LiveScreen()
        is Route.Vod -> VodScreen(r.type)
        is Route.Detail -> VodDetailScreen(r)
        is Route.Player -> PlayerScreen(r)
        else -> SettingsScreens(r)
    }
}
