package com.worldtv.iptvplayer

/**
 * Home's subscription reminders (Xtream accounts with an end date): "expires in 3 days", again
 * 2 days before, and "has expired" once it has. Each shows once; a box that was off for a while
 * shows only the latest that applies. A renewal (a new end date) starts them over.
 */
object ExpiryReminder {
    const val THREE_DAYS = 3
    const val TWO_DAYS = 2
    const val EXPIRED = 0

    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** The reminder that applies at [nowMs] for an account ending at [expiresMs], or null. */
    fun stage(expiresMs: Long, nowMs: Long): Int? {
        val left = expiresMs - nowMs
        return when {
            left <= 0 -> EXPIRED
            left <= TWO_DAYS * DAY_MS -> TWO_DAYS
            left <= THREE_DAYS * DAY_MS -> THREE_DAYS
            else -> null
        }
    }

    /**
     * The reminder to show now, or null. [lastShown]: what was shown before, "expiresMs:stage"
     * (stages go 3, 2, 0; one already shown for this end date isn't shown again).
     */
    fun due(expiresMs: Long, nowMs: Long, lastShown: String?): Int? {
        val stage = stage(expiresMs, nowMs) ?: return null
        val parts = lastShown?.split(':')
        val shownStage = parts?.getOrNull(1)?.toIntOrNull()
        if (parts?.getOrNull(0)?.toLongOrNull() == expiresMs && shownStage != null && shownStage <= stage) return null
        return stage
    }

    fun record(expiresMs: Long, stage: Int) = "$expiresMs:$stage"

    /** Whole days left, rounded up (2 days 3 hours -> 3); at least 1 before the end. */
    fun daysLeft(expiresMs: Long, nowMs: Long): Int = ((expiresMs - nowMs + DAY_MS - 1) / DAY_MS).toInt().coerceAtLeast(1)
}
