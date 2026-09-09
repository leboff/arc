package com.daydreamvr.player.screens

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.daydreamvr.player.state.AppState
import com.daydreamvr.player.state.GazeTarget
import com.daydreamvr.player.state.HudState
import com.daydreamvr.player.state.VrScreen
import com.daydreamvr.vrcore.ui.HitMap
import com.daydreamvr.vrcore.ui.HitRegion
import com.daydreamvr.vrcore.ui.Icon
import com.daydreamvr.vrcore.ui.Icons
import com.daydreamvr.vrcore.ui.PanelSurface
import com.daydreamvr.vrcore.ui.Space
import com.daydreamvr.vrcore.ui.Surfaces
import com.daydreamvr.vrcore.ui.Theme
import com.daydreamvr.vrcore.ui.Type
import com.daydreamvr.vrcore.ui.widgets.Timeline
import java.util.Locale

/**
 * The curved HUD that fades in over the cinema screen (ARCHITECTURE.md §11.4):
 * play state + title, a [Timeline] scrubber, and a row of control chips. Narrowed
 * to 2.4 m × 0.62 m so it sits inside the comfort box (UI_GAZE_PLAN.md §5.5).
 */
class PlayerHud(panel: PanelSurface, theme: Theme) :
    ScreenPanel(panel, theme, panelWidthM = 2.40f, panelHeightM = 0.62f) {

    override val verticalOffsetM: Float = -1.15f

    private val timeline = Timeline(theme, metrics)

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
            state.gaze,
        )
        renderIfChanged(key) { canvas ->
            canvas.panelBackground(theme, metrics)
            val pad = contentLeft()
            val titleSize = metrics.px(Type.rowTitle.degrees)
            val icon = when {
                p.isBuffering -> Icon.SPINNER
                p.isPlaying -> Icon.PAUSE
                else -> Icon.PLAY
            }
            val titlePaint = theme.text(Type.rowTitle, metrics)
            val iconSize = metrics.px(1.6f)
            Icons.draw(canvas, icon, pad + iconSize / 2f, pad + titleSize * 0.5f, iconSize, titlePaint)
            canvas.drawText(p.title, pad + iconSize + metrics.px(Space.S), pad + titleSize * 0.82f, titlePaint)

            if (p.speed != 1.0f) {
                val chip = theme.text(Type.chip, metrics).apply { textAlign = Paint.Align.RIGHT }
                canvas.drawText(String.format(Locale.US, "%.2f×", p.speed), metrics.widthPx - pad, pad + titleSize * 0.82f, chip)
            }

            val barTop = pad + titleSize * 1.4f
            timeline.draw(
                canvas,
                left = pad,
                top = barTop,
                width = metrics.widthPx - pad * 2,
                positionMs = p.positionMs,
                durationMs = p.durationMs,
                bufferedMs = p.bufferedMs,
                previewMs = p.previewPositionMs,
            )

            drawControls(canvas, state, barTop + metrics.px(3.0f))
        }
    }

    private fun drawControls(canvas: Canvas, state: AppState, top: Float) {
        val pad = contentLeft()
        val gap = metrics.px(Space.S)
        val slotW = (metrics.widthPx - pad * 2 - gap * (HudState.CONTROLS.size - 1)) / HudState.CONTROLS.size
        val chipH = metrics.px(2.2f)
        val labelPaint = theme.text(Type.chip, metrics).apply { textAlign = Paint.Align.CENTER }
        val valuePaint = theme.text(Type.meta, metrics).apply { textAlign = Paint.Align.CENTER }

        val regions = ArrayList<HitRegion<GazeTarget>>()
        HudState.CONTROLS.forEachIndexed { i, label ->
            val x = pad + i * (slotW + gap)
            val rect = RectF(x, top, x + slotW, top + chipH)
            Surfaces.chip(canvas, rect, metrics, theme, accented = false)
            val focused = i == state.hud.focusIndex
            val hovered = (state.gaze as? GazeTarget.HudControl)?.index == i
            if (focused) Surfaces.focus(canvas, rect, metrics, theme) else if (hovered) Surfaces.hover(canvas, rect, metrics, theme)
            canvas.drawText(label, rect.centerX(), rect.centerY(), labelPaint)
            valueFor(label, state)?.let { canvas.drawText(it, rect.centerX(), rect.bottom + metrics.px(Type.meta.degrees), valuePaint) }
            regions += HitRegion(rect.left, rect.top, rect.right, rect.bottom, GazeTarget.HudControl(i))
        }
        hitMap = HitMap(regions)
    }

    private fun valueFor(label: String, state: AppState): String? = when (label) {
        "Speed" -> String.format(Locale.US, "%.2f×", state.playback.speed)
        "Projection" -> state.playback.projection.name.lowercase().replace('_', ' ')
        "Screen size" -> "${state.settings.screenWidthDegrees.toInt()}°"
        else -> null
    }
}
