package com.worldtv.iptvplayer.data.telemetry

/**
 * Where telemetry goes. The publishable key is insert-only (RLS): it can write rows and can
 * never read them back, so it is safe to ship. Reports are read with the service_role key from
 * the Supabase dashboard. See supabase/migrations/0001_telemetry.sql.
 */
object TelemetryConfig {
    const val BASE_URL = "https://xzjhjjwhfelywdgvhlno.supabase.co"
    const val PUBLISHABLE_KEY = "sb_publishable_6Q-2oARdSIlCc7sENWxslg_rw1zwsiH"

    /** Rows per HTTP request, per table. */
    const val BATCH_SIZE = 200
    /** Outbox rows kept on disk; oldest are dropped past this. */
    const val OUTBOX_MAX = 8000
    /** Flush at most this often while there is data (ms). */
    const val FLUSH_INTERVAL_MS = 60_000L
    /** "the app is running" heartbeat (ms). */
    const val HEARTBEAT_MS = 5 * 60_000L
    /** Backoff after a failed flush (ms). */
    const val RETRY_BACKOFF_MS = 30_000L
}
