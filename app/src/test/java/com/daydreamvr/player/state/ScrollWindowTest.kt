package com.daydreamvr.player.state

import com.daydreamvr.vrcore.input.InputAction
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins F3/F4: the settings and server lists must scroll so the focused row is
 * always inside the visible window the renderer draws (UI_GAZE_PLAN.md §0).
 */
class ScrollWindowTest {

    private val down = InputAction.Nav(InputAction.Dir.DOWN, repeat = false)

    @Test
    fun settingsScrollKeepsTheLastRowInsideAnEightRowWindow() {
        var d = Driver(AppState(screen = VrScreen.SETTINGS))
        repeat(Settings.ROWS.size - 1) { d = d.input(down) }

        val s = d.state
        assertThat(s.hud.focusIndex).isEqualTo(Settings.ROWS.size - 1)
        val window = s.listWindow.settings
        assertThat(s.hud.focusIndex).isAtLeast(s.settingsScrollTop)
        assertThat(s.hud.focusIndex).isLessThan(s.settingsScrollTop + window)
        // "Forget servers" (row 13) is now reachable and drawn.
        assertThat(Settings.ROWS[s.hud.focusIndex]).isEqualTo("Forget servers")
    }

    @Test
    fun serverScrollKeepsTheFocusedServerVisible() {
        val servers = (1..9).map { Fx.server(udn = "uuid:s$it") }
        var d = Driver(AppState(screen = VrScreen.SERVER_LIST, servers = servers))
        // Walk down to the last real server.
        repeat(servers.size - 1) { d = d.input(down) }

        val s = d.state
        val window = s.listWindow.servers
        assertThat(s.serverFocusIndex).isEqualTo(servers.size - 1)
        assertThat(s.serverFocusIndex).isLessThan(s.serverScrollTop + window)
    }
}
