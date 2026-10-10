package com.worldtv.iptvplayer.data.source

/**
 * Finds an actor's films (titles and years, e.g. from TMDB) among a provider's names, which come
 * dressed up: "EN - The Batman (2022) 4K", "|IN| Inception [2010] Hindi". One pass over the
 * library: each name is cut into words and looked up by word in an index of the wanted titles,
 * so 100k+ names take well under a second.
 *
 * A name matches a title when the title's words appear in it whole, with only a provider prefix
 * before them (set off by "-", "|", ":", brackets) and only years and quality tags after; when
 * both have a year they must agree (±1, release dates differ by country). So "Batman" doesn't
 * match "The LEGO Batman Movie" or "Batman Begins", and "Heat (1995)" doesn't match "Heat (2013)".
 */
object TitleMatch {

    /** First year (1900–2099) in [s], e.g. "2014-05-01" or "Film (2014)" -> "2014". */
    fun year(s: String?): String? {
        if (s == null) return null
        var i = 0
        while (i + 4 <= s.length) {
            if ((s[i] == '1' && s[i + 1] == '9' || s[i] == '2' && s[i + 1] == '0') &&
                s[i + 2].isDigit() && s[i + 3].isDigit() &&
                (i == 0 || !s[i - 1].isDigit()) && (i + 4 == s.length || !s[i + 4].isDigit())
            ) return s.substring(i, i + 4)
            i++
        }
        return null
    }

    /** Lower-case words of letters and digits; [sepBefore] marks words that follow a separator. */
    class Words(val words: List<String>, val sepBefore: BooleanArray)

    fun words(s: String): Words {
        val out = ArrayList<String>(8)
        val seps = ArrayList<Boolean>(8)
        val b = StringBuilder()
        var sep = false
        fun flush() {
            if (b.isEmpty()) return
            out += b.toString()
            seps += sep
            b.setLength(0)
            sep = false
        }
        for (ch in s) {
            when {
                ch.isLetterOrDigit() -> b.append(ch.lowercaseChar())
                ch == '\'' || ch == '’' || ch == '.' && b.length == 1 -> Unit // "Ocean's" = "oceans", "S.W.A.T." = "swat"
                ch == '&' -> { flush(); out += "and"; seps += sep; sep = false }
                else -> {
                    flush()
                    if (ch in SEPARATORS) sep = true
                }
            }
        }
        flush()
        return Words(out, seps.toBooleanArray())
    }

    /** A wanted title. [tag] is whatever the caller needs back (e.g. the credit). */
    class Wanted<T>(val title: String, val year: String?, val tag: T)

    class Index<T>(wanted: List<Wanted<T>>) {
        private class Entry<T>(val words: List<String>, val year: Int?, val tag: T)

        private val byFirst = HashMap<String, MutableList<Entry<T>>>()

        init {
            for (w in wanted) {
                val words = words(w.title).words
                if (words.isEmpty()) continue
                byFirst.getOrPut(words[0]) { ArrayList(1) } += Entry(words, w.year?.toIntOrNull(), w.tag)
            }
        }

        val isEmpty get() = byFirst.isEmpty()

        /** The wanted title [name] is (with the provider's own [year] if it gives one), or null. */
        fun match(name: String, year: String? = null): T? {
            val n = words(name)
            val w = n.words
            val nameYear = (year ?: year(name))?.toIntOrNull()
            // The title starts at the beginning, or after a separator within the first few words.
            for (start in 0 until minOf(w.size, MAX_PREFIX_WORDS + 1)) {
                if (start > 0 && !n.sepBefore[start]) continue
                val candidates = byFirst[w[start]] ?: continue
                for (c in candidates) {
                    val end = start + c.words.size
                    if (end > w.size) continue
                    var same = true
                    for (k in 1 until c.words.size) if (w[start + k] != c.words[k]) { same = false; break }
                    if (!same) continue
                    if (c.year != null && nameYear != null && kotlin.math.abs(c.year - nameYear) > 1) continue
                    if (!restOk(n, end, yearsAgree = c.year != null && nameYear != null)) continue
                    return c.tag
                }
            }
            return null
        }

        /** After the title: only years and quality tags, or a separated part ("- Hindi") when the years agreed. */
        private fun restOk(n: Words, from: Int, yearsAgree: Boolean): Boolean {
            if (from >= n.words.size) return true
            if ((from until n.words.size).all { n.words[it] in TAGS || year(n.words[it]) == n.words[it] }) return true
            return yearsAgree && n.sepBefore[from]
        }
    }

    private const val MAX_PREFIX_WORDS = 4
    private val SEPARATORS = setOf('-', '|', ':', '[', ']', '(', ')', '/', '•', '–', '—', '_')
    private val TAGS = setOf(
        "4k", "8k", "uhd", "fhd", "hd", "sd", "hdr", "hdr10", "dv", "hevc", "x264", "x265", "h264", "h265",
        "480p", "720p", "1080p", "2160p", "multi", "sub", "subs", "subbed", "dub", "dubbed", "cam", "hdcam",
        "ts", "vostfr", "imax", "extended", "remastered", "uncut", "unrated", "theatrical", "en", "eng",
    )
}
