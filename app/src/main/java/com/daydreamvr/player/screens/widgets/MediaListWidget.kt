package com.daydreamvr.player.screens.widgets

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.daydreamvr.player.state.BrowseFocus
import com.daydreamvr.player.state.GazeTarget
import com.daydreamvr.vrcore.render.ProjectionMode
import com.daydreamvr.vrcore.ui.HitRegion
import com.daydreamvr.vrcore.ui.Icon
import com.daydreamvr.vrcore.ui.Icons
import com.daydreamvr.vrcore.ui.PanelMetrics
import com.daydreamvr.vrcore.ui.Radius
import com.daydreamvr.vrcore.ui.Space
import com.daydreamvr.vrcore.ui.Surfaces
import com.daydreamvr.vrcore.ui.TextMeasure
import com.daydreamvr.vrcore.ui.Theme
import com.daydreamvr.vrcore.ui.Type

/**
 * Centre column of the browse panel in compact textual list mode (kanban t_4c330258):
 * a 1-column vertical list of 6 items per page.
 *
 * Each row's whole bounding box is published as an absolutely-indexed hit region
 * with [GazeTarget.GridCell], completely preserving focus, selection, and gaze targeting
 * across view mode toggles.
 */
class MediaListWidget(
    private val theme: Theme,
    private val metrics: PanelMetrics,
    private val measure: TextMeasure = TextMeasure.PAINT,
) {

    data class Model(
        val items: List<MediaGridWidget.Model.Card>,
        val page: Int = 0,
        val pageCount: Int = 1,
        /** Absolute top index for continuous list mode. */
        val scrollTop: Int = page * PAGE_SIZE,
        val visibleRows: Int = PAGE_SIZE,
    )

    data class RowLayout(
        val slot: Int,
        /** `page * PAGE_SIZE + slot`. */
        val absoluteIndex: Int,
        val box: PixRect,
        val iconBox: PixRect,
        val titleBaseline: Float,
        val metaBaseline: Float,
        /** True when [Model.items] has an entry at [absoluteIndex]. */
        val populated: Boolean,
    )

    data class Layout(
        val listBand: PixRect,
        val rowH: Float,
        val rows: List<RowLayout>,
        val bounds: PixRect,
    )

    /** PURE. */
    fun measureLayout(model: Model, bounds: PixRect): Layout {
        val padH = metrics.px(Space.L)
        val padV = metrics.px(Space.XS)

        val listTop = bounds.top + metrics.px(Bands.HEADER_DEG) + metrics.px(Bands.COMPACT_ROW_DEG)
        val listBottom = bounds.bottom - metrics.px(Bands.COMPACT_ROW_DEG) - metrics.px(Bands.BOTTOM_PAD_DEG)
        val listBand = PixRect(bounds.left, listTop, bounds.right, listBottom)
        val rowCount = model.visibleRows.coerceAtLeast(1)
        val pitch = (listBottom - listTop) / rowCount

        val rowH = pitch - 2 * padV

        val titleSize = metrics.px(Type.compactListTitle.degrees)
        val titleAsc = -measure.ascentPx(titleSize, bold = false)
        val titleLineH = titleSize * Type.SUBTITLE_LINE_HEIGHT

        val metaSize = metrics.px(Type.compactListMeta.degrees)
        val metaAsc = -measure.ascentPx(metaSize, bold = false)

        val rows = ArrayList<RowLayout>(rowCount)
        for (slot in 0 until rowCount) {
            val top = listTop + slot * pitch + padV
            val box = PixRect(bounds.left + padH, top, bounds.right - padH, top + rowH)
            val absoluteIndex = model.scrollTop + slot

            val iconSize = metrics.px(2.4f)
            val iconLeft = box.left + metrics.px(Space.M)
            val iconTop = box.centerY - iconSize / 2f
            val iconBox = PixRect(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)

            val textTop = box.top + metrics.px(Space.S)
            val titleBaseline = textTop + titleAsc
            val metaBaseline = titleBaseline + titleLineH

            rows.add(
                RowLayout(
                    slot = slot,
                    absoluteIndex = absoluteIndex,
                    box = box,
                    iconBox = iconBox,
                    titleBaseline = titleBaseline,
                    metaBaseline = metaBaseline,
                    populated = absoluteIndex < model.items.size,
                )
            )
        }
        return Layout(listBand, rowH, rows, bounds)
    }

    fun hitRegions(layout: Layout): List<HitRegion<GazeTarget>> =
        layout.rows
            .filter { it.populated }
            .map { r ->
                HitRegion(r.box.left, r.box.top, r.box.right, r.box.bottom, GazeTarget.GridCell(r.absoluteIndex))
            }

    fun draw(
        canvas: Canvas,
        model: Model,
        layout: Layout,
        focus: BrowseFocus?,
        hover: GazeTarget?,
    ) {
        val focusedIndex = (focus as? BrowseFocus.Grid)?.index
        val hoverIndex = (hover as? GazeTarget.GridCell)?.index
        val titlePaint = theme.text(Type.compactListTitle, metrics)
        val metaPaint = theme.text(Type.compactListMeta, metrics)
        val durationPaint = theme.text(Type.compactListDuration, metrics, theme.textPrimary).apply { textAlign = Paint.Align.RIGHT }
        val iconPaint = theme.text(Type.compactListMeta, metrics)

        for (row in layout.rows) {
            if (!row.populated) continue
            val item = model.items.getOrNull(row.absoluteIndex) ?: continue

            val boxRect = row.box.toRectF()
            when (row.absoluteIndex) {
                focusedIndex -> Surfaces.focus(canvas, boxRect, metrics, theme, Radius.CARD)
                hoverIndex -> Surfaces.hover(canvas, boxRect, metrics, theme, Radius.CARD)
                else -> Surfaces.card(canvas, boxRect, metrics, theme)
            }

            val icon = if (item.finished) Icon.CHECK else Icon.VIDEO
            Icons.draw(canvas, icon, row.iconBox.centerX, row.iconBox.centerY, row.iconBox.width, iconPaint)

            var badgeRight = row.box.right - metrics.px(Space.M)
            val gapS = metrics.px(Space.S)

            item.durationLabel?.let {
                canvas.drawText(it, badgeRight, row.box.centerY + metrics.px(Type.numeral.degrees) * 0.35f, durationPaint)
                val durW = measure.width(it, durationPaint.textSize, durationPaint.typeface?.isBold == true)
                badgeRight -= (durW + gapS * 2)
            }

            item.qualityLabel?.let {
                val qW = badgeWidth(it)
                drawBadge(canvas, it, badgeRight - qW, row.box.centerY - metrics.px(Type.chip.degrees) / 2f - gapS, theme.badgeQuality)
                badgeRight -= (qW + gapS)
            }

            projectionBadge(item.projection)?.let {
                val pW = badgeWidth(it)
                drawBadge(canvas, it, badgeRight - pW, row.box.centerY - metrics.px(Type.chip.degrees) / 2f - gapS, theme.badgeVr)
                badgeRight -= (pW + gapS)
            }

            val textLeft = row.iconBox.right + metrics.px(Space.M)
            val textMax = (badgeRight - textLeft - gapS).coerceAtLeast(0f)

            canvas.drawText(ellipsize(item.title, textMax, titlePaint), textLeft, row.titleBaseline, titlePaint)
            item.meta?.let {
                canvas.drawText(ellipsize(it, textMax, metaPaint), textLeft, row.metaBaseline, metaPaint)
            }

            if (item.watchedFraction > 0f) {
                val barH = metrics.px(0.12f)
                val barTop = row.box.bottom - barH
                canvas.drawRect(
                    row.box.left, barTop,
                    row.box.left + row.box.width * item.watchedFraction.coerceIn(0f, 1f), row.box.bottom,
                    theme.fillPaint(if (item.finished) theme.watched else theme.accent),
                )
            }
        }
    }

    private fun badgeWidth(text: String): Float =
        measure.width(text, metrics.px(Type.compactListBadge.degrees), bold = false) + metrics.px(Space.S) * 2

    private fun drawBadge(canvas: Canvas, text: String, x: Float, y: Float, stroke: Int) {
        val paint = theme.text(Type.compactListBadge, metrics).apply { textAlign = Paint.Align.LEFT }
        val w = badgeWidth(text)
        val h = metrics.px(Type.compactListBadge.degrees) + metrics.px(Space.XS) * 2
        val rect = RectF(x, y, x + w, y + h)
        Surfaces.chip(canvas, rect, metrics, theme, accented = false)
        canvas.drawRoundRect(rect, metrics.px(Radius.CHIP), metrics.px(Radius.CHIP), theme.strokePaint(stroke, 1.5f))
        canvas.drawText(text, x + metrics.px(Space.S), y + h - metrics.px(Space.XS) * 2, paint)
    }

    private fun projectionBadge(mode: ProjectionMode): String? =
        if (mode == ProjectionMode.FLAT) null else mode.label

    private fun ellipsize(text: String, maxWidth: Float, paint: Paint): String {
        if (maxWidth <= 0f) return ""
        if (measure.width(text, paint.textSize, paint.typeface?.isBold == true) <= maxWidth) return text
        var s = text
        while (s.isNotEmpty() && measure.width("$s…", paint.textSize, paint.typeface?.isBold == true) > maxWidth) {
            s = s.dropLast(1)
        }
        return "$s…"
    }

    companion object {
        const val PAGE_SIZE = 6
    }
}
