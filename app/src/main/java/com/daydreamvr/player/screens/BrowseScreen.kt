package com.daydreamvr.player.screens

import com.daydreamvr.player.state.AppState
import com.daydreamvr.player.state.BrowseFrame
import com.daydreamvr.player.state.VrScreen
import com.daydreamvr.upnp.model.DidlContainer
import com.daydreamvr.upnp.model.DidlItem
import com.daydreamvr.vrcore.ui.AngularMetrics
import com.daydreamvr.vrcore.ui.PanelSurface
import com.daydreamvr.vrcore.ui.Theme
import com.daydreamvr.vrcore.ui.widgets.Breadcrumb
import com.daydreamvr.vrcore.ui.widgets.Grid
import com.daydreamvr.vrcore.ui.widgets.ListView
import com.daydreamvr.vrcore.ui.widgets.Timeline

/** Breadcrumb + scrolling folder list with a focused-item detail card (§11.4). */
class BrowseScreen(panel: PanelSurface, theme: Theme) : ScreenPanel(panel, theme, panelWidthM = 2.6f, panelHeightM = 1.5f) {

    private val breadcrumb = Breadcrumb(theme, panel.widthPx)
    private val list = ListView(theme, panel.widthPx)
    private val grid = Grid(theme, panel.widthPx)

    var gridMode: Boolean = false

    fun render(state: AppState) {
        if (state.screen != VrScreen.BROWSE) return
        val frame = state.browse.top ?: return
        val key = listOf(
            state.browse.stack.map { it.objectId },
            frame.rows.map { it.id },
            frame.focusIndex,
            frame.scrollTop,
            frame.loading,
            frame.error,
            gridMode,
        )
        renderIfChanged(key) { canvas ->
            canvas.panelBackground(theme, panel.widthPx, panel.heightPx)
            val listTop = breadcrumb.draw(canvas, state.browse.stack.map { it.title })

            val bodyLeft = theme.paddingPx
            val bodyWidth = (panel.widthPx - theme.paddingPx * 2) * 0.62f
            val bodyHeight = panel.heightPx - listTop - theme.paddingPx

            val rows = frame.rows.map { obj ->
                when (obj) {
                    is DidlContainer -> ListView.Row(obj.title, obj.childCount?.let { "$it items" }, isContainer = true)
                    is DidlItem -> ListView.Row(
                        title = obj.title,
                        subtitle = obj.resolution?.let { "${it.width}×${it.height}" },
                        trailing = obj.durationMs?.let { Timeline.formatMs(it) },
                    )
                    else -> ListView.Row(obj.title)
                }
            }

            if (gridMode) {
                grid.draw(canvas, rows, frame.focusIndex, frame.scrollTop / 3, bodyLeft, listTop, bodyWidth, bodyHeight)
            } else {
                list.draw(canvas, rows, frame.focusIndex, frame.scrollTop, bodyLeft, listTop, bodyWidth, bodyHeight)
            }

            drawDetailCard(canvas, frame, bodyLeft + bodyWidth + theme.paddingPx, listTop, bodyHeight)

            frame.error?.let {
                val s = AngularMetrics.textSizePx(1.4f, panel.widthPx, theme.panelWidthDegrees)
                canvas.drawText(it, theme.paddingPx, panel.heightPx - theme.paddingPx, theme.textPaint(s, theme.errorColor))
            }
            if (frame.loading) {
                val s = AngularMetrics.textSizePx(1.4f, panel.widthPx, theme.panelWidthDegrees)
                canvas.drawText("Loading…", panel.widthPx - theme.paddingPx - 140f, theme.paddingPx + s, theme.textPaint(s, theme.accentColor))
            }
        }
    }

    private fun drawDetailCard(canvas: android.graphics.Canvas, frame: BrowseFrame, left: Float, top: Float, height: Float) {
        val row = frame.focusedRow ?: return
        val titleSize = AngularMetrics.textSizePx(1.7f, panel.widthPx, theme.panelWidthDegrees)
        val lineSize = AngularMetrics.textSizePx(1.4f, panel.widthPx, theme.panelWidthDegrees)
        var y = top + titleSize
        canvas.drawText(row.title, left, y, theme.textPaint(titleSize, theme.textColor, bold = true))
        y += titleSize
        val lines = when (row) {
            is DidlItem -> listOfNotNull(
                row.resolution?.let { "Resolution  ${it.width}×${it.height}" },
                row.durationMs?.let { "Duration    ${Timeline.formatMs(it)}" },
                row.sizeBytes?.let { "Size        ${it / (1024 * 1024)} MB" },
                row.mimeType?.let { "Type        $it" },
            )
            is DidlContainer -> listOfNotNull(row.childCount?.let { "$it items" })
            else -> emptyList()
        }
        lines.forEach {
            y += lineSize + 8f
            if (y < top + height) canvas.drawText(it, left, y, theme.textPaint(lineSize, theme.dimTextColor))
        }
    }
}
