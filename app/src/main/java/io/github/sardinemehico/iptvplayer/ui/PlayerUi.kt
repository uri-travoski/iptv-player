package io.github.sardinemehico.iptvplayer.ui

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import io.github.sardinemehico.iptvplayer.App
import io.github.sardinemehico.iptvplayer.MainActivity
import io.github.sardinemehico.iptvplayer.R

/** Control-bar actions shared by the live and the movie/episode player. */
@OptIn(UnstableApi::class)
object PlayerUi {

    private val MODES = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
    )

    /** Fit → Stretch → Zoom, remembered for next time. */
    fun cycleAspect(activity: MainActivity) {
        val view = activity.playerView
        val next = MODES[(MODES.indexOf(view.resizeMode) + 1).mod(MODES.size)]
        view.resizeMode = next
        App.graph.prefs.resizeMode = next
    }

    /** The aspect button is an icon: say the new mode briefly, and keep it as the button's description. */
    fun showAspect(activity: MainActivity, button: View) {
        val label = activity.getString(aspectLabel(activity))
        button.contentDescription = label
        activity.toast(label)
    }

    fun aspectLabel(activity: MainActivity): Int = when (activity.playerView.resizeMode) {
        AspectRatioFrameLayout.RESIZE_MODE_FILL -> R.string.ctl_aspect_fill
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> R.string.ctl_aspect_zoom
        else -> R.string.ctl_aspect_fit
    }

    /**
     * Audio (C.TRACK_TYPE_AUDIO) or subtitle (C.TRACK_TYPE_TEXT) picker.
     * [onDone] runs when the picker closes, or straight away if there is nothing to pick.
     */
    fun chooseTrack(activity: MainActivity, type: Int, onDone: () -> Unit) {
        val player = App.graph.player
        val tracks = player.tracks(type)
        val isText = type == C.TRACK_TYPE_TEXT
        if (tracks.isEmpty()) {
            activity.toast(activity.getString(R.string.no_tracks))
            onDone()
            return
        }
        val labels = ArrayList<String>()
        if (isText) labels += activity.getString(R.string.track_off)
        tracks.forEach { labels += it.label }
        val offset = if (isText) 1 else 0
        val selected = tracks.indexOfFirst { it.selected }
        val checked = when {
            isText && player.isTrackTypeDisabled(type) -> 0
            selected >= 0 -> selected + offset
            isText -> 0
            else -> -1
        }
        AlertDialog.Builder(activity)
            .setTitle(if (isText) R.string.ctl_subs else R.string.ctl_audio)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { d, which ->
                player.selectTrack(type, if (isText && which == 0) null else tracks[which - offset])
                d.dismiss()
            }
            .setOnDismissListener { onDone() }
            .show()
    }

    /**
     * Hands the stream to another installed player (VLC, MX Player...), as IBO does for streams
     * it can't play. Our player is stopped first so the provider sees one connection.
     */
    fun openExternal(activity: MainActivity, url: String, title: String) {
        App.graph.player.stop()
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(url), "video/*")
            .putExtra("title", title)
        try {
            activity.startActivity(Intent.createChooser(view, activity.getString(R.string.open_with)))
        } catch (e: ActivityNotFoundException) {
            activity.toast(activity.getString(R.string.no_external_player))
        }
    }

    /** 1:05:09 or 5:09. */
    fun time(ms: Long): String {
        val total = (ms.coerceAtLeast(0) / 1000).toInt()
        val h = total / 3600
        val m = total / 60 % 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}

/**
 * Names the player's icon buttons: the focused one (remote) gets a label just above it, taken
 * from its content description; on touch, a long-press shows Android's tooltip instead.
 */
class OsdTips(private val tip: TextView, private val buttons: List<View>) {

    init {
        for (b in buttons) {
            ViewCompat.setTooltipText(b, b.contentDescription)
            b.setOnFocusChangeListener { v, hasFocus -> if (hasFocus) show(v) else if (!buttons.any { it.isFocused }) hide() }
        }
    }

    /** After a button's description changed (Pause → Play, Favourite → Unfavourite...). */
    fun refresh() {
        for (b in buttons) ViewCompat.setTooltipText(b, b.contentDescription)
        buttons.firstOrNull { it.isFocused }?.let { show(it) }
    }

    fun hide() {
        tip.visibility = View.INVISIBLE
    }

    private fun show(button: View) {
        tip.text = button.contentDescription
        tip.visibility = View.VISIBLE
        // Laid out only now that it has text; place it once its size is known.
        tip.post { place(button) }
    }

    private fun place(button: View) {
        val parent = tip.parent as View
        val b = IntArray(2).also { button.getLocationInWindow(it) }
        val p = IntArray(2).also { parent.getLocationInWindow(it) }
        val gap = 6 * button.resources.displayMetrics.density
        val x = b[0] - p[0] + (button.width - tip.width) / 2f
        tip.translationX = x.coerceIn(0f, (parent.width - tip.width).toFloat().coerceAtLeast(0f))
        tip.translationY = b[1] - p[1] - tip.height - gap
    }
}
