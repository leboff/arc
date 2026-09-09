package com.daydreamvr.player.screens.widgets

import com.daydreamvr.player.state.GazeTarget
import com.daydreamvr.vrcore.ui.PanelMetrics
import com.daydreamvr.vrcore.ui.TextMeasure
import com.daydreamvr.vrcore.ui.Theme
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The detached dock (UI_REDESIGN_REVIEWED_PLAN.md §5.4): six equal cells tiling
 * `[0, 672)` exactly, each subtending `4.658° × 7.320°` on the 2.05 m dock
 * surface.
 */
class SystemDockLayoutTest {

    private val metrics = PanelMetrics.curved(672, 176, 1.00f, 2.05f)
    private val measure = TextMeasure.fixed(perCharPx = 12f)
    private val widget = SystemDockWidget(Theme.DEFAULT, metrics, measure)
    private val bounds = PixRect(0f, 0f, 672f, 176f)

    @Test
    fun sixCellsTileTheSurfaceExactly() {
        val layout = widget.measureLayout(SystemDockWidget.Model(), bounds)
        assertThat(layout.cells).hasSize(6)
        assertThat(layout.cells.first().rect.left).isEqualTo(0f)
        assertThat(layout.cells.last().rect.right).isWithin(0.001f).of(672f)
        for (i in 0 until 5) {
            assertThat(layout.cells[i].rect.right).isWithin(0.001f).of(layout.cells[i + 1].rect.left)
        }
    }

    @Test
    fun eachCellSubtendsTheSpecifiedVisualAngle() {
        val layout = widget.measureLayout(SystemDockWidget.Model(), bounds)
        for (c in layout.cells) {
            assertThat(metrics.deg(c.rect.width)).isWithin(0.01f).of(4.658f)
            assertThat(metrics.deg(c.rect.height)).isWithin(0.01f).of(7.320f)
        }
    }

    @Test
    fun slotsPublishTheDockButtonsInOrder() {
        val layout = widget.measureLayout(SystemDockWidget.Model(), bounds)
        val ids = widget.hitRegions(layout).map { it.id }
        assertThat(ids).containsExactly(
            GazeTarget.DockButton(GazeTarget.Dock.RECENTER),
            GazeTarget.DockButton(GazeTarget.Dock.SETTINGS),
            GazeTarget.DockButton(GazeTarget.Dock.CALIBRATE),
            GazeTarget.DockButton(GazeTarget.Dock.VIEW_MODE),
            GazeTarget.DockButton(GazeTarget.Dock.RESCAN),
            GazeTarget.DockButton(GazeTarget.Dock.EXIT),
        ).inOrder()
    }
}
