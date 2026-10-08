package io.github.sardinemehico.iptvplayer.ui

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * The search box. Google's Leanback keyboard (Android TV boxes) stays connected to a focused
 * field after it is closed and keeps taking the remote's arrow keys, so focus could not leave the
 * box. Keys reach this field before the keyboard: with no keyboard on screen, the arrows move
 * focus here; and Back that closes the keyboard also moves focus off the field.
 */
class SearchField @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    EditText(context, attrs) {

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        val keyboardShown = ViewCompat.getRootWindowInsets(this)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && keyboardShown) {
            post { moveFocus(View.FOCUS_DOWN) } // the keyboard closes; leave the field with it
            return super.onKeyPreIme(keyCode, event)
        }
        if (!keyboardShown) {
            val direction = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
                KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
                KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
                else -> 0
            }
            if (direction != 0) {
                if (event.action == KeyEvent.ACTION_DOWN) moveFocus(direction)
                return true
            }
        }
        return super.onKeyPreIme(keyCode, event)
    }

    private fun moveFocus(direction: Int) {
        if (!hasFocus()) return
        focusSearch(direction)?.requestFocus(direction)
    }
}
