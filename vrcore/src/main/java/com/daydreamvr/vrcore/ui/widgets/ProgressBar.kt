package com.daydreamvr.vrcore.ui.widgets

import android.graphics.Canvas
import android.graphics.RectF
import com.daydreamvr.vrcore.ui.Theme

/** A horizontal track with a filled portion — watched-progress and volume. */
class ProgressBar(private val theme: Theme) {

    fun draw(
        canvas: Canvas,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        fraction: Float,
        fillColor: Int = theme.progressFillColor,
    ) {
        val f = fraction.coerceIn(0f, 1f)
        val r = height / 2f
        val track = theme.fillPaint(theme.progressTrackColor)
        canvas.drawRoundRect(RectF(left, top, left + width, top + height), r, r, track)
        if (f > 0f) {
            val fill = theme.fillPaint(fillColor)
            canvas.drawRoundRect(RectF(left, top, left + width * f, top + height), r, r, fill)
        }
    }
}
