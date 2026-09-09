package com.daydreamvr.player.screens

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.daydreamvr.player.state.AppState
import com.daydreamvr.player.state.DiscoveryState
import com.daydreamvr.player.state.Overlay
import com.daydreamvr.player.state.VrScreen
import com.daydreamvr.vrcore.ui.AngularMetrics
import com.daydreamvr.vrcore.ui.PanelSurface
import com.daydreamvr.vrcore.ui.Theme

/**
 * The top-most panel: transient toasts, blocking error / confirm dialogs, the
 * VR keyboard, and a plain "working…" spinner line during discovery or buffering
 * (ARCHITECTURE.md §11.4, §11.5). Sits closer than the content panels so it reads
 * as an overlay. [visible] tells [com.daydreamvr.player.render.AppScene] whether
 * to draw it at all.
 */
class OverlayRenderer(panel: PanelSurface, theme: Theme) :
    ScreenPanel(panel, theme, panelWidthM = 2.0f, panelHeightM = 1.3f) {

    private val keyboard = VrKeyboard(theme = theme)

    var visible: Boolean = false
        private set

    fun render(state: AppState) {
        val overlay = state.overlay
        val ambient = ambientMessage(state)
        visible = overlay != null || ambient != null
        val key = listOf(overlayKey(overlay), ambient)
        renderIfChanged(key) { canvas ->
            canvas.drawColor(0)
            when (overlay) {
                is Overlay.Keyboard -> keyboard.draw(canvas, panel.widthPx, panel.heightPx, overlay.kb, state.settings.subnetPrefix)
                is Overlay.Error -> drawDialog(canvas, overlay.title, overlay.message, if (overlay.canRetry) listOf("Retry", "Dismiss") else listOf("OK"), 0)
                is Overlay.Confirm -> drawDialog(canvas, overlay.title, null, overlay.options, overlay.focusIndex)
                is Overlay.Toast -> drawToast(canvas, overlay.message)
                null -> ambient?.let { drawToast(canvas, it) }
            }
        }
    }

    private fun ambientMessage(state: AppState): String? = when {
        state.screen == VrScreen.SERVER_LIST && state.discovery == DiscoveryState.RUNNING -> "Searching for servers…"
        state.screen == VrScreen.PLAYER && state.playback.isBuffering -> "Buffering…"
        else -> null
    }

    private fun drawToast(canvas: Canvas, message: String) {
        val size = AngularMetrics.textSizePx(1.8f, panel.widthPx, theme.panelWidthDegrees)
        val paint = theme.textPaint(size, theme.textColor).apply { textAlign = Paint.Align.CENTER }
        val w = paint.measureText(message)
        val cx = panel.widthPx / 2f
        val cy = panel.heightPx * 0.82f
        val box = RectF(cx - w / 2f - theme.paddingPx, cy - size, cx + w / 2f + theme.paddingPx, cy + size * 0.6f)
        canvas.drawRoundRect(box, theme.cornerRadiusPx, theme.cornerRadiusPx, theme.fillPaint(theme.panelColor))
        canvas.drawText(message, cx, cy, paint)
    }

    private fun drawDialog(canvas: Canvas, title: String, body: String?, options: List<String>, focusIndex: Int) {
        val pad = theme.paddingPx
        val titleSize = AngularMetrics.textSizePx(2.1f, panel.widthPx, theme.panelWidthDegrees)
        val bodySize = AngularMetrics.textSizePx(1.6f, panel.widthPx, theme.panelWidthDegrees)

        val panelRect = RectF(pad, pad, panel.widthPx - pad, panel.heightPx - pad)
        canvas.drawRoundRect(panelRect, theme.cornerRadiusPx, theme.cornerRadiusPx, theme.fillPaint(theme.panelColor))
        canvas.drawRoundRect(panelRect, theme.cornerRadiusPx, theme.cornerRadiusPx, theme.strokePaint(theme.panelStrokeColor, 2f))

        var y = pad * 2 + titleSize
        canvas.drawText(title, pad * 2, y, theme.textPaint(titleSize, theme.textColor, bold = true))
        body?.let {
            y += bodySize * 1.6f
            canvas.drawText(it, pad * 2, y, theme.textPaint(bodySize, theme.dimTextColor))
        }

        val btnTop = panel.heightPx - pad * 2 - bodySize * 2.2f
        val btnW = (panel.widthPx - pad * 4) / options.size
        options.forEachIndexed { i, opt ->
            val left = pad * 2 + btnW * i
            val rect = RectF(left + 6f, btnTop, left + btnW - 6f, btnTop + bodySize * 2f)
            val focused = i == focusIndex
            canvas.drawRoundRect(rect, 12f, 12f, theme.fillPaint(if (focused) theme.focusFillColor else theme.progressTrackColor))
            if (focused) canvas.drawRoundRect(rect, 12f, 12f, theme.strokePaint(theme.focusStrokeColor, 3f))
            val p = theme.textPaint(bodySize, theme.textColor).apply { textAlign = Paint.Align.CENTER }
            canvas.drawText(opt, rect.centerX(), rect.centerY() + bodySize / 3f, p)
        }
    }

    private fun overlayKey(overlay: Overlay?): Any? = when (overlay) {
        null -> "none"
        is Overlay.Keyboard -> listOf("kb", overlay.purpose, overlay.kb)
        is Overlay.Error -> listOf("err", overlay.title, overlay.message, overlay.canRetry)
        is Overlay.Confirm -> listOf("confirm", overlay.title, overlay.options, overlay.focusIndex, overlay.tag)
        is Overlay.Toast -> listOf("toast", overlay.message)
    }
}
