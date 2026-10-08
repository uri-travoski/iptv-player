package io.github.sardinemehico.iptvplayer.data.source

import java.util.regex.Pattern

/**
 * Tells adult categories and entries apart by name, so they can be hidden by default.
 *
 * Built from published bad-word lists (LDNOOBW, several languages) and tuned on ~450,000 real
 * category, channel, movie and series names from three providers (Oct 2026): about 3 wrong hits
 * left in 436,000 normal titles. Names that don't look adult are not caught; the admin screen says so.
 *
 * - A category is adult on one word (XXX, Adult, 18+, Porn, Erotic, Hentai...): providers label
 *   these sections plainly.
 * - An entry is adult on one STRONG word (never seen in a normal title: studio names, explicit
 *   terms), or on two different WEAK ones: these also appear in mainstream titles ("Sex Tape",
 *   "Zack and Miri Make a Porno", "Stepmom", "xXx"), which one alone must not hide.
 */
object AdultNames {

    /** Raise when the word lists change: existing playlists are scanned again on the next start. */
    const val VERSION = 1

    /**
     * No UNICODE_CHARACTER_CLASS: Android's regex engine rejects it (the JVM, which runs the unit
     * tests, accepts it), so word boundaries spell out Unicode letters and digits instead.
     */
    private const val FLAGS = Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE

    /** Whole words only, in any script: "anal" must not match "canal", nor "sex" "Essex". */
    private fun words(alternatives: String): Pattern = Pattern.compile("(?<![\\p{L}\\p{N}])(?:$alternatives)(?![\\p{L}\\p{N}])", FLAGS)

    private val STRONG = words(
        listOf(
            "busty", "brazzers\\w*", "fucked", "digital\\s*playground", "adultime", "wowgirls", "creampies?", "tits", "titty",
            "titties", "beataporn", "hanime", "cocks", "blowjobs?", "gangbangs?", "deepthroat\\w*", "pussies", "blacked",
            "evilangel", "dorcel\\w*", "bangbros", "handjobs?", "bdsm", "cumshots?", "bukkake", "orgies", "gilfs?", "shemales?",
            "ladyboys?", "reality\\s*kings", "jules\\s*jordan", "tushy", "naughty\\s*america", "trann(?:y|ies)", "anal", "milfs",
            "pornstars?", "nsfw", "babestation", "playboy\\s*tv", "hustler\\s*tv", "private\\s*tv", "redlight(?:\\s*(?:hd|tv))?",
            "vivid\\s*(?:red|tv)", "pink\\s*x", "passion\\s*xxx", "blue\\s*hustler", "barely\\s*legal",
            "xxx\\s*(?:tv|hd|fhd|uhd|4k|channel|live)", "(?:tv|channel)\\s*xxx", "для\\s+взрослых", "порно\\p{L}*",
        ).joinToString("|"),
    )

    /** Weak words by group: the same word in another language counts once ("sex" + "sesso"). */
    private val WEAK: List<Pattern> = listOf(
        "xxx", "porn\\w*|xvideos|xhamster|youporn|redtube|spankbang", "fuck\\w*", "milf", "pussy", "cock", "horny", "cum",
        "slut\\w*", "whore\\w*", "sex|sexy|sexual|sesso|sexo|seks|sextape|секс\\p{L}*", "nudes?|naked|nackt", "erotic\\w*|erotica|erotik\\w*",
        "lesbian\\w*", "step\\s*(?:mom|sis|sister|daughter|son|bro)\\w*", "threesome\\w*|foursome|orgy", "fetish\\w*|bondage",
        "boobs|boobies", "dicks?", "hardcore|softcore", "stripper\\w*|striptease", "escort\\w*", "webcam\\w*|camgirls?|chaturbate",
        "hustler|penthouse|playboy|vixen|onlyfans|evil\\s+angel", "cougar\\w*", "hentai",
    ).map(::words)

    /** Mainstream titles that contain those words. Removed before matching. */
    private val NOT_ADULT = Pattern.compile(
        "x{3}\\W*(?:\\(?\\s*20(?:02|05)|return\\s+of\\s+xander|state\\s+of\\s+the\\s+union|the\\s+next\\s+level|reactivated|" +
            "il\\s+ritorno|die\\s+r\\w*ckkehr|2\\s+the\\s+next)|xander\\s+cage|adult\\s*swim|takeover\\s+xxx|money\\s+shot|" +
            "sex\\s+education|sex\\s+and\\s+the\\s+city|sex\\s+lives\\s+of",
        FLAGS,
    )

    /** "XXX | …" or "XXX: …" at the start: a provider's adult tag. */
    private val LEADING_XXX = Pattern.compile("^\\W*xxx\\s*[|:\\-]", FLAGS)

    private val CATEGORY = Pattern.compile(
        "(?<![\\p{L}\\p{N}])(?:xxx|adults?|porn\\w*|erotic\\w*|hentai|hanime|sex|sexy|playboy|hustler|brazzers|ullu|atrangii|onlyfans|" +
            "для\\s+взрослых|порно\\p{L}*|секс\\p{L}*)(?![\\p{L}\\p{N}])|18\\s*\\+|\\+\\s*18|for\\s+adults|adults?\\s*only|nsfw",
        FLAGS,
    )

    private fun clean(name: String) = NOT_ADULT.matcher(name).replaceAll(" ")

    fun isAdultCategory(name: String): Boolean = CATEGORY.matcher(clean(name)).find()

    fun isAdultEntry(name: String): Boolean {
        val n = clean(name)
        if (STRONG.matcher(n).find() || LEADING_XXX.matcher(n).find()) return true
        var groups = 0
        for (p in WEAK) {
            if (p.matcher(n).find() && ++groups >= 2) return true
        }
        return false
    }
}
