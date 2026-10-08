package io.github.sardinemehico.iptvplayer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.sardinemehico.iptvplayer.MainActivity
import io.github.sardinemehico.iptvplayer.R
import io.github.sardinemehico.iptvplayer.data.source.AdultNames
import kotlinx.coroutines.launch

/**
 * Admin: the adult words that hide categories (one tab) and channels, movies and series (the
 * other), for every playlist. Opened from a playlist's Categories screen, behind its PIN. On
 * leaving, if the words changed, every playlist's automatic hides are worked out again.
 */
class AdultWordsScreen(activity: MainActivity) : Screen(activity) {

    override val root: View = inflater.inflate(R.layout.screen_adult_words, null)
    private val tabCategories: View = root.findViewById(R.id.tab_categories)
    private val tabEntries: View = root.findViewById(R.id.tab_entries)
    private val help: TextView = root.findViewById(R.id.help)
    private val count: TextView = root.findViewById(R.id.count)
    private val field: EditText = root.findViewById(R.id.word)
    private val list: RecyclerView = root.findViewById(R.id.list)
    private val adapter = Rows()

    private val categoryWords = graph.prefs.adultCategoryWords.toMutableList()
    private val entryWords = graph.prefs.adultEntryWords.toMutableList()
    private var showingCategories = true
    private var changed = false
    private var applying = false

    private val words get() = if (showingCategories) categoryWords else entryWords

    init {
        fullWidthOnMobile(R.id.form)
        tabCategories.setOnClickListener { show(categories = true) }
        tabEntries.setOnClickListener { show(categories = false) }
        root.findViewById<View>(R.id.add).setOnClickListener { add() }
        root.findViewById<View>(R.id.restore).setOnClickListener { restore() }
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { add(); true } else false
        }
        list.layoutManager = LinearLayoutManager(activity)
        list.adapter = adapter
        list.itemAnimator = null
        show(categories = true)
    }

    override fun onShown() {
        activity.hideVideo()
        tabCategories.requestFocus()
    }

    /** Leaving with changes: re-check every playlist first, then go back. */
    override fun onBack(): Boolean {
        if (applying) return true
        if (!changed) return false
        applying = true
        activity.toast(activity.getString(R.string.adult_words_applying))
        activity.keepScreenOn(true)
        scope.launch {
            try {
                val names = graph.adultNames()
                graph.repo.playlists().forEach { graph.repo.autoHideAdult(it.id, names) }
                activity.toast(activity.getString(R.string.adult_words_applied))
            } finally {
                activity.keepScreenOn(false)
                changed = false
                applying = false
                activity.pop()
            }
        }
        return true
    }

    private fun show(categories: Boolean) {
        showingCategories = categories
        tabCategories.isActivated = categories
        tabEntries.isActivated = !categories
        help.setText(if (categories) R.string.adult_words_help_categories else R.string.adult_words_help_entries)
        refresh()
    }

    private fun refresh() {
        adapter.notifyDataSetChanged()
        count.text = activity.getString(R.string.adult_words_count, words.size)
    }

    private fun save() {
        if (showingCategories) graph.prefs.adultCategoryWords = categoryWords else graph.prefs.adultEntryWords = entryWords
        changed = true
    }

    private fun add() {
        val word = field.text.toString().trim().lowercase().replace(Regex("\\s+"), " ")
        if (word.isEmpty() || word == "*") return
        if (words.any { it.equals(word, ignoreCase = true) }) {
            activity.toast(activity.getString(R.string.adult_words_exists, word))
            return
        }
        words.add(0, word)
        save()
        field.setText("")
        refresh()
        list.scrollToPosition(0)
    }

    private fun remove(position: Int) {
        val word = words.getOrNull(position) ?: return
        words.removeAt(position)
        save()
        refresh()
        activity.toast(activity.getString(R.string.adult_words_removed, word))
        // Keep the remote's focus in the list, on the next word.
        if (words.isNotEmpty()) {
            val next = position.coerceAtMost(words.size - 1)
            list.post { list.findViewHolderForAdapterPosition(next)?.itemView?.requestFocus() }
        }
    }

    private fun restore() {
        words.clear()
        words.addAll(if (showingCategories) AdultNames.DEFAULT_CATEGORY_WORDS else AdultNames.DEFAULT_ENTRY_WORDS)
        save()
        refresh()
        activity.toast(activity.getString(R.string.adult_words_restored))
    }

    private inner class Rows : RecyclerView.Adapter<Rows.VH>() {
        override fun getItemCount() = words.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.row_admin, parent, false) as TextView)

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.text.text = "✕   " + words[position]
            holder.text.setTextColor(activity.getColor(R.color.text_primary))
        }

        inner class VH(val text: TextView) : RecyclerView.ViewHolder(text) {
            init {
                text.setOnClickListener { bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let(::remove) }
            }
        }
    }
}
