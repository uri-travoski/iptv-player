package io.github.sardinemehico.iptvplayer.ui

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.common.C
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.sardinemehico.iptvplayer.MainActivity
import io.github.sardinemehico.iptvplayer.R
import io.github.sardinemehico.iptvplayer.data.model.ContentType
import io.github.sardinemehico.iptvplayer.data.repo.CategoryRow
import io.github.sardinemehico.iptvplayer.data.repo.EntryRow
import io.github.sardinemehico.iptvplayer.data.repo.Group
import io.github.sardinemehico.iptvplayer.data.repo.Playlist
import io.github.sardinemehico.iptvplayer.data.repo.Repository
import io.github.sardinemehico.iptvplayer.data.source.XtreamCredentials
import io.github.sardinemehico.iptvplayer.data.source.XtreamUrls
import io.github.sardinemehico.iptvplayer.player.PlayerController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Live TV: categories · channels · preview, like IBO's live screen.
 * OK on a channel plays it in the preview; OK again goes full screen.
 * Full screen: Up/Down or CH+/CH- zap, OK shows the controls, Back steps back one level
 * (controls → full screen → list), always to the same category and channel.
 * Mobile layout: preview on top and a sideways category strip; tap = OK, long-press = Menu, a tap
 * on the preview goes full screen (landscape), a tap on the video shows the controls.
 */
class LiveScreen(activity: MainActivity) : Screen(activity) {

    private val mobile = graph.prefs.isMobile
    override val root: View = inflater.inflate(if (mobile) R.layout.screen_live_mobile else R.layout.screen_live, null)

    private val panels: View = root.findViewById(R.id.panels)
    private val categoriesView: RecyclerView = root.findViewById(R.id.categories)
    private val channelsView: RecyclerView = root.findViewById(R.id.channels)
    private val categoryTitle: TextView = root.findViewById(R.id.category_title)
    private val empty: View = root.findViewById(R.id.empty)
    private val preview: View = root.findViewById(R.id.preview)
    private val nowName: TextView = root.findViewById(R.id.now_name)
    private val nowStatus: TextView = root.findViewById(R.id.now_status)
    private val banner: View = root.findViewById(R.id.banner)
    private val bannerName: TextView = root.findViewById(R.id.banner_name)
    private val bannerInfo: TextView = root.findViewById(R.id.banner_info)
    private val osdStatus: TextView = root.findViewById(R.id.osd_status)
    private val controls: View = root.findViewById(R.id.controls)
    private val ctlPause: ImageView = root.findViewById(R.id.ctl_pause)
    private val ctlAspect: ImageView = root.findViewById(R.id.ctl_aspect)
    private val ctlFav: ImageView = root.findViewById(R.id.ctl_fav)
    private val searchField: EditText = root.findViewById(R.id.search)
    private val tips = OsdTips(
        root.findViewById(R.id.osd_tip),
        listOf(
            R.id.ctl_fav, R.id.ctl_audio, R.id.ctl_subs, R.id.ctl_prev, R.id.ctl_pause, R.id.ctl_next, R.id.ctl_aspect, R.id.ctl_external,
        ).map { root.findViewById<View>(it) },
    )

    private val handler = Handler(Looper.getMainLooper())
    private val hideBanner = Runnable { hideOverlay() }

    private val categoryAdapter = CategoryAdapter(
        onFocused = ::onCategoryFocused,
        onClicked = ::onCategoryClicked,
        layout = if (mobile) R.layout.row_category_chip else R.layout.row_category,
        onLongClicked = ::onCategoryMenu,
    )
    private val channelAdapter = PagedEntryAdapter(scope, ::onChannelClicked, onLongClicked = ::onChannelMenu)
    private val cats = CategoryList(categoryAdapter) { activity.getString(R.string.search_results, it) }

    private var playlist: Playlist? = null
    private var urls: XtreamUrls? = null
    private var liveExt = "ts"
    private var categoryIndex = -1
    /** Category the playing channel was picked from, marked in the list while it is shown there. */
    private var playingCategoryKey: String? = null
    private var categoryKey = Repository.KEY_ALL
    private var channelCount = 0
    private var playingIndex = -1
    private var playingRow: EntryRow? = null
    private var fullscreen = false
    private var loaded = false
    private var categoryJob: Job? = null
    private var pendingCategory: Job? = null
    /** Set while focus is moved back from full screen, so a passing category row can't switch the list. */
    private var restoringFocus = false
    /** Name of the list on screen: a category, or the search. */
    private var listName = ""
    private val searchBox = SearchBox(searchField, scope, ::onSearch, onSubmit = ::onSearchSubmit)

