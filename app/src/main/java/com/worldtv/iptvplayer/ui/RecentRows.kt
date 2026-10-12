package com.worldtv.iptvplayer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil3.ImageLoader
import coil3.dispose
import coil3.load
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.worldtv.iptvplayer.AppGraph
import com.worldtv.iptvplayer.R
import com.worldtv.iptvplayer.data.repo.EntryRow
import kotlinx.coroutines.CancellationException

/**
 * Posters of Home's Recently added rows. Downloaded once per playlist load ([prefetch]) into
 * their own cache (AppGraph.homeImages); Home shows them from there only ([show]), so pressing
 * Home never touches the network.
 */
object HomePosters {

    /** True while a [prefetch] runs, so a second Home visit doesn't start another. */
    @Volatile
    var busy = false
        private set

    /**
     * Downloads [urls] into the home cache, one at a time (gentle on a weak box and on a stream
     * that may be starting). Decoded tiny and not kept in memory: only the file matters here.
     * Broken links are skipped. Returns how many came (or are already cached), once all were tried.
     */
    suspend fun prefetch(graph: AppGraph, context: android.content.Context, urls: List<String>): Int {
        if (busy) return 0
        busy = true
        var got = 0
        try {
            for (url in urls.distinct()) {
                try {
                    val result = graph.homeImages.execute(
                        ImageRequest.Builder(context).data(url).size(32, 48)
                            .memoryCachePolicy(CachePolicy.DISABLED).build(),
                    )
                    if (result is coil3.request.SuccessResult) got++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A broken poster link: the row shows the title's name instead.
                }
            }
        } finally {
            busy = false
        }
        return got
    }

    /** [url] from the home cache into [view]; nothing (the name behind shows) if it isn't there. */
    fun show(view: ImageView, url: String?, loader: ImageLoader) {
        if (url.isNullOrBlank()) {
            view.dispose()
            view.setImageDrawable(null)
            return
        }
        view.load(url, loader) { networkCachePolicy(CachePolicy.DISABLED) }
    }
}

/** One Recently added row: posters across. OK/tap opens the title; focus reports it (TV backdrop). */
class RecentAdapter(
    private val loader: ImageLoader,
    private val mobile: Boolean,
    /** Tag prefix of the cells (e.g. "recent:1:"), so Home can give focus back to the same one. */
    private val tagPrefix: String,
    private val onClicked: (Int, EntryRow) -> Unit,
    private val onFocused: (Int, EntryRow) -> Unit,
) : RecyclerView.Adapter<RecentAdapter.VH>() {

    var rows: List<EntryRow> = emptyList()
        private set

    /** Replaces the titles; a list identical to the one shown changes nothing (no flicker, focus kept). */
    fun submit(list: List<EntryRow>) {
        if (list.map { it.itemId to it.logo } == rows.map { it.itemId to it.logo }) return
        rows = list
        notifyDataSetChanged()
    }

    /** Posters were just downloaded: show them. */
    fun refreshImages() = notifyItemRangeChanged(0, rows.size)

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val frame: View = v.findViewById(R.id.poster_frame)
        val poster: ImageView = v.findViewById(R.id.poster)
        val fallback: TextView = v.findViewById(R.id.poster_fallback)
        val name: TextView = v.findViewById(R.id.poster_name)
    }

    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.row_home_poster, parent, false)
        val holder = VH(v)
        if (mobile) {
            val d = parent.resources.displayMetrics.density
            holder.frame.layoutParams = holder.frame.layoutParams.apply { width = (84 * d).toInt(); height = (126 * d).toInt() }
            holder.name.visibility = View.VISIBLE
            v.isFocusable = false
        }
        v.setOnClickListener {
            val i = holder.bindingAdapterPosition
            rows.getOrNull(i)?.let { onClicked(i, it) }
        }
        v.setOnFocusChangeListener { _, has ->
            val i = holder.bindingAdapterPosition
            if (has) rows.getOrNull(i)?.let { onFocused(i, it) }
        }
        return holder
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = rows[position]
        holder.itemView.tag = tagPrefix + position
        holder.itemView.contentDescription = row.name
        holder.fallback.text = row.name
        holder.name.text = row.name
        HomePosters.show(holder.poster, row.logo, loader)
    }
}
