package com.daydreamvr.player.state

import com.daydreamvr.vrcore.input.InputAction
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Gaze moves focus; the controller still selects (UI_GAZE_PLAN.md §3.3).
 */
class GazeReducerTest {

    private val confirm = InputAction.Confirm(long = false)
    private val down = InputAction.Nav(InputAction.Dir.DOWN, repeat = false)

    private fun serverListState() = AppState(
        screen = VrScreen.SERVER_LIST,
        servers = (1..6).map { Fx.server(udn = "uuid:s$it") },
    )

    @Test
    fun hoverWritesTheFocusIndexForTheActiveScreen() {
        val d = Driver(serverListState()).send(Event.GazeMoved(GazeTarget.ServerRow(3)))
        assertThat(d.state.serverFocusIndex).isEqualTo(3)
        assertThat(d.state.gaze).isEqualTo(GazeTarget.ServerRow(3))
    }

    @Test
    fun hoverNeverChangesScrollTop() {
        val start = serverListState().copy(serverScrollTop = 0)
        val d = Driver(start).send(Event.GazeMoved(GazeTarget.ServerRow(5)))
        assertThat(d.state.serverScrollTop).isEqualTo(0)
    }

    @Test
    fun confirmAfterHoverActivatesTheHoveredRow() {
        val d = Driver(serverListState())
            .send(Event.GazeMoved(GazeTarget.ServerRow(2)))
            .input(confirm)
        // Row 2 is a server → we browse into it.
        assertThat(d.state.screen).isEqualTo(VrScreen.BROWSE)
        assertThat(d.stepEffects.any { it is Effect.Browse }).isTrue()
    }

    @Test
    fun dpadAfterHoverStillMovesFocus() {
        val d = Driver(serverListState())
            .send(Event.GazeMoved(GazeTarget.ServerRow(1)))
            .input(down)
        assertThat(d.state.serverFocusIndex).isEqualTo(2)
    }

    @Test
    fun targetsForTheScreenBehindAnOverlayAreIgnored() {
        val start = serverListState().copy(
            overlay = Overlay.Confirm("Leave?", listOf("Stay", "Exit"), 0, tag = "quit"),
        )
        val d = Driver(start).send(Event.GazeMoved(GazeTarget.ServerRow(4)))
        assertThat(d.state.serverFocusIndex).isEqualTo(0) // unchanged
        // but a DialogButton hover moves the dialog focus
        val d2 = Driver(start).send(Event.GazeMoved(GazeTarget.DialogButton(1)))
        assertThat((d2.state.overlay as Overlay.Confirm).focusIndex).isEqualTo(1)
    }

    @Test
    fun hudControlHoverRefreshesLastInputAt() {
        val start = AppState(
            screen = VrScreen.PLAYER,
            hud = HudState(visible = true, lastInputAtMs = 0L),
            nowMs = 5_000L,
        )
        val d = Driver(start).send(Event.GazeMoved(GazeTarget.HudControl(2)))
        assertThat(d.state.hud.focusIndex).isEqualTo(2)
        assertThat(d.state.hud.lastInputAtMs).isEqualTo(5_000L)
    }

    @Test
    fun listWindowMeasuredReplacesTheWindow() {
        val d = Driver().send(Event.ListWindowMeasured(ListWindow(browse = 7, settings = 9, servers = 4)))
        assertThat(d.state.listWindow).isEqualTo(ListWindow(7, 9, 4))
    }
}
