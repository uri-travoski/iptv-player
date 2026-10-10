package com.worldtv.iptvplayer.data.source

import java.util.Locale

/** Panel ratings: out of 10 ("7.3", "8.338"), or only out of 5 (rating_5based). */
object Ratings {

    /** The rating to keep, out of 10 as text; null when the panel has none (or says 0). */
    fun fromPanel(rating: String?, rating5: String?): String? {
        rating?.trim()?.takeIf { (it.replace(',', '.').toDoubleOrNull() ?: 0.0) > 0 }?.let { return it }
        val five = rating5?.trim()?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0 && it <= 5 } ?: return null
        return format(five * 2)
    }

    /** "8.338" -> "8.3", "7" -> "7.0"; null for no rating. Values over 10 (out of 100) are scaled. */
    fun badge(rating: String?): String? {
        var v = rating?.trim()?.replace(',', '.')?.toDoubleOrNull() ?: return null
        if (v > 10 && v <= 100) v /= 10
        if (v <= 0 || v > 10) return null
        return format(v)
    }

    private fun format(v: Double) = String.format(Locale.US, "%.1f", v)
}
