package com.daydreamvr.vrcore.ui.widgets

import android.graphics.Canvas
import android.graphics.RectF
import com.daydreamvr.vrcore.ui.AngularMetrics
import com.daydreamvr.vrcore.ui.Theme

/**
 * A vertically scrolling list of rows with a focus highlight (ARCHITECTURE.md
 * §11.4). Immediate mode: the caller redraws only when the browse slice changed.
 */
class ListView(private val theme: Theme, private val panelWidthPx: Int) {

    data class Row(
        val title: String,
        val subtitle: String? = null,
        val trailing: String? = null,
        val isContainer: Boolean = false,
        val watchedFraction: Float = 0f,
        val finished: Boolean = false,
    )

    private val progress = ProgressBar(theme)

    /** @return the number of rows that fit in [height]. */
    fun visibleRowCount(height: Float): Int = (height / rowHeightPx()).toInt().coerceAtLeast(1)

    fun rowHeightPx(): Float =
        AngularMetrics.textSizePx(3.2f, panelWidthPx, theme.panelWidthDegrees)

    fun draw(
        canvas: Canvas,
        rows: List<Row>,
        focusIndex: Int,
        scrollTop: Int,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
    ) {
        val rowH = rowHeightPx()
        val titleSize = AngularMetrics.textSizePx(1.8f, panelWidthPx, theme.panelWidthDegrees)
        val subSize = AngularMetrics.textSizePx(1.3f, panelWidthPx, theme.panelWidthDegrees)
        val count = visibleRowCount(height)

        val titlePaint = theme.textPaint(titleSize, theme.textColor)
        val subPaint = theme.textPaint(subSize, theme.dimTextColor)
        val trailPaint = theme.textPaint(subSize, theme.dimTextColor).apply { textAlign = android.graphics.Paint.Align.RIGHT }
        val focusFill = theme.fillPaint(theme.focusFillColor)
        val focusStroke = theme.strokePaint(theme.focusStrokeColor, 3f)

        for (i in 0 until count) {
            val idx = scrollTop + i
            if (idx >= rows.size) break
            val row = rows[idx]
            val rowTop = top + i * rowH
            val rect = RectF(left, rowTop, left + width, rowTop + rowH - 6f)
            if (idx == focusIndex) {
                canvas.drawRoundRect(rect, theme.cornerRadiusPx, theme.cornerRadiusPx, focusFill)
                canvas.drawRoundRect(rect, theme.cornerRadiusPx, theme.cornerRadiusPx, focusStroke)
            }
            val glyph = if (row.isContainer) "▸  " else if (row.finished) "✓  " else "▶  "
            canvas.drawText(glyph + row.title, left + theme.paddingPx, rowTop + titleSize + 8f, titlePaint)
            row.subtitle?.let {
                canvas.drawText(it, left + theme.paddingPx, rowTop + titleSize + subSize + 16f, subPaint)
            }
            row.trailing?.let {
                canvas.drawText(it, left + width - theme.paddingPx, rowTop + titleSize + 8f, trailPaint)
            }
            if (row.watchedFraction > 0f && !row.isContainer) {
                progress.draw(
                    canvas,
                    left + theme.paddingPx,
                    rowTop + rowH - 16f,
                    width - theme.paddingPx * 2,
                    5f,
                    row.watchedFraction,
                    if (row.finished) theme.watchedColor else theme.progressFillColor,
                )
            }
        }

        // scrollbar
        if (rows.size > count) {
            val trackH = height
            val thumbH = trackH * count / rows.size
            val thumbY = top + trackH * scrollTop / rows.size
            canvas.drawRect(
                left + width + 8f, thumbY, left + width + 14f, thumbY + thumbH,
                theme.fillPaint(theme.accentColor),
            )
        }
    }
}
