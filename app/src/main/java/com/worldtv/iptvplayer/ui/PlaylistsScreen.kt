package com.worldtv.iptvplayer.ui

import android.app.AlertDialog
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.worldtv.iptvplayer.MainActivity
import com.worldtv.iptvplayer.R
import com.worldtv.iptvplayer.data.repo.Pin
import com.worldtv.iptvplayer.data.repo.Playlist
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings > Playlists: the saved playlists (open, refresh, details, categories, delete) and Add playlist. */
class PlaylistsScreen(activity: MainActivity) : Screen(activity) {

    override val root: View = inflater.inflate(R.layout.screen_playlists, null)
    private val list: LinearLayout = root.findViewById(R.id.list)
    private val add: TextView = root.findViewById(R.id.add)
    private val message: TextView = root.findViewById(R.id.message)
    private val busyDots: View = root.findViewById(R.id.busy)
    private var busy = false

    init {
        fullWidthOnMobile(R.id.form)
        if (graph.prefs.isMobile) root.findViewById<TextView>(R.id.playlists_hint).setText(R.string.playlists_hint_mobile)
        add.setOnClickListener { activity.push(AddPlaylistScreen(activity)) }
    }

    override fun onShown() {
        activity.hideVideo()
        graph.player.stop()
        reload()
    }

    override fun onBack(): Boolean = busy

    private fun reload() {
        scope.launch {
            val playlists = graph.repo.playlists()
            list.removeAllViews()
            val active = graph.prefs.activePlaylist
            for (p in playlists) list.addView(row(p, p.id == active))
            (list.getChildAt(playlists.indexOfFirst { it.id == active }.coerceAtLeast(0)) ?: add).requestFocus()
        }
    }

    private fun row(p: Playlist, active: Boolean): View {
        val v = LayoutInflater.from(activity).inflate(R.layout.row_text, list, false) as TextView
        val kind = when (p.kind) {
            Playlist.KIND_XTREAM -> "Xtream"
            Playlist.KIND_DEMO -> "Sample"
            else -> "M3U"
        }
        val lock = if (p.hasPin) "  ·  PIN" else ""
        v.text = if (active) "${p.name}  ·  $kind$lock  ·  active" else "${p.name}  ·  $kind$lock"
        v.setOnClickListener { open(p) }
        v.setOnLongClickListener { actions(p); true }
        v.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_DOWN) {
                actions(p)
                true
            } else {
                false
            }
        }
        return v
    }

    private fun open(p: Playlist) {
        if (busy) return
        graph.prefs.activePlaylist = p.id
        graph.prefs.lastLiveCategory = null
        graph.prefs.lastLiveItem = null
        activity.resetTo(HomeScreen(activity))
    }

    private fun actions(p: Playlist) {
        if (busy) return
        val labels = arrayOf(
            activity.getString(R.string.action_open),
            activity.getString(R.string.action_refresh),
            activity.getString(R.string.action_details),
            activity.getString(R.string.action_categories),
            activity.getString(R.string.action_delete),
        )
        AlertDialog.Builder(activity)
            .setTitle(p.name)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> open(p)
                    1 -> refresh(p)
                    2 -> PinPrompt.require(activity, p) { details(p) }
                    3 -> manageCategories(p)
                    4 -> PinPrompt.require(activity, p) { delete(p) }
                }
            }
            .show()
    }

    private fun refresh(p: Playlist) {
        busy = true
        activity.keepScreenOn(true) // a phone that sleeps mid-refresh drops the connection
        busyDots.visibility = View.VISIBLE
        message.setTextColor(activity.getColor(R.color.text_secondary))
        scope.launch {
            try {
                graph.syncer.sync(p) { text -> activity.runOnUiThread { message.text = text } }
                message.text = "${p.name} is up to date"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message.setTextColor(activity.getColor(R.color.error))
                message.text = e.message ?: "Refresh failed"
            } finally {
                busy = false
                activity.keepScreenOn(false)
                busyDots.visibility = View.GONE
            }
        }
    }

    /**
     * Which categories are shown: behind the playlist's PIN, so viewers can't change it. A
     * playlist without a PIN gets one set first; otherwise anyone could undo the choice.
     */
    private fun manageCategories(p: Playlist) {
        if (p.hasPin) {
            PinPrompt.require(activity, p) { activity.push(CategoriesScreen(activity, p)) }
            return
        }
        activity.toast(activity.getString(R.string.categories_need_pin))
        PinPrompt.askNew(activity, canRemove = false) { pin ->
            if (pin == null) return@askNew
            scope.launch {
                graph.repo.setPin(p.id, Pin.hash(pin))
                activity.toast(activity.getString(R.string.pin_saved))
                activity.push(CategoriesScreen(activity, graph.repo.playlist(p.id) ?: p))
            }
        }
    }

    /** Server, login and URL. Reached only through [PinPrompt] when the playlist has a PIN. */
    private fun details(p: Playlist) {
        val lines = if (p.isXtream) {
            listOf("Type: Xtream Codes", "Server: ${p.url}", "Username: ${p.username.orEmpty()}", "Password: ${p.password.orEmpty()}")
        } else {
            listOf("Type: M3U", "URL: ${p.url}")
        }
        AlertDialog.Builder(activity)
            .setTitle(p.name)
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(if (p.hasPin) R.string.pin_change else R.string.pin_set) { _, _ -> changePin(p) }
            .show()
    }

    private fun changePin(p: Playlist) {
        PinPrompt.askNew(activity, canRemove = p.hasPin) { pin ->
            scope.launch {
                graph.repo.setPin(p.id, pin?.let { Pin.hash(it) })
                activity.toast(activity.getString(if (pin == null) R.string.pin_removed else R.string.pin_saved))
                reload()
            }
        }
    }

    private fun delete(p: Playlist) {
        AlertDialog.Builder(activity)
            .setTitle("Delete ${p.name}?")
            .setPositiveButton(R.string.action_delete) { _, _ ->
                scope.launch {
                    graph.repo.deletePlaylist(p.id)
                    if (graph.prefs.activePlaylist == p.id) graph.prefs.activePlaylist = -1
                    if (graph.repo.playlists().isEmpty()) {
                        activity.resetTo(HomeScreen(activity))
                        activity.push(AddPlaylistScreen(activity, firstRun = true))
                    } else {
                        reload()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
