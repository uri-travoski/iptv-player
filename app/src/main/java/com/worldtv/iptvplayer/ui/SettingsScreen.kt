package com.worldtv.iptvplayer.ui

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
import com.worldtv.iptvplayer.AutoRefresh
import com.worldtv.iptvplayer.MainActivity
import com.worldtv.iptvplayer.Prefs
import com.worldtv.iptvplayer.R
import com.worldtv.iptvplayer.data.model.ContentType
import com.worldtv.iptvplayer.data.net.AppDns
import com.worldtv.iptvplayer.data.online.OnlineAccount
import com.worldtv.iptvplayer.data.online.OnlineService
import com.worldtv.iptvplayer.player.SubtitleStyle
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
        // Channel check progress ("checking 12 / 40") while this screen is up.
        checkWatch?.cancel()
        checkWatch = scope.launch { graph.channelCheck.progress.collect { updateCheckSummary() } }
    }

    private var checkWatch: kotlinx.coroutines.Job? = null

    /** Save what the settings are now, for support. */
    override fun onHidden() {
        checkWatch?.cancel()
        graph.telemetry.snapshotSettings()
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
        tiles += Tile("auto_refresh", R.drawable.ic_s_autorenew, { s(R.string.auto_refresh_title) }, { autoRefreshLabel(prefs.autoRefresh) }) { chooseAutoRefresh() }
        tiles += Tile("channel_check", R.drawable.ic_s_check, { s(R.string.check_title) }, { checkSummary }) { chooseCheckCategories() }
        tiles += Tile("subtitles", R.drawable.ic_s_subtitles, { s(R.string.set_subtitles) }) { subtitleSettings() }
        tiles += Tile("categories", R.drawable.ic_s_hide, { s(R.string.action_categories) }) { openCategories() }
        tiles += Tile("category_order", R.drawable.ic_s_sort, { s(R.string.category_order_title) }) { openLiveOrder(categories = true) }
        tiles += Tile("live_order", R.drawable.ic_s_sort, { s(R.string.live_order_title) }) { openLiveOrder() }
        tiles += Tile("online", R.drawable.ic_s_key, { s(R.string.set_online) }, { onlineSummary() }) { onlineAccounts() }
        tiles += Tile("dns", R.drawable.ic_s_dns, { s(R.string.set_dns) }, { s(dnsLabel()) }) { chooseDns() }
        if (!mobile) {
            tiles += Tile("auto_start", R.drawable.ic_s_power, { s(R.string.set_auto_start) }, { s(if (prefs.autoStart) R.string.set_on else R.string.set_off) }) { toggleAutoStart() }
            tiles += Tile("home", R.drawable.ic_s_home, { s(R.string.set_home) }, { s(if (Apps.isDefaultHome(activity)) R.string.set_home_is else R.string.set_home_make) }) {
                if (Apps.isDefaultHome(activity)) Apps.changeDefaultHome(activity) else Apps.requestDefaultHome(activity) { refresh() }
            }
            tiles += Tile("slots", R.drawable.ic_s_apps, { s(R.string.set_slots) }, { prefs.appSlotCount.toString() }) { chooseSlots() }
        }
        tiles += Tile("theme", R.drawable.ic_s_palette, { s(R.string.theme_title) }, { s(Themes.label(prefs.theme)) }) { chooseTheme() }
        tiles += Tile("layout", R.drawable.ic_s_layout, { s(R.string.set_layout) }, { s(if (mobile) R.string.set_value_mobile else R.string.set_value_tv) }) {
            prefs.uiMode = if (mobile) Prefs.UI_TV else Prefs.UI_MOBILE
            activity.restartUi(thenSettings = true)
        }
        tiles += Tile("auto_update", R.drawable.ic_s_download, { s(R.string.set_auto_update) }, { s(if (prefs.autoUpdate) R.string.set_on else R.string.set_off) }) {
            prefs.autoUpdate = !prefs.autoUpdate
            refresh()
        }
        tiles += Tile("update_now", R.drawable.ic_s_update, { s(R.string.set_update_now) }) { AppUpdates.checkNow(activity, scope) }
        tiles += Tile("help", R.drawable.ic_s_help, { s(R.string.help) }) { activity.push(HelpScreen(activity)) }
        tiles += Tile("history", R.drawable.ic_s_history, { s(R.string.set_clear_history) }) { clearHistory() }
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
        val dim = activity.themeColor(R.attr.wtTextSecondary)
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

    private fun dnsLabel(mode: Int = prefs.dnsMode) = when (mode) {
        AppDns.MODE_BOTH -> R.string.set_dns_both
        AppDns.MODE_CLOUDFLARE -> R.string.set_dns_cloudflare
        AppDns.MODE_GOOGLE -> R.string.set_dns_google
        else -> R.string.set_dns_system
    }

    /** DNS: 1.1.1.1 + 8.8.8.8 (default), only one of them, or the box's own (off). */
    private fun chooseDns() {
        val modes = listOf(AppDns.MODE_BOTH, AppDns.MODE_CLOUDFLARE, AppDns.MODE_GOOGLE, AppDns.MODE_SYSTEM)
        AlertDialog.Builder(activity)
            .setTitle(R.string.set_dns)
            .setSingleChoiceItems(modes.map { activity.getString(dnsLabel(it)) }.toTypedArray(), modes.indexOf(prefs.dnsMode)) { d, which ->
                prefs.dnsMode = modes[which]
                refresh()
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** How many app slots the home screen shows: a list to pick from (1 to 14, rows of 5). */
    private fun chooseSlots() {
        val counts = (Prefs.MIN_SLOTS..Prefs.MAX_SLOTS).toList()
        val labels = counts.map { activity.resources.getQuantityString(R.plurals.slots_n, it, it) }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle(R.string.set_slots)
            .setSingleChoiceItems(labels, counts.indexOf(prefs.appSlotCount)) { d, which ->
                prefs.appSlotCount = counts[which]
                refresh()
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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

    // ---- Live channel order ----

    /** The active playlist's live categories, to put channels in your own order; or ([categories]) its categories. */
    private fun openLiveOrder(categories: Boolean = false) {
        scope.launch {
            val p = graph.repo.playlist(prefs.activePlaylist) ?: return@launch activity.toast(activity.getString(R.string.no_playlist_yet))
            activity.push(LiveOrderScreen(activity, p, categories = categories))
        }
    }

    // ---- Channel check ----

    /** "3 categories · checked today 3:12 AM", "checking 12 / 40", or "Off". */
    private var checkSummary = ""

    private suspend fun updateCheckSummary() {
        val p = graph.repo.playlist(prefs.activePlaylist)
        val n = if (p == null) 0 else graph.repo.checkCategories(p.id).size
        checkSummary = if (p == null || n == 0) {
            activity.getString(R.string.set_off)
        } else {
            val st = graph.channelCheck.status(p.id)
            val count = "${st.checked} / ${st.total}"
            val time = duration(st.activeMs)
            when {
                st.running -> activity.getString(R.string.check_status_running, count, time)
                st.unfinished -> activity.getString(R.string.check_status_paused, count, time)
                st.doneAt > 0 && st.activeMs < 0 -> activity.getString(R.string.check_status_done_old, count)
                st.doneAt > 0 -> activity.getString(R.string.check_status_done, count, time)
                else -> activity.getString(R.string.check_status_waiting, count)
            }
        }
        refresh()
    }

    /** "45 s", "12 min 30 s", "1 h 5 min". */
    private fun duration(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 60 -> "$s s"
            s < 3600 -> "${s / 60} min ${s % 60} s"
            else -> "${s / 3600} h ${s % 3600 / 60} min"
        }
    }

    /**
     * Picks the active playlist's Live TV categories to check. Chosen ones are listed first. Save
     * (or Check now) starts a check over, in the background, when nothing is playing.
     */
    private fun chooseCheckCategories() {
        scope.launch {
            val p = graph.repo.playlist(prefs.activePlaylist) ?: return@launch activity.toast(activity.getString(R.string.no_playlist_yet))
            val chosen = graph.repo.checkCategories(p.id)
            val all = graph.repo.categories(p.id, ContentType.LIVE).filter { !com.worldtv.iptvplayer.data.repo.Repository.isBuiltIn(it.key) }
            if (all.isEmpty()) return@launch activity.toast(activity.getString(R.string.check_no_categories))
            val cats = all.filter { it.key in chosen } + all.filter { it.key !in chosen }
            val picked = LinkedHashSet(cats.map { it.key }.filter { it in chosen })
            fun save(checkNow: Boolean) {
                val ids = cats.map { it.key }.filter { it in picked }
                scope.launch {
                    graph.repo.setCheckCategories(p.id, ids)
                    if (ids.isNotEmpty() && (checkNow || ids.toSet() != chosen)) {
                        graph.channelCheck.request(p.id, fresh = true, now = checkNow)
                        activity.toast(activity.getString(R.string.check_started))
                    }
                    graph.channelCheck.updates.value = graph.channelCheck.updates.value + 1
                    updateCheckSummary()
                }
            }
            // A filter on top: big playlists have hundreds of categories ("AU" finds the Australian ones).
            val d = activity.resources.displayMetrics.density
            val filter = EditText(activity).apply {
                hint = activity.getString(R.string.check_filter_hint)
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            }
            val list = android.widget.ListView(activity).apply {
                choiceMode = android.widget.ListView.CHOICE_MODE_MULTIPLE
                itemsCanFocus = false
            }
            var shown = cats
            fun show(query: String) {
                val q = query.trim()
                shown = if (q.isEmpty()) cats else cats.filter { it.name.contains(q, ignoreCase = true) }
                list.adapter = android.widget.ArrayAdapter(activity, android.R.layout.simple_list_item_multiple_choice, shown.map { it.name })
                shown.forEachIndexed { i, c -> list.setItemChecked(i, c.key in picked) }
            }
            list.setOnItemClickListener { _, _, pos, _ ->
                val key = shown[pos].key
                if (list.isItemChecked(pos)) picked += key else picked -= key
            }
            filter.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) = show(s?.toString().orEmpty())
            })
            show("")
            val box = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (20 * d).toInt()
                setPadding(pad, (8 * d).toInt(), pad, 0)
                addView(filter, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (activity.resources.displayMetrics.heightPixels * 0.5f).toInt()))
            }
            AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.check_pick_title, p.name))
                .setView(box)
                .setPositiveButton(R.string.group_save) { _, _ -> save(checkNow = false) }
                .setNeutralButton(R.string.check_now) { _, _ -> save(checkNow = true) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    // ---- Theme ----

    private fun chooseTheme() {
        val themes = Themes.ALL
        AlertDialog.Builder(activity)
            .setTitle(R.string.theme_title)
            .setSingleChoiceItems(themes.map { activity.getString(Themes.label(it)) }.toTypedArray(), themes.indexOf(prefs.theme)) { d, which ->
                d.dismiss()
                if (themes[which] != prefs.theme) {
                    prefs.theme = themes[which]
                    activity.applyTheme()
                }
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

    /** Continue watching, the last channel and Most watched. Favourites and the viewer's own groups stay. */
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
                    graph.repo.clearHistory(listOfNotNull(ContentType.LIVE.takeIf { checked[0] }, ContentType.MOVIE.takeIf { checked[1] }, ContentType.SERIES.takeIf { checked[2] }))
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
                    5 -> accountList(OnlineService.OPENSUBTITLES) { subtitleSettings() }
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

    // ---- Online accounts ----

    private fun onlineSummary(): String = OnlineService.values().joinToString(" · ") { "${it.title} ${prefs.accounts(it).size}" }

    /** The services that use the user's own accounts, each with its list. */
    private fun onlineAccounts() {
        val services = OnlineService.values()
        val labels = services.map { svc ->
            val n = prefs.accounts(svc).size
            activity.getString(if (svc == OnlineService.TMDB) R.string.online_tmdb else R.string.online_opensubs, n)
        }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle(R.string.set_online)
            .setItems(labels) { _, which -> accountList(services[which]) { onlineAccounts() } }
            .setNegativeButton(R.string.done) { _, _ -> refresh() }
            .show()
    }

    /**
     * One service's accounts, in the order they are tried (the one that worked last is tried
     * first next time). OK on an account: Test, Edit, Move to top, Remove. Last row: Add account.
     */
    private fun accountList(service: OnlineService, back: () -> Unit) {
        val list = prefs.accounts(service)
        val status = rotation(service).status
        val labels = list.mapIndexed { i, a ->
            val st = status[a.key]?.let { if (it == "OK") "  ✓ works" else "  ✕ $it" }.orEmpty()
            "${i + 1}. ${a.label}$st"
        } + activity.getString(R.string.online_add)
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.online_list_title, service.title))
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == list.size) editAccount(service, null) { accountList(service, back) } else accountActions(service, which) { accountList(service, back) }
            }
            .setNegativeButton(R.string.done) { _, _ -> refresh(); back() }
            .show()
    }

    private fun rotation(service: OnlineService) = if (service == OnlineService.TMDB) graph.tmdb.rotation else graph.openSubs.rotation

    private fun accountActions(service: OnlineService, index: Int, back: () -> Unit) {
        val list = prefs.accounts(service).toMutableList()
        val account = list.getOrNull(index) ?: return back()
        val actions = arrayOf(
            activity.getString(R.string.online_test),
            activity.getString(R.string.online_edit),
            activity.getString(R.string.online_move_top),
            activity.getString(R.string.online_remove),
        )
        AlertDialog.Builder(activity)
            .setTitle(account.label)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> testAccount(service, account, back)
                    1 -> editAccount(service, index, back)
                    2 -> { list.removeAt(index); list.add(0, account); prefs.setAccounts(service, list); back() }
                    3 -> { list.removeAt(index); prefs.setAccounts(service, list); activity.toast(activity.getString(R.string.online_removed)); back() }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> back() }
            .show()
    }

    private fun testAccount(service: OnlineService, account: OnlineAccount, back: () -> Unit) {
        activity.toast(activity.getString(R.string.online_testing))
        scope.launch {
            val result = if (service == OnlineService.TMDB) graph.tmdb.test(account) else graph.openSubs.test(account)
            AlertDialog.Builder(activity)
                .setTitle("${service.title} · ${account.label}")
                .setMessage(result)
                .setPositiveButton(android.R.string.ok) { _, _ -> back() }
                .setOnCancelListener { back() }
                .show()
        }
    }

    /** Add (index null) or edit an account; saved accounts are tested straight away. */
    private fun editAccount(service: OnlineService, index: Int?, back: () -> Unit) {
        val d = activity.resources.displayMetrics.density
        val old = index?.let { prefs.accounts(service).getOrNull(it) }
        fun field(hint: Int, value: String, password: Boolean = false) = EditText(activity).apply {
            setHint(hint)
            setText(value)
            setSingleLine()
            inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val key = field(R.string.online_key_hint, old?.key.orEmpty())
        val user = field(R.string.subs_user_hint, old?.user.orEmpty())
        val pass = field(R.string.subs_pass_hint, old?.password.orEmpty(), password = true)
        val help = TextView(activity).apply {
            setText(if (service == OnlineService.TMDB) R.string.online_tmdb_help else R.string.subs_online_help)
            setTextColor(activity.themeColor(R.attr.wtTextSecondary))
            textSize = 14f
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * d).toInt()
            setPadding(pad, (8 * d).toInt(), pad, 0)
            addView(help)
            addView(key)
            if (service.hasLogin) { addView(user); addView(pass) }
        }
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(if (old == null) R.string.online_add_title else R.string.online_edit_title, service.title))
            .setView(box)
            .setPositiveButton(R.string.group_save) { _, _ ->
                val k = key.text.toString().trim()
                if (k.isEmpty()) return@setPositiveButton back()
                val account = OnlineAccount(k, if (service.hasLogin) user.text.toString().trim() else "", if (service.hasLogin) pass.text.toString() else "")
                val list = prefs.accounts(service).toMutableList()
                if (index != null && index in list.indices) list[index] = account else list += account
                prefs.setAccounts(service, list)
                testAccount(service, account, back)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> back() }
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
                val id = graph.addDefaultPlaylist()
                // Offline: it stays, and loads by itself later (automatic refresh).
                try {
                    graph.repo.playlist(id)?.let { graph.syncer.sync(it) {} }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("WorldTV", "default playlist not loaded", e)
                }
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
