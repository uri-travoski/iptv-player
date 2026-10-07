package io.github.sardinemehico.iptvplayer.ui

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.View
import io.github.sardinemehico.iptvplayer.MainActivity
import io.github.sardinemehico.iptvplayer.Prefs
import io.github.sardinemehico.iptvplayer.R

/**
 * First start: TV (remote, the original layouts) or Mobile (touch, portrait). The likely answer
 * has focus, so a remote user just presses OK. Changeable later in App Settings.
 */
class UiModeScreen(activity: MainActivity) : Screen(activity) {

    override val root: View = inflater.inflate(R.layout.screen_ui_mode, null)
    private val tv: View = root.findViewById(R.id.pick_tv)
    private val mobile: View = root.findViewById(R.id.pick_mobile)

    init {
        tv.setOnClickListener { pick(Prefs.UI_TV) }
        mobile.setOnClickListener { pick(Prefs.UI_MOBILE) }
    }

    override fun onShown() {
        activity.hideVideo()
        (if (looksLikeTv()) tv else mobile).requestFocus()
    }

    private fun looksLikeTv(): Boolean {
        val ui = activity.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        return ui.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            !activity.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }

    private fun pick(mode: String) {
        graph.prefs.uiMode = mode
        activity.restartUi()
    }
}
