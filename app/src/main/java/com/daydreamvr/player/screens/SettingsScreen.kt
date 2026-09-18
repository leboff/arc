package com.daydreamvr.player.screens

import com.daydreamvr.player.state.AppState
import com.daydreamvr.player.state.GazeTarget
import com.daydreamvr.player.state.Settings
import com.daydreamvr.player.state.VrScreen
import com.daydreamvr.vrcore.ui.HitMap
import com.daydreamvr.vrcore.ui.HitRegion
import com.daydreamvr.vrcore.ui.PanelSurface
import com.daydreamvr.vrcore.ui.Space
import com.daydreamvr.vrcore.ui.Theme
import com.daydreamvr.vrcore.ui.widgets.ListView
import java.util.Locale

/**
 * The in-VR settings panel (ARCHITECTURE.md §11.4): two-column single-line rows,
 * scrolled by [AppState.settingsScrollTop] so every row — including "Forget
 * servers" — is reachable and drawn (UI_GAZE_PLAN.md §5.4, fixes F3).
 */
class SettingsScreen(panel: PanelSurface, theme: Theme) :
    ScreenPanel(panel, theme, panelWidthM = 2.20f, panelHeightM = 1.50f) {

    private val list = ListView(theme, metrics)

    /** Rows that fit the list body — the reducer's scroll window (F5). */
    fun visibleRows(): Int = list.visibleRowCount(bodyHeight(), twoLine = false)

    private fun bodyHeight(): Float {
        val contentTop = metrics.px(Space.L) + metrics.px(2.3f) * 1.1f + metrics.px(Space.M)
        return bodyBottom() - contentTop
    }

    fun render(state: AppState) {
        if (state.screen != VrScreen.SETTINGS) return
        val key = listOf(state.settings, state.hud.focusIndex, state.settingsScrollTop, state.gaze)
        renderIfChanged(key) { canvas ->
            canvas.panelBackground(theme, metrics)
            val contentTop = drawHeader(canvas, "Settings")
            val left = contentLeft()
            val width = metrics.widthPx - left * 2
            val height = bodyBottom() - contentTop

            val entries = Settings.ROWS.map { row ->
                ListView.Entry.Item(
                    title = row,
                    trailing = valueFor(row, state.settings),
                    style = if (row == "Forget servers") ListView.Style.DANGER else ListView.Style.DEFAULT,
                )
            }
            val layout = list.measureLayout(entries, contentTop, height, left, width, state.settingsScrollTop)
            val hover = (state.gaze as? GazeTarget.SettingsRow)?.index
            list.draw(canvas, entries, layout, state.hud.focusIndex, hover, state.settingsScrollTop)

            hitMap = HitMap(
                layout.boxes.map { box ->
                    HitRegion(box.left, box.top, box.right, box.bottom, GazeTarget.SettingsRow(box.index))
                },
            )
        }
    }

    private fun valueFor(row: String, s: Settings): String = when (row) {
        "Viewer profile" -> s.deviceProfileId
        "IPD" -> String.format(Locale.US, "%.1f mm", s.ipdMm)
        "Screen distance" -> String.format(Locale.US, "%.1f m", s.screenDistanceM)
        "Screen size" -> "${s.screenWidthDegrees.toInt()}°"
        "Screen-to-lens" -> String.format(Locale.US, "%.1f mm", s.screenToLensMm)
        "Lens k1" -> String.format(Locale.US, "%.2f", s.lensK1)
        "Lens k2" -> String.format(Locale.US, "%.2f", s.lensK2)
        "Divider width" -> "${s.dividerPx} px"
        "Distortion correction" -> onOff(s.distortionCorrection)
        "Supersampling" -> if (s.supersampling) "1.3x (High)" else "1.15x (Standard)"
        "Gamepad buttons" -> if (s.gamepadAbSwapped) "A/B swapped" else "Standard"
        "Motion prediction" -> onOff(s.predictionEnabled)
        "Neck model" -> onOff(s.neckModelEnabled)
        "Auto-recenter" -> if (s.autoRecenterIdleSeconds <= 0) "Off" else "${s.autoRecenterIdleSeconds}s idle"
        "Forget servers" -> "Press to clear"
        else -> ""
    }

    private fun onOff(b: Boolean) = if (b) "On" else "Off"
}
