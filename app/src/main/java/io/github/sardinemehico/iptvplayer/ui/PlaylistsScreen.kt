package io.github.sardinemehico.iptvplayer.ui

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import io.github.sardinemehico.iptvplayer.MainActivity
import io.github.sardinemehico.iptvplayer.R
import io.github.sardinemehico.iptvplayer.Prefs
import io.github.sardinemehico.iptvplayer.data.net.AppDns
import io.github.sardinemehico.iptvplayer.data.repo.Pin
import io.github.sardinemehico.iptvplayer.data.repo.Playlist
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** App Settings: auto-start on boot, all apps, Android settings, and the saved playlists (open, refresh, details, delete, add). */
class PlaylistsScreen(activity: MainActivity) : Screen(activity) {

    override val root: View = inflater.inflate(R.layout.screen_playlists, null)
    private val list: LinearLayout = root.findViewById(R.id.list)
    private val add: TextView = root.findViewById(R.id.add)
    private val message: TextView = root.findViewById(R.id.message)
    private val busyDots: View = root.findViewById(R.id.busy)
    private val autoStart: TextView = root.findViewById(R.id.auto_start)
    private val autoStartStatus: TextView = root.findViewById(R.id.auto_start_status)
    private val defaultHome: TextView = root.findViewById(R.id.default_home)
    private var busy = false