    private val playerListener = object : PlayerController.Listener {
        override fun onState(state: PlayerController.State) {
            val text = when (state) {
                PlayerController.State.BUFFERING -> activity.getString(R.string.loading)
                PlayerController.State.RECONNECTING -> activity.getString(R.string.reconnecting)
                PlayerController.State.FAILED -> activity.getString(R.string.stream_failed)
                else -> ""
            }
            nowStatus.text = text
            osdStatus.text = text
            osdStatus.visibility = if (fullscreen && text.isNotEmpty()) View.VISIBLE else View.GONE
        }

        override fun onAudioUnsupported() {
            activity.toast(activity.getString(R.string.audio_unsupported))
        }
    }

    init {
        categoriesView.layoutManager = LinearLayoutManager(activity, if (mobile) RecyclerView.HORIZONTAL else RecyclerView.VERTICAL, false)
        categoriesView.adapter = categoryAdapter
        categoriesView.itemAnimator = null
        channelsView.layoutManager = LinearLayoutManager(activity)
        channelsView.adapter = channelAdapter
        channelsView.itemAnimator = null
        channelsView.setHasFixedSize(true)
        channelsView.setItemViewCacheSize(12)

        root.findViewById<View>(R.id.ctl_prev).setOnClickListener { zap(-1) }
        root.findViewById<View>(R.id.ctl_next).setOnClickListener { zap(+1) }
        ctlPause.setOnClickListener { togglePause() }
        root.findViewById<View>(R.id.ctl_audio).setOnClickListener { chooseTrack(C.TRACK_TYPE_AUDIO) }
        root.findViewById<View>(R.id.ctl_subs).setOnClickListener { chooseTrack(C.TRACK_TYPE_TEXT) }
        ctlAspect.setOnClickListener { cycleAspect() }
        ctlFav.setOnClickListener { playingRow?.let { toggleFavourite(playingIndex, it) } }
        root.findViewById<View>(R.id.ctl_external).setOnClickListener { openExternal() }
        ctlAspect.contentDescription = activity.getString(PlayerUi.aspectLabel(activity))
        if (mobile) root.findViewById<View>(R.id.osd_top).visibility = View.VISIBLE
        root.findViewById<View>(R.id.ctl_exit).setOnClickListener { exitFullscreen() }
        // Touch: a tap on the video shows or hides the controls; a tap on the preview goes full screen.
        root.setOnClickListener {
            if (!fullscreen) return@setOnClickListener
            if (banner.visibility == View.VISIBLE) hideOverlay() else showOverlay(withControls = true)
        }
        preview.setOnClickListener { if (playingRow != null && !fullscreen) enterFullscreen() }

        // Keep the preview 16:9 and put the video exactly under it whenever layout changes.
        preview.addOnLayoutChangeListener { v, left, _, right, _, _, _, _, _ ->
            val wantHeight = (right - left) * 9 / 16
            if (wantHeight > 0 && v.layoutParams.height != wantHeight) {
                v.layoutParams = v.layoutParams.also { it.height = wantHeight }
            } else if (!fullscreen) {
                v.post { placeVideoInPreview() }
            }
        }
    }

    override fun onShown() {
        activity.keepScreenOn(true)
        activity.setFullscreen(fullscreen)
        graph.player.addListener(playerListener)
        if (fullscreen) activity.setVideoRect(null) else preview.post { placeVideoInPreview() }
        if (!loaded) {
            loaded = true
            scope.launch { load() }
        } else {
            playingRow?.let { play(playingIndex, it, fromCategoryKey = playingCategoryKey) }
        }
    }

    override fun onHidden() {
        activity.keepScreenOn(false)
        if (fullscreen) activity.setFullscreen(false)
        graph.player.removeListener(playerListener)
        graph.player.stop()
        handler.removeCallbacksAndMessages(null)
    }

