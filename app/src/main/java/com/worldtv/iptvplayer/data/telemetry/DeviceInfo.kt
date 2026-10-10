package com.worldtv.iptvplayer.data.telemetry

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.DisplayMetrics
import com.worldtv.iptvplayer.Prefs
import java.util.Locale

/**
 * Everything the app can read about the box, for troubleshooting. All probes are wrapped so a
 * quirky TV-box ROM can never crash the app; missing values are simply left out or null.
 * Called once per app run on the telemetry thread, never on the UI thread.
 */
object DeviceInfo {

    /** The `hardware` jsonb blob (device + OS + display + storage + GPU). */
    fun hardware(context: Context): Map<String, Any?> = buildMap {
        put("manufacturer", Build.MANUFACTURER)
        put("brand", Build.BRAND)
        put("model", Build.MODEL)
        put("device", Build.DEVICE)
        put("product", Build.PRODUCT)
        put("hardware", Build.HARDWARE)
        put("board", Build.BOARD)
        put("bootloader", Build.BOOTLOADER)
        put("fingerprint", Build.FINGERPRINT)
        put("tags", Build.TAGS)
        put("type", Build.TYPE)
        put("soc_manufacturer", if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else null)
        put("soc_model", if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else null)
        put("abis", Build.SUPPORTED_ABIS?.toList())
        put("android_release", Build.VERSION.RELEASE)
        put("sdk", Build.VERSION.SDK_INT)
        put("security_patch", Build.VERSION.SECURITY_PATCH)
        put("incremental", Build.VERSION.INCREMENTAL)
        put("kernel", System.getProperty("os.version"))
        put("locale", Locale.getDefault().toString())
        put("language", Locale.getDefault().language)
        put("timezone", java.util.TimeZone.getDefault().id)

        cpu().let {
            put("cpu_cores", it.first)
            put("cpu_max_khz", it.second)
            put("cpu_min_khz", it.third)
        }
        memory(context).let {
            put("ram_total_mb", it[0])
            put("ram_avail_mb", it[1])
            put("is_low_ram", it[2] == 1L)
            put("memory_class_mb", it[3])
            put("large_heap", it[4] == 1L)
        }
        storage(context).let {
            put("storage_internal_total_mb", it[0])
            put("storage_internal_free_mb", it[1])
            put("storage_external_total_mb", it[2])
            put("storage_external_free_mb", it[3])
        }
        display(context).forEach { (k, v) -> put(k, v) }
        gpu().forEach { (k, v) -> if (v != null) put(k, v) }
        put("features", features(context))
    }

    /** The device-list label: "Sony BRAVIA 4K (Android 12)". */
    fun label(): String = try {
        val model = listOfNotNull(Build.MANUFACTURER.takeIf { it.isNotBlank() }, Build.MODEL.takeIf { it.isNotBlank() })
            .joinToString(" ").ifBlank { "Android device" }
        "$model (Android ${Build.VERSION.RELEASE})"
    } catch (e: Throwable) {
        "Android device"
    }

    /** The app's own build/download metadata + install times. */
    fun app(context: Context): Map<String, Any?> = buildMap {
        try {
            val pm = context.packageManager
            val pi = pm.getPackageInfo(context.packageName, 0)
            put("app_version", pi.versionName)
            put("version_code", if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong())
            put("first_install", pi.firstInstallTime)
            put("last_update", pi.lastUpdateTime)
            val ai = if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(context.packageName).installingPackageName
            else @Suppress("DEPRECATION") pm.getInstallerPackageName(context.packageName)
            put("install_source", ai)
            val debuggable = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            put("build_type", if (debuggable) "debug" else "release")
        } catch (e: Throwable) {
            null
        }
        put("label", label())
    }

    /** The full current settings dump (Settings > everything). */
    fun settings(prefs: Prefs): Map<String, Any?> = buildMap {
        put("ui_mode", prefs.uiMode)
        put("active_playlist", prefs.activePlaylist.takeIf { it > 0 })
        put("app_slot_count", prefs.appSlotCount)
        put("app_slots", (0 until prefs.appSlotCount).mapNotNull { prefs.appSlot(it) })
        put("auto_start", prefs.autoStart)
        put("auto_update", prefs.autoUpdate)
        put("auto_refresh", prefs.autoRefresh)
        put("dns_mode", prefs.dnsMode)
        put("live_sort", prefs.liveSort.name)
        put("live_sort_movie", prefs.vodSort(com.worldtv.iptvplayer.data.model.ContentType.MOVIE).name)
        put("live_sort_series", prefs.vodSort(com.worldtv.iptvplayer.data.model.ContentType.SERIES).name)
        put("resize_mode", prefs.resizeMode)
        put("subs_enabled", prefs.subsEnabled)
        put("subs_language", prefs.subsLanguage)
        put("subs_size", prefs.subsSize)
        put("subs_color", prefs.subsColor)
        put("subs_background", prefs.subsBackground)
        put("adult_category_words", prefs.adultCategoryWords.size)
        put("adult_entry_words", prefs.adultEntryWords.size)
        put("adult_scan_version", prefs.adultScanVersion)
        put("skipped_update", prefs.skippedUpdate)
        put("online_tmdb_accounts", prefs.accounts(com.worldtv.iptvplayer.data.online.OnlineService.TMDB).size)
        put("online_opensubs_accounts", prefs.accounts(com.worldtv.iptvplayer.data.online.OnlineService.OPENSUBTITLES).size)
        put("last_boot_allowed", prefs.lastBootAllowed)
        put("last_boot_at", prefs.lastBootAt.takeIf { it > 0 })
    }

