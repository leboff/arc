package com.daydreamvr.vrcore.ui.widgets

import android.graphics.Canvas
import android.graphics.Paint
import com.daydreamvr.vrcore.ui.PanelMetrics
import com.daydreamvr.vrcore.ui.Space
import com.daydreamvr.vrcore.ui.Theme
import com.daydreamvr.vrcore.ui.Type
import java.util.Locale

/**
 * The player HUD scrubber: elapsed / −remaining in tabular numerals, a track with
 * buffered + played fill, a knob at the play head, and a preview marker while a
 * trigger scrub is held (ARCHITECTURE.md §10.4, §11.4).
 */
class Timeline(private val theme: Theme, private val metrics: PanelMetrics) {

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
        val labelSize = metrics.px(Type.numeral.degrees)
        val dur = durationMs.coerceAtLeast(1L)
        val barTop = top + labelSize + metrics.px(Space.S)
        val barH = labelSize * 0.5f

        progress.draw(canvas, left, barTop, width, barH, bufferedMs.toFloat() / dur, theme.progressTrack)
        progress.draw(canvas, left, barTop, width, barH, positionMs.toFloat() / dur, theme.accent)

        val shown = previewMs ?: positionMs
        val headX = left + width * (shown.toFloat() / dur).coerceIn(0f, 1f)
        canvas.drawCircle(headX, barTop + barH / 2f, metrics.px(0.35f), theme.fillPaint(theme.accent))

        previewMs?.let {
            val x = left + width * (it.toFloat() / dur).coerceIn(0f, 1f)
            val marker = theme.text(Type.numeral, metrics, theme.accentText).apply { textAlign = Paint.Align.CENTER }
            canvas.drawText(formatMs(it), x, barTop - metrics.px(Space.S), marker)
        }

        val elapsed = theme.text(Type.numeral, metrics)
        val remain = theme.text(Type.numeral, metrics, theme.textSecondary).apply { textAlign = Paint.Align.RIGHT }
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
