package io.github.sardinemehico.iptvplayer.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Dates as the app shows them everywhere: 10-Oct-2026 (and 10-Oct-2026 21:05 with a time). */
object Dates {
    fun day(ms: Long): String = SimpleDateFormat("dd-MMM-yyyy", Locale.ENGLISH).format(Date(ms))
    fun dayTime(ms: Long): String = SimpleDateFormat("dd-MMM-yyyy HH:mm", Locale.ENGLISH).format(Date(ms))
}
