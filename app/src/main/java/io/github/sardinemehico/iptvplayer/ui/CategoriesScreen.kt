package io.github.sardinemehico.iptvplayer.ui

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.sardinemehico.iptvplayer.MainActivity
import io.github.sardinemehico.iptvplayer.R
import io.github.sardinemehico.iptvplayer.data.model.ContentType
import io.github.sardinemehico.iptvplayer.data.repo.CategoryRow
import io.github.sardinemehico.iptvplayer.data.repo.Playlist
import kotlinx.coroutines.launch

/**
 * Admin: what a playlist shows. With no [category], its categories per library (Live TV, Movies,
 * Series): OK hides or shows one, Menu or long-press opens it to hide single entries. With a
 * [category], that category's entries. Reached from App Settings behind the playlist's PIN, so
 * viewers can't undo it. Hidden items are left out of every list (see Repository.filter).
 */
class CategoriesScreen(
    activity: MainActivity,
    private val playlist: Playlist,
    private val category: CategoryRow? = null,
    private var type: ContentType = ContentType.LIVE,
) : Screen(activity) {

    /** One row: a category or an entry, hidden or not, and a note (hidden entries inside). */
    private class Row(val id: String, val name: String, var hidden: Boolean, val note: String = "", val cat: CategoryRow? = null)

    override val root: View = inflater.inflate(R.layout.screen_categories, null)
    private val tabs = mapOf(
        ContentType.LIVE to root.findViewById<View>(R.id.tab_live),
        ContentType.MOVIE to root.findViewById<View>(R.id.tab_movies),
        ContentType.SERIES to root.findViewById<View>(R.id.tab_series),
    )
    private val summary: TextView = root.findViewById(R.id.summary)
    private val list: RecyclerView = root.findViewById(R.id.list)
    private val adapter = Rows()
    private var loadedOnce = false

    init {
        fullWidthOnMobile(R.id.form)
        val title = root.findViewById<TextView>(R.id.title)
        if (category == null) {
            title.text = activity.getString(R.string.categories_title, playlist.name)
            tabs.forEach { (t, v) -> v.setOnClickListener { show(t) } }
            root.findViewById<View>(R.id.hide_adult).setOnClickListener { hideAdult() }
        } else {
            title.text = category.name
            (tabs.getValue(ContentType.LIVE).parent as View).visibility = View.GONE
            root.findViewById<View>(R.id.hide_adult).visibility = View.GONE
            root.findViewById<View>(R.id.adult_note).visibility = View.GONE
            root.findViewById<TextView>(R.id.hint).setText(R.string.entries_hint)
        }
        root.findViewById<View>(R.id.show_all).setOnClickListener { setAll(hidden = false) }
        root.findViewById<View>(R.id.hide_all).setOnClickListener { setAll(hidden = true) }
        list.layoutManager = LinearLayoutManager(activity)
        list.adapter = adapter
        list.itemAnimator = null
    }

    override fun onShown() {
        activity.hideVideo()
        graph.player.stop()
        show(type) // also after coming back from a category: its hidden count may have changed
    }

    private fun show(t: ContentType, focusAt: Int = -1) {
        type = t
        tabs.forEach { (k, v) -> v.isActivated = k == t }
        scope.launch {
            adapter.rows = if (category == null) {
                graph.repo.categoriesForAdmin(playlist.id, t).map {
                    val note = if (it.hiddenEntries > 0) "   " + activity.getString(R.string.categories_hidden_entries, it.hiddenEntries) else ""
                    Row(it.row.key, it.row.name, it.hidden, note, it.row)
                }
            } else {
                graph.repo.entriesForAdmin(playlist.id, t, category.key).map { Row(it.itemId, it.name, it.hidden) }
            }
            updateSummary()
            if (focusAt >= 0) {
                list.scrollToPosition(focusAt)
                list.post { list.findViewHolderForAdapterPosition(focusAt)?.itemView?.requestFocus() }
            }
            if (!loadedOnce) {
                loadedOnce = true
                if (category == null) tabs.getValue(t).requestFocus() else list.post { list.getChildAt(0)?.requestFocus() }
            }
        }
    }

    private suspend fun save(ids: List<String>, hidden: Boolean) {
        if (category == null) graph.repo.setHidden(playlist.id, type, ids, hidden)
        else graph.repo.setEntriesHidden(playlist.id, type, ids, hidden)
    }

    private fun toggle(position: Int) {
        val row = adapter.rows.getOrNull(position) ?: return
        scope.launch {
            save(listOf(row.id), !row.hidden)
            if (category == null && row.hidden) {
                show(type, focusAt = position) // shown whole: refresh the "(N hidden)" notes, focus stays put
                return@launch
            }
            row.hidden = !row.hidden
            adapter.notifyItemChanged(position)
            updateSummary()
        }
    }

    private fun open(position: Int) {
        val cat = adapter.rows.getOrNull(position)?.cat ?: return
        activity.push(CategoriesScreen(activity, playlist, cat, type))
    }

    private fun setAll(hidden: Boolean) {
        scope.launch {
            save(adapter.rows.map { it.id }, hidden)
            show(type)
        }
    }

    private fun hideAdult() {
        val adult = adapter.rows.filter { !it.hidden && isAdult(it.name) }
        scope.launch {
            save(adult.map { it.id }, true)
            activity.toast(
                if (adult.isEmpty()) activity.getString(R.string.categories_adult_none)
                else activity.resources.getQuantityString(R.plurals.categories_adult_hidden, adult.size, adult.size),
            )
            show(type)
        }
    }

    private fun updateSummary() {
        val rows = adapter.rows
        summary.text = activity.getString(R.string.categories_summary, rows.count { !it.hidden }, rows.size)
    }

    private inner class Rows : RecyclerView.Adapter<Rows.VH>() {
        var rows: List<Row> = emptyList()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.row_admin, parent, false) as TextView)

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = rows[position]
            holder.text.text = (if (row.hidden) "✕   " else "✓   ") + row.name + row.note
            holder.text.setTextColor(activity.getColor(if (row.hidden) R.color.text_secondary else R.color.text_primary))
        }

        inner class VH(val text: TextView) : RecyclerView.ViewHolder(text) {
            private fun pos() = bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }

            init {
                text.setOnClickListener { pos()?.let(::toggle) }
                if (category == null) {
                    text.setOnLongClickListener { pos()?.let(::open); true }
                    text.setOnKeyListener { _, keyCode, event ->
                        if (keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_DOWN) {
                            pos()?.let(::open)
                            true
                        } else {
                            false
                        }
                    }
                }
            }
        }
    }

    private companion object {
        /** Category names that usually mean adult content, matched case-insensitively. */
        val ADULT = Regex("""\b(xxx|adults?|porn\w*|sex\w*|erotic\w*|playboy|hustler|brazzers|red\s*light)\b|18\s*\+|\+\s*18""", RegexOption.IGNORE_CASE)

        /** Names that contain those words but aren't adult content. */
        val NOT_ADULT = Regex("""adult\s*swim""", RegexOption.IGNORE_CASE)

        fun isAdult(name: String) = ADULT.containsMatchIn(name) && !NOT_ADULT.containsMatchIn(name)
    }
}
