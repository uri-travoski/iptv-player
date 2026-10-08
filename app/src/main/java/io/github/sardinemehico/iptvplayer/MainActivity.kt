package io.github.sardinemehico.iptvplayer

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.ViewTreeObserver
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import io.github.sardinemehico.iptvplayer.data.source.AdultNames
import io.github.sardinemehico.iptvplayer.ui.AddPlaylistScreen
import io.github.sardinemehico.iptvplayer.ui.HomeScreen
import io.github.sardinemehico.iptvplayer.ui.PlaylistsScreen
import io.github.sardinemehico.iptvplayer.ui.Screen
import io.github.sardinemehico.iptvplayer.ui.UiModeScreen
import kotlinx.coroutines.launch

/**
 * Single activity. The video [PlayerView] sits at the bottom of the window for the whole app
 * lifetime (one SurfaceView, never hidden); screens are stacked above it and either cover it
 * or leave a transparent hole where the video should show.
 */
@OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {

    lateinit var playerView: PlayerView
        private set
    private lateinit var screens: FrameLayout
    private val stack = ArrayList<Screen>()

    /**
     * Draws text at the designed size whatever the box's "Font size" setting is. The TV layouts
     * are fixed-size (rows, tiles, buttons); some boxes ship with a 115% font scale, which made
     * every screen look zoomed in and could clip labels.
     */
    override fun attachBaseContext(newBase: Context) {
        val config = newBase.resources.configuration
        if (config.fontScale == 1f) return super.attachBaseContext(newBase)
        val fixed = Configuration(config).apply { fontScale = 1f }
        super.attachBaseContext(newBase.createConfigurationContext(fixed))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // The root layout paints the background; the window's copy would be painted under it
        // on every frame for nothing.
        window.setBackgroundDrawable(null)
        playerView = findViewById(R.id.player)
        screens = findViewById(R.id.screens)
        App.graph.player.attach(playerView)
        playerView.resizeMode = App.graph.prefs.resizeMode
        // Draw under the status and navigation bars on every Android version (Android 15+ forces
        // it anyway) and keep the screens clear of them; the video stays full-bleed. TV boxes
        // report no bars, so nothing changes there.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // In video full screen the controls lie over the whole picture (the camera cutout too),
        // so the shade under them reaches the edges.
        ViewCompat.setOnApplyWindowInsetsListener(screens) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            if (videoFullscreen) v.setPadding(0, 0, 0, 0) else v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setFullscreen(false)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val top = stack.lastOrNull() ?: return finish()
                if (top.onBack()) return
                if (stack.size > 1) pop() else if (!isLauncher()) finish()
                // As the launcher, Back on the home screen stays put: there is nothing behind it.
            }
        })

        lifecycleScope.launch { route() }
        lifecycleScope.launch { scanForAdult() }
        logStartup()
    }

    /**
     * First screen. A new install first asks TV or Mobile. Then the home screen is always at the
     * bottom of the stack (it is also the launcher, with the app slots); with no playlist yet,
     * the add/choose screen opens on top of it.
     */
    private suspend fun route() {
        val graph = App.graph
        val playlists = graph.repo.playlists()
        if (graph.prefs.uiMode == null) {
            // Updated from a version without the choice: those installs are all TV boxes.
            if (playlists.isNotEmpty()) graph.prefs.uiMode = Prefs.UI_TV
            else return resetTo(UiModeScreen(this))
        }
        setFullscreen(false)
        val active = playlists.firstOrNull { it.id == graph.prefs.activePlaylist }
        push(HomeScreen(this))
        when {
            playlists.isEmpty() -> push(AddPlaylistScreen(this, firstRun = true))
            active == null -> push(PlaylistsScreen(this))
        }
    }

    /** Rebuilds every screen, e.g. after switching between TV and Mobile; [thenSettings] reopens App Settings. */
    fun restartUi(thenSettings: Boolean = false) {
        resetTo(null)
        lifecycleScope.launch {
            route()
            if (thenSettings) push(PlaylistsScreen(this@MainActivity))
        }
    }

    /** Mobile: true while a screen shows video full screen (bars hidden, no inset padding). */
    private var videoFullscreen = false

    /**
     * Mobile: video full screen turns the phone to landscape and hides the system bars; everything
     * else is portrait with the bars shown. TV layout: TV boxes are left as they are; a phone or
     * tablet set to the TV layout is held in landscape, which that layout is made for.
     */
    fun setFullscreen(on: Boolean) {
        if (!App.graph.prefs.isMobile) {
            val touch = packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
            requestedOrientation = if (touch) ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            return
        }
        requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        if (videoFullscreen != on) {
            videoFullscreen = on
            ViewCompat.requestApplyInsets(screens)
        }
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            bars.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    /**
     * Once per word-list version: hide adult content in playlists loaded before this check
     * existed (or before the list last changed). Loads and refreshes do it themselves.
     */
    private suspend fun scanForAdult() {
        val graph = App.graph
        if (graph.prefs.adultScanVersion >= AdultNames.VERSION) return
        try {
            // From version 1 (before hides were marked automatic): adopt the rows the rules explain.
            val from = graph.prefs.adultScanVersion
            val adopt = from == 1 // (2 and later already mark automatic hides)
            graph.repo.playlists().forEach {
                if (from < 4) graph.repo.forgetBulkShown(it.id) // old "Show all" switched adult hiding off
                graph.repo.autoHideAdult(it.id, graph.adultNames(), adopt)
            }
            graph.prefs.adultScanVersion = AdultNames.VERSION
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("WorldTV", "adult scan failed", e) // never crash at start over this; retried next start
        }
    }

    private var resultCallback: ((Int) -> Unit)? = null

    /** For system dialogs that report back (e.g. "set as Home app"). Registered before onStart, as required. */
    private val resultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        resultCallback?.invoke(r.resultCode)
        resultCallback = null
    }

    /** Starts [intent] and calls [done] with its result code. */
    fun launchForResult(intent: Intent, done: (Int) -> Unit) {
        resultCallback = done
        resultLauncher.launch(intent)
    }

    /** Logs process start -> first drawn screen, and tells Android the app is fully drawn. */
    private fun logStartup() {
        val root = findViewById<View>(R.id.screens)
        root.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (stack.isEmpty()) return true // wait for the first screen
                root.viewTreeObserver.removeOnPreDrawListener(this)
                val ms = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
                Log.i("WorldTV.Startup", "first screen drawn ${ms}ms after process start")
                reportFullyDrawn()
                return true
            }
        })
    }

    /** True when the box opened us as its launcher (Home), not from the app list. */
    fun isLauncher() = intent?.hasCategory(Intent.CATEGORY_HOME) == true

    /** Home button while WorldTV is the launcher: back to the home screen, wherever we are. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!intent.hasCategory(Intent.CATEGORY_HOME)) return
        setIntent(intent)
        while (stack.size > 1 && stack.last() !is HomeScreen) pop()
    }

    fun push(screen: Screen) {
        stack.lastOrNull()?.let {
            it.savedFocus = currentFocus?.takeIf { v -> isInside(v, it.root) }
            it.onHidden()
            screens.removeView(it.root)
        }
        stack += screen
        screens.addView(screen.root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        screen.onShown()
    }

    fun pop() {
        val top = stack.removeLastOrNull() ?: return
        top.onHidden()
        screens.removeView(top.root)
        top.destroy()
        stack.lastOrNull()?.let {
            screens.addView(it.root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            it.onShown()
            restoreFocus(it)
            it.root.post { restoreFocus(it) } // in case the screen's own focus request didn't land
        }
    }

    /**
     * Back on a screen with nothing focused, the remote's next presses went nowhere (and then
     * jumped): give focus back to the view the user left from, else to the screen's first one.
     */
    private fun restoreFocus(screen: Screen) {
        if (stack.lastOrNull() !== screen) return
        val current = currentFocus
        if (current != null && isInside(current, screen.root)) return
        val saved = screen.savedFocus
        if (saved != null && saved.isShown && isInside(saved, screen.root) && saved.requestFocus()) return
        screen.root.requestFocus()
    }

    private fun isInside(v: View, root: View): Boolean {
        var p: Any? = v
        while (p is View) {
            if (p === root) return true
            p = p.parent
        }
        return false
    }

    /** Replaces the whole stack with [screen] (or empties it). */
    fun resetTo(screen: Screen?) {
        while (stack.isNotEmpty()) {
            val s = stack.removeAt(stack.size - 1)
            s.onHidden()
            screens.removeView(s.root)
            s.destroy()
        }
        if (screen != null) push(screen)
    }

    /**
     * Takes the video surface off screen while no screen shows video.
     *
     * It used to be shrunk to 1x1 px instead (to keep the surface alive). Some TV-box display
     * hardware (seen on an Allwinner H618 "8K618-T", Android 12) mis-composes such a tiny layer
     * and sends only the window background to HDMI: a plain maroon screen, while screenshots
     * (composed differently) look fine.
     */
    fun hideVideo() {
        if (playerView.visibility != View.GONE) playerView.visibility = View.GONE
    }

    /** Positions the video and shows it. null = full screen. */
    fun setVideoRect(rect: Rect?) {
        if (playerView.visibility != View.VISIBLE) playerView.visibility = View.VISIBLE
        val lp = playerView.layoutParams as FrameLayout.LayoutParams
        if (rect == null) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            lp.leftMargin = 0
            lp.topMargin = 0
        } else {
            lp.width = rect.width()
            lp.height = rect.height()
            lp.leftMargin = rect.left
            lp.topMargin = rect.top
        }
        playerView.layoutParams = lp
    }

    fun keepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode != KeyEvent.KEYCODE_BACK) {
            val top = stack.lastOrNull()
            if (top != null && top.onKeyDown(event.keyCode, event)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        // Leaving the app (Home button): stop the stream so it doesn't keep downloading.
        // onHidden first, so a screen can note where playback was (onStart calls onShown again).
        stack.lastOrNull()?.onHidden()
        App.graph.player.stop()
    }

    override fun onStart() {
        super.onStart()
        io.github.sardinemehico.iptvplayer.ui.AppUpdates.resumeInstall(this)
        stack.lastOrNull()?.onShown()
    }

    override fun onDestroy() {
        stack.forEach { it.destroy() }
        stack.clear()
        super.onDestroy()
    }
}
