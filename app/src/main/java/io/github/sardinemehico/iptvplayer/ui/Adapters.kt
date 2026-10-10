package io.github.sardinemehico.iptvplayer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil3.dispose
import coil3.load
import coil3.request.error
import coil3.request.placeholder
import io.github.sardinemehico.iptvplayer.R
import io.github.sardinemehico.iptvplayer.data.repo.CategoryRow
import io.github.sardinemehico.iptvplayer.data.repo.EntryRow
import io.github.sardinemehico.iptvplayer.data.repo.Repository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The category list beside a library, narrowed while a search is typed: a "Search: …" row (the
 * name matches) followed by the provider categories whose name contains the text, so a search
 * for "cricket" also finds the CRICKET category. Few rows, so it filters in memory.
 */
class CategoryList(private val adapter: CategoryAdapter, private val searchLabel: (String) -> String) {

    /** Every category, as loaded. Setting it clears any filter. */
    var all: List<CategoryRow> = emptyList()
        set(value) {
            field = value
            filter(null)
        }

    /** What the list shows now; adapter positions index into this. */
    var shown: List<CategoryRow> = emptyList()
        private set

    /** The search text the list is narrowed to, or null. */
    var query: String? = null
        private set

    fun filter(text: String?) {
        query = text
        shown = if (text == null) {
            all
        } else {
            listOf(CategoryRow(Repository.searchKey(text), searchLabel(text))) +
                all.filter { !Repository.isBuiltIn(it.key) && it.name.contains(text, ignoreCase = true) }
        }
        adapter.selected = -1
        adapter.playing = -1
        adapter.items = shown
    }

    fun indexOf(key: String?): Int = if (key == null) -1 else shown.indexOfFirst { it.key == key }
}

/**
 * Categories are few (tens to hundreds), so they are held in memory. [layout]: a TV list row, or
 * row_category_chip for the Mobile layout's sideways strip.
 */
class CategoryAdapter(
    private val onFocused: (Int) -> Unit,
    private val onClicked: (Int) -> Unit,
    private val layout: Int = R.layout.row_category,
    /** Long-press (touch): what the remote's Menu key does on a category. */
    private val onLongClicked: ((Int) -> Unit)? = null,
) : RecyclerView.Adapter<CategoryAdapter.VH>() {

    var items: List<CategoryRow> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    /** Category whose items are listed (outlined). */
    var selected = -1
        set(value) {
            val old = field
            field = value
            if (old >= 0) notifyItemChanged(old)
            if (value >= 0) notifyItemChanged(value)
        }

    /** Category the playing channel was chosen from (filled, like the playing channel), or -1. */
    var playing = -1
        set(value) {
            val old = field
            field = value
            if (old >= 0) notifyItemChanged(old)
            if (value >= 0) notifyItemChanged(value)
        }

    init {
        setHasStableIds(true)
    }

    override fun getItemCount() = items.size
    override fun getItemId(position: Int) = position.toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(layout, parent, false) as TextView
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.text.text = items[position].name
        holder.text.isSelected = position == selected
        holder.text.isActivated = position == playing
    }

    inner class VH(val text: TextView) : RecyclerView.ViewHolder(text) {
        init {
            text.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus && bindingAdapterPosition != RecyclerView.NO_POSITION) onFocused(bindingAdapterPosition)
            }
            text.setOnClickListener {
                if (bindingAdapterPosition != RecyclerView.NO_POSITION) onClicked(bindingAdapterPosition)
            }
            if (onLongClicked != null) {
                text.setOnLongClickListener {
                    if (bindingAdapterPosition != RecyclerView.NO_POSITION) onLongClicked.invoke(bindingAdapterPosition)
                    true
                }
            }
        }
    }
}

/**
 * Channel list (or, with [layout] = row_poster, a movie/series poster grid) backed by SQLite pages of [PAGE] rows. Only a few pages stay in memory, so a
 * 50,000-channel "All" list costs the same as a 50-channel one. Rows not loaded yet show a
 * placeholder and fill in when their page arrives; the UI thread never waits.
 */
