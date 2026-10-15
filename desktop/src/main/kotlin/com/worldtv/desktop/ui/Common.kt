package com.worldtv.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.onClick
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.worldtv.iptvplayer.data.repo.EntryRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Short messages at the bottom of the window ("Added to favourites"). */
class Toaster(private val scope: CoroutineScope) {
    var message by mutableStateOf<String?>(null)
        private set
    private var job: Job? = null

    fun show(text: String) {
        message = text
        job?.cancel()
        job = scope.launch {
            delay(3000)
            message = null
        }
    }
}

val LocalToaster = staticCompositionLocalOf<Toaster> { error("no toaster") }

@Composable
fun ToastHost(toaster: Toaster, modifier: Modifier = Modifier) {
    val wt = LocalWt.current
    // The last text stays while it fades out.
    var shown by remember { mutableStateOf("") }
    toaster.message?.let { shown = it }
    AnimatedVisibility(toaster.message != null, modifier, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier.padding(24.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xEE111111))
                .border(1.dp, wt.divider, RoundedCornerShape(8.dp)).padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Text(shown, color = Color.White, fontSize = 15.sp)
        }
    }
}

/** Screen title row: Back, the title, then [actions] on the right. */
@Composable
fun TopBar(title: String, onBack: (() -> Unit)?, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    val wt = LocalWt.current
    Row(
        Modifier.fillMaxWidth().background(wt.bar).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconAction(Icons.AutoMirrored.Filled.ArrowBack, "Back (Esc)", onBack)
            Spacer(Modifier.width(8.dp))
        }
        Text(title, color = wt.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) {
            Spacer(Modifier.width(14.dp))
            Text(subtitle, color = wt.textSecondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** A round icon button with a tooltip-like label on hover. */
@Composable
fun IconAction(icon: ImageVector, label: String, onClick: () -> Unit, size: Dp = 40.dp, tint: Color? = null, active: Boolean = false) {
    val wt = LocalWt.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    androidx.compose.foundation.TooltipArea(
        tooltip = {
            Box(Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xEE111111)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                Text(label, color = Color.White, fontSize = 13.sp)
            }
        },
        delayMillis = 500,
    ) {
        Box(
            Modifier.size(size).clip(RoundedCornerShape(size / 2))
                .background(
                    when {
                        active -> wt.accent
                        hovered || focused -> wt.surfaceActive
                        else -> Color.Transparent
                    },
                )
                .hoverable(source)
                .clickable(interactionSource = source, indication = null, onClick = onClick)
                .pointerHoverIcon(PointerIcon.Hand),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, tint = tint ?: wt.textPrimary, modifier = Modifier.size(size * 0.58f))
        }
    }
}

/** Text button: filled with the accent ([primary]) or the surface colour. */
@Composable
fun WtButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val wt = LocalWt.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val bg = when {
        !enabled -> wt.surface.copy(alpha = 0.5f)
        primary && (hovered || focused) -> wt.pressed
        primary -> wt.accent
        hovered || focused -> wt.surfaceActive
        else -> wt.surface
    }
    Row(
        modifier.clip(RoundedCornerShape(8.dp)).background(bg)
            .then(if (wt.stroke.alpha > 0f) Modifier.border(1.dp, wt.stroke, RoundedCornerShape(8.dp)) else Modifier)
            .hoverable(source)
            .clickable(enabled = enabled, interactionSource = source, indication = null, onClick = onClick)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = wt.textPrimary.copy(alpha = if (enabled) 1f else 0.5f), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = wt.textPrimary.copy(alpha = if (enabled) 1f else 0.5f), fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** A list row: highlighted on hover, filled when [selected], marked with the accent when [current]. */
@Composable
fun ListRow(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    current: Boolean = false,
    height: Dp = 44.dp,
    onDoubleClick: (() -> Unit)? = null,
    onSecondary: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val wt = LocalWt.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Row(
        modifier.fillMaxWidth().height(height).padding(horizontal = 6.dp, vertical = 2.dp).clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    selected -> wt.surfaceActive
                    hovered -> wt.surface
                    else -> Color.Transparent
                },
            )
            .then(if (current) Modifier.border(BorderStroke(2.dp, wt.accent), RoundedCornerShape(6.dp)) else Modifier)
            .hoverable(source)
            .clicks(source, onClick, onDoubleClick, onSecondary)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Click, double click and right click (context menu) on one element. */
fun Modifier.clicks(
    source: MutableInteractionSource,
    onClick: () -> Unit,
    onDoubleClick: (() -> Unit)? = null,
    onSecondary: (() -> Unit)? = null,
): Modifier {
    var m = this
    if (onSecondary != null) {
        m = m.onClick(matcher = androidx.compose.foundation.PointerMatcher.mouse(androidx.compose.ui.input.pointer.PointerButton.Secondary), onClick = onSecondary)
    }
    return m.onClick(interactionSource = source, onDoubleClick = onDoubleClick, onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
}

/** The search box of lists: filters as you type; Esc clears it. */
@Composable
fun SearchField(state: MutableState<String>, hint: String, modifier: Modifier = Modifier, focus: FocusRequester? = null, onFocus: (Boolean) -> Unit = {}) {
    val wt = LocalWt.current
    Row(
        modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(wt.surface)
            .then(if (wt.stroke.alpha > 0f) Modifier.border(1.dp, wt.stroke, RoundedCornerShape(20.dp)) else Modifier)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, null, tint = wt.textSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (state.value.isEmpty()) Text(hint, color = wt.textSecondary, fontSize = 15.sp, maxLines = 1)
            BasicTextField(
                state.value,
                { state.value = it },
                singleLine = true,
                textStyle = TextStyle(color = wt.textPrimary, fontSize = 15.sp),
                cursorBrush = SolidColor(wt.textPrimary),
                modifier = Modifier.fillMaxWidth().then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
                    .onFocusChanged { onFocus(it.isFocused) }
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.Escape && state.value.isNotEmpty()) {
                            state.value = ""
                            true
                        } else {
                            false
                        }
                    },
            )
        }
        if (state.value.isNotEmpty()) {
            Icon(
                Icons.Default.Close, "Clear", tint = wt.textSecondary,
                modifier = Modifier.size(20.dp).clickable { state.value = "" }.pointerHoverIcon(PointerIcon.Hand),
            )
        }
    }
}