    init {
        fullWidthOnMobile(R.id.form)
        val uiMode = root.findViewById<TextView>(R.id.ui_mode)
        uiMode.setText(if (graph.prefs.isMobile) R.string.ui_mode_setting_mobile else R.string.ui_mode_setting_tv)
        uiMode.setOnClickListener {
            graph.prefs.uiMode = if (graph.prefs.isMobile) Prefs.UI_TV else Prefs.UI_MOBILE
            activity.restartUi(thenSettings = true)
        }
        if (graph.prefs.isMobile) {
            // Launcher and boot options are for TV boxes.
            for (id in intArrayOf(R.id.auto_start, R.id.slot_count, R.id.default_home, R.id.launcher_row)) {
                root.findViewById<View>(id).visibility = View.GONE
            }
            root.findViewById<TextView>(R.id.playlists_hint).setText(R.string.playlists_hint_mobile)
            // Narrow screen: "Check for updates on start: On" takes two lines; let the tiles grow.
            val pad = (10 * activity.resources.displayMetrics.density).toInt()
            for (id in intArrayOf(R.id.auto_update, R.id.check_update)) {
                val tile = root.findViewById<TextView>(id)
                tile.minHeight = tile.layoutParams.height
                tile.layoutParams = tile.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
                tile.setPadding(tile.paddingLeft, pad, tile.paddingRight, pad)
            }
        }
        add.setOnClickListener { activity.push(AddPlaylistScreen(activity)) }
        autoStart.setOnClickListener { toggleAutoStart() }
        val slotCount = root.findViewById<TextView>(R.id.slot_count)
        fun showSlotCount() { slotCount.text = activity.getString(R.string.slot_count, graph.prefs.appSlotCount) }
        fun changeSlots(delta: Int, wrap: Boolean) {
            var n = graph.prefs.appSlotCount + delta
            if (n > Prefs.MAX_SLOTS) n = if (wrap) Prefs.MIN_SLOTS else Prefs.MAX_SLOTS
            if (n < Prefs.MIN_SLOTS) n = Prefs.MIN_SLOTS
            graph.prefs.appSlotCount = n
            showSlotCount()
        }
        slotCount.setOnClickListener { changeSlots(+1, wrap = true) }
        slotCount.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { changeSlots(-1, wrap = false); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { changeSlots(+1, wrap = false); true }
                else -> false
            }
        }
        showSlotCount()
        val autoUpdate = root.findViewById<TextView>(R.id.auto_update)
        fun showAutoUpdate() = autoUpdate.setText(if (graph.prefs.autoUpdate) R.string.update_auto_on else R.string.update_auto_off)
        autoUpdate.setOnClickListener { graph.prefs.autoUpdate = !graph.prefs.autoUpdate; showAutoUpdate() }
        showAutoUpdate()
        root.findViewById<View>(R.id.check_update).setOnClickListener { AppUpdates.checkNow(activity, scope) }
        val dns = root.findViewById<TextView>(R.id.dns)
        fun showDns() = dns.setText(
            when (graph.prefs.dnsMode) {
                AppDns.MODE_CLOUDFLARE -> R.string.dns_cloudflare
                AppDns.MODE_GOOGLE -> R.string.dns_google
                else -> R.string.dns_system
            },
        )
        dns.setOnClickListener {
            graph.prefs.dnsMode = (graph.prefs.dnsMode + 1) % 3
            showDns()
        }
        showDns()
        root.findViewById<View>(R.id.all_apps).setOnClickListener {
            scope.launch {
                val apps = withContext(graph.io) { Apps.list(activity) }
                Apps.pick(activity, R.string.all_apps, apps) { Apps.launch(activity, it.pkg) }
            }
        }
        root.findViewById<View>(R.id.android_settings).setOnClickListener { Apps.openAndroidSettings(activity) }
        defaultHome.setOnClickListener {
            // Already the Home app: open Android's launcher choice (box's own launcher or another).
            if (Apps.isDefaultHome(activity)) Apps.changeDefaultHome(activity)
            else Apps.requestDefaultHome(activity) { updateDefaultHome() }
        }
        updateAutoStart()
    }

    private fun updateDefaultHome() {
        defaultHome.setText(if (Apps.isDefaultHome(activity)) R.string.home_is_default else R.string.home_make_default)
    }

    private fun canLaunchAtBoot() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(activity)

    /** Toggle text, plus what happened at the last boot and what's still missing. */
    private fun updateAutoStart() {
        val prefs = graph.prefs
        autoStart.setText(if (prefs.autoStart) R.string.auto_start_on else R.string.auto_start_off)
        if (!prefs.autoStart || prefs.isMobile) {
            autoStartStatus.visibility = View.GONE
            return
        }
        val lines = ArrayList<String>()
        if (!canLaunchAtBoot()) lines += activity.getString(R.string.auto_start_blocked, activity.packageName)
        val at = prefs.lastBootAt
        lines += if (at == 0L) {
            activity.getString(R.string.auto_start_never)
        } else {
            val time = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(at))
            activity.getString(if (prefs.lastBootAllowed) R.string.auto_start_last_boot else R.string.auto_start_last_boot_blocked, time)
        }
        autoStartStatus.text = lines.joinToString("\n\n")
        autoStartStatus.visibility = View.VISIBLE
    }

    private fun toggleAutoStart() {
        val prefs = graph.prefs
        // Already on but still blocked: OK reopens the permission page instead of switching off.
        val on = if (prefs.autoStart && !canLaunchAtBoot()) true else !prefs.autoStart
        prefs.autoStart = on
        updateAutoStart()
        // Android 10+ blocks opening at boot unless the app may draw over other apps.
        if (on && !canLaunchAtBoot()) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + activity.packageName))
            try {
                activity.toast(activity.getString(R.string.auto_start_permission))
                activity.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                // Many TV builds have no such settings page.
                activity.toast(activity.getString(R.string.auto_start_no_settings))
            }
        }
    }

    override fun onShown() {
        activity.hideVideo()
        updateAutoStart() // the permission may have been granted in system settings meanwhile
        updateDefaultHome() // may have been changed in system settings meanwhile
        graph.player.stop()
        reload()
    }

    override fun onBack(): Boolean = busy

    private fun reload() {
        scope.launch {
            val playlists = graph.repo.playlists()
            list.removeAllViews()
            val active = graph.prefs.activePlaylist
            for (p in playlists) list.addView(row(p, p.id == active))
            (list.getChildAt(playlists.indexOfFirst { it.id == active }.coerceAtLeast(0)) ?: add).requestFocus()
        }
    }

    private fun row(p: Playlist, active: Boolean): View {
        val v = LayoutInflater.from(activity).inflate(R.layout.row_text, list, false) as TextView
        val kind = if (p.isXtream) "Xtream" else "M3U"
        val lock = if (p.hasPin) "  ·  PIN" else ""
        v.text = if (active) "${p.name}  ·  $kind$lock  ·  active" else "${p.name}  ·  $kind$lock"
        v.setOnClickListener { open(p) }
        v.setOnLongClickListener { actions(p); true }
        v.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_DOWN) {
                actions(p)
                true
            } else {
                false
            }
        }
        return v
    }

    private fun open(p: Playlist) {
        if (busy) return
        graph.prefs.activePlaylist = p.id
        graph.prefs.lastLiveCategory = null
        graph.prefs.lastLiveItem = null
        activity.resetTo(HomeScreen(activity))
    }

    private fun actions(p: Playlist) {
        if (busy) return
        val labels = arrayOf(
            activity.getString(R.string.action_open),
            activity.getString(R.string.action_refresh),
            activity.getString(R.string.action_details),
            activity.getString(R.string.action_delete),
        )
        AlertDialog.Builder(activity)
            .setTitle(p.name)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> open(p)
                    1 -> refresh(p)
                    2 -> PinPrompt.require(activity, p) { details(p) }
                    3 -> PinPrompt.require(activity, p) { delete(p) }
                }
            }
            .show()
    }

    private fun refresh(p: Playlist) {
        busy = true
        busyDots.visibility = View.VISIBLE
        message.setTextColor(activity.getColor(R.color.text_secondary))
        scope.launch {
            try {
                graph.syncer.sync(p) { text -> activity.runOnUiThread { message.text = text } }
                message.text = "${p.name} is up to date"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message.setTextColor(activity.getColor(R.color.error))
                message.text = e.message ?: "Refresh failed"
            } finally {
                busy = false
                busyDots.visibility = View.GONE
            }
        }
    }

    /** Server, login and URL. Reached only through [PinPrompt] when the playlist has a PIN. */
    private fun details(p: Playlist) {
        val lines = if (p.isXtream) {
            listOf("Type: Xtream Codes", "Server: ${p.url}", "Username: ${p.username.orEmpty()}", "Password: ${p.password.orEmpty()}")
        } else {
            listOf("Type: M3U", "URL: ${p.url}")
        }
        AlertDialog.Builder(activity)
            .setTitle(p.name)
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(if (p.hasPin) R.string.pin_change else R.string.pin_set) { _, _ -> changePin(p) }
            .show()
    }

    private fun changePin(p: Playlist) {
        PinPrompt.askNew(activity, canRemove = p.hasPin) { pin ->
            scope.launch {
                graph.repo.setPin(p.id, pin?.let { Pin.hash(it) })
                activity.toast(activity.getString(if (pin == null) R.string.pin_removed else R.string.pin_saved))
                reload()
            }
        }
    }

    private fun delete(p: Playlist) {
        AlertDialog.Builder(activity)
            .setTitle("Delete ${p.name}?")
            .setPositiveButton(R.string.action_delete) { _, _ ->
                scope.launch {
                    graph.repo.deletePlaylist(p.id)
                    if (graph.prefs.activePlaylist == p.id) graph.prefs.activePlaylist = -1
                    if (graph.repo.playlists().isEmpty()) {
                        activity.resetTo(HomeScreen(activity))
                        activity.push(AddPlaylistScreen(activity, firstRun = true))
                    } else {
                        reload()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
