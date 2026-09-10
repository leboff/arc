package com.daydreamvr.player.screens.widgets

import android.graphics.Canvas
import android.graphics.Paint
import com.daydreamvr.player.media.thumb.ThumbnailCache
import com.daydreamvr.player.state.BrowseFocus
import com.daydreamvr.player.state.BrowseViewMode
import com.daydreamvr.player.state.GazeTarget
import com.daydreamvr.player.state.GazeTarget.Dock
import com.daydreamvr.vrcore.ui.HitRegion
import com.daydreamvr.vrcore.ui.Icon
import com.daydreamvr.vrcore.ui.Icons
import com.daydreamvr.vrcore.ui.PanelMetrics
import com.daydreamvr.vrcore.ui.Radius
import com.daydreamvr.vrcore.ui.Surfaces
import com.daydreamvr.vrcore.ui.TextMeasure
import com.daydreamvr.vrcore.ui.Theme
import com.daydreamvr.vrcore.ui.Type

/**
 * The detached system dock (UI_REDESIGN_REVIEWED_PLAN.md §5.4): a separate curved
 * surface, `672 × 176 px`, tiled into six equal cells. Every cell is far above
 * `MIN_TARGET_DEGREES` — that is the point of a dock, it must be hittable with a
 * glance. Drawn only on `VrScreen.BROWSE`.
 */
class SystemDockWidget(
    private val theme: Theme,
    private val metrics: PanelMetrics,
    private val measure: TextMeasure = TextMeasure.PAINT,
) {

    data class Model(
        /** e.g. the current view-mode cell, painted as selected. */
        val highlight: Dock? = null,
        val viewMode: BrowseViewMode = BrowseViewMode.GRID,
    )

    data class Cell(
        val slot: Int,
        val button: Dock,
        val icon: Icon,
        val label: String,
        val rect: PixRect,
    )

    data class Layout(val cells: List<Cell>, val bounds: PixRect)

    /** PURE. */
    fun measureLayout(model: Model, bounds: PixRect): Layout {
        val cellW = bounds.width / SLOTS.size
        val cells = SLOTS.mapIndexed { slot, spec ->
            val left = bounds.left + slot * cellW
            val icon = if (spec.button == Dock.VIEW_MODE) {
                if (model.viewMode == BrowseViewMode.GRID) Icon.GRID else Icon.LIST
            } else spec.icon
            val label = if (spec.button == Dock.VIEW_MODE) {
                if (model.viewMode == BrowseViewMode.GRID) "Grid" else "List"
            } else spec.label
            Cell(slot, spec.button, icon, label, PixRect(left, bounds.top, left + cellW, bounds.bottom))
        }
        return Layout(cells, bounds)
    }

    fun hitRegions(layout: Layout): List<HitRegion<GazeTarget>> =
        layout.cells.map { c ->
            HitRegion(c.rect.left, c.rect.top, c.rect.right, c.rect.bottom, GazeTarget.DockButton(c.button))
        }

    fun draw(
        canvas: Canvas,
        model: Model,
        layout: Layout,
        focus: BrowseFocus?,
        hover: GazeTarget?,
        thumbs: ThumbnailCache? = null,
    ) {
        Surfaces.panel(canvas, metrics, theme)
        val focused = (focus as? BrowseFocus.Dock)?.button
        val hovered = (hover as? GazeTarget.DockButton)?.button
        val labelPaint = theme.text(Type.chip, metrics).apply { textAlign = Paint.Align.CENTER }

        for (cell in layout.cells) {
            val r = cell.rect.inset(metrics.px(0.2f), metrics.px(0.2f)).toRectF()
            when {
                cell.button == focused -> Surfaces.focus(canvas, r, metrics, theme, Radius.CARD)
                cell.button == hovered -> Surfaces.hover(canvas, r, metrics, theme, Radius.CARD)
                cell.button == model.highlight -> Surfaces.chip(canvas, r, metrics, theme, accented = true)
            }
            val iconSize = metrics.px(2.6f)
            Icons.draw(canvas, cell.icon, cell.rect.centerX, cell.rect.top + metrics.px(2.6f), iconSize, labelPaint)
            canvas.drawText(cell.label, cell.rect.centerX, cell.rect.bottom - metrics.px(0.9f), labelPaint)
        }
    }

    private data class Spec(val button: Dock, val icon: Icon, val label: String)

    companion object {
        private val SLOTS = listOf(
            Spec(Dock.RECENTER, Icon.RECENTER, "Recenter"),
            Spec(Dock.SETTINGS, Icon.GEAR, "Settings"),
            Spec(Dock.CALIBRATE, Icon.ASPECT, "Calibrate"),
            Spec(Dock.VIEW_MODE, Icon.GRID, "View"),
            Spec(Dock.RESCAN, Icon.REFRESH, "Rescan"),
            Spec(Dock.EXIT, Icon.EXIT, "Exit"),
        )
    }
}
