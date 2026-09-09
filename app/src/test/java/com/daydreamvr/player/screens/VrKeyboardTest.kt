package com.daydreamvr.player.screens

import com.daydreamvr.vrcore.input.InputAction
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The in-VR text grid reducer (ARCHITECTURE.md §11.5). Numeric-first layout,
 * A types the key under the cursor, B deletes, X types a space, Y toggles
 * numeric/alpha, and the cursor wraps at every edge.
 */
class VrKeyboardTest {

    private val start = VrKeyboard.KeyboardState()

    private fun nav(dir: InputAction.Dir) = InputAction.Nav(dir, repeat = false)
    private val type = InputAction.Confirm(long = false)

    private fun VrKeyboard.KeyboardState.after(vararg actions: InputAction): VrKeyboard.KeyboardState =
        actions.fold(this) { s, a -> VrKeyboard.reduce(a, s, subnetPrefix = null) }

    @Test
    fun numericLayoutIsTheDefaultAndTypesDigits() {
        assertThat(start.numericMode).isTrue()
        // (0,0)=1 ; right ->(0,1)=2 ; down ->(1,1)=5
        val s = start.after(type, nav(InputAction.Dir.RIGHT), type, nav(InputAction.Dir.DOWN), type)
        assertThat(s.text).isEqualTo("125")
    }

    @Test
    fun recenterTogglesToAlphaAndResetsTheCursor() {
        val alpha = start.after(nav(InputAction.Dir.DOWN), InputAction.Recenter)
        assertThat(alpha.numericMode).isFalse()
        assertThat(alpha.cursorRow).isEqualTo(0)
        assertThat(alpha.cursorCol).isEqualTo(0)

        // (0,0)=q ; right ->(0,1)=w
        val s = alpha.after(type, nav(InputAction.Dir.RIGHT), type)
        assertThat(s.text).isEqualTo("qw")
    }

    @Test
    fun cancelIsBackspace() {
        val s = start.after(type, nav(InputAction.Dir.RIGHT), type) // "12"
        assertThat(s.text).isEqualTo("12")
        assertThat(s.after(InputAction.Cancel(long = false)).text).isEqualTo("1")
        // Backspace on an empty buffer is a no-op, not a crash.
        assertThat(start.after(InputAction.Cancel(long = false)).text).isEmpty()
    }

    @Test
    fun theBackspaceKeyItselfAlsoDeletes() {
        // NUMERIC (2,3) is the "\b" key.
        val s = start.after(type, nav(InputAction.Dir.DOWN), nav(InputAction.Dir.DOWN), nav(InputAction.Dir.RIGHT), nav(InputAction.Dir.RIGHT), nav(InputAction.Dir.RIGHT), type)
        assertThat(s.text).isEqualTo("")
    }

    @Test
    fun playPauseTypesASpace() {
        val s = start.after(type, InputAction.PlayPause, type)
        assertThat(s.text).isEqualTo("1 1")
    }

    @Test
    fun cursorWrapsAtEveryEdge() {
        // Up from the top row lands on the last row.
        assertThat(start.after(nav(InputAction.Dir.UP)).cursorRow).isEqualTo(3)
        // Left from column 0 lands on the last column of that row (numeric: 4 wide).
        assertThat(start.after(nav(InputAction.Dir.LEFT)).cursorCol).isEqualTo(3)
        // Down from the last row wraps back to the top.
        val bottom = start.after(nav(InputAction.Dir.UP))
        assertThat(bottom.after(nav(InputAction.Dir.DOWN)).cursorRow).isEqualTo(0)
        // Right past the last column wraps to 0.
        val lastCol = start.after(nav(InputAction.Dir.LEFT))
        assertThat(lastCol.after(nav(InputAction.Dir.RIGHT)).cursorCol).isEqualTo(0)
    }

    @Test
    fun alphaRowsWrapAcrossTheirFullTenColumnWidth() {
        val alpha = start.after(InputAction.Recenter)
        assertThat(alpha.after(nav(InputAction.Dir.LEFT)).cursorCol).isEqualTo(9)
    }

    @Test
    fun menuFillsInASubnetSuggestion() {
        val s = VrKeyboard.reduce(InputAction.Menu, start, subnetPrefix = "192.168.1")
        assertThat(s.text).isEqualTo("192.168.1.10:49152")
    }
}
