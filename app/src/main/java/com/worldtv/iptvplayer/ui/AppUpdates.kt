package com.worldtv.iptvplayer.ui

import android.app.AlertDialog
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.worldtv.iptvplayer.App
import com.worldtv.iptvplayer.MainActivity
import com.worldtv.iptvplayer.R
import com.worldtv.iptvplayer.data.update.Release
import com.worldtv.iptvplayer.data.update.Updates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Self-update from the GitHub releases. A background check a few seconds after start (at most
 * every 6 hours, only if "Check for updates" is on), a prompt on the home screen (Update / Not
 * now / Skip this version), a download with progress, then Android's own installer. Android
 * only accepts the new APK if it is signed with the same key as the installed one.
 */
object AppUpdates {

    private const val TAG = "WorldTV.Update"
    private const val START_DELAY_MS = 10_000L
    private const val INTERVAL_MS = 6 * 60 * 60 * 1000L

    /** A newer release found by the start-up check, waiting for the home screen to offer it. */
    var pending: Release? = null
        private set

    /** Downloaded APK waiting for the "install unknown apps" permission. */
    private var readyToInstall: File? = null
    private var startCheckDone = false

    fun currentVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"

    /** Once per app start, in the background, after the start-up work has settled. */
    fun checkOnStart(activity: MainActivity, scope: CoroutineScope, onFound: () -> Unit) {
        if (startCheckDone) return
        startCheckDone = true
        val prefs = App.graph.prefs
        if (!prefs.autoUpdate) return
        if (System.currentTimeMillis() - prefs.lastUpdateCheck < INTERVAL_MS) return
        scope.launch {
            delay(START_DELAY_MS)
            val release = try {
                fetchNewest()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "check failed: $e")
                return@launch
            }
            prefs.lastUpdateCheck = System.currentTimeMillis()
            if (release != null && Updates.isNewer(release.version, currentVersion(activity)) &&
                release.version != prefs.skippedUpdate
            ) {
                Log.i(TAG, "update available: ${release.version}")
                App.graph.telemetry.event("update", "available", mapOf("from_version" to currentVersion(activity), "to_version" to release.version))
                pending = release
                onFound()
            }
        }
    }

    /** "Check for updates now" in App Settings: ignores the interval and a skipped version. */
    fun checkNow(activity: MainActivity, scope: CoroutineScope) {
        activity.toast(activity.getString(R.string.update_checking))
        scope.launch {
            val release = try {
                fetchNewest()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                activity.toast(activity.getString(R.string.update_check_failed))
                return@launch
            }
            App.graph.prefs.lastUpdateCheck = System.currentTimeMillis()
            val current = currentVersion(activity)
            if (release == null || !Updates.isNewer(release.version, current)) {
                activity.toast(activity.getString(R.string.update_none, current))
            } else {
                offer(activity, release, scope)
            }
        }
    }

    /**
     * After the user allowed "install unknown apps" and came back (to any WorldTV screen), carry
     * on with the downloaded update. Called from MainActivity.onStart.
     */
    fun resumeInstall(activity: MainActivity) {
        val file = readyToInstall ?: return
        if (!canInstall(activity)) return
        readyToInstall = null
        install(activity, file)
    }

    /** Shows the waiting offer, if any. Called by the home screen when it is on screen. */
    fun offerPending(activity: MainActivity, scope: CoroutineScope) {
        if (readyToInstall != null) return resumeInstall(activity)
        val release = pending ?: return
        if (App.graph.player.playingUrl != null) return // never interrupt playback
        pending = null
        offer(activity, release, scope)
    }

    private fun offer(activity: MainActivity, release: Release, scope: CoroutineScope) {
        val current = currentVersion(activity)
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_title, release.version))
            .setMessage(activity.getString(R.string.update_message, current, release.version))
            .setPositiveButton(R.string.update_yes) { _, _ -> download(activity, release, scope) }
            .setNegativeButton(R.string.update_later, null)
            .setNeutralButton(R.string.update_skip) { _, _ ->
                App.graph.prefs.skippedUpdate = release.version
                activity.toast(activity.getString(R.string.update_skipped, release.version))
            }
            .show()
    }

    private suspend fun fetchNewest(): Release? = withContext(App.graph.io) {
        val request = Request.Builder().url(Updates.RELEASES_API)
            .header("Accept", "application/vnd.github+json")
            .build()
        App.graph.http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("GitHub returned HTTP ${r.code}")
            Updates.newest(r.body!!.charStream())
        }
    }

    private fun download(activity: MainActivity, release: Release, scope: CoroutineScope) {
        var job: Job? = null
        val dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_title, release.version))
            .setMessage(activity.getString(R.string.update_downloading, 0))
            .setNegativeButton(android.R.string.cancel) { _, _ -> job?.cancel() }
            .setCancelable(false)
            .show()
        job = scope.launch {
            try {
                val file = withContext(App.graph.io) {
                    fetchApk(activity, release) { pct ->
                        activity.runOnUiThread { dialog.setMessage(activity.getString(R.string.update_downloading, pct)) }
                    }
                }
                dialog.dismiss()
                App.graph.telemetry.event("update", "downloaded", mapOf("to_version" to release.version))
                if (canInstall(activity)) {
                    install(activity, file)
                } else {
                    // Android 8+: the user must allow WorldTV to install apps, once.
                    readyToInstall = file
                    activity.toast(activity.getString(R.string.update_allow_install))
                    try {
                        activity.startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.packageName)),
                        )
                    } catch (e: ActivityNotFoundException) {
                        activity.toast(activity.getString(R.string.update_failed, "no install permission page"))
                    }
                }
            } catch (e: CancellationException) {
                dialog.dismiss()
                throw e
            } catch (e: Exception) {
                dialog.dismiss()
                App.graph.telemetry.event("update", "download_failed", mapOf("to_version" to release.version, "error_message" to (e.message ?: e.javaClass.simpleName)))
                activity.toast(activity.getString(R.string.update_failed, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    /** Downloads the APK to the app's cache, checking its size and (when GitHub gives one) its SHA-256. */
    private fun fetchApk(context: Context, release: Release, progress: (Int) -> Unit): File {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // only ever keep the one being installed
        val file = File(dir, "worldtv-${release.version}.apk")
        val sha = MessageDigest.getInstance("SHA-256")
        val request = Request.Builder().url(release.apkUrl).build()
        App.graph.http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("download failed: HTTP ${r.code}")
            val body = r.body!!
            val total = body.contentLength().takeIf { it > 0 } ?: release.apkSize
            var done = 0L
            var lastPct = -1
            body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        sha.update(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else 0
                        if (pct != lastPct) { lastPct = pct; progress(pct) }
                    }
                }
            }
            if (release.apkSize > 0 && done != release.apkSize) throw IOException("download incomplete")
        }
        val expected = release.digest?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
        if (expected != null) {
            val actual = sha.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(expected, ignoreCase = true)) {
                file.delete()
                throw IOException("download corrupted (checksum mismatch)")
            }
        }
        return file
    }

    private fun canInstall(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Hands the APK to Android's installer; it shows its own confirmation, then replaces WorldTV. */
    private fun install(activity: MainActivity, file: File) {
        try {
            val installer = activity.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(activity.packageName)
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("worldtv.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val status = PendingIntent.getBroadcast(
                    activity, id, Intent(activity, InstallStatusReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or mutable,
                )
                session.commit(status.intentSender)
            }
        } catch (e: Exception) {
            activity.toast(activity.getString(R.string.update_failed, e.message ?: e.javaClass.simpleName))
        }
    }
}

/** Receives the installer's progress: shows its confirmation screen, or reports a failure. */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // WorldTV is replaced and restarted by Android
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "install failed"
                Log.w("WorldTV.Update", "install status: $msg")
                Toast.makeText(context, context.getString(R.string.update_failed, msg), Toast.LENGTH_LONG).show()
            }
        }
    }
}
