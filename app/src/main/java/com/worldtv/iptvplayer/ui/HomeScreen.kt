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
 * TV: the slider (5 recent movies, then 3 series, from data/spotlight) fills the screen behind
 * Live TV · Movies · Series; More info opens the title's page, Left / Right on it change slides,
 * Menu on it hides one (behind the playlist PIN). Slides change by themselves every 8 s.
 * Mobile: the tiles alone.
 *
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
    /** Mobile only: the tiles and apps scroll there. */
    private val scroll: View? = root.findViewById(R.id.home_scroll)
    private val busy: View = root.findViewById(R.id.busy)
    private val reloadStatus: TextView = root.findViewById(R.id.reload_status)
    private val reloadIcon: View = root.findViewById(R.id.home_reload)
    private val menuIcon: View = root.findViewById(R.id.home_menu)
    private var reloading = false

    /** App tiles shown (slot index → view), rebuilt whenever the apps may have changed. */
    private var slots: List<Pair<Int, View>> = emptyList()
    /** Tag of the tile that had focus when the screen was left (another app, a sub-screen). */
    private var lastFocusTag: String? = null
    private var shown = false

    // The slider (TV): declared before init, which sets it up.
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val heroViews: Array<ImageView> by lazy { arrayOf(root.findViewById(R.id.hero_back), root.findViewById(R.id.hero_front)) }
    private val heroMore: TextView by lazy { root.findViewById(R.id.hero_more) }
    private val heroDots: LinearLayout by lazy { root.findViewById(R.id.hero_dots) }
    private var slides: List<com.worldtv.iptvplayer.data.spotlight.Slide> = emptyList()
    private var slideIndex = 0
    /** Which of [heroViews] shows the current backdrop. */
    private var heroShowing = 0
    private var heroJob: kotlinx.coroutines.Job? = null
    private val advance = Runnable { if (shown && !heroMore.isFocused) showSlide(slideIndex + 1) }

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
        root.findViewById<TextView>(R.id.version)?.let {
            val version = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
            it.text = activity.getString(R.string.version_label, version)
        }
        if (!mobile) setUpSlider()
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
        if (!mobile) {
            graph.spotlight.refreshIfDue()
            scheduleAdvance()
        }
        // Update check: once per start, in the background, offered only on the home screen.
        AppUpdates.offerPending(activity, scope)
        AppUpdates.checkOnStart(activity, activity.lifecycleScope) { if (shown) AppUpdates.offerPending(activity, scope) }
    }


    override fun onHidden() {
        shown = false
        handler.removeCallbacks(advance)
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
        val scroll = scroll ?: return // TV: fixed tiles under the slider
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

    /** "Valid Till: 10-Aug-2027" for an Xtream account; nothing for playlists without an end. */
    private suspend fun showAccount() {
        val p = graph.repo.playlist(graph.prefs.activePlaylist)
        val expires = p?.expires?.takeIf { it > 0 }
        account.text = expires?.let {
            activity.getString(R.string.valid_till, java.text.SimpleDateFormat("dd-MMM-yyyy", java.util.Locale.ENGLISH).format(java.util.Date(it * 1000)))
        }.orEmpty()
        account.visibility = if (expires == null) View.GONE else View.VISIBLE
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

    // ---- the slider (TV) ----


    private fun setUpSlider() {
        val logo = root.findViewById<TextView>(R.id.home_logo)
        logo.text = android.text.SpannableString("WorldTV").apply {
            setSpan(android.text.style.ForegroundColorSpan(activity.themeColor(R.attr.wtPressed)), 5, 7, 0)
        }
        bigTiles.forEach { tile ->
            val t = tile as TextView
            t.compoundDrawablesRelative[0]?.let { icon ->
                icon.setBounds(0, 0, dp(34), dp(34))
                t.setCompoundDrawablesRelative(icon, null, null, null)
            }
            // Icon and name together in the middle (a TextView puts its icon at the edge).
            t.addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ ->
                val tv = v as TextView
                val content = dp(34) + tv.compoundDrawablePadding + tv.paint.measureText(tv.text.toString()).toInt()
                val side = ((r - l - content) / 2).coerceAtLeast(dp(12))
                if (tv.paddingStart != side) tv.post { tv.setPaddingRelative(side, tv.paddingTop, side, tv.paddingBottom) }
            }
            t.setOnFocusChangeListener { v, focused ->
                v.animate().scaleX(if (focused) 1.04f else 1f).scaleY(if (focused) 1.04f else 1f).setDuration(150).start()
            }
        }
        heroMore.tag = "more"
        heroMore.setOnClickListener { openSlide() }
        heroMore.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { showSlide(slideIndex - 1); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { showSlide(slideIndex + 1); true }
                KeyEvent.KEYCODE_MENU -> { hideSlide(); true }
                else -> false
            }
        }
        heroMore.setOnLongClickListener { hideSlide(); true }
        heroMore.setOnFocusChangeListener { _, focused -> if (!focused) scheduleAdvance() }
        heroMore.nextFocusDownId = live.id
        heroMore.nextFocusUpId = reloadIcon.id
        scope.launch { graph.spotlight.slides.collect { showSlides(it) } }
    }

    private fun showSlides(list: List<com.worldtv.iptvplayer.data.spotlight.Slide>) {
        val current = slides.getOrNull(slideIndex)?.key
        slides = list
        val on = list.isNotEmpty()
        listOf(R.id.hero_info, R.id.hero_dots, R.id.hero_shade_left, R.id.hero_fade).forEach {
            root.findViewById<View>(it).visibility = if (on) View.VISIBLE else View.GONE
        }
        root.findViewById<View>(R.id.home_bottom_space).visibility = if (on) View.GONE else View.VISIBLE
        bigTiles.forEach { it.nextFocusUpId = if (on) heroMore.id else reloadIcon.id }
        if (!on) {
            heroViews.forEach { it.animate().cancel(); it.alpha = 0f; it.setImageDrawable(null) }
            if (heroMore.isFocused) live.requestFocus()
            return
        }
        heroDots.removeAllViews()
        for (i in list.indices) {
            val dot = View(activity)
            val lp = LinearLayout.LayoutParams(dp(6), dp(6))
            // A small gap between the movies and the series.
            if (i > 0) lp.marginStart = dp(if (list[i].type != list[i - 1].type) 14 else 7)
            heroDots.addView(dot, lp)
        }
        showSlide(list.indexOfFirst { it.key == current }.coerceAtLeast(0), animate = false)
    }

    private fun showSlide(index: Int, animate: Boolean = true) {
        if (slides.isEmpty()) return
        slideIndex = ((index % slides.size) + slides.size) % slides.size
        val slide = slides[slideIndex]
        root.findViewById<TextView>(R.id.hero_title).text = slide.title
        root.findViewById<TextView>(R.id.hero_meta).text = listOfNotNull(slide.year, slide.genre, slide.rating?.let { "★ $it" }).joinToString("  ·  ")
        root.findViewById<TextView>(R.id.hero_plot).text = slide.overview
        for (i in 0 until heroDots.childCount) {
            val dot = heroDots.getChildAt(i)
            dot.layoutParams = dot.layoutParams.apply { width = dp(if (i == slideIndex) 22 else 6) }
            dot.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(3).toFloat()
                setColor(if (i == slideIndex) 0xE6FFFFFF.toInt() else 0x4DFFFFFF)
            }
        }
        val file = graph.spotlight.imageFile(slide)
        val width = activity.resources.displayMetrics.widthPixels
        heroJob?.cancel()
        heroJob = scope.launch {
            val bitmap = withContext(graph.io) { decodeBackdrop(file, width) } ?: return@launch
            val next = 1 - heroShowing
            val incoming = heroViews[next]
            val outgoing = heroViews[heroShowing]
            incoming.setImageBitmap(bitmap)
            heroShowing = next
            if (animate && outgoing.alpha > 0f) {
                incoming.animate().alpha(1f).setDuration(FADE_MS).withLayer().start()
                outgoing.animate().alpha(0f).setDuration(FADE_MS).withLayer().withEndAction { outgoing.setImageDrawable(null) }.start()
            } else {
                incoming.alpha = 1f
                outgoing.alpha = 0f
                outgoing.setImageDrawable(null)
            }
        }
        scheduleAdvance()
    }

    /** The stored backdrop at the screen's width, 16 bits a pixel (half the memory, no visible loss on video-like images). */
    private fun decodeBackdrop(file: java.io.File, width: Int): android.graphics.Bitmap? {
        if (!file.exists()) return null
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width) sample *= 2
        return android.graphics.BitmapFactory.decodeFile(file.path, android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
        })
    }

    private fun scheduleAdvance() {
        handler.removeCallbacks(advance)
        if (!mobile && shown && slides.size > 1) handler.postDelayed(advance, SLIDE_MS)
    }

    /** More info: the title's page (play from there). */
    private fun openSlide() {
        val slide = slides.getOrNull(slideIndex) ?: return
        scope.launch {
            val p = graph.repo.playlist(graph.prefs.activePlaylist) ?: return@launch
            val row = graph.repo.entryRow(p.id, slide.type, slide.itemId)
                ?: return@launch activity.toast(activity.getString(R.string.spotlight_gone))
            activity.push(VodDetailScreen(activity, p, slide.type, row) {})
        }
    }

    /** Menu on More info: takes the title off the slider for good (the admin's PIN first). */
    private fun hideSlide() {
        val slide = slides.getOrNull(slideIndex) ?: return
        scope.launch {
            val p = graph.repo.playlist(graph.prefs.activePlaylist) ?: return@launch
            AlertDialog.Builder(activity)
                .setTitle(slide.title)
                .setItems(arrayOf(activity.getString(R.string.spotlight_hide))) { _, _ ->
                    PinPrompt.require(activity, p) {
                        graph.spotlight.hide(slide)
                        activity.toast(activity.getString(R.string.spotlight_hidden))
                    }
                }
                .show()
        }
    }

    private companion object {
        const val SLIDE_MS = 8_000L
        const val FADE_MS = 700L
        const val COLUMNS = 5
        const val CELL_HEIGHT = 80
        const val ROW_GAP = 12
    }
}
