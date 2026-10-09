package io.github.sardinemehico.iptvplayer.data.source

import java.util.regex.Pattern

/**
 * Tells adult categories and entries apart by name, so they can be hidden by default.
 *
 * [categoryWords] and [entryWords] are the admin's lists (App Settings > playlist > Categories >
 * Adult words), starting from [DEFAULT_CATEGORY_WORDS] / [DEFAULT_ENTRY_WORDS]. Those defaults come
 * from published bad-word lists (LDNOOBW, several languages), tuned on ~450,000 real category,
 * channel, movie and series names from three providers (Oct 2026): about 3 wrong hits left in
 * 436,000 normal titles. Names that don't look adult are not caught; the admin screen says so.
 *
 * - A category is adult on one of [categoryWords]: providers label these sections plainly.
 * - An entry is adult on one of [entryWords] (never seen in a normal title), or on two different
 *   built-in WEAK words: those also appear in mainstream titles ("Sex Tape", "Zack and Miri Make a
 *   Porno", "Stepmom", "xXx"), which one alone must not hide.
 *
 * A word matches whole words only, any case; spaces in it match any spacing ("playboy tv" also
 * finds "PlayboyTV"); a trailing * matches any ending ("porn*" finds "porno").
 */
class AdultNames(categoryWords: List<String>, entryWords: List<String>) {

    private val category = words(categoryWords)
    private val entry = words(entryWords)

    /**
     * Cheap first test for entries: plain substrings that every adult match must contain (first
     * word of each admin word, and the built-in weak stems). Over 99% of names have none of them,
     * so the regexes only run on the few that might match: big playlists are checked ~10x faster.
     */
    private val triggers: Array<String> = (entryWords.map { it.trim().lowercase().removeSuffix("*").trim().substringBefore(' ') } + WEAK_STEMS)
        .filter { it.isNotEmpty() }
        .distinct()
        .toTypedArray()

    fun isAdultCategory(name: String): Boolean = category?.matcher(clean(name))?.find() == true

    fun isAdultEntry(name: String): Boolean {
        val lower = name.lowercase()
        if (triggers.none { lower.contains(it) }) return false
        val n = clean(name)
        if (entry?.matcher(n)?.find() == true || LEADING_XXX.matcher(n).find()) return true
        var groups = 0
        for (p in WEAK) {
            if (p.matcher(n).find() && ++groups >= 2) return true
        }
        return false
    }

