package com.worldtv.desktop.ui

import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Dates and times as the system's locale writes them. */
object Dates {
    private val dayFormat = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
    private val timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault())
    private val weekday = SimpleDateFormat("EEE d MMM", Locale.getDefault())

    fun day(ms: Long): String = synchronized(dayFormat) { dayFormat.format(Date(ms)) }

    /** Clock time of a guide listing ([epoch] in seconds). */
    fun time(epoch: Long): String = synchronized(timeFormat) { timeFormat.format(Date(epoch * 1000)) }

    fun dayTime(ms: Long): String = day(ms) + " " + time(ms / 1000)

    /** "just now", "5 min ago", "3 h ago", "2 days ago". */
    fun ago(ms: Long, now: Long = System.currentTimeMillis()): String {
        val d = (now - ms).coerceAtLeast(0) / 1000
        return when {
            d < 60 -> "just now"
            d < 3600 -> "${d / 60} min ago"
            d < 86400 -> "${d / 3600} h ago"
            d < 2 * 86400 -> "yesterday"
            else -> "${d / 86400} days ago"
        }
    }

    /** Today / Yesterday / "Mon 12 Oct" for the catch-up list ([epoch] in seconds). */
    fun weekday(epoch: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = epoch * 1000 }
        val today = Calendar.getInstance()
        fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        if (same(c, today)) return "Today"
        today.add(Calendar.DAY_OF_YEAR, -1)
        if (same(c, today)) return "Yesterday"
        return synchronized(weekday) { weekday.format(c.time) }
    }

    /** 1:02:03 or 12:34 for a position in a film. */
    fun clock(ms: Long): String {
        val s = ms.coerceAtLeast(0) / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** "12 min 30 s", "2 h 5 min". */
    fun duration(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 60 -> "$s s"
            s < 3600 -> "${s / 60} min ${s % 60} s"
            else -> "${s / 3600} h ${(s % 3600) / 60} min"
        }
    }
}
