package com.worldtv.iptvplayer.data.source

/**
 * Is a movie or series English? Providers mark the language with a code: on the title
 * ("EN - Animals (2026)", "4K-IN - Runner", "AR| …") or on its category ("|EN| DRAMA", "UK| …").
 * The title's own code decides; then the category's; with no code anywhere, words in the
 * category name ("ENGLISH", "HOLLYWOOD" / "BOLLYWOOD", "TAMIL", …). Anything still unknown is
 * not English: Home's Recently added rows show only titles known to be English.
 */
object TitleLanguage {

    private val ENGLISH = setOf("EN", "ENG", "UK", "US", "USA", "GB", "AU", "AUS", "NZ", "IE", "IRL")

    /**
     * Other languages and countries (as providers use them: EX = ex-Yugoslavia, SC/SCA = Nordic,
     * TM = Tamil, PB = Punjabi). Only listed codes count: "TRON: Ares" or "WALL-E" are titles.
     */
    private val OTHER = setOf(
        "AR", "FR", "DE", "PL", "ES", "IN", "NL", "EX", "EXYU", "SE", "AL", "ALB", "IR", "GR", "SC", "SCA",
        "RU", "LAT", "LA", "TM", "BG", "DK", "TG", "PT", "IT", "QFR", "PB", "BN", "TL", "UR", "PK", "ML",
        "MR", "TR", "MXC", "MX", "RS", "BE", "RO", "BR", "NO", "FI", "HU", "CZ", "SK", "HR", "SI", "BA",
        "MK", "KU", "KR", "JP", "CN", "TW", "HK", "PH", "TH", "VN", "ID", "MY", "AF", "SO", "ET", "HI",
        "TA", "TE", "KN", "IL", "HE", "FA", "AZ", "AM", "GE", "UA", "LT", "LV", "EE", "IS", "CH", "AT",
        "CO", "CL", "PE", "VE", "EG", "MA", "DZ", "TN", "SA", "AE", "KW", "QA", "IQ", "SY", "LB", "JO",
        "KH", "LK", "NP", "BD", "PS", "CY", "BY", "KZ", "UZ",
    )

    private val ENGLISH_WORDS = listOf("ENGLISH", "HOLLYWOOD")
    private val OTHER_WORDS = listOf(
        "BOLLYWOOD", "TOLLYWOOD", "KOLLYWOOD", "HINDI", "TAMIL", "TELUGU", "MALAYALAM", "KANNADA",
        "PUNJABI", "BENGALI", "BANGLA", "MARATHI", "GUJARATI", "URDU", "PAKISTAN", "ARABIC", "TURK",
        "FRENCH", "FRANCAIS", "GERMAN", "DEUTSCH", "SPANISH", "ESPANOL", "LATINO", "ITALIAN",
        "PORTUGUES", "BRASIL", "BRAZIL", "DUTCH", "POLISH", "POLSKI", "RUSSIAN", "PERSIAN", "FARSI",
        "KOREAN", "CHINESE", "FILIPINO", "TAGALOG", "SOMALI", "AFRICAN", "NOLLYWOOD",
    )

    /** A leading code: "EN - ", "EN| ", "|EN| ", "[EN] ", "(EN) ", "EN: ", "4K-IN - ", "PL 4K - ". */
    private val CODE = Regex("""^\s*[|\[(]?\s*([A-Z0-9+]{2,8}(?:[- ][A-Z0-9+]{2,8})?)\s*(?:[|\])]\s*(?:-|\||:)?|-|\||:)""")

    fun isEnglish(title: String, category: String?): Boolean {
        code(title)?.let { return it }
        category?.let { c -> code(c)?.let { return it } }
        val words = category?.uppercase() ?: return false
        if (OTHER_WORDS.any { it in words }) return false
        return ENGLISH_WORDS.any { it in words }
    }

    /** True / false when [s] starts with a language code, null when it has none (or only others: 4K, NF, …). */
    internal fun code(s: String): Boolean? {
        val m = CODE.find(s) ?: return null
        val first = m.groupValues[1].split('-', ' ').firstOrNull { it in ENGLISH || it in OTHER } ?: return null
        return first in ENGLISH
    }
}
