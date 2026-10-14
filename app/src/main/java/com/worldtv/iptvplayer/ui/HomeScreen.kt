package com.worldtv.iptvplayer.ui

import android.app.AlertDialog
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.worldtv.iptvplayer.MainActivity
import com.worldtv.iptvplayer.R
import com.worldtv.iptvplayer.data.model.ContentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Live TV · Movies · Series, with small Reload playlist and menu (⋮) icons top right; the menu
 * holds App Settings, All apps and System settings (TV: also Add app to home screen). Below, the
 * user's own apps in rows of 5, only when some are added (App Settings > Home app slots, none
 * by default): no empty "+" slots. The big tiles shrink to fit; extra rows scroll in on focus.
 * WorldTV can be the box's launcher, so focus comes back to the tile the user left from.
 */
class HomeScreen(activity: MainActivity) : Screen(activity) {

    private val mobile = graph.prefs.isMobile
    override val root: View = inflater.inflate(if (mobile) R.layout.screen_home_mobile else R.layout.screen_home, null)
    private val account: TextView = root.findViewById(R.id.account)
    private val live: View = root.findViewById(R.id.tile_live)
    private val bigTiles: List<View> = listOf(live, root.findViewById(R.id.tile_movies), root.findViewById(R.id.tile_series))
    private val rows: LinearLayout = root.findViewById(R.id.home_rows)
    private val scroll: View = root.findViewById(R.id.home_scroll)
    private val busy: View = root.findViewById(R.id.busy)
    private val reloadStatus: TextView = root.findViewById(R.id.reload_status)
    private val reloadIcon: View = root.findViewById(R.id.home_reload)
    private val menuIcon: View = root.findViewById(R.id.home_menu)
    private var reloading = false

    /** App tiles shown (slot index → view), rebuilt whenever the apps may have changed. */
    private var slots: List<Pair<Int, View>> = emptyList()
    /** Tag of the tile that had focus when the screen was left (another app, a sub-screen). */
    private var lastFocusTag: String? = null

    init {
        live.tag = "live"
        bigTiles[1].tag = "movies"
        bigTiles[2].tag = "series"
        live.setOnClickListener { withPlaylist { activity.push(LiveScreen(activity)) } }
        bigTiles[1].setOnClickListener { withPlaylist { activity.push(VodScreen(activity, ContentType.MOVIE)) } }
        bigTiles[2].setOnClickListener { withPlaylist { activity.push(VodScreen(activity, ContentType.SERIES)) } }
        if (mobile) bigTiles.forEach { shrinkIcon(it as TextView) }
        reloadIcon.tag = "reload"
        menuIcon.tag = "menu"
        reloadIcon.setOnClickListener { reloadPlaylist() }
        menuIcon.setOnClickListener { showMenu() }
        // Up from the big tiles reaches the icons top right.
        bigTiles.forEach { it.nextFocusUpId = reloadIcon.id }
        val version = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
        root.findViewById<TextView>(R.id.version).text = activity.getString(R.string.version_label, version)
    }

    override fun onShown() {
        activity.hideVideo()
        graph.player.stop()
        // Back to where the user was (e.g. the app slot they opened), else Live TV.
        (lastFocusTag?.let { root.findViewWithTag<View>(it) } ?: live).requestFocus()
        scope.launch { showAccount() }
        scope.launch { showSlots() } // an app may have been installed or removed meanwhile
        scope.launch { autoRefresh() }
        graph.channelCheck.requestDue() // overdue after the box was off; runs only when idle
        shown = true
        // Update check: once per start, in the background, offered only on the home screen.
        AppUpdates.offerPending(activity, scope)
        AppUpdates.checkOnStart(activity, activity.lifecycleScope) { if (shown) AppUpdates.offerPending(activity, scope) }
    }

    private var shown = false

    override fun onHidden() {
        shown = false
        val focused = activity.currentFocus
        if (focused != null && focused.tag is String && isInside(focused)) lastFocusTag = focused.tag as String
    }

    private fun isInside(v: View): Boolean {
        var p: Any? = v
        while (p is View) {
            if (p === root) return true
            p = p.parent
        }
        return false
    }

    // ---- rows ----

    /** Mobile tiles: the 88dp TV icon drawn at 52dp beside the label. */
    private fun shrinkIcon(tile: TextView) {
        val icon = tile.compoundDrawablesRelative[0] ?: return
        icon.setBounds(0, 0, dp(52), dp(52))
        tile.setCompoundDrawablesRelative(icon, null, null, null)
    }

