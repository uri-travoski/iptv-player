package com.worldtv.iptvplayer.ui

import android.app.AlertDialog
import android.app.Activity
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.os.Build
import android.os.SystemClock
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import com.worldtv.iptvplayer.MainActivity
import com.worldtv.iptvplayer.R

/** An installed app the home screen can open. [banner] is the TV banner (16:9) if it has one. */
class LaunchableApp(val pkg: String, val label: String, val icon: Drawable?, val banner: Drawable?)

/** Installed-app lookups for the home screen's app slots and the "All apps" list. Disk work: call off the UI thread. */
object Apps {

    /** Every app with a TV or phone launcher entry, except this one, sorted by name. */
    fun list(context: Context): List<LaunchableApp> {
        val pm = context.packageManager
        val seen = HashSet<String>()
        val out = ArrayList<LaunchableApp>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (ri in pm.queryIntentActivities(query, 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg == context.packageName || !seen.add(pkg)) continue
                out += LaunchableApp(pkg, ri.loadLabel(pm).toString(), ri.loadIcon(pm), null)
            }
        }
        out.sortBy { it.label.lowercase() }
        return out
    }

    /** One app with its banner (preferred on the home screen) and icon, or null if it is gone. */
    fun info(context: Context, pkg: String): LaunchableApp? {
        val pm = context.packageManager
        return try {
            val app = pm.getApplicationInfo(pkg, 0)
            val leanback = pm.getLeanbackLaunchIntentForPackage(pkg)?.let { pm.resolveActivity(it, 0) }
            val banner = leanback?.activityInfo?.loadBanner(pm) ?: app.loadBanner(pm)
            LaunchableApp(pkg, app.loadLabel(pm).toString(), app.loadIcon(pm), banner)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    fun launch(activity: MainActivity, pkg: String) {
        val pm = activity.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            activity.toast(activity.getString(R.string.app_missing))
            return
        }
        try {
            activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            activity.toast(activity.getString(R.string.app_missing))
        }
    }

    /** The box's own Settings app. */
    fun openAndroidSettings(activity: MainActivity) {
        try {
            activity.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            activity.toast(activity.getString(R.string.app_missing))
        }
    }

    /** True if WorldTV is the box's default Home app (launcher). */
    fun isDefaultHome(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = context.getSystemService(RoleManager::class.java)
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME)) return roles.isRoleHeld(RoleManager.ROLE_HOME)
        }
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName == context.packageName
    }

    /**
     * Asks Android to make WorldTV the Home app. Android 10+ shows its own "Set as default Home
     * app?" dialog (RoleManager). Some TV builds have no such dialog and return at once; then,
     * and on older Android, the Home app settings page opens instead. Box firmwares that hide
     * that page get the main Settings.
     */
    fun requestDefaultHome(activity: MainActivity, onDone: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = activity.getSystemService(RoleManager::class.java)
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME)) {
                val started = SystemClock.elapsedRealtime()
                activity.launchForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME)) { result ->
                    val instant = SystemClock.elapsedRealtime() - started < 700
                    if (result != Activity.RESULT_OK && instant && !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
                        openHomeSettings(activity) // no dialog on this box
                    }
                    onDone()
                }
                return
            }
        }
        openHomeSettings(activity)
        onDone()
    }

    /**
     * WorldTV is already the Home app: open Android's Home app setting so the user can go back to
     * the box's own launcher or choose another. Boxes without that page get a list of installed
     * launchers to open instead (the box then asks which to keep, if it does).
     */
    fun changeDefaultHome(activity: MainActivity) {
        try {
            activity.startActivity(Intent(android.provider.Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            val launchers = launchers(activity)
            pick(activity, R.string.choose_launcher, launchers) { app ->
                val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(app.pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    activity.startActivity(home)
                } catch (e: ActivityNotFoundException) {
                    activity.toast(activity.getString(R.string.app_missing))
                }
            }
        }
    }

    /** Installed launchers (apps that can be the Home screen), WorldTV included. */
    fun launchers(context: Context): List<LaunchableApp> {
        val pm = context.packageManager
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return pm.queryIntentActivities(home, 0)
            .filter { it.priority >= 0 } // skips Android's built-in "fallback home"
            .map { LaunchableApp(it.activityInfo.packageName, it.loadLabel(pm).toString(), it.loadIcon(pm), null) }
            .distinctBy { it.pkg }
    }

    private fun openHomeSettings(activity: MainActivity) {
        try {
            activity.startActivity(Intent(android.provider.Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            activity.toast(activity.getString(R.string.home_pick_worldtv))
        } catch (e: ActivityNotFoundException) {
            activity.toast(activity.getString(R.string.home_no_setting))
            openAndroidSettings(activity)
        }
    }

    /** A list dialog of [apps] with their icons; [onPick] gets the chosen one. */
    fun pick(activity: MainActivity, title: Int, apps: List<LaunchableApp>, onPick: (LaunchableApp) -> Unit) {
        val size = (32 * activity.resources.displayMetrics.density).toInt()
        val pad = (12 * activity.resources.displayMetrics.density).toInt()
        val adapter = object : ArrayAdapter<LaunchableApp>(activity, android.R.layout.simple_list_item_1, apps) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                val app = apps[position]
                v.text = app.label
                v.compoundDrawablePadding = pad
                val icon = app.icon?.constantState?.newDrawable()?.mutate()?.also { it.setBounds(0, 0, size, size) }
                v.setCompoundDrawablesRelative(icon, null, null, null)
                return v
            }
        }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setAdapter(adapter) { _, which -> onPick(apps[which]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
