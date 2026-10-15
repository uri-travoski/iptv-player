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

/** Window full screen (F11, the players). */
private val fullscreen = mutableStateOf(false)
private var normalBounds: java.awt.Rectangle? = null
private var normalState = java.awt.Frame.NORMAL

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
                if (!fullscreen.value && state.placement == WindowPlacement.Floating) {
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
                        if (!nav.onBack() && fullscreen.value) fullscreen.value = false
                        true
                    }
                    Key.F11 -> {
                        fullscreen.value = !fullscreen.value
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
                    override val isFullscreen get() = fullscreen.value
                    override fun setFullscreen(on: Boolean) {
                        fullscreen.value = on
                    }
                    override fun setCursorHidden(hidden: Boolean) {
                        awtWindow.cursor = if (hidden) blank else Cursor.getDefaultCursor()
                    }
                }
            }
            // Full screen by hand (Compose's own leaves some window managers, e.g. Cinnamon,
            // without the title bar afterwards): the window's place and size are kept before and
            // put back after, with the title bar on the screen.
            LaunchedEffect(fullscreen.value) {
                val device = awtWindow.graphicsConfiguration.device
                if (fullscreen.value) {
                    if (device.fullScreenWindow !== awtWindow) {
                        normalBounds = awtWindow.bounds
                        normalState = awtWindow.extendedState
                        device.fullScreenWindow = awtWindow
                    }
                } else if (device.fullScreenWindow === awtWindow) {
                    device.fullScreenWindow = null
                    val b = normalBounds
                    if (b != null) {
                        val screen = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
                        awtWindow.setBounds(
                            b.x.coerceIn(screen.x, maxOf(screen.x, screen.x + screen.width - 200)),
                            b.y.coerceIn(screen.y, maxOf(screen.y, screen.y + screen.height - 200)),
                            minOf(b.width, screen.width),
                            minOf(b.height, screen.height),
                        )
                    }
                    awtWindow.extendedState = normalState
                    awtWindow.toFront()
                    awtWindow.requestFocus()
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