/** A small label badge (rating, "NEW"). */
@Composable
fun Badge(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(4.dp)).background(color).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/** A dim empty-list or loading message, centred. */
@Composable
fun CenterNote(text: String, modifier: Modifier = Modifier) {
    val wt = LocalWt.current
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(text, color = wt.textSecondary, fontSize = 16.sp, modifier = Modifier.padding(24.dp))
    }
}

/**
 * Rows of a long list (300k movies), read from SQLite a page at a time as they come into view.
 * [version] changes when the list must be read again (a reload, a new category).
 */
class PagedRows(
    private val scope: CoroutineScope,
    val count: Int,
    private val load: suspend (offset: Int, limit: Int) -> List<EntryRow>,
) {
    private val pages = mutableStateMapOf<Int, List<EntryRow>>()
    private val loading = HashSet<Int>()

    operator fun get(index: Int): EntryRow? {
        if (index !in 0 until count) return null
        val p = index / PAGE
        val page = pages[p]
        if (page == null) request(p)
        return page?.getOrNull(index % PAGE)
    }

    /** Reads the row at [index] (from its page, loading it if needed). */
    suspend fun fetch(index: Int): EntryRow? {
        if (index !in 0 until count) return null
        val p = index / PAGE
        pages[p]?.let { return it.getOrNull(index % PAGE) }
        val rows = load(p * PAGE, PAGE)
        pages[p] = rows
        return rows.getOrNull(index % PAGE)
    }

    private fun request(p: Int) {
        if (!loading.add(p)) return
        scope.launch {
            try {
                pages[p] = load(p * PAGE, PAGE)
            } finally {
                loading.remove(p)
            }
        }
    }

    /** One row changed (a favourite): only it is redrawn. */
    fun update(index: Int, row: EntryRow) {
        val p = index / PAGE
        val page = pages[p] ?: return
        pages[p] = page.toMutableList().also { it[index % PAGE] = row }
    }

    companion object {
        const val PAGE = 100
    }
}