class PagedEntryAdapter(
    private val scope: CoroutineScope,
    private val onClicked: (Int, EntryRow) -> Unit,
    private val layout: Int = R.layout.row_channel,
    /** Long-press (touch): what the remote's Menu key does, e.g. toggle favourite. */
    private val onLongClicked: ((Int, EntryRow) -> Unit)? = null,
) : RecyclerView.Adapter<PagedEntryAdapter.VH>() {

    private var loader: (suspend (offset: Int, limit: Int) -> List<EntryRow>)? = null
    private var total = 0
    private var generation = 0
    private val pages = HashMap<Int, List<EntryRow>>()
    private val loading = HashSet<Int>()

    /** Item id of the stream now playing, highlighted in the list. */
    var playingItemId: String? = null
        set(value) {
            field = value
            notifyItemRangeChanged(0, total, PAYLOAD_STATE)
        }

    init {
        setHasStableIds(true)
    }

    fun reset(count: Int, load: suspend (offset: Int, limit: Int) -> List<EntryRow>) {
        generation++
        total = count
        loader = load
        pages.clear()
        loading.clear()
        notifyDataSetChanged()
    }

    fun rowAt(position: Int): EntryRow? = pages[position / PAGE]?.getOrNull(position % PAGE)

    fun setFavourite(position: Int, favourite: Boolean) {
        val page = position / PAGE
        val rows = pages[page] ?: return
        val i = position % PAGE
        if (i !in rows.indices) return
        pages[page] = rows.toMutableList().also { it[i] = it[i].copy(favourite = favourite) }
        notifyItemChanged(position)
    }

    override fun getItemCount() = total
    override fun getItemId(position: Int) = position.toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(layout, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_STATE }) {
            holder.itemView.isActivated = rowAt(position)?.itemId == playingItemId && playingItemId != null
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.num?.text = (position + 1).toString()
        val row = rowAt(position)
        if (row == null) {
            holder.name.text = ""
            holder.fav?.visibility = View.GONE
            showLogo(holder, null)
            holder.itemView.isActivated = false
            request(position / PAGE)
            return
        }
        holder.name.text = row.name
        holder.fav?.visibility = if (row.favourite) View.VISIBLE else View.GONE
        showLogo(holder, row.logo)
        holder.itemView.isActivated = row.itemId == playingItemId
    }

    /**
     * Channel rows always show something at once: the muted stand-in is set directly (no image
     * pipeline) when there is no logo, and shown while a logo downloads or if its link is broken.
     */
    private fun showLogo(holder: VH, url: String?) {
        if (layout != R.layout.row_channel) {
            holder.logo.load(url)
            return
        }
        if (url.isNullOrBlank()) {
            holder.logo.dispose() // a recycled row may still be loading another channel's logo
            holder.logo.setImageResource(R.drawable.ic_channel_placeholder)
            return
        }
        holder.logo.load(url) {
            placeholder(R.drawable.ic_channel_placeholder)
            error(R.drawable.ic_channel_placeholder)
        }
    }

    private fun request(page: Int) {
        if (page in pages || page in loading) return
        val load = loader ?: return
        val gen = generation
        loading += page
        scope.launch {
            val rows = try {
                load(page * PAGE, PAGE)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == generation) loading -= page
                return@launch
            }
            if (gen != generation) return@launch
            loading -= page
            pages[page] = rows
            evictAround(page)
            notifyRows(page * PAGE, rows.size)
        }
    }

    private var recycler: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        recycler = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        recycler = null
    }

    /**
     * A page can arrive while the list is in the middle of a layout pass (seen when a category is
     * tapped while Live TV is still loading): RecyclerView forbids changes then and crashes, so
     * the update waits for the next frame.
     */
    private fun notifyRows(start: Int, count: Int) {
        val rv = recycler
        if (rv != null && rv.isComputingLayout) {
            val gen = generation
            rv.post { if (gen == generation) notifyItemRangeChanged(start, count) }
        } else {
            notifyItemRangeChanged(start, count)
        }
    }

    private fun evictAround(center: Int) {
        if (pages.size <= MAX_PAGES) return
        val far = pages.keys.sortedByDescending { kotlin.math.abs(it - center) }
        for (k in far.take(pages.size - MAX_PAGES)) pages.remove(k)
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val num: TextView? = view.findViewById(R.id.num)
        val logo: ImageView = view.findViewById(R.id.logo)
        val name: TextView = view.findViewById(R.id.name)
        val fav: TextView? = view.findViewById(R.id.fav)

        init {
            view.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                rowAt(pos)?.let { onClicked(pos, it) }
            }
            if (onLongClicked != null) {
                view.setOnLongClickListener {
                    val pos = bindingAdapterPosition
                    val row = if (pos == RecyclerView.NO_POSITION) null else rowAt(pos)
                    row?.let { onLongClicked.invoke(pos, it) }
                    row != null
                }
            }
        }
    }

    companion object {
        const val PAGE = 100
        private const val MAX_PAGES = 8
        private const val PAYLOAD_STATE = "state"
    }
}
