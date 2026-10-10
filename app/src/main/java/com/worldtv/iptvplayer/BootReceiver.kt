package com.worldtv.iptvplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * Opens the app after the box boots, if "Auto-start on boot" is on in Settings.
 * On Android 10+ the system only lets this through when the app may draw over other apps
 * (Settings asks for that permission when the option is switched on).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in BOOT_ACTIONS) return
        val prefs = App.graph.prefs
        if (!prefs.autoStart) return
        // Recorded so Settings can say whether the boot signal arrived and whether Android allowed the launch.
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(context)
        prefs.lastBootAt = System.currentTimeMillis()
        prefs.lastBootAllowed = allowed
        val launch = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            context.startActivity(launch)
        } catch (e: RuntimeException) {
            // Blocked by the system (no overlay permission): nothing more we can do quietly.
        }
    }

    private companion object {
        val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            // Some TV boxes resume from a "quick boot" standby instead of a full boot.
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
        )
    }
}
