package com.daydreamvr.player.subtitles

import android.graphics.Paint
import android.graphics.PorterDuff
import android.text.TextPaint
import com.daydreamvr.player.screens.ScreenPanel
import com.daydreamvr.vrcore.ui.AngularMetrics
import com.daydreamvr.vrcore.ui.PanelSurface
import com.daydreamvr.vrcore.ui.Theme

/**
 * Renders subtitle cues on a dedicated panel below the cinema screen
 * (ARCHITECTURE.md §11, docs/PLAN.md §6). The panel is transparent apart from the
 * text, is world-locked with the screen (no lazy-follow — subtitles that drift
 * are worse than useless), and is repainted only when the cue set changes.
 *
 * The text never crosses the centre divider: it is centre-anchored on a panel
 * that sits entirely within the stereo-overlap region.
 */
class SubtitleRenderer(
    panel: PanelSurface,
    theme: Theme = Theme(),
) : ScreenPanel(panel, theme, panelWidthM = 3.0f, panelHeightM = 0.9f) {

    /** Subtitles ride below the eye line, a touch further out than the screen. */
    override val verticalOffsetM: Float = -0.95f

    private var lastAngularDeg = 0f

    fun render(cues: List<String>, angularSizeDeg: Float) {
        val clamped = angularSizeDeg.coerceIn(
            AngularMetrics.MIN_TEXT_DEGREES,
            AngularMetrics.COMFORT_V_DEGREES,
        )
        val key = listOf(cues, clamped)
        lastAngularDeg = clamped
        renderIfChanged(key) { canvas ->
            canvas.drawColor(0, PorterDuff.Mode.CLEAR)
            if (cues.isEmpty()) return@renderIfChanged

            val sizePx = metrics.px(clamped)
            val fill = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = theme.textColor
                textSize = sizePx
                textAlign = Paint.Align.CENTER
                isSubpixelText = true
            }
            val outline = TextPaint(fill).apply {
                style = Paint.Style.STROKE
                strokeWidth = sizePx * 0.11f
                color = 0xCC000000.toInt()
            }

            val lineHeight = sizePx * 1.3f
            val block = cues.size * lineHeight
            var y = (panel.heightPx - block) / 2f + sizePx
            val cx = panel.widthPx / 2f
            for (cue in cues) {
                canvas.drawText(cue, cx, y, outline)
                canvas.drawText(cue, cx, y, fill)
                y += lineHeight
            }
        }
    }
}
