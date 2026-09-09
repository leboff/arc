package com.daydreamvr.player.state

import com.daydreamvr.player.screens.VrKeyboard
import com.daydreamvr.vrcore.input.InputAction
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * The two-tier escape hatch (ARCHITECTURE.md §15): a short cancel must always
 * visibly change something, and a long cancel must always get the user back
 * out to the server list (at most one hop away, from a player that has a
 * browse stack behind it).
 */
class EscapeHatchTest {

    private val server = Fx.server()
    private val rootFrame = BrowseFrame(server, "0", "root", loading = false)

    /** Every screen + every overlay the user can be looking at. */
    private fun everyContext(): List<Pair<String, AppState>> = listOf(
        "server list" to AppState(screen = VrScreen.SERVER_LIST, servers = listOf(server)),
        "browse (depth 1)" to AppState(
            screen = VrScreen.BROWSE, servers = listOf(server), browse = BrowseState(listOf(rootFrame)),
        ),
        "browse (depth 2)" to AppState(
            screen = VrScreen.BROWSE,
            servers = listOf(server),
            browse = BrowseState(listOf(rootFrame, rootFrame.copy(objectId = "c1"))),
        ),
        "settings" to AppState(screen = VrScreen.SETTINGS),
        "player (hud hidden)" to AppState(screen = VrScreen.PLAYER, playback = PlaybackSlice(itemKey = "v1")),
        "player (hud shown)" to AppState(
            screen = VrScreen.PLAYER, playback = PlaybackSlice(itemKey = "v1"), hud = HudState(visible = true),
        ),
        "error overlay" to AppState(overlay = Overlay.Error("Oops", "broke", canRetry = false)),
        "toast overlay" to AppState(overlay = Overlay.Toast("hi", expiresAtMs = 9_999L)),
        "keyboard overlay" to AppState(
            overlay = Overlay.Keyboard(KeyboardPurpose.MANUAL_SERVER, VrKeyboard.KeyboardState(text = "abc")),
        ),
        "confirm overlay" to AppState(overlay = Overlay.Confirm("Sure?", listOf("Yes", "No"), 0, tag = "x")),
    )

    @Test
    fun shortCancelAlwaysChangesState() {
        for ((name, state) in everyContext()) {
            val (next, _) = AppStateMachine.reduce(state, Event.Input(InputAction.Cancel(long = false)))
            assertWithMessage("short cancel in %s", name).that(next).isNotEqualTo(state)
        }
    }

    @Test
    fun longCancelAlwaysReachesTheServerList() {
        for ((name, state) in everyContext()) {
            val (next, _) = AppStateMachine.reduce(state, Event.Input(InputAction.Cancel(long = true)))
            assertWithMessage("long cancel in %s -> screen", name).that(next.screen).isEqualTo(VrScreen.SERVER_LIST)
            assertWithMessage("long cancel in %s -> overlay", name).that(next.overlay).isNull()
        }
    }

    @Test
    fun longCancelFromPlayerWithABrowseStackLandsOnBrowseThenServerList() {
        val state = AppState(
            screen = VrScreen.PLAYER,
            servers = listOf(server),
            browse = BrowseState(listOf(rootFrame, rootFrame.copy(objectId = "c1"))),
            playback = PlaybackSlice(itemKey = "v1"),
        )

        val (afterPlayer, playerFx) = AppStateMachine.reduce(state, Event.Input(InputAction.Cancel(long = true)))
        assertThat(afterPlayer.screen).isEqualTo(VrScreen.BROWSE)
        assertThat(afterPlayer.browse.depth).isEqualTo(2)
        assertThat(playerFx).contains(Effect.StopPlayback)

        val (afterBrowse, _) = AppStateMachine.reduce(afterPlayer, Event.Input(InputAction.Cancel(long = true)))
        assertThat(afterBrowse.screen).isEqualTo(VrScreen.SERVER_LIST)
        assertThat(afterBrowse.browse.depth).isEqualTo(0)
    }

    @Test
    fun shortCancelClearsWhicheverOverlayIsUp() {
        for (overlay in listOf(
            Overlay.Error("t", "m", canRetry = true),
            Overlay.Toast("t", 1L),
            Overlay.Confirm("t", listOf("a", "b"), 1, tag = "x"),
        )) {
            val (next, _) = AppStateMachine.reduce(
                AppState(screen = VrScreen.BROWSE, overlay = overlay),
                Event.Input(InputAction.Cancel(long = false)),
            )
            assertWithMessage(overlay.toString()).that(next.overlay).isNull()
        }
    }
}
