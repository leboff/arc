package com.daydreamvr.player.screens

import com.daydreamvr.vrcore.input.InputBindings
import com.daydreamvr.vrcore.input.RawKey
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The A/B-swap detection and remap (ARCHITECTURE.md §8.2). An 8BitDo in Switch
 * mode reports the confirm button as `BUTTON_B`; the remap has to make it behave
 * like an Xbox pad and be its own inverse so a double-toggle is a no-op.
 */
class GamepadCalibrationTest {

    @Test
    fun detectsSwapFromTheConfirmButtonKeyCode() {
        assertThat(GamepadCalibration.isAbSwapped(RawKey.KEYCODE_BUTTON_A)).isFalse()
        assertThat(GamepadCalibration.isAbSwapped(RawKey.KEYCODE_BUTTON_B)).isTrue()
    }

    @Test
    fun applyAbSwapExchangesConfirmAndCancelFaceButtons() {
        val swapped = GamepadCalibration.applyAbSwap(InputBindings())

        assertThat(swapped.confirmKeys).contains(RawKey.KEYCODE_BUTTON_B)
        assertThat(swapped.confirmKeys).doesNotContain(RawKey.KEYCODE_BUTTON_A)
        assertThat(swapped.cancelKeys).contains(RawKey.KEYCODE_BUTTON_A)
        assertThat(swapped.cancelKeys).doesNotContain(RawKey.KEYCODE_BUTTON_B)
        // Non-face-button members are untouched.
        assertThat(swapped.confirmKeys).contains(RawKey.KEYCODE_ENTER)
        assertThat(swapped.cancelKeys).contains(RawKey.KEYCODE_BACK)
    }

    @Test
    fun applyAbSwapAlsoExchangesXAndY() {
        val swapped = GamepadCalibration.applyAbSwap(InputBindings())
        assertThat(swapped.playPauseKeys).contains(RawKey.KEYCODE_BUTTON_Y)
        assertThat(swapped.recenterKeys).containsExactly(RawKey.KEYCODE_BUTTON_X)
    }

    @Test
    fun swappingTwiceRestoresTheOriginal() {
        val base = InputBindings()
        val roundTrip = GamepadCalibration.applyAbSwap(GamepadCalibration.applyAbSwap(base))
        assertThat(roundTrip.confirmKeys).isEqualTo(base.confirmKeys)
        assertThat(roundTrip.cancelKeys).isEqualTo(base.cancelKeys)
        assertThat(roundTrip.playPauseKeys).isEqualTo(base.playPauseKeys)
        assertThat(roundTrip.recenterKeys).isEqualTo(base.recenterKeys)
    }

    @Test
    fun bindingsForOnlyRemapsWhenAsked() {
        val base = InputBindings()
        assertThat(GamepadCalibration.bindingsFor(base, swapped = false)).isSameInstanceAs(base)
        assertThat(GamepadCalibration.bindingsFor(base, swapped = true)).isEqualTo(GamepadCalibration.applyAbSwap(base))
    }
}
