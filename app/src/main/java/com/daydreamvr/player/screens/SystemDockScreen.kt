package com.daydreamvr.player.screens

import android.graphics.PorterDuff
import com.daydreamvr.player.screens.widgets.PixRect
import com.daydreamvr.player.screens.widgets.SystemDockWidget
import com.daydreamvr.player.state.AppState
import com.daydreamvr.player.state.BrowseViewMode
import com.daydreamvr.player.state.VrScreen
import com.daydreamvr.vrcore.ui.HitMap
import com.daydreamvr.vrcore.ui.PanelSurface
import com.daydreamvr.vrcore.ui.Theme

/**
 * The detached system dock (UI_REDESIGN_REVIEWED_PLAN.md §3.1, §5.4): a second
 * interactive [ScreenPanel] on `BROWSE`, at a smaller radius than [BrowseScreen].
 * Its anchor is *slaved* to the browse anchor each frame by `AppScene` so the two
 * move as one rigid assembly.
 */
class SystemDockScreen(panel: PanelSurface, theme: Theme) : ScreenPanel(
    panel, theme,
    panelWidthM = DockLayout.WIDTH_M,
    panelHeightM = DockLayout.HEIGHT_M,
    distanceM = DockLayout.RADIUS_M,
) {
    override val verticalOffsetM = DockLayout.VERTICAL_OFFSET_M

    init {
        anchor.followThresholdDeg = BrowseLayout.FOLLOW_THRESHOLD_DEG
    }

    private val dockWidget = SystemDockWidget(theme, metrics)
    private val bounds = PixRect(0f, 0f, panel.widthPx.toFloat(), panel.heightPx.toFloat())

    fun render(state: AppState) {
        if (state.screen != VrScreen.BROWSE) return
        val top = state.browse.top
        val focus = top?.focus
        val viewMode = top?.viewMode ?: BrowseViewMode.GRID
        val key = listOf(state.screen, focus, state.gaze, viewMode)
        renderIfChanged(key) { canvas ->
            canvas.drawColor(0, PorterDuff.Mode.CLEAR)
            val model = SystemDockWidget.Model(viewMode = viewMode)
            val layout = dockWidget.measureLayout(model, bounds)
            dockWidget.draw(canvas, model, layout, focus, state.gaze)
            hitMap = HitMap(dockWidget.hitRegions(layout))
        }
    }
}