    /** The app tiles, rows of 5 (TV only). [apps]: slot index and its app, in order. */
    private fun buildRows(apps: List<Pair<Int, LaunchableApp>>) {
        val focusedTag = (activity.currentFocus?.tag as? String)?.takeIf { activity.currentFocus?.let(::isInside) == true }
        rows.removeAllViews()
        val list = ArrayList<Pair<Int, View>>()
        val rowCount = (apps.size + COLUMNS - 1) / COLUMNS
        for (r in 0 until rowCount) {
            val row = newRow(r)
            for (col in 0 until COLUMNS) {
                val k = r * COLUMNS + col
                if (k < apps.size) list += apps[k].first to addSlot(row, col, apps[k].first, apps[k].second) else addSpace(row, col)
            }
        }
        slots = list
        // Down from any big tile goes to the first app (when there is one).
        bigTiles.forEach { it.nextFocusDownId = list.firstOrNull()?.second?.id ?: View.NO_ID }
        rows.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        fitBigTiles(rowCount)
        focusedTag?.let { tag -> root.findViewWithTag<View>(tag)?.requestFocus() }
    }

    private fun newRow(index: Int): LinearLayout {
        val row = LinearLayout(activity)
        row.orientation = LinearLayout.HORIZONTAL
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        if (index > 0) lp.topMargin = dp(ROW_GAP)
        rows.addView(row, lp)
        return row
    }

    /**
     * Big tiles take what's left after the rows: 250dp when there is room, down to 170dp. Only if
     * even that doesn't fit (small screens with many apps) does the area scroll.
     */
    private fun fitBigTiles(rowCount: Int) {
        scroll.post {
            val rowsHeight = if (rowCount == 0) 0 else rowCount * dp(CELL_HEIGHT) + (rowCount - 1) * dp(ROW_GAP) + dp(24 + 16)
            val target = (scroll.height - rowsHeight).coerceIn(dp(170), dp(if (rowCount == 0) 300 else 250))
            val top = if (target < dp(220)) dp(20) else dp(44) // keeps icon + label centred
            bigTiles.forEach {
                it.layoutParams = it.layoutParams.apply { height = target }
                it.setPadding(it.paddingLeft, top, it.paddingRight, it.paddingBottom)
            }
        }
    }

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

    private fun cellParams(col: Int) = LinearLayout.LayoutParams(0, dp(CELL_HEIGHT), 1f).apply {
        if (col > 0) marginStart = dp(14)
    }

    private fun addSpace(row: LinearLayout, col: Int) {
        row.addView(Space(activity), cellParams(col))
    }

