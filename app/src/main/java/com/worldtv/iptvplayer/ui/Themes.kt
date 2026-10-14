package com.worldtv.iptvplayer.ui

import android.content.Context
import com.worldtv.iptvplayer.R

/** App Settings > Theme: the look of every screen. Changing it rebuilds the UI. */
object Themes {
    const val CLASSIC = "classic"
    const val GLASS = "glass"
    const val SHOWTIME = "showtime"
    val ALL = listOf(CLASSIC, GLASS, SHOWTIME)

    fun style(theme: String) = when (theme) {
        GLASS -> R.style.Theme_IptvPlayer_Glass
        SHOWTIME -> R.style.Theme_IptvPlayer_Showtime
        else -> R.style.Theme_IptvPlayer
    }

    fun label(theme: String) = when (theme) {
        GLASS -> R.string.theme_glass
        SHOWTIME -> R.string.theme_showtime
        else -> R.string.theme_classic
    }
}

/** A colour of the current theme ([attr] is one of R.attr.wt*). */
fun Context.themeColor(attr: Int): Int {
    val a = obtainStyledAttributes(intArrayOf(attr))
    try {
        return a.getColor(0, 0)
    } finally {
        a.recycle()
    }
}
