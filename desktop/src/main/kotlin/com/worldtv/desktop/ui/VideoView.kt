package com.worldtv.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.worldtv.desktop.player.VlcPlayer
import kotlin.math.roundToInt

/** Picture shapes (Settings, and the Aspect button): as on Android, plus fixed 16:9 / 4:3. */
object Aspect {
    const val FIT = 0
    const val FILL = 1
    const val STRETCH = 2
    const val WIDE = 3
    const val STANDARD = 4
    val LABELS = listOf("Fit", "Fill (crop)", "Stretch", "16:9", "4:3")
}

/** The player's picture on black, shaped by [aspect], with a spinner while it buffers and the error if it fails. */
@Composable
fun VideoView(player: VlcPlayer, aspect: Int, modifier: Modifier = Modifier, showStatus: Boolean = true) {
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val frame = player.frame
        if (frame != null) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                if (w <= 0 || h <= 0) return@Canvas
                val source = when (aspect) {
                    Aspect.WIDE -> 16f / 9f
                    Aspect.STANDARD -> 4f / 3f
                    else -> player.frameAspect
                }
                val box = w / h
                val (dw, dh) = when (aspect) {
                    Aspect.STRETCH -> w to h
                    Aspect.FILL -> if (source > box) h * source to h else w to w / source
                    else -> if (source > box) w to w / source else h * source to h
                }
                drawImage(
                    frame,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(frame.width, frame.height),
                    dstOffset = IntOffset(((w - dw) / 2).roundToInt(), ((h - dh) / 2).roundToInt()),
                    dstSize = IntSize(dw.roundToInt(), dh.roundToInt()),
                    filterQuality = FilterQuality.Low,
                )
            }
        }
        if (showStatus) {
            when (player.state) {
                VlcPlayer.State.OPENING, VlcPlayer.State.BUFFERING ->
                    CircularProgressIndicator(Modifier.size(48.dp), color = Color.White.copy(alpha = 0.85f), strokeWidth = 4.dp)
                VlcPlayer.State.ERROR -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        player.error ?: "Couldn't play this stream",
                        color = Color.White,
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                else -> {}
            }
        }
    }
}
