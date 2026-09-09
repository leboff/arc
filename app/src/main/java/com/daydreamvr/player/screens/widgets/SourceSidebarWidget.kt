package com.daydreamvr.player.screens.widgets

import android.graphics.Canvas
import android.graphics.Paint
import com.daydreamvr.player.state.BrowseFocus
import com.daydreamvr.player.state.GazeTarget
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
import kotlin.math.floor

/**
 * Left column of the PLAY'A browse panel (UI_REDESIGN_REVIEWED_PLAN.md §5.1, R15):
 * a "SOURCES" header, a **vertical** 3-row source switcher (a horizontal strip
 * cannot hold "Favourites" at the 1.5° type minimum), a divider, then the folder
 * list for the selected source.
 *
 * `measureLayout` is pure — no `Canvas`, no `Paint`; every size resolves through
 * [metrics] and [measure].
 */
class SourceSidebarWidget(
    private val theme: Theme,
    private val metrics: PanelMetrics,
    private val measure: TextMeasure = TextMeasure.PAINT,
) {

    data class Model(
        val sources: List<Source>,
        val folders: List<Folder>,
        val folderScrollTop: Int = 0,
    ) {
        /** One row of the vertical source switcher. */
        data class Source(val title: String, val icon: Icon, val count: Int? = null)

        /** One folder row for the selected source. [count] is the child video count. */
        data class Folder(val title: String, val count: Int? = null, val icon: Icon = Icon.FOLDER)
    }

    data class SourceBox(val index: Int, val rect: PixRect)

    /** [index] is the absolute folder index (`folderScrollTop + slot`). */
    data class FolderBox(val index: Int, val rect: PixRect)

    data class Layout(
        val header: PixRect,
        val sourceBoxes: List<SourceBox>,
        val dividerY: Float,
        val folderTop: Float,
        val folderRowH: Float,
        val folderBoxes: List<FolderBox>,
        val bounds: PixRect,
        /** Exact integer count of folder rows that fit `[folderTop, bounds.bottom)` (§5.1). */
        val capacity: Int,
    ) {
        /** The number the reducer must use as the sidebar scroll window (feeds `ListWindow.sidebar`). */
        fun visibleRows(): Int = capacity
    }

    /** PURE. */
    fun measureLayout(model: Model, bounds: PixRect): Layout {
        val headerH = metrics.px(Bands.HEADER_DEG)
        val sourceRowH = metrics.px(Bands.COMPACT_ROW_DEG)
        val folderRowH = metrics.px(Bands.STD_ROW_DEG)

        val header = PixRect(bounds.left, bounds.top, bounds.right, bounds.top + headerH)

        val sourceBoxes = ArrayList<SourceBox>(model.sources.size)
        var y = header.bottom
        for (i in model.sources.indices) {
            sourceBoxes.add(SourceBox(i, PixRect(bounds.left, y, bounds.right, y + sourceRowH)))
            y += sourceRowH
        }
        // The switcher always reserves three slots even if fewer sources exist.
        val dividerY = header.bottom + 3 * sourceRowH
        val folderTop = dividerY + metrics.px(Space.M)

        val capacity = floor(((bounds.bottom - folderTop) / folderRowH).toDouble())
            .toInt()
            .coerceAtLeast(0)

        val start = model.folderScrollTop.coerceAtLeast(0)
        val end = (start + capacity).coerceAtMost(model.folders.size)
        val folderBoxes = ArrayList<FolderBox>(end - start)
        var fy = folderTop
        for (i in start until end) {
            folderBoxes.add(FolderBox(i, PixRect(bounds.left, fy, bounds.right, fy + folderRowH)))
            fy += folderRowH
        }

        return Layout(header, sourceBoxes, dividerY, folderTop, folderRowH, folderBoxes, bounds, capacity)
    }

    fun hitRegions(layout: Layout): List<HitRegion<GazeTarget>> {
        val out = ArrayList<HitRegion<GazeTarget>>(layout.sourceBoxes.size + layout.folderBoxes.size)
        for (s in layout.sourceBoxes) {
            out.add(HitRegion(s.rect.left, s.rect.top, s.rect.right, s.rect.bottom, GazeTarget.SourceTab(s.index)))
        }
        for (f in layout.folderBoxes) {
            out.add(HitRegion(f.rect.left, f.rect.top, f.rect.right, f.rect.bottom, GazeTarget.SidebarRow(f.index)))
        }
        return out
    }

    fun draw(
        canvas: Canvas,
        model: Model,
        layout: Layout,
        focus: BrowseFocus?,
        hover: GazeTarget?,
        thumbs: com.daydreamvr.player.media.thumb.ThumbnailCache? = null,
    ) {
        val padH = metrics.px(Space.L)
        val gutter = metrics.px(2.4f)

        canvas.drawText(
            "SOURCES",
            layout.header.left + padH,
            layout.header.bottom - metrics.px(Space.M),
            theme.text(Type.sectionLabel, metrics),
        )

        val titlePaint = theme.text(Type.chip, metrics)
        val countPaint = theme.text(Type.meta, metrics).apply { textAlign = Paint.Align.RIGHT }
        val focusedSource = (focus as? BrowseFocus.Source)?.index
        val hoverSource = (hover as? GazeTarget.SourceTab)?.index

        for (s in layout.sourceBoxes) {
            val src = model.sources.getOrNull(s.index) ?: continue
            paintRow(canvas, s.rect, focused = s.index == focusedSource, hovered = s.index == hoverSource)
            val iconSize = metrics.px(1.7f)
            Icons.draw(canvas, src.icon, s.rect.left + padH + iconSize / 2f, s.rect.centerY, iconSize, titlePaint)
            canvas.drawText(src.title, s.rect.left + padH + gutter, s.rect.centerY + metrics.px(0.5f), titlePaint)
            src.count?.let { canvas.drawText("$it", s.rect.right - padH, s.rect.centerY + metrics.px(0.5f), countPaint) }
        }

        Surfaces.divider(canvas, layout.bounds.left + padH, layout.bounds.right - padH, layout.dividerY, theme)

        val folderPaint = theme.text(Type.rowTitle, metrics)
        val focusedFolder = (focus as? BrowseFocus.Sidebar)?.index
        val hoverFolder = (hover as? GazeTarget.SidebarRow)?.index
        for (f in layout.folderBoxes) {
            val folder = model.folders.getOrNull(f.index) ?: continue
            paintRow(canvas, f.rect, focused = f.index == focusedFolder, hovered = f.index == hoverFolder)
            val iconSize = metrics.px(1.7f)
            Icons.draw(canvas, folder.icon, f.rect.left + padH + iconSize / 2f, f.rect.centerY, iconSize, folderPaint)
            canvas.drawText(folder.title, f.rect.left + padH + gutter, f.rect.centerY + metrics.px(0.5f), folderPaint)
            folder.count?.let { canvas.drawText("$it", f.rect.right - padH, f.rect.centerY + metrics.px(0.5f), countPaint) }
        }
    }

    private fun paintRow(canvas: Canvas, rect: PixRect, focused: Boolean, hovered: Boolean) {
        val r = rect.inset(metrics.px(Space.S), 1f).toRectF()
        when {
            focused -> Surfaces.focus(canvas, r, metrics, theme, Radius.CARD)
            hovered -> Surfaces.hover(canvas, r, metrics, theme, Radius.CARD)
        }
    }
}