    private fun addSlot(row: LinearLayout, col: Int, index: Int, app: LaunchableApp): View {
        val v = inflater.inflate(R.layout.row_app_slot, row, false)
        v.id = View.generateViewId()
        v.tag = "slot:$index"
        v.setOnClickListener { Apps.launch(activity, app.pkg) }
        v.setOnLongClickListener { slotMenu(index, app); true }
        v.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_DOWN) { slotMenu(index, app); true } else false
        }
        val icon = v.findViewById<ImageView>(R.id.app_icon)
        val label = v.findViewById<TextView>(R.id.app_label)
        icon.imageTintList = null
        if (app.banner != null) {
            // TV banners already carry the app's name.
            icon.setImageDrawable(app.banner)
            label.visibility = View.GONE
        } else {
            icon.setImageDrawable(app.icon)
            label.text = app.label
        }
        v.contentDescription = app.label
        row.addView(v, cellParams(col))
        return v
    }

    /** ⋮ top right: App Settings, All apps, System settings (TV: and adding an app to this screen). */
    private fun showMenu() {
        val menu = android.widget.PopupMenu(activity, menuIcon)
        menu.menu.add(0, 1, 0, R.string.settings)
        if (!mobile) menu.menu.add(0, 4, 1, R.string.add_app_home)
        menu.menu.add(0, 2, 2, R.string.all_apps)
        menu.menu.add(0, 3, 3, R.string.android_settings_short)
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> activity.push(SettingsScreen(activity))
                2 -> showAllApps()
                3 -> Apps.openAndroidSettings(activity)
                4 -> addApp()
            }
            true
        }
        menu.show()
    }

    /** Opens a library, or the playlist screens first if there is no playlist yet. */
    private fun withPlaylist(open: () -> Unit) {
        scope.launch {
            val playlists = graph.repo.playlists()
            when {
                playlists.isEmpty() -> activity.push(AddPlaylistScreen(activity, firstRun = true))
                playlists.none { it.id == graph.prefs.activePlaylist } -> activity.push(PlaylistsScreen(activity))
                else -> open()
            }
        }
    }

    // ---- app slots ----

    /** The slots that hold an installed app, loaded off the UI thread (icons and banners come from disk). */
    private suspend fun showSlots() {
        if (mobile) return
        val count = graph.prefs.appSlotCount
        val apps = withContext(graph.io) {
            (0 until count).mapNotNull { i -> graph.prefs.appSlot(i)?.let { pkg -> Apps.info(activity, pkg)?.let { i to it } } }
        }
        buildRows(apps)
    }

    private fun slotMenu(i: Int, app: LaunchableApp) {
        AlertDialog.Builder(activity)
            .setTitle(app.label)
            .setItems(arrayOf(activity.getString(R.string.slot_change), activity.getString(R.string.slot_remove))) { _, which ->
                if (which == 0) {
                    chooseApp(i)
                } else {
                    graph.prefs.setAppSlot(i, null)
                    scope.launch { showSlots() }
                }
            }
            .show()
    }

    /** Menu > Add app to home screen: into the first free slot, adding one if they are all used. */
    private fun addApp() {
        val prefs = graph.prefs
        val free = (0 until prefs.appSlotCount).firstOrNull { prefs.appSlot(it) == null }
        val index = free ?: prefs.appSlotCount
        if (index >= com.worldtv.iptvplayer.Prefs.MAX_SLOTS) return activity.toast(activity.getString(R.string.slots_full))
        chooseApp(index)
    }

    /** Every installed app; OK opens it. */
    private fun showAllApps() {
        scope.launch {
            val apps = withContext(graph.io) { Apps.list(activity) }
            Apps.pick(activity, R.string.all_apps, apps) { Apps.launch(activity, it.pkg) }
        }
    }

    private fun chooseApp(i: Int) {
        scope.launch {
            val apps = withContext(graph.io) { Apps.list(activity) }
            Apps.pick(activity, R.string.choose_app, apps) { app ->
                graph.prefs.setAppSlot(i, app.pkg)
                if (i >= graph.prefs.appSlotCount) graph.prefs.appSlotCount = i + 1
                scope.launch { showSlots() }
            }
        }
    }

    private suspend fun showAccount() {
        val p = graph.repo.playlist(graph.prefs.activePlaylist) ?: return
        val parts = arrayListOf(p.name)
        p.expires?.let { parts += "Expires " + Dates.day(it * 1000) }
        if (p.maxConnections > 0) parts += "${p.maxConnections} connection" + if (p.maxConnections > 1) "s" else ""
        account.text = parts.joinToString("  ·  ")
    }

    /**
     * Settings > Automatic refresh: reloads the active playlist when it is due (every start, every
     * day, or every 2 days). Checked whenever the home screen shows, so a box left on for days
     * still refreshes. Runs like Reload playlist: in the background, lists usable meanwhile.
     */
    private suspend fun autoRefresh() {
        if (reloading || graph.syncing) return
        val p = graph.repo.playlist(graph.prefs.activePlaylist) ?: return
        val due = com.worldtv.iptvplayer.AutoRefresh.isDue(
            graph.prefs.autoRefresh, p.lastSync, System.currentTimeMillis(), p.id in graph.autoRefreshedThisRun,
        )
        if (!due) return
        android.util.Log.i("WorldTV.Refresh", "auto-refresh ${p.name} (${graph.prefs.autoRefresh}, last ${if (p.lastSync > 0) Dates.dayTime(p.lastSync) else "never"})")
        graph.autoRefreshedThisRun += p.id
        reloadPlaylist(auto = true)
    }

    /**
     * Downloads the active playlist again: channels, movies, series and their links.
     * Favourites and Continue watching are kept. The old lists stay usable until it finishes.
     */
    private fun reloadPlaylist(auto: Boolean = false) {
        if (reloading || graph.syncing) return
        reloading = true
        graph.syncing = true
        activity.keepScreenOn(true) // a phone that sleeps mid-reload drops the connection
        busy.visibility = View.VISIBLE
        reloadStatus.setTextColor(activity.themeColor(R.attr.wtTextSecondary))
        reloadStatus.setText(if (auto) R.string.auto_refreshing else R.string.reloading)
        // App-wide, not this screen's: a rebuilt UI (e.g. a phone turning to landscape for the
        // TV layout) must not cut a load off half-way.
        graph.appScope.launch {
            try {
                val p = graph.repo.playlist(graph.prefs.activePlaylist)
                if (p == null) {
                    reloadStatus.setText(R.string.no_playlist_yet)
                    return@launch
                }
                graph.syncer.sync(p) { text -> activity.runOnUiThread { reloadStatus.text = text } }
                graph.autoRefreshedThisRun += p.id
                reloadStatus.setText(if (auto) R.string.auto_refresh_done else R.string.reload_done)
                showAccount()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reloadStatus.setTextColor(activity.getColor(R.color.error))
                reloadStatus.text = activity.getString(R.string.reload_failed, e.message ?: e.javaClass.simpleName)
            } finally {
                reloading = false
                graph.syncing = false
                activity.keepScreenOn(false)
                busy.visibility = View.GONE
            }
        }
    }

    private companion object {
        const val COLUMNS = 5
        const val CELL_HEIGHT = 80
        const val ROW_GAP = 12
    }
}
