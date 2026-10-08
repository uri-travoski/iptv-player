package io.github.sardinemehico.iptvplayer.ui

import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.sardinemehico.iptvplayer.MainActivity
import io.github.sardinemehico.iptvplayer.R
import io.github.sardinemehico.iptvplayer.data.model.ContentType
import io.github.sardinemehico.iptvplayer.data.repo.EntryRow
import io.github.sardinemehico.iptvplayer.data.repo.Playlist
import io.github.sardinemehico.iptvplayer.data.repo.Repository
import io.github.sardinemehico.iptvplayer.data.repo.Sort
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Movies or Series: categories on the left, a paged poster grid on the right.
 * OK on a poster opens its page; Menu toggles favourite. Back returns to the same poster.
 * Mobile layout: categories in a sideways strip above the grid; long-press toggles favourite.
 */
class VodScreen(activity: MainActivity, private val type: ContentType) : Screen(activity) {

    private val mobile = graph.prefs.isMobile
    override val root: View = inflater.inflate(if (mobile) R.layout.screen_vod_mobile else R.layout.screen_vod, null)

    private val categoriesView: RecyclerView = root.findViewById(R.id.categories)
    private val grid: RecyclerView = root.findViewById(R.id.grid)
    private val categoryTitle: TextView = root.findViewById(R.id.category_title)
    private val empty: View = root.findViewById(R.id.empty)
    private val searchField: EditText = root.findViewById(R.id.search)
    private val sortButton: TextView = root.findViewById(R.id.sort)
    private var sort = graph.prefs.vodSort(type)

    private val categoryAdapter = CategoryAdapter(
        onFocused = ::onCategoryFocused,
        onClicked = ::onCategoryClicked,
        layout = if (mobile) R.layout.row_category_chip else R.layout.row_category,
    )
    private val posterAdapter = PagedEntryAdapter(scope, ::onPosterClicked, R.layout.row_poster, ::toggleFavourite)
    private val cats = CategoryList(categoryAdapter) { activity.getString(R.string.search_results, it) }
    /** TV: 5. Mobile: as many ~120dp posters as fit across (3 on most phones). */
    private val columns = if (mobile) (activity.resources.configuration.screenWidthDp / 120).coerceAtLeast(2) else COLUMNS

    private var playlist: Playlist? = null
    private var categoryIndex = -1
    private var categoryKey = Repository.KEY_ALL
    private var count = 0
    private var loaded = false
    private var pendingCategory: Job? = null
    /** Poster to give focus back to when returning from its page. */
    private var lastOpened = -1
    /** Favourite state set on that page, applied to the grid on return. */
    private var favChanged: Boolean? = null
    private val searchBox = SearchBox(searchField, scope, ::onSearch, onSubmit = ::onSearchSubmit)

    init {
        root.findViewById<TextView>(R.id.title).setText(if (type == ContentType.MOVIE) R.string.movies else R.string.series)
        categoriesView.layoutManager = LinearLayoutManager(activity, if (mobile) RecyclerView.HORIZONTAL else RecyclerView.VERTICAL, false)
        categoriesView.adapter = categoryAdapter
        categoriesView.itemAnimator = null
        grid.layoutManager = GridLayoutManager(activity, columns)
        grid.adapter = posterAdapter
        grid.itemAnimator = null
        grid.setHasFixedSize(true)
        grid.setItemViewCacheSize(columns * 2)
        updateSortLabel()
        sortButton.setOnClickListener {
            // Newest → Rating → A–Z → Provider order → Newest
            sort = Sort.values()[(sort.ordinal + 1) % Sort.values().size]
            graph.prefs.setVodSort(type, sort)
            updateSortLabel()
            scope.launch { reloadList() }
        }
    }

