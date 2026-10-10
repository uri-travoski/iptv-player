package com.worldtv.iptvplayer.ui

import android.app.AlertDialog
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import com.worldtv.iptvplayer.App
import com.worldtv.iptvplayer.MainActivity
import com.worldtv.iptvplayer.R

/**
 * First-run notice: one tick accepts the Terms of Use, the Privacy Policy and diagnostics.
 * The box is pre-ticked (opt-in by default); unticking it turns diagnostics off. Shown once.
 */
object AgreementDialog {

    fun showIfNeeded(activity: MainActivity) {
        val prefs = App.graph.prefs
        if (prefs.telemetryAgreed) return
        val d = activity.resources.displayMetrics.density
        val body = TextView(activity).apply {
            setText(R.string.agreement_body)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(activity.getColor(R.color.text_secondary))
            movementMethod = LinkMovementMethod.getInstance()
        }
        val check = CheckBox(activity).apply {
            isChecked = true
            setText(R.string.agreement_accept)
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * d).toInt()
            setPadding(pad, (8 * d).toInt(), pad, 0)
            addView(body)
            addView(check)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.agreement_title)
            .setView(box)
            .setCancelable(false)
            .setPositiveButton(R.string.agreement_continue) { _, _ ->
                prefs.telemetryEnabled = check.isChecked
                prefs.telemetryAgreed = true
            }
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        // Compact: a third of the screen wide, in the middle.
        dialog.window?.setLayout(activity.resources.displayMetrics.widthPixels / 3, ViewGroup.LayoutParams.WRAP_CONTENT)
        // The cursor starts on Continue, coloured like a selected tile, so one OK press finishes.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.let { cont ->
            cont.setTextColor(activity.getColor(R.color.text_primary))
            cont.setBackgroundResource(R.drawable.bg_tile)
            val pad = (24 * d).toInt()
            cont.setPadding(pad, cont.paddingTop, pad, cont.paddingBottom)
            cont.isFocusable = true
            cont.isFocusableInTouchMode = true
            cont.post { cont.requestFocus() }
        }
    }
}
