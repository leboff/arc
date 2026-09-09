package com.daydreamvr.player.screens

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.daydreamvr.player.state.AppState
import com.daydreamvr.player.state.DiscoveryState
import com.daydreamvr.player.state.GazeTarget
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
import com.daydreamvr.vrcore.ui.widgets.ListView

/**
 * Discovered servers in a scrolling list with the two actions ("Add manually",
 * "Retry discovery") in a **pinned footer** — so the reported subtitle/row
 * collision is structurally impossible, not merely padded around
 * (UI_GAZE_PLAN.md §5.2).
 */
class ServerListScreen(panel: PanelSurface, theme: Theme) :
    ScreenPanel(panel, theme, panelWidthM = 2.20f, panelHeightM = 1.45f) {

    private val list = ListView(theme, metrics)

    /** Footer actions keep absolute focus indices `servers.size` and `servers.size + 1`. */
    private fun footerLabels() = listOf("Add manually" to Icon.PLUS, "Retry discovery" to Icon.REFRESH)

    fun render(state: AppState) {
        if (state.screen != VrScreen.SERVER_LIST) return
        val key = listOf(
            state.servers.map { it.udn },
            state.serverFocusIndex,
            state.serverScrollTop,
            state.discovery,
            state.gaze,
        )
        renderIfChanged(key) { canvas ->
            canvas.panelBackground(theme, metrics)
            val chip = when (state.discovery) {
                DiscoveryState.RUNNING -> Chip("Searching…", accented = true)
                else -> Chip("${state.servers.size} found")
            }
            val contentTop = drawHeader(canvas, "Media servers", chip)

            val left = contentLeft()
            val width = metrics.widthPx - left * 2
            val footerH = metrics.px(3.4f)
            val footerTop = bodyBottom() - footerH
            val listHeight = footerTop - metrics.px(Space.M) - contentTop

            val regions = ArrayList<HitRegion<GazeTarget>>()

            if (state.servers.isEmpty() && state.discovery != DiscoveryState.RUNNING) {
                drawEmptyState(canvas, contentTop, listHeight, left, width)
            } else {
                val entries = state.servers.map { srv ->
                    ListView.Entry.Item(
                        title = srv.friendlyName,
                        subtitle = listOfNotNull(srv.manufacturer, srv.descriptionUrl.host).joinToString(" · "),
                        icon = Icon.SERVER,
                    )
                }
                val layout = list.measureLayout(entries, contentTop, listHeight, left, width, state.serverScrollTop)
                val hover = (state.gaze as? GazeTarget.ServerRow)?.index
                list.draw(canvas, entries, layout, state.serverFocusIndex, hover, state.serverScrollTop)
                layout.boxes.forEach { box ->
                    regions += HitRegion(box.left, box.top, box.right, box.bottom, GazeTarget.ServerRow(box.index))
                }
            }

            drawFooter(canvas, state, footerTop, footerH, left, width, regions)
            hitMap = HitMap(regions)
        }
    }

    private fun drawEmptyState(canvas: Canvas, top: Float, height: Float, left: Float, width: Float) {
        val cx = left + width / 2f
        val cy = top + height / 2f
        Icons.draw(canvas, Icon.SEARCH, cx, cy - metrics.px(1.6f), metrics.px(2.6f), theme.text(Type.meta, metrics))
        val title = theme.text(Type.rowTitle, metrics).apply { textAlign = Paint.Align.CENTER }
        val meta = theme.text(Type.meta, metrics).apply { textAlign = Paint.Align.CENTER }
        canvas.drawText("No servers found", cx, cy + metrics.px(0.8f), title)
        canvas.drawText("Check the phone is on the same Wi-Fi as your server.", cx, cy + metrics.px(2.6f), meta)
    }

    private fun drawFooter(
        canvas: Canvas,
        state: AppState,
        top: Float,
        height: Float,
        left: Float,
        width: Float,
        regions: MutableList<HitRegion<GazeTarget>>,
    ) {
        val gap = metrics.px(Space.M)
        val btnW = (width - gap) / 2f
        footerLabels().forEachIndexed { i, (label, icon) ->
            val absIndex = state.servers.size + i
            val x = left + i * (btnW + gap)
            val rect = RectF(x, top, x + btnW, top + height)
            Surfaces.card(canvas, rect, metrics, theme)
            val focused = state.serverFocusIndex == absIndex
            val hovered = (state.gaze as? GazeTarget.ServerRow)?.index == absIndex
            if (focused) Surfaces.focus(canvas, rect, metrics, theme) else if (hovered) Surfaces.hover(canvas, rect, metrics, theme)
            val paint = theme.text(Type.rowTitle, metrics, theme.accentText).apply { textAlign = Paint.Align.CENTER }
            val iconSize = metrics.px(1.7f)
            Icons.draw(canvas, icon, rect.centerX() - paint.measureText(label) / 2f - iconSize, rect.centerY() - metrics.px(0.5f), iconSize, paint)
            canvas.drawText(label, rect.centerX() + iconSize / 2f, rect.centerY() + metrics.px(0.55f), paint)
            regions += HitRegion(rect.left, rect.top, rect.right, rect.bottom, GazeTarget.ServerRow(absIndex))
        }
    }
}
