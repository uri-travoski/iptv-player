package com.worldtv.iptvplayer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.worldtv.iptvplayer.R

/**
 * Three dots, one lit at a time, stepping every [STEP_MS]. A deliberately cheap "busy" sign:
 * a few redraws a second of a tiny view, instead of a 60 fps spinner, so a sync on a slow box
 * keeps the CPU and GPU for the parser. Ticks only while visible and attached.
 */
class LoadingDots @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val on = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.accent) }
    private val off = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.divider) }
    private var lit = 0

    private val tick = object : Runnable {
        override fun run() {
            lit = (lit + 1) % DOTS
            invalidate()
            postDelayed(this, STEP_MS)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val r = height / 2f
        val gap = if (DOTS > 1) (width - 2 * r * DOTS) / (DOTS - 1) else 0f
        for (i in 0 until DOTS) {
            val cx = r + i * (2 * r + gap)
            canvas.drawCircle(cx, r, r, if (i == lit) on else off)
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        removeCallbacks(tick)
        if (isVisible) postDelayed(tick, STEP_MS)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    private companion object {
        const val DOTS = 3
        const val STEP_MS = 350L
    }
}