    private fun cpu(): Triple<Int, Long, Long> {
        var max = -1L
        var min = -1L
        try {
            val cores = Runtime.getRuntime().availableProcessors()
            for (i in 0 until cores) {
                runCatching {
                    val hi = java.io.File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
                    if (hi > max) max = hi
                }
                runCatching {
                    val lo = java.io.File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_min_freq").readText().trim().toLong()
                    if (min < 0 || lo < min) min = lo
                }
            }
            return Triple(cores, max, min)
        } catch (e: Throwable) {
            return Triple(Runtime.getRuntime().availableProcessors(), max, min)
        }
    }

    /** [totalMb, availMb, isLowRam(0/1), memoryClassMb, largeHeap(0/1)]. */
    private fun memory(context: Context): LongArray {
        val out = longArrayOf(-1, -1, 0, -1, 0)
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            out[0] = mi.totalMem / (1024 * 1024)
            out[1] = mi.availMem / (1024 * 1024)
            out[2] = if (am.isLowRamDevice) 1 else 0
            out[3] = am.memoryClass.toLong()
            out[4] = if (am.isLowRamDevice) 0 else 1
        } catch (e: Throwable) {
            null
        }
        return out
    }

    /** [internalTotalMb, internalFreeMb, externalTotalMb, externalFreeMb]. */
    private fun storage(context: Context): LongArray {
        val out = longArrayOf(-1, -1, -1, -1)
        try {
            val internal = StatFs(context.filesDir.absolutePath)
            out[0] = internal.totalBytes / (1024 * 1024)
            out[1] = internal.availableBytes / (1024 * 1024)
        } catch (e: Throwable) {
            null
        }
        try {
            val ext = Environment.getExternalStorageDirectory() ?: return out
            val s = StatFs(ext.absolutePath)
            out[2] = s.totalBytes / (1024 * 1024)
            out[3] = s.availableBytes / (1024 * 1024)
        } catch (e: Throwable) {
            null
        }
        return out
    }

    private fun display(context: Context): Map<String, Any?> = buildMap {
        try {
            val dm: DisplayMetrics = context.resources.displayMetrics
            put("display_width", dm.widthPixels)
            put("display_height", dm.heightPixels)
            put("display_dpi", dm.densityDpi)
            put("display_density", dm.density.toDouble())
        } catch (e: Throwable) {
            null
        }
        runCatching {
            val d = if (Build.VERSION.SDK_INT >= 30) context.display
            else @Suppress("DEPRECATION") (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay
            d?.let {
                put("refresh_hz", it.refreshRate.toDouble())
                put("display_name", it.name)
                put("hdr", it.hdrCapabilities?.supportedHdrTypes?.toList())
            }
        }
    }

    private fun gpu(): Map<String, Any?> {
        var eglDisplay: EGLDisplay? = null
        var eglContext: EGLContext? = null
        var eglSurface: EGLSurface? = null
        return try {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return emptyMap()
            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfig = IntArray(1)
            EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfig, 0)
            if (numConfig[0] == 0) return emptyMap()
            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            val pbAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0], pbAttribs, 0)
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
            mapOf(
                "gpu_vendor" to GLES20.glGetString(GLES20.GL_VENDOR),
                "gpu_renderer" to GLES20.glGetString(GLES20.GL_RENDERER),
                "gpu_version" to GLES20.glGetString(GLES20.GL_VERSION),
            )
        } catch (e: Throwable) {
            emptyMap()
        } finally {
            runCatching {
                if (eglDisplay != null) {
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (eglSurface != null) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                    if (eglContext != null) EGL14.eglDestroyContext(eglDisplay, eglContext)
                    EGL14.eglTerminate(eglDisplay)
                }
            }
        }
    }

    private fun features(context: Context): List<String> {
        val pm = context.packageManager
        val names = listOf(
            PackageManager.FEATURE_LEANBACK to "leanback",
            PackageManager.FEATURE_TOUCHSCREEN to "touchscreen",
            PackageManager.FEATURE_TELEVISION to "television",
            PackageManager.FEATURE_WIFI to "wifi",
            PackageManager.FEATURE_ETHERNET to "ethernet",
            PackageManager.FEATURE_BLUETOOTH to "bluetooth",
            PackageManager.FEATURE_TELEPHONY to "telephony",
            PackageManager.FEATURE_CAMERA_ANY to "camera",
        )
        return names.filter { runCatching { pm.hasSystemFeature(it.first) }.getOrDefault(false) }.map { it.second }
    }
}
