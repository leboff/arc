package com.daydreamvr.player.screens

import android.graphics.Canvas
import com.daydreamvr.player.state.AppState
import com.daydreamvr.player.state.Settings
import com.daydreamvr.player.state.VrScreen
import com.daydreamvr.vrcore.profile.DeviceProfile
import com.daydreamvr.vrcore.ui.PanelSurface
import com.daydreamvr.vrcore.ui.Space
import com.daydreamvr.vrcore.ui.Theme
import java.util.Locale

/**
 * The in-VR optics calibration surface (ARCHITECTURE.md §6.6 / §11.4, Phase 6).
 *
 * It draws a large, flat, high-contrast reference grid on a wall-sized panel
 * behind the [SettingsScreen] list. With distortion correction on and the right
 * `k1`/`k2` the grid lines are straight through the real lenses; wrong values
 * bow them. The user nudges IPD, screen-to-lens distance, `k1`, `k2` and the
 * divider width with left/right on the matching [SettingsScreen] row and watches
 * the grid react live — the one situation where the correct values are obvious
 * and a touch UI would be useless.
 *
 * The panel itself is only ever repainted when a calibration value changed
 * (ARCHITECTURE.md R4); the correction maths happen in the distortion mesh, fed
 * the [overrideProfile] this screen derives.
 */
class CalibrationScreen(panel: PanelSurface, theme: Theme) :
    ScreenPanel(panel, theme, panelWidthM = 6.0f, panelHeightM = 4.0f) {

    /** Sits further out than the UI panels so it reads as a back wall, not a card. */
    override val verticalOffsetM: Float = 0f

    fun render(state: AppState) {
        if (state.screen != VrScreen.SETTINGS) return
        val row = Settings.ROWS.getOrNull(state.hud.focusIndex)
        if (row !in Settings.CALIBRATION_ROWS) return
        renderIfChanged(calibrationKey(state.settings)) { canvas ->
            drawGrid(canvas)
            drawReadout(canvas, state.settings)
        }
    }

    private fun drawGrid(canvas: Canvas) {
        val w = panel.widthPx
        val h = panel.heightPx
        canvas.drawColor(GRID_BG)
        val line = theme.strokePaint(GRID_LINE, 3f)
        val axis = theme.strokePaint(theme.accent, 5f)

        for (i in 0..GRID_COLUMNS) {
            val x = w * i.toFloat() / GRID_COLUMNS
            canvas.drawLine(x, 0f, x, h.toFloat(), if (i == GRID_COLUMNS / 2) axis else line)
        }
        for (j in 0..GRID_ROWS) {
            val y = h * j.toFloat() / GRID_ROWS
            canvas.drawLine(0f, y, w.toFloat(), y, if (j == GRID_ROWS / 2) axis else line)
        }
        // A centre cross-hair so IPD / lens-centre error shows as a doubled target.
        val cx = w / 2f
        val cy = h / 2f
        val r = h / 12f
        canvas.drawCircle(cx, cy, r, theme.strokePaint(theme.accent, 5f))
    }

    private fun drawReadout(canvas: Canvas, s: Settings) {
        val size = metrics.px(1.6f)
        val paint = theme.textPaint(size, theme.textPrimary, bold = true)
        val text = String.format(
            Locale.US,
            "IPD %.1f mm   lens %.1f mm   k1 %.2f   k2 %.2f   divider %d px   distortion %s",
            s.ipdMm, s.screenToLensMm, s.lensK1, s.lensK2, s.dividerPx,
            if (s.distortionCorrection) "on" else "off",
        )
        canvas.drawText(text, metrics.px(Space.XL), panel.heightPx - metrics.px(Space.XL), paint)
    }

    companion object {
        private const val GRID_COLUMNS = 16
        private const val GRID_ROWS = 12
        private const val GRID_BG = 0xFF101014.toInt()
        private const val GRID_LINE = 0xFFB8B8BE.toInt()

        /** The panel view-model: repaint only when one of these moved. */
        fun calibrationKey(s: Settings): Any = listOf(
            s.ipdMm, s.screenToLensMm, s.lensK1, s.lensK2, s.dividerPx, s.distortionCorrection,
        )

        /**
         * Folds the user's live calibration into [base], producing the profile the
         * renderer and distortion mesh actually use. Observer IPD deliberately
         * does not alter fixed viewer lens centres, source bounds, or warp UVs.
         */
        fun overrideProfile(base: DeviceProfile, s: Settings): DeviceProfile = base.copy(
            screenToLensDistanceM = s.screenToLensMm / 1000f,
            distortionK = floatArrayOf(s.lensK1, s.lensK2),
            dividerPx = s.dividerPx,
        )
    }
}
