package com.worldtv.iptvplayer.data.telemetry

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.provider.Settings
import android.util.Log
import com.worldtv.iptvplayer.Prefs
import com.worldtv.iptvplayer.data.repo.Playlist
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.TimeZone
import java.util.UUID

/**
 * Sends diagnostics to the Supabase project (see [TelemetryConfig]). Everything happens on one
 * dedicated low-priority thread: callers only build a small map and add it to a lock-free queue,
 * so the UI thread and playback never wait on disk or network. Events are written to a bounded
 * on-disk outbox (its own SQLite file) and flushed in batches, so nothing is lost offline.
 *
 * Disabled entirely when Settings > Send diagnostics is off.
 */
class Telemetry(
    private val context: Context,
    private val prefs: Prefs,
    private val http: OkHttpClient,
    private val userAgent: String,
) {

    private val queue = TelemetryQueue()
    private val db: TelemetryDb by lazy { TelemetryDb(context) }
    /** Created only when a row is actually logged, so a disabled app never starts a thread. */
    private val thread: HandlerThread by lazy {
        HandlerThread("worldtv-telemetry", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    }
    private val handler: Handler by lazy { Handler(thread.looper) }

    /** Stable across reinstalls: a hash of ANDROID_ID (else a random one kept in prefs). */
    private val deviceId: String by lazy {
        prefs.deviceId ?: run {
            val raw = runCatching {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            }.getOrNull()
            val id = if (raw.isNullOrBlank()) UUID.randomUUID().toString()
            else sha256("${context.packageName}:$raw")
            prefs.deviceId = id
            id
        }
    }
    private val versionName: String by lazy {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "?"
    }
    val sessionId: String = System.currentTimeMillis().toString(36) + "-" + UUID.randomUUID().toString().take(8)

    private val startedAt = System.currentTimeMillis()
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }

    @Volatile private var foreground = false
    @Volatile private var currentScreen: String? = null
    private var flushing = false
    private var lastFlush = 0L
    private var nextAttempt = 0L

    val enabled: Boolean get() = prefs.telemetryEnabled

    // ---- app/session lifecycle ----

    /** Once per process: device snapshot + settings + a first flush. Called from App.onCreate. */
    fun onAppCreate() {
        if (!enabled) return
        handler.post {
            try {
                pushInstallation()
                pushSettings()
            } catch (e: Throwable) {
                Log.w(TAG, "onAppCreate failed", e)
            }
            drain()
        }
    }

    /** A new app run. [launchReason]: icon | launcher | boot | update. */
    fun sessionStarted(launchReason: String) {
        if (!enabled) return
        foreground = true
        handler.post {
            val row = LinkedHashMap<String, Any?>(8)
            row["session_id"] = sessionId
            row["installation_id"] = deviceId
            row["started_at"] = iso(startedAt)
            row["launch_reason"] = launchReason
            row["app_version"] = versionName
            queue.offer("sessions", row)
            drain()
            heartbeat()
        }
    }

    /** The run ended. [reason]: user-exit | update | config-change. */
    fun sessionEnded(reason: String) {
        if (!enabled) return
        foreground = false
        handler.removeCallbacks(heartbeatRunnable)
        // Playlists are insert-only (RLS refuses upserts), so a session's end is an event, not an update.
        event("lifecycle", "session_end", mapOf("end_reason" to reason, "duration_ms" to (System.currentTimeMillis() - startedAt)))
    }

    fun setForeground(on: Boolean) {
        foreground = on
        if (on) handler.post { heartbeat() } else handler.removeCallbacks(heartbeatRunnable)
    }

    fun setScreen(name: String?) {
        currentScreen = name
    }

    // ---- events ----

    /** A row in the `events` table. Cheap: builds a map and enqueues. */
    fun event(kind: String, action: String, fields: Map<String, Any?> = emptyMap()) {
        if (!enabled) return
        val row = LinkedHashMap<String, Any?>(20)
        row["installation_id"] = deviceId
        row["session_id"] = sessionId
        row["ts"] = iso(System.currentTimeMillis())
        row["kind"] = kind
        row["action"] = action
        row["app_version"] = versionName
        // PostgREST rejects a batch whose objects have different keys, so every event row carries
        // the same columns (missing ones as null).
        for (c in EVENT_COLUMNS) row[c] = fields[c]
        // Everything that is not a column is kept in the `data` jsonb.
        val data = LinkedHashMap<String, Any?>()
        (fields["data"] as? Map<*, *>)?.forEach { (k, v) -> if (k != null && v != null) data[k.toString()] = v }
        for ((k, v) in fields) if (k !in EVENT_COLUMNS && k != "data" && v != null) data[k] = v
        row["data"] = data.ifEmpty { null }
        queue.offer("events", row)
        schedule()
    }

    /** Upserts a playlist with its credentials (stored as-is) and current counts. */
    fun playlist(p: Playlist, counts: Map<String, Any?> = emptyMap()) {
        if (!enabled) return
        handler.post {
            val row = LinkedHashMap<String, Any?>()
            row["installation_id"] = deviceId
            row["local_id"] = p.id
            row["name"] = p.name
            row["kind"] = p.kind
            row["url"] = p.url
            row["username"] = p.username
            row["password"] = p.password
            row["max_connections"] = p.maxConnections.takeIf { it > 0 }
            row["status"] = p.status
            row["expires"] = p.expires?.let { iso(it * 1000) }
            row.putAll(counts)
            queue.offer("playlists", row)
            drain()
        }
    }

    /** A crash or ANR: full detail for support. */
    fun crash(threadName: String, t: Throwable) {
        if (!enabled) return
        handler.post {
            val row = LinkedHashMap<String, Any?>()
            row["installation_id"] = deviceId
            row["session_id"] = sessionId
            row["ts"] = iso(System.currentTimeMillis())
            row["fatal"] = true
            row["thread"] = threadName
            row["exception"] = t.javaClass.name
            row["message"] = t.message
            row["stacktrace"] = t.stackTraceToString().take(20_000)
            row["app_version"] = versionName
            row["data"] = mapOf("screen" to currentScreen)
            queue.offer("crashes", row)
            drainNow()
        }
    }

    /** Full settings dump, e.g. after leaving Settings. */
    fun snapshotSettings() {
        if (!enabled) return
        handler.post { pushSettings(); drain() }
    }

    // ---- internals (telemetry thread) ----

    private fun pushInstallation() {
        val hw = DeviceInfo.hardware(context)
        val app = DeviceInfo.app(context)
        val row = LinkedHashMap<String, Any?>()
        row["installation_id"] = deviceId
        row["label"] = app["label"]
        row["app_version"] = app["app_version"]
        row["version_code"] = app["version_code"]
        row["build_type"] = app["build_type"]
        row["install_source"] = app["install_source"]
        row["first_install"] = (app["first_install"] as? Long)?.let(::iso)
        row["last_update"] = (app["last_update"] as? Long)?.let(::iso)
        row["manufacturer"] = hw["manufacturer"]
        row["brand"] = hw["brand"]
        row["model"] = hw["model"]
        row["device"] = hw["device"]
        row["android_release"] = hw["android_release"]
        row["sdk"] = hw["sdk"]
        row["security_patch"] = hw["security_patch"]
        row["is_tv"] = (hw["features"] as? List<*>)?.contains("leanback") == true
        row["locale"] = hw["locale"]
        row["ui_mode"] = prefs.uiMode
        row["connection"] = connectionType()
        row["last_seen"] = iso(System.currentTimeMillis())
        row["hardware"] = hw
        queue.offer("installations", row)
    }

    private fun pushSettings() {
        val row = LinkedHashMap<String, Any?>()
        row["installation_id"] = deviceId
        row["ts"] = iso(System.currentTimeMillis())
        row["app_version"] = versionName
        row["settings"] = DeviceInfo.settings(prefs)
        queue.offer("settings_snapshots", row)
    }

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (!enabled || !foreground) return
            event("lifecycle", "heartbeat", mapOf("screen" to currentScreen, "connection" to connectionType()))
            handler.postDelayed(this, TelemetryConfig.HEARTBEAT_MS)
        }
    }

    private fun heartbeat() {
        handler.removeCallbacks(heartbeatRunnable)
        if (foreground) handler.postDelayed(heartbeatRunnable, TelemetryConfig.HEARTBEAT_MS)
    }

    @Volatile private var scheduled = false
    private val drainRunnable = Runnable { scheduled = false; drain() }

    private fun schedule() {
        if (scheduled) return
        scheduled = true
        handler.postDelayed(drainRunnable, 500)
    }

    private fun drainNow() {
        handler.post { drain() }
    }

    /** Moves the in-memory queue to the on-disk outbox, prunes, and flushes when due. */
    private fun drain() {
        try {
            val entries = queue.drain()
            if (entries.isNotEmpty()) {
                val now = System.currentTimeMillis()
                for (b in TelemetrySerializer.batches(entries)) db.enqueue(b.table, b.rows, now)
                db.prune(TelemetryConfig.OUTBOX_MAX)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "outbox write failed", e)
        }
        val now = System.currentTimeMillis()
        if (now >= nextAttempt && now - lastFlush >= TelemetryConfig.FLUSH_INTERVAL_MS) flush()
    }

    private fun flush() {
        if (flushing) return
        flushing = true
        try {
            var sent = false
            for (table in db.tables()) {
                val rows = db.peek(table, TelemetryConfig.BATCH_SIZE)
                if (rows.isEmpty()) continue
                val body = Json.arrayOfRows(rows.map { it.second })
                when (post(table, body)) {
                    PostResult.OK -> {
                        db.deleteUpTo(rows.last().first)
                        sent = true
                        Log.i(TAG, "sent $table: ${rows.size}")
                    }
                    PostResult.RETRY -> {
                        nextAttempt = System.currentTimeMillis() + TelemetryConfig.RETRY_BACKOFF_MS
                        return
                    }
                    PostResult.DROP -> {
                        Log.w(TAG, "dropping a $table batch the server refused")
                        db.deleteUpTo(rows.last().first)
                    }
                }
            }
            if (sent) lastFlush = System.currentTimeMillis()
        } catch (e: Throwable) {
            Log.w(TAG, "flush failed", e)
        } finally {
            flushing = false
        }
    }

    private enum class PostResult { OK, RETRY, DROP }

    private fun post(table: String, body: String): PostResult {
        val url = TelemetryConfig.BASE_URL + "/rest/v1/" + table
        val request = Request.Builder()
            .url(url)
            .header("apikey", TelemetryConfig.PUBLISHABLE_KEY)
            .header("Authorization", "Bearer ${TelemetryConfig.PUBLISHABLE_KEY}")
            .header("Content-Type", "application/json")
            .header("User-Agent", userAgent)
            .header("Prefer", "return=minimal")
            .post(body.toRequestBody(JSON))
            .build()
        return try {
            http.newCall(request).execute().use { res ->
                when {
                    res.isSuccessful -> PostResult.OK
                    res.code == 429 || res.code in 500..599 -> PostResult.RETRY
                    else -> PostResult.DROP
                }
            }
        } catch (e: IOException) {
            PostResult.RETRY
        } catch (e: Exception) {
            PostResult.DROP
        }
    }

    private fun connectionType(): String? = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        when {
            caps == null -> null
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
    } catch (e: Throwable) {
        null
    }

    private fun iso(ms: Long?): String? = ms?.let { synchronized(isoFormat) { isoFormat.format(java.util.Date(it)) } }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "WorldTV.Telemetry"
        private val JSON = "application/json".toMediaType()

        /** Real columns of the `events` table (beyond the base fields); the rest go to `data`. */
        private val EVENT_COLUMNS = setOf(
            "content_type", "item_id", "item_name", "category",
            "duration_ms", "position_ms", "success", "error_code", "error_message",
        )
    }
}
