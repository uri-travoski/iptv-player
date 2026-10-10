package io.github.sardinemehico.iptvplayer.ui

import android.app.AlertDialog
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import io.github.sardinemehico.iptvplayer.MainActivity
import io.github.sardinemehico.iptvplayer.R
import io.github.sardinemehico.iptvplayer.data.model.ContentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Live TV · Movies · Series on top. Below, rows of 5: the first holds the user's first app and,
 * always on the right, Reload playlist, App Settings, All apps and System settings; more apps
 * (1..14 slots, set in App Settings) continue in rows of 5 underneath. The big tiles shrink to
 * fit; extra rows scroll into view on focus.
 * WorldTV can be the box's launcher, so focus comes back to the tile the user left from.
 * Mobile layout: the three tiles stacked, then only Reload playlist and App Settings.
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
    private var reloading = false

    /** Slot views in order, rebuilt when the slot count changes. */
    private var slots: List<View> = emptyList()
    private var builtCount = -1
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
        val version = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
        root.findViewById<TextView>(R.id.version).text = activity.getString(R.string.version_label, version)
    }

    override fun onShown() {
        activity.hideVideo()
        graph.player.stop()
        val count = graph.prefs.appSlotCount
        if (count != builtCount) buildRows(count)
        // Back to where the user was (e.g. the app slot they opened), else Live TV.
        (lastFocusTag?.let { root.findViewWithTag<View>(it) } ?: live).requestFocus()
        scope.launch { showAccount() }
        scope.launch { showSlots() } // an app may have been installed or removed meanwhile
        scope.launch { autoRefresh() }
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

    private fun buildRows(count: Int) {
        builtCount = count
        rows.removeAllViews()
        if (mobile) {
            val row = newRow(0)
            addFixed(row, 0, "reload", R.drawable.ic_refresh, R.string.reload_playlist) { reloadPlaylist() }
            addFixed(row, 1, "settings", R.drawable.ic_settings_small, R.string.settings) { activity.push(SettingsScreen(activity)) }
            slots = emptyList()
            slotApps = emptyList()
            return
        }
        val list = ArrayList<View>()
        // First row: the user's first app, then the four fixed tiles, always on the right.
        val first = newRow(0)
        list += addSlot(first, 0, 0)
        addFixed(first, 1, "reload", R.drawable.ic_refresh, R.string.reload_playlist) { reloadPlaylist() }
        addFixed(first, 2, "settings", R.drawable.ic_settings_small, R.string.settings) { activity.push(SettingsScreen(activity)) }
        addFixed(first, 3, "allapps", R.drawable.ic_all_apps, R.string.all_apps) { showAllApps() }
        addFixed(first, 4, "system", R.drawable.ic_android_settings, R.string.android_settings_short) { Apps.openAndroidSettings(activity) }
        // Any more apps: rows of 5 underneath.
        val more = count - 1
        val moreRows = (more + COLUMNS - 1) / COLUMNS
        for (r in 0 until moreRows) {
            val row = newRow(r + 1)
            for (col in 0 until COLUMNS) {
                val index = 1 + r * COLUMNS + col
                if (index < count) list += addSlot(row, col, index) else addSpace(row, col)
            }
        }
        slots = list
        slotApps = emptyList() // filled by showSlots()

        // Down from any big tile goes to the first app slot, not the one under it.
        bigTiles.forEach { it.nextFocusDownId = list.first().id }
        fitBigTiles(1 + moreRows)
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
     * even that doesn't fit (small screens with many slots) does the area scroll.
     */
    private fun fitBigTiles(rowCount: Int) {
        scroll.post {
            val rowsHeight = rowCount * dp(CELL_HEIGHT) + (rowCount - 1) * dp(ROW_GAP) + dp(24 + 16)
            val target = (scroll.height - rowsHeight).coerceIn(dp(170), dp(250))
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

    private fun addSlot(row: LinearLayout, col: Int, index: Int): View {
        val v = inflater.inflate(R.layout.row_app_slot, row, false)
        v.id = View.generateViewId()
        v.tag = "slot:$index"
        v.setOnClickListener { onSlotClicked(index) }
        v.setOnLongClickListener { slotMenu(index); true }
        v.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_DOWN) { slotMenu(index); true } else false
        }
        row.addView(v, cellParams(col))
        return v
    }

    private fun addFixed(row: LinearLayout, col: Int, tag: String, icon: Int, label: Int, onClick: () -> Unit) {
        val v = inflater.inflate(R.layout.row_app_slot, row, false)
        v.id = View.generateViewId()
        v.tag = tag
        v.findViewById<ImageView>(R.id.app_icon).apply {
            setImageResource(icon)
            imageTintList = activity.getColorStateList(R.color.slot_content)
        }
        v.findViewById<TextView>(R.id.app_label).setText(label)
        v.contentDescription = activity.getString(label)
        v.setOnClickListener { onClick() }
        row.addView(v, cellParams(col))
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

    private var slotApps: List<LaunchableApp?> = emptyList()

    private suspend fun showSlots() {
        val views = slots
        val packages = List(views.size) { graph.prefs.appSlot(it) }
        // Icons and banners come from disk: load them off the UI thread.
        val apps = withContext(graph.io) { packages.map { pkg -> pkg?.let { Apps.info(activity, it) } } }
        if (views !== slots) return // rows rebuilt meanwhile; that rebuild loads its own
        slotApps = apps
        views.forEachIndexed { i, v ->
            val icon = v.findViewById<ImageView>(R.id.app_icon)
            val label = v.findViewById<TextView>(R.id.app_label)
            val app = slotApps[i]
            when {
                app == null -> {
                    icon.setImageResource(R.drawable.ic_add)
                    icon.imageTintList = activity.getColorStateList(R.color.slot_content)
                    label.setText(R.string.add_app)
                    label.visibility = View.VISIBLE
                }
                app.banner != null -> {
                    // TV banners already carry the app's name.
                    icon.imageTintList = null
                    icon.setImageDrawable(app.banner)
                    label.visibility = View.GONE
                }
                else -> {
                    icon.imageTintList = null
                    icon.setImageDrawable(app.icon)
                    label.text = app.label
                    label.visibility = View.VISIBLE
                }
            }
            v.contentDescription = app?.label ?: activity.getString(R.string.add_app)
        }
    }

    private fun onSlotClicked(i: Int) {
        if (slotApps.isEmpty()) return // still loading the slot's app
        val app = slotApps.getOrNull(i)
        if (app != null) Apps.launch(activity, app.pkg) else chooseApp(i)
    }

    private fun slotMenu(i: Int) {
        if (slotApps.isEmpty()) return
        val app = slotApps.getOrNull(i) ?: return chooseApp(i)
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
        val due = io.github.sardinemehico.iptvplayer.AutoRefresh.isDue(
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
        reloadStatus.setTextColor(activity.getColor(R.color.text_secondary))
        reloadStatus.setText(if (auto) R.string.auto_refreshing else R.string.reloading)
        scope.launch {
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
