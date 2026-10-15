package com.worldtv.desktop.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf
import com.worldtv.iptvplayer.data.model.ContentType
import com.worldtv.iptvplayer.data.repo.EntryRow
import com.worldtv.iptvplayer.data.repo.Playlist

/** One thing to play in the movie player (a film, an episode, a catch-up programme). */
data class PlayItem(
    val url: String,
    val title: String,
    /** Where its position is saved; null for catch-up (not resumed). */
    val type: ContentType? = null,
    val itemId: String? = null,
    val episodeId: String? = null,
)

/** The screens, as in the Android app. */
sealed interface Route {
    data object Home : Route
    data object Live : Route
    data class Vod(val type: ContentType) : Route
    data class Detail(val playlist: Playlist, val type: ContentType, val row: EntryRow) : Route
    data class Player(val playlistId: Long, val items: List<PlayItem>, val index: Int, val startMs: Long = 0) : Route
    data object Settings : Route
    data object Playlists : Route
    data class EditPlaylist(val playlistId: Long?) : Route
    data object ChannelCheck : Route
    data object Categories : Route
    data object Help : Route
}

class Navigator {
    val stack = mutableStateListOf<Route>(Route.Home)

    /** Screens' own Back steps (full screen → list), newest last; see [BackHandler]. */
    internal val backHandlers = ArrayList<() -> Boolean>()

    /** Esc / Back: the screen's own step first, else the previous screen. */
    fun onBack(): Boolean {
        for (i in backHandlers.indices.reversed()) if (backHandlers[i]()) return true
        return back()
    }

    val top: Route get() = stack.last()

    fun push(r: Route) {
        stack += r
    }

    /** Back one screen; false at Home. */
    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun replace(r: Route) {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
        stack += r
    }

    fun home() {
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
}

val LocalNav = staticCompositionLocalOf<Navigator> { error("no navigator") }

/** Full screen on/off for the window (video). */
interface WindowControls {
    val isFullscreen: Boolean
    fun setFullscreen(on: Boolean)
    /** The pointer disappears over the picture after a few still seconds. */
    fun setCursorHidden(hidden: Boolean)
}

val LocalWindowControls = staticCompositionLocalOf<WindowControls> { error("no window") }

/** While on screen, [onBack] gets Esc first; it returns false to let the screen close. */
@androidx.compose.runtime.Composable
fun BackHandler(onBack: () -> Boolean) {
    val nav = LocalNav.current
    val current = androidx.compose.runtime.rememberUpdatedState(onBack)
    androidx.compose.runtime.DisposableEffect(nav) {
        val h: () -> Boolean = { current.value() }
        nav.backHandlers += h
        onDispose { nav.backHandlers.remove(h) }
    }
}
