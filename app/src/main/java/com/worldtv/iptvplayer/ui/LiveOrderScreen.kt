package com.worldtv.iptvplayer.ui

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.worldtv.iptvplayer.MainActivity
import com.worldtv.iptvplayer.R
import com.worldtv.iptvplayer.data.model.ContentType
import com.worldtv.iptvplayer.data.repo.CategoryRow
import com.worldtv.iptvplayer.data.repo.Playlist
import com.worldtv.iptvplayer.data.repo.Repository
import kotlinx.coroutines.launch

/**
 * Settings > Live channel order. Without [category]: the live categories; OK opens one. With
 * [category]: its channels in the order Live TV shows them. OK (tap) on a channel picks it up
 * (⇅); Up / Down move it; OK or Back puts it down. The buttons move the picked channel too
 * (touch), and "Provider order" undoes the category's changes. Saved at once; reloads keep it.
 */
class LiveOrderScreen(
    activity: MainActivity,
    private val playlist: Playlist,
    private val category: CategoryRow? = null,
) : Screen(activity) {

    override val root: View = inflater.inflate(R.layout.screen_live_order, null)
    private val list: RecyclerView = root.findViewById(R.id.list)
    private val hint: TextView = root.findViewById(R.id.hint)
    private val rows = ArrayList<Pair<String, String>>() // id, name
    private val adapter = Rows()
    /** Index of the channel picked up, or -1. */
    private var picked = -1
    private var loaded = false

    init {
        fullWidthOnMobile(R.id.form)
        root.findViewById<TextView>(R.id.title).text =
            if (category == null) activity.getString(R.string.live_order_title) else category.name
        hint.setText(if (category == null) R.string.live_order_hint_categories else R.string.live_order_hint)
        list.layoutManager = LinearLayoutManager(activity)
        list.adapter = adapter
        list.itemAnimator = null
        if (category != null) {
            root.findViewById<View>(R.id.buttons).visibility = View.VISIBLE
            root.findViewById<View>(R.id.move_top).setOnClickListener { moveTo(0) }
            root.findViewById<View>(R.id.move_up).setOnClickListener { moveTo(picked - 1) }
            root.findViewById<View>(R.id.move_down).setOnClickListener { moveTo(picked + 1) }
            root.findViewById<View>(R.id.move_bottom).setOnClickListener { moveTo(rows.size - 1) }
            root.findViewById<View>(R.id.order_reset).setOnClickListener { resetOrder() }
        }
    }

    override fun onShown() {
        activity.hideVideo()
        graph.player.stop()
        if (!loaded) {
            loaded = true
            scope.launch { load(focus = 0) }
        }
    }

    private suspend fun load(focus: Int) {
        rows.clear()
        if (category == null) {
            graph.repo.categories(playlist.id, ContentType.LIVE)
                .filterNot { Repository.isBuiltIn(it.key) }
                .forEach { rows += it.key to it.name }
        } else {
            graph.repo.liveOrder(playlist.id, category.key).forEach { rows += it.itemId to it.name }
        }
        picked = -1
        adapter.notifyDataSetChanged()
        focusRow(focus)
    }

    private fun focusRow(i: Int) {
        if (rows.isEmpty()) return
        val at = i.coerceIn(0, rows.size - 1)
        (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(at, list.height / 3)
        list.post { list.findViewHolderForAdapterPosition(at)?.itemView?.requestFocus() }
    }

    private fun onRow(i: Int) {
        if (category == null) {
            val (key, name) = rows[i]
            activity.push(LiveOrderScreen(activity, playlist, CategoryRow(key, name)))
            return
        }
        val before = picked
        picked = if (picked == i) -1 else i
        if (before >= 0) adapter.notifyItemChanged(before)
        adapter.notifyItemChanged(i)
    }

    /** Moves the picked channel to [to]; it stays picked and focused. */
    private fun moveTo(to: Int) {
        val cat = category ?: return
        val from = picked
        if (from < 0) return activity.toast(activity.getString(R.string.order_pick_first))
        val target = to.coerceIn(0, rows.size - 1)
        if (target == from) return
        rows.add(target, rows.removeAt(from))
        picked = target
        adapter.notifyItemMoved(from, target)
        // The numbers of every row in between change too.
        adapter.notifyItemRangeChanged(minOf(from, target), kotlin.math.abs(from - target) + 1)
        focusRow(target)
        scope.launch { graph.repo.moveLive(playlist.id, cat.key, from, target) }
    }

    private fun resetOrder() {
        val cat = category ?: return
        scope.launch {
            graph.repo.resetLiveOrder(playlist.id, cat.key)
            activity.toast(activity.getString(R.string.order_reset_done))
            load(focus = 0)
        }
    }

    /** While a channel is picked up, Up / Down move it instead of the cursor. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (picked < 0) return false
        val focused = activity.currentFocus ?: return false
        if (focused.parent !== list) return false
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { moveTo(picked - 1); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { moveTo(picked + 1); true }
            else -> false
        }
    }

    /** Back puts a picked channel down first. */
    override fun onBack(): Boolean {
        if (picked < 0) return false
        val was = picked
        picked = -1
        adapter.notifyItemChanged(was)
        return true
    }

    private inner class Rows : RecyclerView.Adapter<Rows.VH>() {
        inner class VH(val text: TextView) : RecyclerView.ViewHolder(text)

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.row_text, parent, false) as TextView
            val holder = VH(v)
            v.setOnClickListener { holder.bindingAdapterPosition.takeIf { it >= 0 }?.let { onRow(it) } }
            return holder
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val name = rows[position].second
            holder.text.text = when {
                category == null -> name
                position == picked -> "⇅   ${position + 1}.  $name"
                else -> "${position + 1}.  $name"
            }
            holder.text.isActivated = position == picked
        }
    }
}
