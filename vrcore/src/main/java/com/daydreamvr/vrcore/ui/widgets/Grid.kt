package com.daydreamvr.vrcore.ui.widgets

import android.graphics.Canvas
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import com.daydreamvr.vrcore.ui.PanelMetrics
import com.daydreamvr.vrcore.ui.Radius
import com.daydreamvr.vrcore.ui.Space
import com.daydreamvr.vrcore.ui.Surfaces
import com.daydreamvr.vrcore.ui.Theme
import com.daydreamvr.vrcore.ui.Type

/**
 * The alternate poster-grid layout for the browse screen (toggled with R3,
 * ARCHITECTURE.md §8.4). Scroll row and focused cell are derived through the
 * **same** divisor so they can never disagree (UI_GAZE_PLAN.md F13).
 */
class Grid(private val theme: Theme, private val metrics: PanelMetrics, val columns: Int = 3) {

    /** Row that holds [flatIndex]. Use for both scroll and focus. */
    fun rowOf(flatIndex: Int): Int = flatIndex / columns

    fun visibleRows(height: Float, cellHeight: Float): Int = (height / cellHeight).toInt().coerceAtLeast(1)

    fun draw(
        canvas: Canvas,
        rows: List<ListView.Entry.Item>,
        focusIndex: Int,
        scrollTop: Int,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
    ) {
        val gap = metrics.px(Space.M)
        val cellW = (width - gap * (columns - 1)) / columns
        val labelSize = metrics.px(Type.rowSubtitle.degrees)
        val cellH = cellW * 0.62f + labelSize * 1.6f
        val rowsVisible = visibleRows(height, cellH + gap)
        val scrollRow = rowOf(scrollTop)

        val labelPaint = theme.text(Type.rowSubtitle, metrics)
        val r = metrics.px(Radius.CARD)

        for (gr in 0 until rowsVisible) {
            for (c in 0 until columns) {
                val idx = (scrollRow + gr) * columns + c
                if (idx >= rows.size) break
                val x = left + c * (cellW + gap)
                val y = top + gr * (cellH + gap)
                val poster = RectF(x, y, x + cellW, y + cellW * 0.62f)
                Surfaces.card(canvas, poster, metrics, theme)
                if (idx == focusIndex) Surfaces.focus(canvas, poster, metrics, theme)
                canvas.drawText(
                    ellipsize(rows[idx].title, cellW, labelPaint),
                    x, y + cellW * 0.62f + labelSize, labelPaint,
                )
            }
        }
    }

    private fun ellipsize(text: String, maxWidth: Float, paint: android.graphics.Paint): String =
        TextUtils.ellipsize(text, TextPaint(paint), maxWidth, TextUtils.TruncateAt.END).toString()
}
