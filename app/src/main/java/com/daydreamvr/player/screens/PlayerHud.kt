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

    override val verticalOffsetM: Float = -0.85f

    private val timeline = Timeline(theme, metrics)
    private var timelineLeft = 0f
    private var timelineWidth = 0f
    private var timelineTop = 0f
    private var timelineHeight = 0f

    override fun hitTest(xPx: Float, yPx: Float): GazeTarget? {
        if (xPx >= timelineLeft && xPx <= timelineLeft + timelineWidth &&
            yPx >= timelineTop && yPx <= timelineTop + timelineHeight) {
            val fraction = ((xPx - timelineLeft) / timelineWidth).coerceIn(0f, 1f)
            return GazeTarget.HudTimeline(fraction)
        }
        return super.hitTest(xPx, yPx)
    }

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

            val regions = ArrayList<HitRegion<GazeTarget>>()

            // Timeline seek hit-strip: generous vertical target across duration bar
            val barW = metrics.widthPx - pad * 2
            val barHitH = metrics.px(3.0f)
            val barHitTop = (barTop - metrics.px(0.8f)).coerceAtLeast(0f)
            timelineLeft = pad
            timelineWidth = barW
            timelineTop = barHitTop
            timelineHeight = barHitH
            // One continuous target. The reducer receives the exact pointer fraction;
            // there is deliberately no timeline binning.
            regions += HitRegion(pad - metrics.px(0.35f), barHitTop, pad + barW + metrics.px(0.35f), barHitTop + barHitH,
                GazeTarget.HudTimeline(0f))

            drawControls(canvas, state, barTop + metrics.px(3.0f), regions)
        }
    }

    private fun drawControls(
        canvas: Canvas,
        state: AppState,
        top: Float,
        regions: ArrayList<HitRegion<GazeTarget>>,
    ) {
        val pad = contentLeft()
        val gap = metrics.px(Space.S)
        val primary = listOf("Previous", "Play/Pause", "Next")
        val utility = listOf("Back", "Projection", "Speed", "Screen size")
        val primaryW = (metrics.widthPx - pad * 2 - gap * 2) / 3f
        val utilityW = (metrics.widthPx - pad * 2 - gap * 3) / 4f
        val chipH = metrics.px(3.0f)
        val labelPaint = theme.text(Type.chip, metrics).apply { textAlign = Paint.Align.CENTER }
        val valuePaint = theme.text(Type.meta, metrics).apply { textAlign = Paint.Align.CENTER }

        fun drawRow(labels: List<String>, y: Float, slotW: Float, startIndex: Int) {
        labels.forEachIndexed { offset, label ->
            val i = startIndex + offset
            val x = pad + offset * (slotW + gap)
            val rect = RectF(x, y, x + slotW, y + chipH)
            Surfaces.chip(canvas, rect, metrics, theme, accented = false)
            val focused = i == state.hud.focusIndex
            val hovered = (state.gaze as? GazeTarget.HudControl)?.index == i
            if (focused) Surfaces.focus(canvas, rect, metrics, theme) else if (hovered) Surfaces.hover(canvas, rect, metrics, theme)
            val visible = if (label == "Play/Pause") if (state.playback.isPlaying) "Pause" else "Play" else label
            canvas.drawText(visible, rect.centerX(), rect.centerY(), labelPaint)
            valueFor(label, state)?.let { canvas.drawText(it, rect.centerX(), rect.bottom + metrics.px(Type.meta.degrees), valuePaint) }
            regions += HitRegion(rect.left, rect.top, rect.right, rect.bottom, GazeTarget.HudControl(i))
        }
        }
        drawRow(primary, top, primaryW, 0)
        drawRow(utility, top + chipH + metrics.px(Space.S), utilityW, 3)
        hitMap = HitMap(regions)
    }

    private fun valueFor(label: String, state: AppState): String? = when (label) {
        "Speed" -> String.format(Locale.US, "%.2f×", state.playback.speed)
        "Projection" -> state.playback.projection.label
        "Screen size" -> "${state.settings.screenWidthDegrees.toInt()}°"
        else -> null
    }
}
