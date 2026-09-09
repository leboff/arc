package com.daydreamvr.vrcore.ui.widgets

import android.graphics.Canvas
import android.graphics.Paint
import com.daydreamvr.vrcore.ui.AngularMetrics
import com.daydreamvr.vrcore.ui.Theme
import java.util.Locale

/**
 * The player HUD scrubber: elapsed / remaining, a track with buffered + played
 * fill, and a preview marker while a trigger scrub is held (ARCHITECTURE.md
 * §10.4, §11.4).
 */
class Timeline(private val theme: Theme, private val panelWidthPx: Int) {

    private val progress = ProgressBar(theme)

    fun draw(
        canvas: Canvas,
        left: Float,
        top: Float,
        width: Float,
        positionMs: Long,
        durationMs: Long,
        bufferedMs: Long,
        previewMs: Long?,
    ) {
        val labelSize = AngularMetrics.textSizePx(1.5f, panelWidthPx, theme.panelWidthDegrees)
        val dur = durationMs.coerceAtLeast(1L)
        val barTop = top + labelSize + 12f
        val barH = labelSize * 0.6f

        progress.draw(canvas, left, barTop, width, barH, bufferedMs.toFloat() / dur, theme.progressTrackColor)
        progress.draw(canvas, left, barTop, width, barH, positionMs.toFloat() / dur, theme.progressFillColor)

        previewMs?.let {
            val x = left + width * (it.toFloat() / dur).coerceIn(0f, 1f)
            canvas.drawCircle(x, barTop + barH / 2f, barH, theme.fillPaint(theme.accentColor))
        }

        val elapsed = theme.textPaint(labelSize, theme.textColor)
        val remain = theme.textPaint(labelSize, theme.dimTextColor).apply { textAlign = Paint.Align.RIGHT }
        val shown = previewMs ?: positionMs
        canvas.drawText(formatMs(shown), left, top + labelSize, elapsed)
        canvas.drawText("-" + formatMs((durationMs - shown).coerceAtLeast(0L)), left + width, top + labelSize, remain)
    }

    companion object {
        fun formatMs(ms: Long): String {
            val totalSec = ms / 1000
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            return if (h > 0) {
                String.format(Locale.US, "%d:%02d:%02d", h, m, s)
            } else {
                String.format(Locale.US, "%d:%02d", m, s)
            }
        }
    }
}
