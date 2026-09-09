package com.daydreamvr.player.screens

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.daydreamvr.player.state.AppState
import com.daydreamvr.player.state.DiscoveryState
import com.daydreamvr.player.state.GazeTarget
import com.daydreamvr.player.state.Overlay
import com.daydreamvr.player.state.VrScreen
import com.daydreamvr.vrcore.ui.HitMap
import com.daydreamvr.vrcore.ui.HitRegion
import com.daydreamvr.vrcore.ui.Icon
import com.daydreamvr.vrcore.ui.Icons
import com.daydreamvr.vrcore.ui.PanelSurface
import com.daydreamvr.vrcore.ui.Radius
import com.daydreamvr.vrcore.ui.Space
import com.daydreamvr.vrcore.ui.Surfaces
import com.daydreamvr.vrcore.ui.Theme
import com.daydreamvr.vrcore.ui.Type
import com.daydreamvr.vrcore.ui.widgets.ListView

/**
 * The top-most panel: transient toasts, blocking error / confirm dialogs, the
 * projection chooser, the VR keyboard, and an ambient "working…" line
 * (ARCHITECTURE.md §11.4, §11.5).
 */
class OverlayRenderer(panel: PanelSurface, theme: Theme) :
    ScreenPanel(panel, theme, panelWidthM = 2.00f, panelHeightM = 1.30f) {

    private val keyboard = VrKeyboard(theme = theme)
    private val list = ListView(theme, metrics)

    var visible: Boolean = false
        private set

    fun render(state: AppState) {
        val overlay = state.overlay
        val ambient = ambientMessage(state)
        visible = overlay != null || ambient != null
        val key = listOf(overlayKey(overlay), ambient, state.gaze)
        renderIfChanged(key) { canvas ->
            canvas.drawColor(0)
            val regions = ArrayList<HitRegion<GazeTarget>>()
            when (overlay) {
                is Overlay.Keyboard -> {
                    canvas.drawColor(theme.scrim)
                    canvas.panelBackground(theme, metrics)
                    val boxes = keyboard.draw(canvas, metrics, overlay.kb, state.settings.subnetPrefix)
                    boxes.forEach { b ->
                        regions += HitRegion(b.left, b.top, b.right, b.bottom, GazeTarget.KeyboardKey(b.row, b.col))
                    }
                }
                is Overlay.Error ->
                    drawDialog(canvas, overlay.title, overlay.message, if (overlay.canRetry) listOf("Retry", "Dismiss") else listOf("OK"), 0, state.gaze, regions, error = true)
                is Overlay.Confirm ->
                    drawDialog(canvas, overlay.title, null, overlay.options, overlay.focusIndex, state.gaze, regions, error = false)
                is Overlay.ProjectionChooser -> drawProjectionChooser(canvas, overlay, state.gaze, regions)
                is Overlay.Toast -> drawToast(canvas, overlay.message)
                null -> ambient?.let { drawToast(canvas, it) }
            }
            hitMap = HitMap(regions)
        }
    }

    private fun ambientMessage(state: AppState): String? = when {
        state.screen == VrScreen.SERVER_LIST && state.discovery == DiscoveryState.RUNNING -> "Searching for servers…"
        state.screen == VrScreen.PLAYER && state.playback.isBuffering -> "Buffering…"
        else -> null
    }

    private fun drawToast(canvas: Canvas, message: String) {
        val paint = theme.text(Type.rowTitle, metrics).apply { textAlign = Paint.Align.CENTER }
        val w = paint.measureText(message).coerceAtMost(metrics.widthPx * 0.8f)
        val cx = metrics.widthPx / 2f
        val cy = metrics.heightPx * 0.82f
        val padH = metrics.px(Space.L)
        val box = RectF(cx - w / 2f - padH, cy - metrics.px(1.4f), cx + w / 2f + padH, cy + metrics.px(1.0f))
        Surfaces.card(canvas, box, metrics, theme, Radius.CHIP)
        canvas.drawText(message, cx, cy + metrics.px(0.5f), paint)
    }

    private fun drawDialog(
        canvas: Canvas,
        title: String,
        body: String?,
        options: List<String>,
        focusIndex: Int,
        gaze: GazeTarget?,
        regions: MutableList<HitRegion<GazeTarget>>,
        error: Boolean,
    ) {
        canvas.drawColor(theme.scrim)
        val pad = metrics.px(Space.XL)
        val rect = RectF(pad, pad, metrics.widthPx - pad, metrics.heightPx - pad)
        Surfaces.card(canvas, rect, metrics, theme, Radius.CARD)

        val titlePaint = theme.text(Type.screenTitle, metrics)
        var y = pad * 2 + metrics.px(Type.screenTitle.degrees)
        if (error) {
            Icons.draw(canvas, Icon.WARNING, rect.left + pad + metrics.px(1.0f), y - metrics.px(0.8f), metrics.px(2.0f), theme.text(Type.rowTitle, metrics, theme.warning))
            canvas.drawText(title, rect.left + pad + metrics.px(2.6f), y, titlePaint)
        } else {
            canvas.drawText(title, rect.left + pad, y, titlePaint)
        }
        body?.let {
            y += metrics.px(Type.rowSubtitle.degrees) * 1.7f
            canvas.drawText(it, rect.left + pad, y, theme.text(Type.rowSubtitle, metrics))
        }

        val btnH = metrics.px(2.8f)
        val gap = metrics.px(Space.M)
        val btnTop = rect.bottom - pad - btnH
        val btnW = (rect.width() - pad * 2 - gap * (options.size - 1)) / options.size
        val hover = (gaze as? GazeTarget.DialogButton)?.index
        options.forEachIndexed { i, opt ->
            val left = rect.left + pad + i * (btnW + gap)
            val br = RectF(left, btnTop, left + btnW, btnTop + btnH)
            Surfaces.card(canvas, br, metrics, theme, Radius.CHIP)
            if (i == focusIndex) Surfaces.focus(canvas, br, metrics, theme, Radius.CHIP) else if (i == hover) Surfaces.hover(canvas, br, metrics, theme, Radius.CHIP)
            val p = theme.text(Type.rowTitle, metrics, theme.accentText).apply { textAlign = Paint.Align.CENTER }
            canvas.drawText(opt, br.centerX(), br.centerY() + metrics.px(0.55f), p)
            regions += HitRegion(br.left, br.top, br.right, br.bottom, GazeTarget.DialogButton(i))
        }
    }

    /**
     * A scrollable, selectable list of every [com.daydreamvr.vrcore.render.ProjectionMode]
     * plus a leading "Auto" entry (kanban t_af6bc99f). Replaces the old
     * click-to-cycle HUD/inspector control — confirming a row applies it directly.
     */
    private fun drawProjectionChooser(
        canvas: Canvas,
        overlay: Overlay.ProjectionChooser,
        gaze: GazeTarget?,
        regions: MutableList<HitRegion<GazeTarget>>,
    ) {
        canvas.drawColor(theme.scrim)
        val pad = metrics.px(Space.XL)
        val rect = RectF(pad, pad, metrics.widthPx - pad, metrics.heightPx - pad)
        Surfaces.card(canvas, rect, metrics, theme, Radius.CARD)

        val titlePaint = theme.text(Type.screenTitle, metrics)
        val titleY = pad * 2 + metrics.px(Type.screenTitle.degrees)
        canvas.drawText("Choose Projection", rect.left + pad, titleY, titlePaint)

        val contentTop = titleY + metrics.px(Space.M)
        val bodyLeft = rect.left + pad
        val bodyWidth = rect.width() - pad * 2
        val bodyHeight = rect.bottom - pad - contentTop

        val options = Overlay.ProjectionChooser.OPTIONS
        val entries = options.map { mode ->
            ListView.Entry.Item(
                title = Overlay.ProjectionChooser.labelFor(mode),
                trailing = if (mode == overlay.current) "Current" else null,
            )
        }
        val visibleRows = list.visibleRowCount(bodyHeight, twoLine = false)
        val scrollTop = scrollWindowFor(overlay.focusIndex, options.size, visibleRows)
        val layout = list.measureLayout(entries, contentTop, bodyHeight, bodyLeft, bodyWidth, scrollTop)
        val hover = (gaze as? GazeTarget.ProjectionOption)?.index
        list.draw(canvas, entries, layout, overlay.focusIndex, hover, scrollTop)

        regions += layout.boxes.map { box ->
            HitRegion(box.left, box.top, box.right, box.bottom, GazeTarget.ProjectionOption(box.index))
        }
    }

    /** Keeps [focus] inside a [window]-row scroll, mirroring [com.daydreamvr.player.state.AppStateMachine.clampScroll]. */
    private fun scrollWindowFor(focus: Int, total: Int, window: Int): Int {
        if (total <= window) return 0
        val st = focus.coerceIn(0, maxOf(0, total - window))
        return if (focus >= st + window) focus - window + 1 else st
    }

    private fun overlayKey(overlay: Overlay?): Any? = when (overlay) {
        null -> "none"
        is Overlay.Keyboard -> listOf("kb", overlay.purpose, overlay.kb)
        is Overlay.Error -> listOf("err", overlay.title, overlay.message, overlay.canRetry)
        is Overlay.Confirm -> listOf("confirm", overlay.title, overlay.options, overlay.focusIndex, overlay.tag)
        is Overlay.ProjectionChooser -> listOf("projection", overlay.current, overlay.returnTo, overlay.targetKey, overlay.focusIndex)
        is Overlay.Toast -> listOf("toast", overlay.message)
    }
}
