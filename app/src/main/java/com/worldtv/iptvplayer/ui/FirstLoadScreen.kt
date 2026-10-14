package com.worldtv.iptvplayer.ui

import android.view.View
import android.widget.TextView
import com.worldtv.iptvplayer.MainActivity
import com.worldtv.iptvplayer.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * First start, over Home: "please wait" with a spinner while the free demo playlist (Free TV)
 * loads, and a word on what a paid subscription adds. The load itself is app-wide
 * (AppGraph.startFirstLoad), so a rebuilt UI shows this screen again and waits for the same load.
 * Done: back to Home. Failed: Try again, or Add my own playlist.
 */
class FirstLoadScreen(activity: MainActivity) : Screen(activity) {

    override val root: View = inflater.inflate(R.layout.screen_first_load, null)
    private val spinner: View = root.findViewById(R.id.first_load_spinner)
    private val status: TextView = root.findViewById(R.id.first_load_status)
    private val buttons: View = root.findViewById(R.id.first_load_buttons)
    private val retry: View = root.findViewById(R.id.first_load_retry)
    private val own: View = root.findViewById(R.id.first_load_own)
    private var waiting: Job? = null

    init {
        retry.setOnClickListener { graph.startFirstLoad(); watch() }
        own.setOnClickListener {
            graph.firstLoadShown = true
            activity.pop()
            activity.push(AddPlaylistScreen(activity))
        }
    }

    override fun onShown() {
        activity.hideVideo()
        watch()
    }

    override fun onHidden() {
        activity.keepScreenOn(false)
    }

    /** While loading there is nothing to go back to; after a failure Back goes on to Home. */
    override fun onBack(): Boolean {
        if (buttons.visibility == View.VISIBLE) graph.firstLoadShown = true
        return buttons.visibility != View.VISIBLE
    }

    private fun watch() {
        val load = graph.firstLoad ?: graph.startFirstLoad()
        waiting?.cancel()
        spinner.visibility = View.VISIBLE
        buttons.visibility = View.GONE
        status.setTextColor(activity.themeColor(R.attr.wtTextSecondary))
        activity.keepScreenOn(true) // a sleeping phone drops the connection
        waiting = scope.launch {
            val progress = launch { graph.firstLoadProgress.collect { status.text = it } }
            try {
                load.await()
                progress.cancel()
                graph.firstLoadShown = true
                activity.keepScreenOn(false)
                activity.pop()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                progress.cancel()
                activity.keepScreenOn(false)
                spinner.visibility = View.GONE
                status.setTextColor(activity.getColor(R.color.error))
                status.text = activity.getString(R.string.first_load_failed, e.message ?: e.javaClass.simpleName)
                buttons.visibility = View.VISIBLE
                retry.requestFocus()
            }
        }
    }
}
