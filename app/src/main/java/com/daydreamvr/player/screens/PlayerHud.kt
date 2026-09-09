package com.daydreamvr.player.screens

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.daydreamvr.player.state.AppState
import com.daydreamvr.player.state.HudState
import com.daydreamvr.player.state.VrScreen
import com.daydreamvr.vrcore.ui.AngularMetrics
import com.daydreamvr.vrcore.ui.PanelSurface
import com.daydreamvr.vrcore.ui.Theme
import com.daydreamvr.vrcore.ui.widgets.Timeline
import java.util.Locale

/**
 * The curved HUD that fades in over the cinema screen during playback
 * (ARCHITECTURE.md §11.4): a [Timeline] scrubber, the play/pause state, elapsed /
 * remaining time, and the focusable control row (audio, subtitles, speed,
 * projection, screen size). Sits just below the eye line so it never covers the
 * picture; only redrawn when the playback slice changes.
 */
class PlayerHud(panel: PanelSurface, theme: Theme) :
    ScreenPanel(panel, theme, panelWidthM = 2.8f, panelHeightM = 0.9f) {

    override val verticalOffsetM: Float = -1.15f

    private val timeline = Timeline(theme, panel.widthPx)

    fun render(state: AppState) {
        if (state.screen != VrScreen.PLAYER) return
        val p = state.playback
        val key = listOf(
            p.title,
            p.positionMs / 500,
            p.durationMs / 1000,
            p.bufferedMs / 1000,
            p.previewPositionMs?.div(500),
            p.isPlaying,
            p.isBuffering,
            p.speed,
            p.projection,
            state.hud.focusIndex,
        )
        renderIfChanged(key) { canvas ->
            canvas.panelBackground(theme, panel.widthPx, panel.heightPx)

            val pad = theme.paddingPx
            val titleSize = AngularMetrics.textSizePx(1.7f, panel.widthPx, theme.panelWidthDegrees)
            val glyph = when {
                p.isBuffering -> "…"
                p.isPlaying -> "❚❚"
                else -> "▶"
            }
            canvas.drawText(
                "$glyph  ${p.title}",
                pad,
                pad + titleSize,
                theme.textPaint(titleSize, theme.textColor, bold = true),
            )

            val barTop = pad + titleSize * 1.6f
            timeline.draw(
                canvas,
                left = pad,
                top = barTop,
                width = panel.widthPx - pad * 2,
                positionMs = p.positionMs,
                durationMs = p.durationMs,
                bufferedMs = p.bufferedMs,
                previewMs = p.previewPositionMs,
            )

            drawControls(canvas, state, barTop + AngularMetrics.textSizePx(3.4f, panel.widthPx, theme.panelWidthDegrees))
        }
    }

    private fun drawControls(canvas: Canvas, state: AppState, top: Float) {
        val pad = theme.paddingPx
        val labelSize = AngularMetrics.textSizePx(1.5f, panel.widthPx, theme.panelWidthDegrees)
        val slotW = (panel.widthPx - pad * 2) / HudState.CONTROLS.size
        val paint = theme.textPaint(labelSize, theme.textColor).apply { textAlign = Paint.Align.CENTER }
        val dim = theme.textPaint(labelSize, theme.dimTextColor).apply { textAlign = Paint.Align.CENTER }

        HudState.CONTROLS.forEachIndexed { i, label ->
            val cx = pad + slotW * (i + 0.5f)
            val focused = i == state.hud.focusIndex
            if (focused) {
                val rect = RectF(pad + slotW * i + 6f, top - labelSize, pad + slotW * (i + 1) - 6f, top + labelSize * 0.6f)
                canvas.drawRoundRect(rect, 12f, 12f, theme.fillPaint(theme.focusFillColor))
                canvas.drawRoundRect(rect, 12f, 12f, theme.strokePaint(theme.focusStrokeColor, 3f))
            }
            canvas.drawText(label, cx, top - labelSize * 0.2f, if (focused) paint else dim)
            canvas.drawText(valueFor(label, state), cx, top + labelSize, dim)
        }
    }

    private fun valueFor(label: String, state: AppState): String = when (label) {
        "Speed" -> String.format(Locale.US, "%.2f×", state.playback.speed)
        "Projection" -> state.playback.projection.name.lowercase().replace('_', ' ')
        "Screen size" -> "${state.settings.screenWidthDegrees.toInt()}°"
        else -> ""
    }
}
