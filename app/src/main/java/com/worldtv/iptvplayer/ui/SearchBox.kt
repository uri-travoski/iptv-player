package com.worldtv.iptvplayer.ui

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The search field above a category list. Typing is debounced so a slow on-screen keyboard
 * doesn't run a query per letter. [onQuery] gets the text (2+ characters), or null when the
 * field is cleared. [onSubmit] runs on the keyboard's Search key, after the keyboard closes.
 */
class SearchBox(
    private val field: EditText,
    private val scope: CoroutineScope,
    private val onQuery: (String?) -> Unit,
    private val onSubmit: () -> Unit,
) {
    private var pending: Job? = null
    private var quiet = false

    init {
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                if (quiet) return
                val q = s.toString().trim()
                pending?.cancel()
                pending = scope.launch {
                    delay(DEBOUNCE_MS)
                    onQuery(q.takeIf { it.length >= MIN_CHARS })
                }
            }
        })
        field.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                pending?.cancel()
                val q = field.text.toString().trim()
                onQuery(q.takeIf { it.length >= MIN_CHARS })
                (v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(v.windowToken, 0)
                onSubmit()
                true
            } else {
                false
            }
        }
    }

    /**
     * Empties the field without reporting it (a category was picked instead). Never while the
     * field has focus: rewriting text under the cursor wiped a half-typed letter, and the TV's
     * Leanback keyboard then closed and dropped focus into the lists.
     */
    fun clearQuietly() {
        if (field.text.isEmpty() || field.hasFocus()) return
        pending?.cancel()
        quiet = true
        field.setText("")
        quiet = false
    }

    private companion object {
        const val DEBOUNCE_MS = 500L
        const val MIN_CHARS = 2
    }
}
