package com.daydreamvr.vrcore.ui.widgets

import android.graphics.Canvas
import android.graphics.RectF
import com.daydreamvr.vrcore.ui.AngularMetrics
import com.daydreamvr.vrcore.ui.Theme

/**
 * The alternate poster-grid layout for the browse screen (toggled with R3,
 * ARCHITECTURE.md §8.4). Same row model as [ListView]; laid out in [columns].
 */
class Grid(private val theme: Theme, private val panelWidthPx: Int, private val columns: Int = 3) {

    fun cellForIndex(index: Int): Pair<Int, Int> = (index / columns) to (index % columns)

    fun visibleRows(height: Float, cellHeight: Float): Int = (height / cellHeight).toInt().coerceAtLeast(1)

    fun draw(
        canvas: Canvas,
        rows: List<ListView.Row>,
        focusIndex: Int,
        scrollRow: Int,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
    ) {
        val gap = 18f
        val cellW = (width - gap * (columns - 1)) / columns
        val cellH = cellW * 0.62f + AngularMetrics.textSizePx(1.6f, panelWidthPx, theme.panelWidthDegrees)
        val labelSize = AngularMetrics.textSizePx(1.5f, panelWidthPx, theme.panelWidthDegrees)
        val rowsVisible = visibleRows(height, cellH + gap)

        val labelPaint = theme.textPaint(labelSize, theme.textColor)
        val tileFill = theme.fillPaint(theme.progressTrackColor)
        val focusStroke = theme.strokePaint(theme.focusStrokeColor, 3f)

        for (gr in 0 until rowsVisible) {
            for (c in 0 until columns) {
                val idx = (scrollRow + gr) * columns + c
                if (idx >= rows.size) break
                val row = rows[idx]
                val x = left + c * (cellW + gap)
                val y = top + gr * (cellH + gap)
                val poster = RectF(x, y, x + cellW, y + cellW * 0.62f)
                canvas.drawRoundRect(poster, theme.cornerRadiusPx, theme.cornerRadiusPx, tileFill)
                if (idx == focusIndex) {
                    canvas.drawRoundRect(poster, theme.cornerRadiusPx, theme.cornerRadiusPx, focusStroke)
                }
                canvas.drawText(
                    ellipsize(row.title, cellW, labelPaint),
                    x, y + cellW * 0.62f + labelSize + 6f, labelPaint,
                )
            }
        }
    }

    private fun ellipsize(text: String, maxWidth: Float, paint: android.graphics.Paint): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + "…") > maxWidth) end--
        return text.substring(0, end) + "…"
    }
}