    companion object {

        /** Raise when the defaults or the rules change: playlists are checked again on the next start. */
        const val VERSION = 5

        val DEFAULT_CATEGORY_WORDS = listOf(
            "xxx", "adult", "adults", "18+", "+18", "for adults", "adults only", "porn*", "erotic*", "hentai", "hanime",
            "sex", "sexy", "playboy", "hustler", "brazzers", "onlyfans", "ullu", "atrangii", "nsfw", "для взрослых",
            "порно*", "секс*", "dasi muj", "xx | hindi xx",
        )

        /** Category words added in [VERSION] 5: also added to a list the admin has edited. */
        val ADDED_CATEGORY_WORDS_5 = listOf("dasi muj", "xx | hindi xx")

        val DEFAULT_ENTRY_WORDS = listOf(
            "busty", "brazzers*", "fucked", "digital playground", "adultime", "wowgirls", "creampie", "creampies", "tits",
            "titty", "titties", "beataporn", "hanime", "cocks", "blowjob", "blowjobs", "gangbang", "gangbangs", "deepthroat*",
            "pussies", "blacked", "evilangel", "dorcel*", "bangbros", "handjob", "handjobs", "bdsm", "cumshot", "cumshots",
            "bukkake", "orgies", "gilf", "gilfs", "shemale", "shemales", "ladyboy", "ladyboys", "reality kings",
            "jules jordan", "tushy", "naughty america", "tranny", "trannies", "anal", "milfs", "pornstar", "pornstars",
            "nsfw", "babestation", "playboy tv", "hustler tv", "private tv", "redlight", "vivid red", "vivid tv", "pink x",
            "passion xxx", "blue hustler", "barely legal", "xxx tv", "xxx hd", "xxx fhd", "xxx 4k", "xxx channel",
            "xxx live", "tv xxx", "channel xxx", "для взрослых", "порно*",
        )

        /**
         * No UNICODE_CHARACTER_CLASS: Android's regex engine rejects it (the JVM, which runs the unit
         * tests, accepts it), so word boundaries spell out Unicode letters and digits instead.
         */
        private const val FLAGS = Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE

        private const val LETTER = "[\\p{L}\\p{N}]"

        /** Whole words only, in any script: "anal" must not match "canal", nor "sex" "Essex". */
        private fun bounded(alternatives: String): Pattern = Pattern.compile("(?<!$LETTER)(?:$alternatives)(?!$LETTER)", FLAGS)

        /** One admin word as a regex: literal, spaces match any spacing, trailing * any ending. */
        fun wordRegex(word: String): String {
            val w = word.trim().lowercase()
            val stem = w.removeSuffix("*").trim()
            val body = stem.split(Regex("\\s+")).joinToString("\\s*") { Pattern.quote(it) }
            return if (w.endsWith("*")) "$body$LETTER*" else body
        }

        private fun words(list: List<String>): Pattern? {
            val parts = list.map { it.trim() }.filter { it.isNotEmpty() && it != "*" }.map(::wordRegex)
            return if (parts.isEmpty()) null else bounded(parts.joinToString("|"))
        }

        /** Substrings every built-in weak word (and the leading "XXX |" tag) contains. Keep in step with [WEAK]. */
        private val WEAK_STEMS = listOf(
            "xxx", "porn", "xvideos", "xhamster", "youporn", "redtube", "spankbang", "fuck", "milf", "pussy", "cock", "horny",
            "cum", "slut", "whore", "sex", "sesso", "seks", "секс", "nude", "naked", "nackt", "erotic", "erotik", "lesbian",
            "step", "threesome", "foursome", "orgy", "fetish", "bondage", "boob", "dick", "hardcore", "softcore", "strip",
            "escort", "webcam", "camgirl", "chaturbate", "hustler", "penthouse", "playboy", "vixen", "onlyfans", "evil",
            "cougar", "hentai",
        )

        /** Weak words by group: the same word in another language counts once ("sex" + "sesso"). */
        private val WEAK: List<Pattern> = listOf(
            "xxx", "porn\\w*|xvideos|xhamster|youporn|redtube|spankbang", "fuck\\w*", "milf", "pussy", "cock", "horny", "cum",
            "slut\\w*", "whore\\w*", "sex|sexy|sexual|sesso|sexo|seks|sextape|секс\\p{L}*", "nudes?|naked|nackt",
            "erotic\\w*|erotica|erotik\\w*", "lesbian\\w*", "step\\s*(?:mom|sis|sister|daughter|son|bro)\\w*",
            "threesome\\w*|foursome|orgy", "fetish\\w*|bondage", "boobs|boobies", "dicks?", "hardcore|softcore",
            "stripper\\w*|striptease", "escort\\w*", "webcam\\w*|camgirls?|chaturbate",
            "hustler|penthouse|playboy|vixen|onlyfans|evil\\s+angel", "cougar\\w*", "hentai",
        ).map(::bounded)

        /** Mainstream titles that contain adult words. Removed before matching. */
        private val NOT_ADULT = Pattern.compile(
            "x{3}\\W*(?:\\(?\\s*20(?:02|05)|return\\s+of\\s+xander|state\\s+of\\s+the\\s+union|the\\s+next\\s+level|reactivated|" +
                "il\\s+ritorno|die\\s+r\\w*ckkehr|2\\s+the\\s+next)|xander\\s+cage|adult\\s*swim|takeover\\s+xxx|money\\s+shot|" +
                "sex\\s+education|sex\\s+and\\s+the\\s+city|sex\\s+lives\\s+of",
            FLAGS,
        )

        /** "XXX | …" or "XXX: …" at the start: a provider's adult tag. */
        private val LEADING_XXX = Pattern.compile("^\\W*xxx\\s*[|:\\-]", FLAGS)

        private fun clean(name: String) = NOT_ADULT.matcher(name).replaceAll(" ")

        val DEFAULT = AdultNames(DEFAULT_CATEGORY_WORDS, DEFAULT_ENTRY_WORDS)
    }
}
