package io.github.sardinemehico.iptvplayer.data.model

/** The three libraries every playlist is split into, as in IBO Player Pro. */
enum class ContentType { LIVE, MOVIE, SERIES }

/** A category (group) inside one library. [id] is the panel's id, or the group title for M3U. */
data class Category(
    val id: String,
    val name: String,
    val type: ContentType,
)

/**
 * One playable or browsable item: a live channel, a movie, or a series.
 * Kept flat so it maps straight onto one SQLite row.
 */
data class Entry(
    /** Panel stream/series id for Xtream; stable hash of the URL for M3U. */
    val id: String,
    val type: ContentType,
    val name: String,
    val categoryId: String?,
    val logo: String?,
    /** Direct stream URL (M3U), or null when it is built from the Xtream id at play time. */
    val streamUrl: String? = null,
    /** EPG channel id (tvg-id / epg_channel_id). Live only. */
    val epgId: String? = null,
    /** Days of catch-up the provider keeps. 0 = no catch-up. Live only. */
    val catchupDays: Int = 0,
    /** File extension for VOD/episodes, e.g. "mp4", "mkv". */
    val containerExt: String? = null,
    val rating: String? = null,
    val plot: String? = null,
    /** Unix seconds when the item was added on the panel, 0 if unknown. */
    val added: Long = 0,
    /** Position in the provider's list, used as the default sort order. */
    val order: Int = 0,
    /** The provider marks it adult (Xtream `is_adult`). Hidden by default. */
    val adult: Boolean = false,
    /** Movies/series: actors as the provider lists them ("A, B, C"); series lists carry it. */
    val cast: String? = null,
    /** Movies/series: release year when the provider gives one (else it may be in [name]). */
    val year: String? = null,
)
