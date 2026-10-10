package io.github.sardinemehico.iptvplayer.ui

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import io.github.sardinemehico.iptvplayer.MainActivity
import io.github.sardinemehico.iptvplayer.R

/**
 * Trailer button: the provider's YouTube trailer when it gives one, else a YouTube search for
 * "<title> <year> trailer". Opens SmartTube or the YouTube app (TV or phone), else the browser.
 */
object Trailers {

    /**
     * SmartTube first when installed: the official YouTube TV app refuses to run on many
     * uncertified boxes ("YouTube is not supported on this device"), SmartTube doesn't.
     */
    private val YOUTUBE_APPS = listOf(
        "com.teamsmart.videomanager.tv", "com.liskovsoft.smarttubetv.beta", "com.liskovsoft.smarttubetv",
        "com.google.android.youtube.tv", "com.amazon.firetv.youtube", "com.google.android.youtube",
    )

    fun open(activity: MainActivity, trailer: String?, title: String, released: String?) {
        val id = trailer?.trim()?.let { t ->
            Regex("(?:v=|youtu\\.be/|embed/|shorts/)([A-Za-z0-9_-]{11})").find(t)?.groupValues?.get(1) ?: t.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
        }
        val year = released?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }
        val clean = io.github.sardinemehico.iptvplayer.data.online.SubQuery.fromName(title, year).let { listOfNotNull(it.title, it.year).joinToString(" ") }
        activity.graphStopPlayback()
        if (id != null) {
            val watch = Uri.parse("https://www.youtube.com/watch?v=$id")
            for (pkg in YOUTUBE_APPS) if (start(activity, Intent(Intent.ACTION_VIEW, watch).setPackage(pkg))) return
            if (start(activity, Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$id")))) return
            if (start(activity, Intent(Intent.ACTION_VIEW, watch))) return
        } else {
            val query = "$clean trailer"
            for (pkg in YOUTUBE_APPS) {
                if (start(activity, Intent(Intent.ACTION_SEARCH).setPackage(pkg).putExtra(SearchManager.QUERY, query).putExtra("query", query))) return
            }
            if (start(activity, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))))) return
        }
        activity.toast(activity.getString(R.string.trailer_no_app))
    }

    private fun start(activity: MainActivity, intent: Intent): Boolean = try {
        activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}
