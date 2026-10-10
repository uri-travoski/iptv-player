package com.worldtv.iptvplayer.ui

import android.app.AlertDialog
import android.os.SystemClock
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import com.worldtv.iptvplayer.MainActivity
import com.worldtv.iptvplayer.R
import com.worldtv.iptvplayer.data.repo.Pin
import com.worldtv.iptvplayer.data.repo.Playlist

/**
 * Asks for a playlist's PIN before [onOk] runs. Playlists without a PIN pass straight through.
 * The dialog closes by itself on the sixth digit. After [MAX_TRIES] wrong PINs, entry is
 * locked for [LOCK_MS] so the 1,000,000 combinations can't be tried quickly.
 */
object PinPrompt {

    private const val MAX_TRIES = 5
    private const val LOCK_MS = 30_000L
    private var wrong = 0
    private var lockedUntil = 0L

    fun require(activity: MainActivity, playlist: Playlist, onOk: () -> Unit) {
        val stored = playlist.pinHash ?: return onOk()
        val waitMs = lockedUntil - SystemClock.elapsedRealtime()
        if (waitMs > 0) {
            activity.toast(activity.getString(R.string.pin_locked, (waitMs + 999) / 1000))
            return
        }
        ask(activity, activity.getString(R.string.pin_enter, playlist.name)) { pin, dialog ->
            if (Pin.matches(pin, stored)) {
                wrong = 0
                dialog.dismiss()
                onOk()
            } else {
                dialog.dismiss()
                if (++wrong >= MAX_TRIES) {
                    wrong = 0
                    lockedUntil = SystemClock.elapsedRealtime() + LOCK_MS
                    activity.toast(activity.getString(R.string.pin_locked, LOCK_MS / 1000))
                } else {
                    activity.toast(activity.getString(R.string.pin_wrong))
                }
            }
        }
    }

    /** Asks for a new PIN; [onPin] gets null if the user picks "Remove PIN" (offered when [canRemove]). */
    fun askNew(activity: MainActivity, canRemove: Boolean, onPin: (String?) -> Unit) {
        val remove = if (canRemove) activity.getString(R.string.pin_remove) to { onPin(null) } else null
        ask(activity, activity.getString(R.string.pin_new), remove) { pin, dialog ->
            dialog.dismiss()
            onPin(pin)
        }
    }

    private fun ask(
        activity: MainActivity,
        title: String,
        extra: Pair<String, () -> Unit>? = null,
        onSix: (String, AlertDialog) -> Unit,
    ) {
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(Pin.LENGTH))
            setTextColor(activity.getColor(R.color.text_primary))
            textSize = 24f
            letterSpacing = 0.4f
            isSingleLine = true
        }
        val pad = (24 * activity.resources.displayMetrics.density).toInt()
        val box = FrameLayout(activity).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(box)
            .setNegativeButton(android.R.string.cancel, null)
            .apply { extra?.let { (label, action) -> setPositiveButton(label) { _, _ -> action() } } }
            .create()
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                val pin = s.toString()
                if (Pin.isValid(pin)) onSix(pin, dialog)
            }
        })
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
        input.requestFocus()
    }
}