    override fun onBack(): Boolean {
        if (!fullscreen) return false
        if (controls.visibility == View.VISIBLE) hideOverlay() else exitFullscreen()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (fullscreen) {
            val controlsShown = controls.visibility == View.VISIBLE
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> zap(-1)
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_UP -> zap(+1)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    // With the controls up, these move focus or press the focused button.
                    if (controlsShown) {
                        scheduleHide()
                        return false
                    }
                    showOverlay(withControls = true)
                }
                KeyEvent.KEYCODE_INFO -> showOverlay(withControls = true)
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE -> togglePause()
                KeyEvent.KEYCODE_MENU -> playingRow?.let { onChannelMenu(playingIndex, it) }
                else -> return false
            }
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_MENU -> {
                val focused = activity.currentFocus ?: return false
                if (focused.parent === categoriesView) {
                    onCategoryMenu(categoriesView.getChildAdapterPosition(focused))
                    return true
                }
                if (focused.parent !== channelsView) return false
                val pos = channelsView.getChildAdapterPosition(focused)
                val row = channelAdapter.rowAt(pos) ?: return true
                onChannelMenu(pos, row)
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_UP -> { zap(+1); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(-1); return true }
        }
        return false
    }

    // ---- loading ----

    private suspend fun load() {
        val p = graph.repo.playlist(graph.prefs.activePlaylist) ?: return
        playlist = p
        if (p.isXtream) {
            urls = XtreamUrls(XtreamCredentials(p.url, p.username.orEmpty(), p.password.orEmpty()))
            liveExt = if (p.formats.isEmpty() || "ts" in p.formats) "ts" else "m3u8"
        }
        loadCategories()

        // Favourites first when there are any (they are the viewer's own list); else the last category.
        val hasFavourites = graph.repo.count(p.id, ContentType.LIVE, Repository.KEY_FAV) > 0
        val startKey = if (hasFavourites) Repository.KEY_FAV else graph.prefs.lastLiveCategory.takeUnless { it == Repository.KEY_FAV }
        val startIndex = indexOrAll(startKey)
        selectCategory(startIndex)

        // Restore the last channel and play it in the preview.
        val lastItem = graph.prefs.lastLiveItem
        val index = if (lastItem != null) graph.repo.indexOf(p.id, ContentType.LIVE, categoryKey, lastItem) else -1
        if (index >= 0) {
            val row = graph.repo.page(p.id, ContentType.LIVE, categoryKey, index, 1).firstOrNull()
            if (row != null) play(index, row)
            focusChannel(index)
        } else {
            focusChannel(0)
        }
        categoriesView.scrollToPosition(startIndex)
    }

    /** "＋ Add group" on top, then the viewer's own groups (newest first), All, Favourites and the provider's. */
    private suspend fun loadCategories() {
        val p = playlist ?: return
        cats.all = listOf(CategoryRow(KEY_ADD_GROUP, activity.getString(R.string.group_add))) + graph.repo.categories(p.id, ContentType.LIVE)
    }

    /** Position of [key] in the list, or of All when it isn't there. */
    private fun indexOrAll(key: String?): Int = cats.indexOf(key).takeIf { it >= 0 } ?: cats.indexOf(Repository.KEY_ALL).coerceAtLeast(0)

    private fun onCategoryFocused(index: Int) {
        if (cats.shown.getOrNull(index)?.key == KEY_ADD_GROUP) {
            pendingCategory?.cancel() // a button, not a list: the channels stay as they are
            return
        }
        if (index == categoryIndex || restoringFocus) return
        // Debounce: holding Down through the category list must not run a query per row.
        pendingCategory?.cancel()
        pendingCategory = scope.launch {
            delay(300)
            selectCategory(index)
        }
    }

    private fun onCategoryClicked(index: Int) {
        pendingCategory?.cancel()
        if (cats.shown.getOrNull(index)?.key == KEY_ADD_GROUP) {
            askGroupName(null)
            return
        }
        scope.launch {
            if (index != categoryIndex) selectCategory(index)
            focusChannel(0)
        }
    }

    private suspend fun selectCategory(index: Int) {
        val cat = cats.shown.getOrNull(index)?.takeUnless { it.key == KEY_ADD_GROUP } ?: return
        // Picking from the full list drops a half-typed search; inside search results it stays.
        if (cats.query == null) searchBox.clearQuietly()
        categoryIndex = index
        categoryAdapter.selected = index
        showList(cat.key, cat.name)
    }

    /**
     * Typing narrows the category list to a "Search: …" row (channel names, shown at once) and the
     * categories whose name matches. Clearing the field brings back every category, at the one
     * being browsed (or the last one played from).
     */
    private fun onSearch(query: String?) {
        scope.launch {
            pendingCategory?.cancel()
            if (query != null) {
                cats.filter(query)
                markPlayingCategory()
                categoriesView.scrollToPosition(0)
                selectCategory(0)
            } else if (cats.query != null) {
                val back = categoryKey.takeUnless { Repository.isSearch(it) } ?: graph.prefs.lastLiveCategory
                cats.filter(null)
                markPlayingCategory()
                val i = indexOrAll(back)
                categoriesView.scrollToPosition(i)
                selectCategory(i)
            }
        }
    }

    /** Search key: to the channels found, or, if none, to the first matching category. */
    private fun onSearchSubmit() {
        if (channelCount == 0 && cats.shown.size > 1) focusCategory(1) else focusChannel(0)
    }

    private fun focusCategory(index: Int) {
        categoriesView.scrollToPosition(index)
        categoriesView.post { categoriesView.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus() }
    }

    private fun markPlayingCategory() {
        categoryAdapter.playing = cats.indexOf(playingCategoryKey)
    }

    private suspend fun showList(key: String, name: String) {
        val p = playlist ?: return
        categoryKey = key
        listName = name
        categoryJob?.cancel()
        val count = graph.repo.count(p.id, ContentType.LIVE, key)
        channelCount = count
        categoryTitle.text = "$name  ($count)"
        empty.visibility = if (count == 0) View.VISIBLE else View.GONE
        channelAdapter.reset(count) { offset, limit -> graph.repo.page(p.id, ContentType.LIVE, key, offset, limit) }
        channelAdapter.playingItemId = playingRow?.itemId
        channelsView.scrollToPosition(0)
    }

    private fun focusChannel(index: Int, done: () -> Unit = {}) {
        if (channelCount == 0) return done()
        val i = index.coerceIn(0, channelCount - 1)
        (channelsView.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(i, channelsView.height / 3)
        // The row exists only after the next layout pass; on a slow box that can take a frame or two.
        var tries = 0
        fun attempt() {
            val row = channelsView.findViewHolderForAdapterPosition(i)?.itemView
            if (row != null) {
                row.requestFocus()
                done()
            } else if (++tries < 5) {
                channelsView.post { attempt() }
            } else {
                done()
            }
        }
        channelsView.post { attempt() }
    }

    // ---- playback ----

    private fun onChannelClicked(index: Int, row: EntryRow) {
        if (row.itemId == playingRow?.itemId && graph.player.playingUrl != null) {
            // The same channel can sit at another position in another category (e.g. All):
            // zapping and returning from full screen must use its place in this list.
            playingIndex = index
            playingRow = row
            enterFullscreen()
        } else {
            play(index, row)
        }
    }

    /** [fromCategoryKey]: the list [index] belongs to (the shown one, unless replaying). */
    private fun play(index: Int, row: EntryRow, fromCategoryKey: String? = categoryKey) {
        val url = row.streamUrl ?: urls?.live(row.itemId, liveExt) ?: return
        playingIndex = index
        playingRow = row
        // Mark the category this channel was picked from.
        playingCategoryKey = fromCategoryKey
        markPlayingCategory()
        channelAdapter.playingItemId = row.itemId
        nowName.text = row.name
        graph.player.play(url)
        if (!Repository.isSearch(categoryKey)) graph.prefs.lastLiveCategory = categoryKey
        graph.prefs.lastLiveItem = row.itemId
        if (fullscreen) showOverlay(withControls = controls.visibility == View.VISIBLE)
    }

    private fun zap(delta: Int) {
        val p = playlist ?: return
        if (channelCount == 0) return
        val base = if (playingIndex >= 0) playingIndex else 0
        val next = ((base + delta) % channelCount + channelCount) % channelCount
        val cached = channelAdapter.rowAt(next)
        if (cached != null) {
            play(next, cached)
            return
        }
        scope.launch {
            val row = graph.repo.page(p.id, ContentType.LIVE, categoryKey, next, 1).firstOrNull() ?: return@launch
            play(next, row)
        }
    }

    private fun toggleFavourite(index: Int, row: EntryRow) {
        val p = playlist ?: return
        scope.launch {
            val fav = graph.repo.toggleFavourite(p.id, ContentType.LIVE, row.itemId)
            activity.toast(activity.getString(if (fav) R.string.favourite_added else R.string.favourite_removed))
            if (row.itemId == playingRow?.itemId) {
                playingRow = playingRow?.copy(favourite = fav)
                updateFavLabel()
            }
            if (categoryKey == Repository.KEY_FAV) {
                if (!fullscreen) selectCategory(categoryIndex)
            } else {
                channelAdapter.setFavourite(index, fav)
            }
        }
    }

    // ---- own groups ----

    /**
     * Menu (long-press) on a channel: with no groups of their own yet, toggles the favourite as it
     * always did; else a choice of Favourites and every group, each to add the channel or take it out.
     */
    private fun onChannelMenu(index: Int, row: EntryRow) {
        val p = playlist ?: return
        scope.launch {
            val groups = graph.repo.groups(p.id)
            if (groups.isEmpty()) return@launch toggleFavourite(index, row)
            val fav = graph.repo.isFavourite(p.id, ContentType.LIVE, row.itemId)
            val inGroups = graph.repo.groupsOf(row.itemId, groups.map { it.id })
            val labels = ArrayList<String>()
            labels += activity.getString(if (fav) R.string.group_remove_from else R.string.group_add_to, activity.getString(R.string.favourites))
            groups.forEach { labels += activity.getString(if (it.id in inGroups) R.string.group_remove_from else R.string.group_add_to, it.name) }
            android.app.AlertDialog.Builder(activity)
                .setTitle(row.name)
                .setItems(labels.toTypedArray()) { _, which ->
                    if (which == 0) return@setItems toggleFavourite(index, row)
                    val g = groups[which - 1]
                    val add = g.id !in inGroups
                    scope.launch {
                        graph.repo.setInGroup(g.id, row.itemId, add)
                        activity.toast(activity.getString(if (add) R.string.group_added else R.string.group_removed, g.name))
                        if (categoryKey == Repository.groupKey(g.id) && !fullscreen) selectCategory(categoryIndex)
                    }
                }
                .show()
        }
    }

    /** Menu (long-press) on one of the viewer's groups: rename or delete it. Other categories: nothing. */
    private fun onCategoryMenu(index: Int) {
        val cat = cats.shown.getOrNull(index) ?: return
        val id = Repository.groupId(cat.key) ?: return
        android.app.AlertDialog.Builder(activity)
            .setTitle(cat.name)
            .setItems(arrayOf(activity.getString(R.string.group_rename), activity.getString(R.string.group_delete))) { _, which ->
                if (which == 0) askGroupName(Group(id, cat.name)) else confirmDeleteGroup(Group(id, cat.name))
            }
            .show()
    }

    /** Name for a new group, or a new name for [group]. */
    private fun askGroupName(group: Group?) {
        val p = playlist ?: return
        val field = EditText(activity).apply {
            setSingleLine()
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            hint = activity.getString(R.string.group_name_hint)
            if (group != null) { setText(group.name); setSelection(group.name.length) }
        }
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val box = android.widget.FrameLayout(activity).apply { setPadding(pad, pad / 2, pad, 0); addView(field) }
        val dialog = android.app.AlertDialog.Builder(activity)
            .setTitle(if (group == null) R.string.group_add_title else R.string.group_rename)
            .setView(box)
            .setPositiveButton(if (group == null) R.string.group_create else R.string.group_save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        fun save() {
            val name = field.text.toString().trim()
            if (name.isEmpty()) return
            dialog.dismiss()
            scope.launch {
                val key = if (group == null) Repository.groupKey(graph.repo.addGroup(p.id, name)) else {
                    graph.repo.renameGroup(group.id, name)
                    categoryKey
                }
                if (cats.query != null) searchBox.clearQuietly()
                loadCategories()
                val i = indexOrAll(key)
                categoriesView.scrollToPosition(i)
                selectCategory(i)
                if (group == null) {
                    activity.toast(activity.getString(R.string.group_created, name))
                    focusCategory(i)
                }
            }
        }
        // The keyboard's Done, or Enter / OK from a remote or keyboard.
        field.setOnEditorActionListener { _, actionId, event ->
            val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE || enter) {
                if (event == null || event.action == KeyEvent.ACTION_DOWN) save()
                true
            } else {
                false
            }
        }
        dialog.setOnShowListener { dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener { save() } }
        dialog.show()
        field.requestFocus()
    }

    private fun confirmDeleteGroup(group: Group) {
        android.app.AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.group_delete_q, group.name))
            .setMessage(R.string.group_delete_msg)
            .setPositiveButton(R.string.group_delete) { _, _ ->
                scope.launch {
                    graph.repo.deleteGroup(group.id)
                    val back = categoryKey.takeUnless { it == Repository.groupKey(group.id) }
                    loadCategories()
                    val i = indexOrAll(back)
                    categoriesView.scrollToPosition(i)
                    selectCategory(i)
                    focusCategory(i)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- full screen ----

    private fun enterFullscreen() {
        fullscreen = true
        panels.visibility = View.INVISIBLE
        activity.setFullscreen(true)
        activity.setVideoRect(null)
        // The root only takes focus in full screen, so it never steals D-pad focus from the lists.
        root.isFocusable = true
        root.requestFocus()
        showOverlay(withControls = false)
    }

    private fun exitFullscreen() {
        if (!fullscreen) return
        fullscreen = false
        activity.setFullscreen(false)
        handler.removeCallbacks(hideBanner)
        banner.visibility = View.GONE
        controls.visibility = View.GONE
        osdStatus.visibility = View.GONE
        if (graph.player.isPaused) togglePause()
        // Bug fix: making the focused root unfocusable used to drop focus on the first category
        // row, whose focus listener then switched the list to that category. Panels first, then
        // focus the channel, and ignore category focus until it lands.
        pendingCategory?.cancel()
        restoringFocus = true
        panels.visibility = View.VISIBLE
        root.isFocusable = false
        preview.post { placeVideoInPreview() }
        scope.launch {
            // Favourites and groups may have changed while in full screen.
            if (categoryKey == Repository.KEY_FAV || Repository.groupId(categoryKey) != null) selectCategory(categoryIndex)
            focusChannel(playingIndex.coerceAtLeast(0)) {
                restoringFocus = false
                pendingCategory?.cancel()
            }
        }
    }

    /** Channel banner, plus the control buttons when [withControls]. Hides itself after a few seconds. */
    private fun showOverlay(withControls: Boolean) {
        val row = playingRow ?: return
        bannerName.text = "${playingIndex + 1}  ${row.name}"
        bannerInfo.text = listName
        updateFavLabel()
        updatePauseLabel()
        banner.visibility = View.VISIBLE
        if (withControls && controls.visibility != View.VISIBLE) {
            controls.visibility = View.VISIBLE
            ctlPause.requestFocus()
        }
        scheduleHide()
    }

    private fun hideOverlay() {
        handler.removeCallbacks(hideBanner)
        banner.visibility = View.GONE
        controls.visibility = View.GONE
        if (fullscreen) root.requestFocus()
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideBanner)
        handler.postDelayed(hideBanner, if (controls.visibility == View.VISIBLE) 6_000 else 4_000)
    }

    private fun togglePause() {
        graph.player.togglePause()
        updatePauseLabel()
        if (fullscreen) showOverlay(withControls = controls.visibility == View.VISIBLE)
    }

    private fun updatePauseLabel() {
        val paused = graph.player.isPaused
        ctlPause.setImageResource(if (paused) R.drawable.ic_play else R.drawable.ic_pause)
        ctlPause.contentDescription = activity.getString(if (paused) R.string.ctl_play else R.string.ctl_pause)
        tips.refresh()
    }

    private fun updateFavLabel() {
        val fav = playingRow?.favourite == true
        ctlFav.setImageResource(if (fav) R.drawable.ic_star else R.drawable.ic_star_border)
        ctlFav.contentDescription = activity.getString(if (fav) R.string.ctl_fav_remove else R.string.ctl_fav_add)
        tips.refresh()
    }

    private fun openExternal() {
        val row = playingRow ?: return
        val url = row.streamUrl ?: urls?.live(row.itemId, liveExt) ?: return
        PlayerUi.openExternal(activity, url, row.name)
    }

    private fun cycleAspect() {
        PlayerUi.cycleAspect(activity)
        PlayerUi.showAspect(activity, ctlAspect)
        tips.refresh()
        scheduleHide()
    }

    /** Audio or subtitle picker. The overlay stays up while the dialog is open. */
    private fun chooseTrack(type: Int) {
        handler.removeCallbacks(hideBanner)
        PlayerUi.chooseTrack(activity, type) { if (fullscreen) scheduleHide() }
    }

    private fun placeVideoInPreview() {
        if (fullscreen || preview.width == 0) return
        val loc = IntArray(2)
        preview.getLocationInWindow(loc)
        val parentLoc = IntArray(2)
        (activity.playerView.parent as View).getLocationInWindow(parentLoc)
        val left = loc[0] - parentLoc[0]
        val top = loc[1] - parentLoc[1]
        activity.setVideoRect(Rect(left, top, left + preview.width, top + preview.height))
    }

    private companion object {
        /** The "＋ Add group" row: a button at the top of the category list, never a list itself. */
        const val KEY_ADD_GROUP = "\u0000addgroup"
    }
}
