package com.daydreamvr.player.screens

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.daydreamvr.player.state.AppState
import com.daydreamvr.player.state.BrowseFrame
import com.daydreamvr.player.state.GazeTarget
import com.daydreamvr.player.state.VrScreen
import com.daydreamvr.upnp.model.DidlContainer
import com.daydreamvr.upnp.model.DidlItem
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
import com.daydreamvr.vrcore.ui.widgets.Breadcrumb
import com.daydreamvr.vrcore.ui.widgets.ListView
import com.daydreamvr.vrcore.ui.widgets.Timeline

/** Breadcrumb + single-line folder list + a focused-item detail card (§5.3). */
class BrowseScreen(panel: PanelSurface, theme: Theme) :
    ScreenPanel(panel, theme, panelWidthM = 2.40f, panelHeightM = 1.50f) {

    private val breadcrumb = Breadcrumb(theme, metrics)
    private val list = ListView(theme, metrics)

    fun visibleRows(): Int {
        val contentTop = metrics.px(Space.L) + metrics.px(Type.rowTitle.degrees) * 1.1f + metrics.px(Space.M)
        return list.visibleRowCount(bodyBottom() - contentTop, twoLine = false)
    }

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
            state.gaze,
        )
        renderIfChanged(key) { canvas ->
            canvas.panelBackground(theme, metrics)
            val chip = if (frame.loading) Chip("Loading…", accented = true) else null
            drawHeader(canvas, state.browse.stack.lastOrNull()?.title ?: "Browse", chip)
            val listTop = breadcrumb.draw(canvas, state.browse.stack.map { it.title })

            val left = contentLeft()
            val totalWidth = metrics.widthPx - left * 2
            val bodyWidth = totalWidth * 0.60f
            val bodyHeight = bodyBottom() - listTop

            val entries = frame.rows.map { obj ->
                when (obj) {
                    is DidlContainer -> ListView.Entry.Item(
                        obj.title,
                        icon = Icon.FOLDER,
                        trailing = obj.childCount?.let { "$it" },
                    )
                    is DidlItem -> ListView.Entry.Item(
                        title = obj.title,
                        icon = Icon.VIDEO,
                        trailing = obj.durationMs?.let { Timeline.formatMs(it) },
                    )
                    else -> ListView.Entry.Item(obj.title)
                }
            }
            val layout = list.measureLayout(entries, listTop, bodyHeight, left, bodyWidth, frame.scrollTop)
            val hover = (state.gaze as? GazeTarget.BrowseRow)?.index
            list.draw(canvas, entries, layout, frame.focusIndex, hover, frame.scrollTop)

            drawDetailCard(canvas, frame, left + bodyWidth + metrics.px(Space.L), listTop, totalWidth - bodyWidth - metrics.px(Space.L), bodyHeight)

            frame.error?.let { drawErrorBar(canvas, it) }

            hitMap = HitMap(
                layout.boxes.map { box ->
                    HitRegion(box.left, box.top, box.right, box.bottom, GazeTarget.BrowseRow(box.index))
                },
            )
        }
    }

    private fun drawErrorBar(canvas: Canvas, message: String) {
        val h = metrics.px(2.4f)
        val rect = RectF(0f, metrics.heightPx - h, metrics.widthPx.toFloat(), metrics.heightPx.toFloat())
        canvas.drawRect(rect, theme.fillPaint((theme.error and 0x00FFFFFF) or (0x1A shl 24)))
        val paint = theme.text(Type.rowSubtitle, metrics, theme.error)
        val iconSize = metrics.px(1.6f)
        Icons.draw(canvas, Icon.WARNING, contentLeft() + iconSize / 2f, rect.centerY(), iconSize, paint)
        canvas.drawText(message, contentLeft() + iconSize + metrics.px(Space.S), rect.centerY() + metrics.px(0.55f), paint)
    }

    private fun drawDetailCard(canvas: Canvas, frame: BrowseFrame, left: Float, top: Float, width: Float, height: Float) {
        val row = frame.focusedRow ?: return
        val rect = RectF(left, top, left + width, top + height)
        Surfaces.card(canvas, rect, metrics, theme, Radius.CARD)

        val pad = metrics.px(Space.L)
        val titlePaint = theme.text(Type.rowTitle, metrics)
        var y = top + pad + metrics.px(Type.rowTitle.degrees) * 0.82f
        canvas.drawText(row.title, left + pad, y, titlePaint)
        y += metrics.px(Type.rowTitle.degrees) * 0.6f + metrics.px(Space.M)

        val pairs: List<Pair<String, String>> = when (row) {
            is DidlItem -> listOfNotNull(
                row.resolution?.let { "Resolution" to "${it.width}×${it.height}" },
                row.durationMs?.let { "Duration" to Timeline.formatMs(it) },
                row.sizeBytes?.let { "Size" to "${it / (1024 * 1024)} MB" },
                row.mimeType?.let { "Type" to it },
            )
            is DidlContainer -> listOfNotNull(row.childCount?.let { "Items" to "$it" })
            else -> emptyList()
        }
        val labelPaint = theme.text(Type.meta, metrics)
        val valuePaint = theme.text(Type.meta, metrics, theme.textSecondary).apply { textAlign = Paint.Align.RIGHT }
        val lineH = metrics.px(Type.meta.degrees) + metrics.px(Space.M)
        for ((label, value) in pairs) {
            if (y > top + height - pad) break
            canvas.drawText(label, left + pad, y, labelPaint)
            canvas.drawText(value, left + width - pad, y, valuePaint)
            y += lineH
        }
    }
}
