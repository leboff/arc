package com.daydreamvr.player.screens

import android.graphics.Canvas
import com.daydreamvr.player.data.OpticsSettingsResolver
import com.daydreamvr.player.state.AppState
import com.daydreamvr.player.state.Settings
import com.daydreamvr.player.state.VrScreen
import com.daydreamvr.vrcore.profile.DeviceProfile
import com.daydreamvr.vrcore.ui.PanelSurface
import com.daydreamvr.vrcore.ui.Space
import com.daydreamvr.vrcore.ui.Theme
import java.util.Locale

/**
 * The in-VR optics calibration surface (ARCHITECTURE.md §6.6 / §11.4, Phase 6 / DISTORTION_REMEDIATION_PLAN §6).
 *
 * Displays reference grid lines and optical readout. Observer IPD adjusts stereo scale,
 * while screen-to-lens, k1, k2, and divider width calibrate physical optics independently.
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
            "Observer IPD %.1f mm   lens %.1f mm   k1 %.2f   k2 %.2f   divider %d px   distortion %s",
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
         * Folds the user's live calibration into a [DeviceProfile] via [OpticsSettingsResolver].
         * Fixed viewer interLensDistanceM is strictly preserved from the profile baseline (§4).
         */
        fun overrideProfile(base: DeviceProfile, s: Settings): DeviceProfile =
            OpticsSettingsResolver.resolveDeviceProfile(s)
    }
}
