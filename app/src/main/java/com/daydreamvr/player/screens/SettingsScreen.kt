package com.daydreamvr.player.screens

import com.daydreamvr.player.state.AppState
import com.daydreamvr.player.state.Settings
import com.daydreamvr.player.state.VrScreen
import com.daydreamvr.vrcore.ui.AngularMetrics
import com.daydreamvr.vrcore.ui.PanelSurface
import com.daydreamvr.vrcore.ui.Theme
import com.daydreamvr.vrcore.ui.widgets.ListView
import java.util.Locale

/**
 * The in-VR settings panel (ARCHITECTURE.md §11.4): viewer profile, IPD, screen
 * distance / size, motion prediction, neck model, auto-recenter, and a
 * "forget servers" action. Left/right on a row adjusts it; the reducer owns the
 * clamping and emits [com.daydreamvr.player.state.Effect.ApplySettings].
 */
class SettingsScreen(panel: PanelSurface, theme: Theme) :
    ScreenPanel(panel, theme, panelWidthM = 2.4f, panelHeightM = 1.5f) {

    private val list = ListView(theme, panel.widthPx)

    fun render(state: AppState) {
        if (state.screen != VrScreen.SETTINGS) return
        val key = listOf(state.settings, state.hud.focusIndex)
        renderIfChanged(key) { canvas ->
            canvas.panelBackground(theme, panel.widthPx, panel.heightPx)
            val titleSize = AngularMetrics.textSizePx(2.4f, panel.widthPx, theme.panelWidthDegrees)
            canvas.drawText(
                "Settings",
                theme.paddingPx,
                theme.paddingPx + titleSize,
                theme.textPaint(titleSize, theme.textColor, bold = true),
            )

            val rows = Settings.ROWS.map { ListView.Row(title = it, subtitle = valueFor(it, state.settings)) }
            list.draw(
                canvas, rows, state.hud.focusIndex, scrollTop = 0,
                left = theme.paddingPx,
                top = theme.paddingPx * 2 + titleSize,
                width = panel.widthPx - theme.paddingPx * 2,
                height = panel.heightPx - theme.paddingPx * 3 - titleSize,
            )
        }
    }

    private fun valueFor(row: String, s: Settings): String = when (row) {
        "Viewer profile" -> s.deviceProfileId
        "IPD" -> String.format(Locale.US, "%.1f mm", s.ipdMm)
        "Screen distance" -> String.format(Locale.US, "%.1f m", s.screenDistanceM)
        "Screen size" -> "${s.screenWidthDegrees.toInt()}°"
        "Motion prediction" -> onOff(s.predictionEnabled)
        "Neck model" -> onOff(s.neckModelEnabled)
        "Auto-recenter" -> if (s.autoRecenterIdleSeconds <= 0) "Off" else "${s.autoRecenterIdleSeconds}s idle"
        "Forget servers" -> "Press to clear"
        else -> ""
    }

    private fun onOff(b: Boolean) = if (b) "On" else "Off"
}
