package com.daydreamvr.vrcore.ui.widgets

import android.graphics.Canvas
import com.daydreamvr.vrcore.ui.AngularMetrics
import com.daydreamvr.vrcore.ui.Theme

/** The path strip at the top of the browse screen ("Gerbera › Video › Films"). */
class Breadcrumb(private val theme: Theme, private val panelWidthPx: Int) {

    fun draw(canvas: Canvas, segments: List<String>, top: Float = theme.paddingPx): Float {
        val sizePx = AngularMetrics.textSizePx(1.8f, panelWidthPx, theme.panelWidthDegrees)
        val paint = theme.textPaint(sizePx, theme.dimTextColor)
        val activePaint = theme.textPaint(sizePx, theme.textColor, bold = true)
        val sepPaint = theme.textPaint(sizePx, theme.dimTextColor)

        var x = theme.paddingPx
        val baseline = top + sizePx
        segments.forEachIndexed { i, seg ->
            if (i > 0) {
                canvas.drawText("  ›  ", x, baseline, sepPaint)
                x += sepPaint.measureText("  ›  ")
            }
            val p = if (i == segments.lastIndex) activePaint else paint
            canvas.drawText(seg, x, baseline, p)
            x += p.measureText(seg)
        }
        return baseline + sizePx * 0.6f
    }
}
