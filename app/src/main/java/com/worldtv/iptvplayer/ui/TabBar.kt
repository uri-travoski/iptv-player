package com.worldtv.iptvplayer.ui

import android.view.View
import com.worldtv.iptvplayer.MainActivity
import com.worldtv.iptvplayer.R

/** The top tab bar of Live TV, Movies and Series (layout/tab_bar.xml). */
object TabBar {

    enum class Tab(val id: Int) { HOME(R.id.tab_home), LIVE(R.id.tab_live), MOVIES(R.id.tab_movies), SERIES(R.id.tab_series) }

    fun bind(activity: MainActivity, root: View, current: Tab) {
        val bar = root.findViewById<View>(R.id.tab_bar) ?: return
        if (activity.isMobileLayout) {
            val side = (12 * activity.resources.displayMetrics.density).toInt()
            bar.setPadding(side, bar.paddingTop, side, bar.paddingBottom)
        }
        for (tab in Tab.values()) {
            val v = bar.findViewById<View>(tab.id)
            v.isActivated = tab == current
            v.setOnClickListener { if (tab != current) activity.switchTab(tab) }
        }
    }
}
