package com.daydreamvr.player.screens

import android.graphics.Canvas
import com.daydreamvr.player.state.AppState
import com.daydreamvr.player.state.Settings
import com.daydreamvr.player.state.VrScreen
import com.daydreamvr.vrcore.input.InputBindings
import com.daydreamvr.vrcore.input.RawKey
import com.daydreamvr.vrcore.ui.AngularMetrics
import com.daydreamvr.vrcore.ui.PanelSurface
import com.daydreamvr.vrcore.ui.Theme

/**
 * The in-VR gamepad button-mapping check (ARCHITECTURE.md §8.2 / §11.4, Phase 6).
 *
 * Android's HID mapping is nearly consistent; the exception that matters here is
 * an 8BitDo (and similar) in **Switch mode**, which swaps A/B and X/Y relative to
 * the Xbox layout the rest of the app assumes. The user toggles "Gamepad buttons"
 * on the [SettingsScreen]; this panel shows the resulting mapping and the
 * one-line instruction, and the swap is persisted in [Settings.gamepadAbSwapped]
 * so it survives a Bluetooth reconnect (the profile is keyed by descriptor, not
 * the transient device id).
 *
 * [GamepadCalibration] is the pure, JVM-tested core: given the key code the pad
 * actually emits for the confirm button it decides whether A/B are swapped, and
 * [GamepadCalibration.applyAbSwap] rewrites an [InputBindings] accordingly.
 */
class GamepadCalibrationScreen(panel: PanelSurface, theme: Theme) :
    ScreenPanel(panel, theme, panelWidthM = 2.4f, panelHeightM = 1.4f) {

    fun render(state: AppState) {
        if (state.screen != VrScreen.SETTINGS) return
        if (Settings.ROWS.getOrNull(state.hud.focusIndex) != "Gamepad buttons") return
        renderIfChanged(state.settings.gamepadAbSwapped) { canvas ->
            draw(canvas, state.settings.gamepadAbSwapped)
        }
    }

    private fun draw(canvas: Canvas, swapped: Boolean) {
        canvas.panelBackground(theme, panel.widthPx, panel.heightPx)
        val titleSize = AngularMetrics.textSizePx(2.4f, panel.widthPx, theme.panelWidthDegrees)
        val bodySize = AngularMetrics.textSizePx(1.6f, panel.widthPx, theme.panelWidthDegrees)
        var y = theme.paddingPx + titleSize
        canvas.drawText("Gamepad buttons", theme.paddingPx, y, theme.textPaint(titleSize, theme.textColor, bold = true))

        y += theme.paddingPx + bodySize
        canvas.drawText(
            if (swapped) "Mapping: A/B and X/Y swapped (Switch layout)" else "Mapping: standard (Xbox layout)",
            theme.paddingPx, y, theme.textPaint(bodySize, theme.accentColor),
        )

        y += theme.paddingPx + bodySize
        canvas.drawText(
            "Confirm = ${if (swapped) "B" else "A"}    Back = ${if (swapped) "A" else "B"}",
            theme.paddingPx, y, theme.textPaint(bodySize, theme.textColor),
        )

        y += theme.paddingPx + bodySize
        canvas.drawText(
            "Left / right to toggle if the lower face button doesn't confirm.",
            theme.paddingPx, y, theme.textPaint(bodySize, theme.dimTextColor),
        )
    }
}

/**
 * Pure A/B-swap detection and remapping (ARCHITECTURE.md §8.2). No Android.
 */
object GamepadCalibration {

    /** The key code a correctly-mapped pad emits for the confirm (lower) face button. */
    const val EXPECTED_CONFIRM_KEYCODE: Int = RawKey.KEYCODE_BUTTON_A

    /**
     * True when the pad reports A/B swapped: the user pressed the button they use
     * to confirm and it came through as `BUTTON_B` instead of `BUTTON_A`.
     */
    fun isAbSwapped(pressedConfirmKeyCode: Int): Boolean =
        pressedConfirmKeyCode == RawKey.KEYCODE_BUTTON_B

    /**
     * Returns [base] with the A/B and X/Y face-button assignments exchanged, so a
     * Switch-mode pad drives the app the same way an Xbox-mode one does. Idempotent
     * pairs: applying it twice restores [base].
     */
    fun applyAbSwap(base: InputBindings): InputBindings = base.copy(
        confirmKeys = base.confirmKeys.swap(RawKey.KEYCODE_BUTTON_A, RawKey.KEYCODE_BUTTON_B),
        cancelKeys = base.cancelKeys.swap(RawKey.KEYCODE_BUTTON_A, RawKey.KEYCODE_BUTTON_B),
        playPauseKeys = base.playPauseKeys.swap(RawKey.KEYCODE_BUTTON_X, RawKey.KEYCODE_BUTTON_Y),
        recenterKeys = base.recenterKeys.swap(RawKey.KEYCODE_BUTTON_X, RawKey.KEYCODE_BUTTON_Y),
    )

    /** [base] if [swapped] is false, otherwise [applyAbSwap] of it. */
    fun bindingsFor(base: InputBindings, swapped: Boolean): InputBindings =
        if (swapped) applyAbSwap(base) else base

    private fun Set<Int>.swap(a: Int, b: Int): Set<Int> =
        mapTo(HashSet(size)) { key ->
            when (key) {
                a -> b
                b -> a
                else -> key
            }
        }
}
