package com.worldtv.iptvplayer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.worldtv.iptvplayer.MainActivity
import com.worldtv.iptvplayer.R
import com.worldtv.iptvplayer.data.source.AdultNames
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

    /**
     * Leaving with changes: go back at once and re-check every playlist in the background (a big
     * playlist takes a while on a slow box; nothing waits for it).
     */
    override fun onBack(): Boolean {
        if (changed) {
            changed = false
            activity.toast(activity.getString(R.string.adult_words_applying))
            graph.rescanAdult { activity.toast(activity.getString(R.string.adult_words_applied)) }
        }
        return false
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

    /** A stray OK on the remote mustn't delete a word: ask first. */
    private fun confirmRemove(position: Int) {
        val word = words.getOrNull(position) ?: return
        android.app.AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.adult_words_remove_q, word))
            .setPositiveButton(R.string.adult_words_remove) { _, _ -> remove(position) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
                text.setOnClickListener { bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let(::confirmRemove) }
            }
        }
    }
}
