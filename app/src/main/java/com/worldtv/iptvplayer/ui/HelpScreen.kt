package com.worldtv.iptvplayer.ui

import android.graphics.Typeface
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.worldtv.iptvplayer.MainActivity
import com.worldtv.iptvplayer.R
import com.worldtv.iptvplayer.data.help.HelpGuide

/**
 * App Settings > Help: the user guide (data/help/HelpGuide). TV: the topics, grouped, on the
 * left; Up / Down picks one and its text shows on the right; Right (or OK) goes into the text,
 * where Up / Down scroll, and Left goes back to the topic. Mobile: every topic in turn, to scroll.
 */
class HelpScreen(activity: MainActivity) : Screen(activity) {

    private val mobile = graph.prefs.isMobile
    private val topics = HelpGuide.topics(if (mobile) HelpGuide.Platform.MOBILE else HelpGuide.Platform.TV)
    private val density = activity.resources.displayMetrics.density

    override val root: View = inflater.inflate(if (mobile) R.layout.screen_help else R.layout.screen_help_tv, null)

    /** TV: the row of the topic on show. */
    private var shownRow: View? = null

    init {
        if (mobile) buildMobile() else buildTv()
    }

    private fun dp(v: Int) = (v * density).toInt()

    private fun heading(text: String, size: Float, top: Int, color: Int = R.attr.wtTextPrimary) = TextView(activity).apply {
        this.text = text
        setTextColor(activity.themeColor(color))
        textSize = size
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(top), 0, dp(6))
    }

    private fun point(text: String) = TextView(activity).apply {
        this.text = "•  $text"
        setTextColor(activity.themeColor(R.attr.wtTextSecondary))
        textSize = 17f
        setLineSpacing(4 * density, 1f)
        setPadding(dp(4), dp(5), 0, dp(5))
    }

    private fun buildMobile() {
        fullWidthOnMobile(R.id.form)
        val sections = root.findViewById<LinearLayout>(R.id.sections)
        var group = ""
        for (t in topics) {
            if (t.group != group) {
                group = t.group
                sections.addView(heading(group.uppercase(), 14f, 28, R.attr.wtAccent))
            }
            sections.addView(heading(t.title, 19f, 14))
            t.points.forEach { sections.addView(point(it)) }
        }
    }

    private fun buildTv() {
        val list = root.findViewById<LinearLayout>(R.id.help_topics)
        val text = root.findViewById<LinearLayout>(R.id.help_text)
        val scroll = root.findViewById<ScrollView>(R.id.help_text_scroll)
        fun show(t: HelpGuide.Topic) {
            text.removeAllViews()
            text.addView(heading(t.group.uppercase(), 14f, 0, R.attr.wtAccent))
            text.addView(heading(t.title, 24f, 2))
            t.points.forEach { text.addView(point(it)) }
            scroll.scrollTo(0, 0)
        }
        var group = ""
        var last: View? = null
        for (t in topics) {
            if (t.group != group) {
                group = t.group
                list.addView(
                    heading(group.uppercase(), 13f, if (list.childCount == 0) 0 else 18, R.attr.wtAccent).apply {
                        setPadding(dp(12), paddingTop, 0, dp(4))
                    },
                )
            }
            val row = inflater.inflate(R.layout.row_category, list, false) as TextView
            row.id = View.generateViewId()
            row.text = t.title
            // Long names wrap onto a second line.
            row.maxLines = 2
            row.minHeight = dp(48)
            row.setPadding(row.paddingLeft, dp(8), row.paddingRight, dp(8))
            row.layoutParams = row.layoutParams.apply { height = LinearLayout.LayoutParams.WRAP_CONTENT }
            row.setOnFocusChangeListener { v, has ->
                if (has && shownRow !== v) {
                    shownRow?.isSelected = false
                    shownRow = v
                    show(t)
                }
                // Still marked while its text is read.
                v.isSelected = !has && shownRow === v
            }
            row.setOnClickListener { scroll.requestFocus() }
            row.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && event.action == KeyEvent.ACTION_DOWN) {
                    scroll.requestFocus()
                    true
                } else {
                    false
                }
            }
            list.addView(row)
            if (shownRow == null) {
                shownRow = row
                show(t)
            }
            last = row
        }
        last?.let { it.nextFocusDownId = it.id }
        // In the text: Up / Down only scroll (never jump back to the list), Left / Back return to the topic.
        scroll.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener keyCode in TEXT_KEYS
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> shownRow?.requestFocus()
                KeyEvent.KEYCODE_DPAD_UP -> scroll.smoothScrollBy(0, -dp(240))
                KeyEvent.KEYCODE_DPAD_DOWN -> scroll.smoothScrollBy(0, dp(240))
                else -> return@setOnKeyListener false
            }
            true
        }
    }

    /** In the text, Back first returns to the topics. */
    override fun onBack(): Boolean {
        if (!mobile && root.findViewById<View>(R.id.help_text_scroll).hasFocus()) {
            shownRow?.requestFocus()
            return true
        }
        return false
    }

    override fun onShown() {
        activity.hideVideo()
        if (mobile) root.findViewById<View>(R.id.form).requestFocus() else shownRow?.requestFocus()
    }

    private companion object {
        val TEXT_KEYS = setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)
    }
}
