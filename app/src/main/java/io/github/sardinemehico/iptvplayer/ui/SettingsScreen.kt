package io.github.sardinemehico.iptvplayer.ui

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import coil3.SingletonImageLoader
import io.github.sardinemehico.iptvplayer.AutoRefresh
import io.github.sardinemehico.iptvplayer.MainActivity
import io.github.sardinemehico.iptvplayer.Prefs
import io.github.sardinemehico.iptvplayer.R
import io.github.sardinemehico.iptvplayer.data.model.ContentType
import io.github.sardinemehico.iptvplayer.data.net.AppDns
import io.github.sardinemehico.iptvplayer.data.repo.Playlist
import io.github.sardinemehico.iptvplayer.data.repo.Sort
import io.github.sardinemehico.iptvplayer.data.sync.Demo
import io.github.sardinemehico.iptvplayer.player.SubtitleStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings: one screen of tiles, icon and name, as in WorldTV2 (4 across on TV, 2 on a phone).
 * Tiles that switch something show the current value in their name; the others open a short
 * choice. Playlists themselves are managed on their own screen (the Playlists tile).
 */
class SettingsScreen(activity: MainActivity) : Screen(activity) {

    override val root: View = inflater.inflate(R.layout.screen_settings, null)
    private val grid: LinearLayout = root.findViewById(R.id.grid)
    private val status: TextView = root.findViewById(R.id.status)
    private val prefs get() = graph.prefs
    private val mobile = prefs.isMobile

    /**
     * One tile: its name, and under it (smaller) what is set now. Both are re-read on every
     * refresh, so values stay current.
     */
    private class Tile(
        val tag: String,
        val icon: Int,
        val label: () -> String,
        val value: (() -> String)? = null,
        val onKey: ((Int) -> Boolean)? = null,
        val onClick: () -> Unit,
    )

    private val tiles = ArrayList<Tile>()
    private val views = HashMap<String, TextView>()

    init {
        if (mobile) {
            val side = (16 * activity.resources.displayMetrics.density).toInt()
            root.setPadding(side, root.paddingTop, side, root.paddingBottom)
        }
        buildTiles()
        layoutTiles()
    }

    override fun onShown() {
        activity.hideVideo()
        graph.player.stop()
        refresh() // the launcher or overlay permission may have changed in system settings meanwhile
        if (activity.currentFocus == null || !isInside(activity.currentFocus!!)) views[tiles.first().tag]?.requestFocus()
    }

    private fun isInside(v: View): Boolean {
        var p: Any? = v
        while (p is View) {
            if (p === root) return true
            p = p.parent
        }
        return false
    }

    // ---- the tiles ----