    override fun onShown() {
        activity.hideVideo()
        graph.player.stop()
        if (!loaded) {
            loaded = true
            scope.launch { load() }
        } else if (lastOpened >= 0) {
            val fav = favChanged
            favChanged = null
            // "Continue watching" changes order (or loses the title) after playback.
            val reload = categoryKey == Repository.KEY_CONTINUE || (fav != null && categoryKey == Repository.KEY_FAV)
            if (reload) {
                scope.launch { reloadList(); focusPoster(if (categoryKey == Repository.KEY_CONTINUE) 0 else lastOpened) }
            } else {
                if (fav != null) posterAdapter.setFavourite(lastOpened, fav)
                focusPoster(lastOpened)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_MENU) return false
        val focused = activity.currentFocus ?: return false
        if (focused.parent !== grid) return false
        val pos = grid.getChildAdapterPosition(focused)
        val row = posterAdapter.rowAt(pos) ?: return true
        toggleFavourite(pos, row)
        return true
    }

    private fun toggleFavourite(pos: Int, row: EntryRow) {
        val p = playlist ?: return
        scope.launch {
            val fav = graph.repo.toggleFavourite(p.id, type, row.itemId)
            activity.toast(activity.getString(if (fav) R.string.favourite_added else R.string.favourite_removed))
            if (categoryKey == Repository.KEY_FAV) reloadList() else posterAdapter.setFavourite(pos, fav)
        }
    }

    private suspend fun load() {
        val p = graph.repo.playlist(graph.prefs.activePlaylist) ?: return
        playlist = p
        cats.all = graph.repo.categories(p.id, type)
        selectCategory(0)
        categoriesView.post { categoriesView.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
    }

    private fun onCategoryFocused(index: Int) {
        if (index == categoryIndex) return
        pendingCategory?.cancel()
        pendingCategory = scope.launch {
            delay(300)
            selectCategory(index)
        }
    }

    private fun onCategoryClicked(index: Int) {
        pendingCategory?.cancel()
        scope.launch {
            if (index != categoryIndex) selectCategory(index)
            focusPoster(0)
        }
    }

    private suspend fun selectCategory(index: Int) {
        val cat = cats.shown.getOrNull(index) ?: return
        // Picking from the full list drops a half-typed search; inside search results it stays.
        if (cats.query == null) searchBox.clearQuietly()
        categoryIndex = index
        categoryAdapter.selected = index
        showList(cat.key, cat.name)
    }

    private var listName = ""

    private suspend fun reloadList() = showList(categoryKey, listName)

    /**
     * Typing narrows the category list to a "Search: …" row (title matches, shown at once) and the
     * categories whose name matches. Clearing the field brings back every category, at the one
     * being browsed (or the one browsed before the search).
     */
    private fun onSearch(query: String?) {
        scope.launch {
            pendingCategory?.cancel()
            if (query != null) {
                if (cats.query == null) lastCategoryKey = categoryKey
                cats.filter(query)
                categoriesView.scrollToPosition(0)
                selectCategory(0)
            } else if (cats.query != null) {
                val back = categoryKey.takeUnless { Repository.isSearch(it) } ?: lastCategoryKey
                cats.filter(null)
                val i = cats.indexOf(back).coerceAtLeast(0)
                categoriesView.scrollToPosition(i)
                selectCategory(i)
            }
        }
    }

    /** Category shown before the search started. */
    private var lastCategoryKey: String? = null

    /** Search key: to the titles found, or, if none, to the first matching category. */
    private fun onSearchSubmit() {
        if (count == 0 && cats.shown.size > 1) {
            categoriesView.scrollToPosition(1)
            categoriesView.post { categoriesView.findViewHolderForAdapterPosition(1)?.itemView?.requestFocus() }
        } else {
            focusPoster(0)
        }
    }

    private suspend fun showList(key: String, name: String) {
        val p = playlist ?: return
        categoryKey = key
        listName = name
        count = graph.repo.count(p.id, type, key)
        categoryTitle.text = "$name  ($count)"
        // Favourites and Continue watching keep their own order.
        sortButton.visibility = if (key == Repository.KEY_FAV || key == Repository.KEY_CONTINUE) View.INVISIBLE else View.VISIBLE
        empty.visibility = if (count == 0) View.VISIBLE else View.GONE
        val order = sort
        posterAdapter.reset(count) { offset, limit -> graph.repo.page(p.id, type, key, offset, limit, order) }
        grid.scrollToPosition(0)
    }

    private fun updateSortLabel() = sortButton.setText(
        when (sort) {
            Sort.NEWEST -> R.string.sort_newest
            Sort.RATING -> R.string.sort_rating
            Sort.NAME -> R.string.sort_name
            Sort.PROVIDER -> R.string.sort_provider
        },
    )

    private fun focusPoster(index: Int) {
        if (count == 0) return
        val i = index.coerceIn(0, count - 1)
        grid.scrollToPosition(i)
        var tries = 0
        fun attempt() {
            val v = grid.findViewHolderForAdapterPosition(i)?.itemView
            if (v != null) v.requestFocus() else if (++tries < 5) grid.post { attempt() }
        }
        grid.post { attempt() }
    }

    private fun onPosterClicked(index: Int, row: EntryRow) {
        val p = playlist ?: return
        lastOpened = index
        activity.push(VodDetailScreen(activity, p, type, row) { favChanged = it })
    }

    private companion object {
        const val COLUMNS = 5
    }
}
