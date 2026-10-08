package io.github.sardinemehico.iptvplayer.ui

import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import io.github.sardinemehico.iptvplayer.MainActivity
import io.github.sardinemehico.iptvplayer.R

/** App Settings > Help: setup and how everything works (res/values/help.xml). Up/Down scroll. */
class HelpScreen(activity: MainActivity) : Screen(activity) {

    override val root: View = inflater.inflate(R.layout.screen_help, null)
    private val scroll: View = root.findViewById(R.id.form)

    init {
        fullWidthOnMobile(R.id.form)
        val sections = root.findViewById<LinearLayout>(R.id.sections)
        val titles = activity.resources.getStringArray(R.array.help_titles)
        val bodies = activity.resources.getStringArray(R.array.help_bodies)
        val density = activity.resources.displayMetrics.density
        titles.forEachIndexed { i, title ->
            sections.addView(
                TextView(activity).apply {
                    text = title
                    setTextColor(activity.getColor(R.color.text_primary))
                    textSize = 19f
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, (22 * density).toInt(), 0, (6 * density).toInt())
                },
            )
            sections.addView(
                TextView(activity).apply {
                    text = bodies.getOrElse(i) { "" }
                    setTextColor(activity.getColor(R.color.text_secondary))
                    textSize = 16f
                    setLineSpacing(4 * density, 1f)
                },
            )
        }
    }

    override fun onShown() {
        activity.hideVideo()
        scroll.requestFocus()
    }
}