    private fun buildTiles() {
        fun s(id: Int, vararg args: Any) = activity.getString(id, *args)
        tiles += Tile("playlists", R.drawable.ic_s_list, { s(R.string.set_playlists) }) { activity.push(PlaylistsScreen(activity)) }
        tiles += Tile("add", R.drawable.ic_s_add, { s(R.string.add_playlist) }) { activity.push(AddPlaylistScreen(activity)) }
        tiles += Tile("categories", R.drawable.ic_s_hide, { s(R.string.action_categories) }) { openCategories() }
        tiles += Tile("live_sort", R.drawable.ic_s_sort, { s(R.string.live_sort_title) }, { liveSortLabel(prefs.liveSort) }) { chooseLiveSort() }
        tiles += Tile("auto_refresh", R.drawable.ic_s_autorenew, { s(R.string.auto_refresh_title) }, { autoRefreshLabel(prefs.autoRefresh) }) { chooseAutoRefresh() }
        tiles += Tile("history", R.drawable.ic_s_history, { s(R.string.set_clear_history) }) { clearHistory() }
        tiles += Tile("subtitles", R.drawable.ic_s_subtitles, { s(R.string.set_subtitles) }) { subtitleSettings() }
        tiles += Tile("layout", R.drawable.ic_s_layout, { s(R.string.set_layout) }, { s(if (mobile) R.string.set_value_mobile else R.string.set_value_tv) }) {
            prefs.uiMode = if (mobile) Prefs.UI_TV else Prefs.UI_MOBILE
            activity.restartUi(thenSettings = true)
        }
        if (!mobile) {
            tiles += Tile("auto_start", R.drawable.ic_s_power, { s(R.string.set_auto_start) }, { s(if (prefs.autoStart) R.string.set_on else R.string.set_off) }) { toggleAutoStart() }
            tiles += Tile(
                "slots", R.drawable.ic_s_apps, { s(R.string.set_slots) }, { "‹ ${prefs.appSlotCount} ›" },
                onKey = { key ->
                    when (key) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> { changeSlots(-1, wrap = false); true }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> { changeSlots(+1, wrap = false); true }
                        else -> false
                    }
                },
            ) { changeSlots(+1, wrap = true) }
            tiles += Tile("home", R.drawable.ic_s_home, { s(R.string.set_home) }, { s(if (Apps.isDefaultHome(activity)) R.string.set_home_is else R.string.set_home_make) }) {
                if (Apps.isDefaultHome(activity)) Apps.changeDefaultHome(activity) else Apps.requestDefaultHome(activity) { refresh() }
            }
        }
        tiles += Tile("dns", R.drawable.ic_s_dns, { s(R.string.set_dns) }, { s(dnsLabel()) }) { prefs.dnsMode = (prefs.dnsMode + 1) % 3; refresh() }
        tiles += Tile("auto_update", R.drawable.ic_s_download, { s(R.string.set_auto_update) }, { s(if (prefs.autoUpdate) R.string.set_on else R.string.set_off) }) {
            prefs.autoUpdate = !prefs.autoUpdate
            refresh()
        }
        tiles += Tile("update_now", R.drawable.ic_s_update, { s(R.string.set_update_now) }) { AppUpdates.checkNow(activity, scope) }
        if (!mobile) {
            tiles += Tile("all_apps", R.drawable.ic_s_apps, { s(R.string.all_apps) }) {
                scope.launch {
                    val apps = withContext(graph.io) { Apps.list(activity) }
                    Apps.pick(activity, R.string.all_apps, apps) { Apps.launch(activity, it.pkg) }
                }
            }
            tiles += Tile("system", R.drawable.ic_s_settings, { s(R.string.android_settings) }) { Apps.openAndroidSettings(activity) }
        }
        tiles += Tile("help", R.drawable.ic_s_help, { s(R.string.help) }) { activity.push(HelpScreen(activity)) }
        tiles += Tile("reset", R.drawable.ic_s_reset, { s(R.string.set_reset) }) { confirmReset() }
    }

    private fun layoutTiles() {
        val columns = if (mobile) 2 else 4
        val d = activity.resources.displayMetrics.density
        var row: LinearLayout? = null
        tiles.forEachIndexed { i, tile ->
            if (i % columns == 0) {
                // Not baseline-aligned: tiles with one or two lines of text stay level.
                row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
                grid.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    if (i > 0) topMargin = (14 * d).toInt()
                })
            }
            val v = TextView(activity, null, 0, R.style.SettingsTile)
            v.tag = tile.tag
            v.isFocusable = true
            v.setCompoundDrawablesRelativeWithIntrinsicBounds(tile.icon, 0, 0, 0)
            v.setOnClickListener { tile.onClick() }
            tile.onKey?.let { k -> v.setOnKeyListener { _, code, e -> e.action == KeyEvent.ACTION_DOWN && k(code) } }
            row!!.addView(v, tileParams(i % columns, d))
            views[tile.tag] = v
        }
        // Fill the last row so tiles keep their width.
        val left = (columns - tiles.size % columns) % columns
        for (k in 0 until left) row!!.addView(Space(activity), tileParams(columns - left + k, d))
        refresh()
    }

    private fun tileParams(col: Int, d: Float) =
        LinearLayout.LayoutParams(0, (if (mobile) 84 else 76).times(d).toInt(), 1f).apply { if (col > 0) marginStart = (14 * d).toInt() }

    private fun refresh() {
        val dim = activity.getColor(R.color.text_secondary)
        for (t in tiles) {
            val name = t.label()
            val value = t.value?.invoke()
            views[t.tag]?.text = if (value == null) name else android.text.SpannableString("$name\n$value").apply {
                setSpan(android.text.style.RelativeSizeSpan(0.82f), name.length + 1, name.length + 1 + value.length, 0)
                setSpan(android.text.style.ForegroundColorSpan(dim), name.length + 1, name.length + 1 + value.length, 0)
            }
        }
        val lines = ArrayList<String>()
        if (!mobile && prefs.autoStart) lines += autoStartStatus()
        val version = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
        lines += activity.getString(R.string.version_label, version)
        status.text = lines.joinToString("\n")
    }

    // ---- simple settings ----

    private fun dnsLabel() = when (prefs.dnsMode) {
        AppDns.MODE_CLOUDFLARE -> R.string.set_dns_cloudflare
        AppDns.MODE_GOOGLE -> R.string.set_dns_google
        else -> R.string.set_dns_system
    }

    private fun changeSlots(delta: Int, wrap: Boolean) {
        var n = prefs.appSlotCount + delta
        if (n > Prefs.MAX_SLOTS) n = if (wrap) Prefs.MIN_SLOTS else Prefs.MAX_SLOTS
        if (n < Prefs.MIN_SLOTS) n = Prefs.MIN_SLOTS
        prefs.appSlotCount = n
        refresh()
    }

    private fun canLaunchAtBoot() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(activity)

    private fun autoStartStatus(): String {
        val lines = ArrayList<String>()
        if (!canLaunchAtBoot()) lines += activity.getString(R.string.auto_start_blocked, activity.packageName)
        val at = prefs.lastBootAt
        lines += if (at == 0L) {
            activity.getString(R.string.auto_start_never)
        } else {
            activity.getString(if (prefs.lastBootAllowed) R.string.auto_start_last_boot else R.string.auto_start_last_boot_blocked, Dates.dayTime(at))
        }
        return lines.joinToString("\n")
    }

    private fun toggleAutoStart() {
        // Already on but still blocked: OK reopens the permission page instead of switching off.
        val on = if (prefs.autoStart && !canLaunchAtBoot()) true else !prefs.autoStart
        prefs.autoStart = on
        refresh()
        // Android 10+ blocks opening at boot unless the app may draw over other apps.
        if (on && !canLaunchAtBoot()) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + activity.packageName))
            try {
                activity.toast(activity.getString(R.string.auto_start_permission))
                activity.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                activity.toast(activity.getString(R.string.auto_start_no_settings)) // many TV builds have no such page
            }
        }
    }

    /** Categories (show / hide) of the active playlist, behind its PIN. */
    private fun openCategories() {
        scope.launch {
            val p = graph.repo.playlist(prefs.activePlaylist) ?: return@launch activity.toast(activity.getString(R.string.no_playlist_yet))
            PinPrompt.require(activity, p) { activity.push(CategoriesScreen(activity, p)) }
        }
    }

    // ---- Live channel sort ----

    private fun liveSortLabel(s: Sort) = activity.getString(
        when (s) {
            Sort.NAME -> R.string.live_sort_az
            Sort.NAME_DESC -> R.string.live_sort_za
            else -> R.string.live_sort_default
        },
    )

    /** Default (the provider's order), A–Z or Z–A; kept over reloads. Reset sorting = Default. */
    private fun chooseLiveSort() {
        val options = listOf(Sort.PROVIDER, Sort.NAME, Sort.NAME_DESC)
        AlertDialog.Builder(activity)
            .setTitle(R.string.live_sort_title)
            .setSingleChoiceItems(options.map { liveSortLabel(it) }.toTypedArray(), options.indexOf(prefs.liveSort)) { d, which ->
                prefs.liveSort = options[which]
                refresh()
                d.dismiss()
            }
            .setNeutralButton(R.string.live_sort_reset) { _, _ ->
                prefs.liveSort = Sort.PROVIDER
                refresh()
                activity.toast(activity.getString(R.string.live_sort_was_reset))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- Automatic refresh ----

    private fun autoRefreshLabel(mode: String) = activity.getString(
        when (mode) {
            AutoRefresh.EVERY_START -> R.string.refresh_every_start
            AutoRefresh.DAILY -> R.string.refresh_daily
            else -> R.string.refresh_2_days
        },
    )

    private fun chooseAutoRefresh() {
        val modes = AutoRefresh.MODES
        AlertDialog.Builder(activity)
            .setTitle(R.string.auto_refresh_title)
            .setSingleChoiceItems(modes.map { activity.getString(longRefreshLabel(it)) }.toTypedArray(), modes.indexOf(prefs.autoRefresh)) { d, which ->
                prefs.autoRefresh = modes[which]
                refresh()
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun longRefreshLabel(mode: String) = when (mode) {
        AutoRefresh.EVERY_START -> R.string.refresh_every_start_long
        AutoRefresh.DAILY -> R.string.refresh_daily_long
        else -> R.string.refresh_2_days_long
    }

    // ---- Clear history ----

    /** Continue watching and the last channel. Favourites and the viewer's own groups stay. */
    private fun clearHistory() {
        val labels = arrayOf(
            activity.getString(R.string.history_live),
            activity.getString(R.string.history_movies),
            activity.getString(R.string.history_series),
        )
        val checked = booleanArrayOf(true, true, true)
        AlertDialog.Builder(activity)
            .setTitle(R.string.history_title)
            .setMultiChoiceItems(labels, checked) { _, which, on -> checked[which] = on }
            .setPositiveButton(R.string.history_clear) { _, _ ->
                scope.launch {
                    if (checked[0]) {
                        prefs.lastLiveItem = null
                        prefs.lastLiveCategory = null
                    }
                    graph.repo.clearHistory(listOfNotNull(ContentType.MOVIE.takeIf { checked[1] }, ContentType.SERIES.takeIf { checked[2] }))
                    activity.toast(activity.getString(R.string.history_cleared))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- Subtitle settings ----

    private fun subtitleSettings() {
        val p = prefs
        val items = arrayOf(
            activity.getString(if (p.subsEnabled) R.string.subs_auto_on else R.string.subs_auto_off),
            activity.getString(R.string.subs_language, SubtitleStyle.languageLabel(p.subsLanguage)),
            activity.getString(R.string.subs_size, SubtitleStyle.SIZE_LABELS.getOrElse(p.subsSize) { "" }),
            activity.getString(R.string.subs_color, SubtitleStyle.COLOR_LABELS.getOrElse(p.subsColor) { "" }),
            activity.getString(R.string.subs_background, SubtitleStyle.BACKGROUND_LABELS.getOrElse(p.subsBackground) { "" }),
            activity.getString(if (graph.openSubs.isSetUp) R.string.subs_online_on else R.string.subs_online_off),
        )
        AlertDialog.Builder(activity)
            .setTitle(R.string.set_subtitles)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> { p.subsEnabled = !p.subsEnabled; applySubtitles(); subtitleSettings() }
                    1 -> pick(R.string.subs_language_title, SubtitleStyle.LANGUAGES.map { it.second }, SubtitleStyle.LANGUAGES.indexOfFirst { it.first == p.subsLanguage }) {
                        p.subsLanguage = SubtitleStyle.LANGUAGES[it].first
                    }
                    2 -> pick(R.string.subs_size_title, SubtitleStyle.SIZE_LABELS, p.subsSize) { p.subsSize = it }
                    3 -> pick(R.string.subs_color_title, SubtitleStyle.COLOR_LABELS, p.subsColor) { p.subsColor = it }
                    4 -> pick(R.string.subs_background_title, SubtitleStyle.BACKGROUND_LABELS, p.subsBackground) { p.subsBackground = it }
                    5 -> openSubtitlesAccount()
                }
            }
            .setNegativeButton(R.string.done, null)
            .show()
    }

    /** One choice from [labels]; saved, applied, then back to the subtitle menu. */
    private fun pick(title: Int, labels: List<String>, current: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), current) { d, which ->
                onPick(which)
                applySubtitles()
                d.dismiss()
                subtitleSettings()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> subtitleSettings() }
            .show()
    }

    private fun applySubtitles() {
        SubtitleStyle.apply(activity.playerView, prefs)
        graph.player.applySubtitlePrefs(prefs.subsEnabled, prefs.subsLanguage)
    }

    /** OpenSubtitles API key (needed) and account (optional) for "Search online" in the player. */
    private fun openSubtitlesAccount() {
        val d = activity.resources.displayMetrics.density
        fun field(hint: Int, value: String, password: Boolean = false) = EditText(activity).apply {
            setHint(hint)
            setText(value)
            setSingleLine()
            inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val key = field(R.string.subs_key_hint, prefs.openSubsKey)
        val user = field(R.string.subs_user_hint, prefs.openSubsUser)
        val pass = field(R.string.subs_pass_hint, prefs.openSubsPassword, password = true)
        val help = TextView(activity).apply {
            setText(R.string.subs_online_help)
            setTextColor(activity.getColor(R.color.text_secondary))
            textSize = 14f
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * d).toInt()
            setPadding(pad, (8 * d).toInt(), pad, 0)
            addView(help)
            addView(key)
            addView(user)
            addView(pass)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.subs_online_title)
            .setView(box)
            .setPositiveButton(R.string.group_save) { _, _ ->
                prefs.openSubsKey = key.text.toString()
                prefs.openSubsUser = user.text.toString()
                prefs.openSubsPassword = pass.text.toString()
                subtitleSettings()
            }
            .setNeutralButton(R.string.subs_online_clear) { _, _ ->
                prefs.openSubsKey = ""
                prefs.openSubsUser = ""
                prefs.openSubsPassword = ""
                subtitleSettings()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> subtitleSettings() }
            .show()
    }

    // ---- Reset app ----

    private fun confirmReset() {
        AlertDialog.Builder(activity)
            .setTitle(R.string.reset_title)
            .setMessage(R.string.reset_message)
            .setPositiveButton(R.string.reset_confirm) { _, _ ->
                scope.launch {
                    // The active playlist's PIN first (000000 unless changed): no reset by a stray OK.
                    val active = graph.repo.playlist(prefs.activePlaylist) ?: graph.repo.playlists().firstOrNull()
                    if (active != null) PinPrompt.require(activity, active) { resetApp() } else resetApp()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Back to a fresh install: every playlist, group, favourite, hidden category, history and
     * setting goes (only the TV / Mobile choice stays), the cache is emptied, and the sample
     * playlist is put in. Runs in the app scope: this screen is gone when the UI restarts.
     */
    private fun resetApp() {
        val app = activity
        graph.appScope.launch {
            try {
                graph.player.stop()
                graph.repo.resetAll()
                prefs.resetAll()
                graph.autoRefreshedThisRun.clear()
                withContext(graph.io) {
                    val images = SingletonImageLoader.get(app)
                    images.memoryCache?.clear()
                    images.diskCache?.clear()
                    // Everything else in the cache (downloaded subtitles, update files).
                    app.cacheDir.listFiles()?.filter { it.name != "images" }?.forEach { it.deleteRecursively() }
                }
                val id = graph.repo.addPlaylist(Demo.NAME, Playlist.KIND_DEMO, "demo", null, null)
                graph.repo.playlist(id)?.let { graph.syncer.sync(it) {} }
                prefs.activePlaylist = id
                SubtitleStyle.apply(app.playerView, prefs)
                graph.player.applySubtitlePrefs(prefs.subsEnabled, prefs.subsLanguage)
                app.playerView.resizeMode = prefs.resizeMode
                app.restartUi()
                app.toast(app.getString(R.string.reset_done))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("WorldTV", "reset failed", e)
                app.toast(app.getString(R.string.reset_failed, e.message ?: e.javaClass.simpleName))
                app.restartUi()
            }
        }
    }
}
