package com.daydreamvr.player.state

import com.daydreamvr.player.media.MediaKey
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The end-to-end happy path over the 3-column PLAY'A browse model
 * (UI_REDESIGN_REVIEWED_PLAN.md §10, M4 acceptance 7): server list -> browse a
 * folder from the sidebar -> descend -> confirm a grid cell -> player, then back
 * out one level at a time. Confirm on the grid now emits [Effect.PlayNode].
 */
class NavigationTest {

    private val server = Fx.server()

    @Test
    fun serverList_sidebarFolder_grid_player_thenBackOut() {
        val d = Driver()

        d.send(Event.ServersChanged(listOf(server)))
        assertThat(d.state.screen).isEqualTo(VrScreen.SERVER_LIST)

        // Open the server -> BROWSE root, focus parked on the sidebar.
        d.input(Fx.confirm)
        assertThat(d.state.screen).isEqualTo(VrScreen.BROWSE)
        assertThat(d.state.browse.top!!.focus).isEqualTo(BrowseFocus.Sidebar(0))
        assertThat(d.stepEffects).contains(Effect.Browse(server, "0", com.daydreamvr.upnp.model.PageRequest.DEFAULT))

        // Root listing: one folder, one video.
        d.send(
            Event.BrowseLoaded(
                "0",
                Fx.result("0", containers = listOf(Fx.container("c1")), items = listOf(Fx.video("v1"))),
            ),
        )
        assertThat(d.state.browse.top!!.folders).hasSize(1)
        assertThat(d.state.browse.top!!.videos).hasSize(1)

        // Confirm on the focused sidebar folder descends one level.
        d.input(Fx.confirm)
        assertThat(d.state.browse.depth).isEqualTo(2)
        assertThat(d.state.browse.top!!.objectId).isEqualTo("c1")
        val browseNode = d.stepEffects.filterIsInstance<Effect.BrowseNode>().single()
        assertThat(browseNode.objectId).isEqualTo("c1")

        // Nested listing arrives; focus lands on the grid.
        d.send(Event.BrowseLoaded("c1", Fx.result("c1", items = listOf(Fx.video("v2")))))
        assertThat(d.state.browse.top!!.focus).isEqualTo(BrowseFocus.Grid(0))

        // Confirm on the grid cell asks the effect runner to play it.
        d.input(Fx.confirm)
        val play = d.stepEffects.filterIsInstance<Effect.PlayNode>().single()
        assertThat(play.node.id).isEqualTo("v2")
        assertThat(play.key).isEqualTo(MediaKey("upnp:${server.udn}", "v2"))
        assertThat(play.skipResumeCheck).isFalse()

        // The player publishes its first snapshot -> screen flips to PLAYER.
        d.send(Event.PlayerStateChanged(Fx.playing("v2")))
        assertThat(d.state.screen).isEqualTo(VrScreen.PLAYER)

        // Long-cancel returns to the browser it came from.
        d.input(Fx.longCancel)
        assertThat(d.state.screen).isEqualTo(VrScreen.BROWSE)
        assertThat(d.state.browse.depth).isEqualTo(2)
        assertThat(d.stepEffects).contains(Effect.StopPlayback)

        // B backs out of the nested folder...
        d.input(Fx.cancel)
        assertThat(d.state.browse.depth).isEqualTo(1)
        assertThat(d.state.screen).isEqualTo(VrScreen.BROWSE)

        // ...and once more lands back on the server list with a cleared stack.
        d.input(Fx.cancel)
        assertThat(d.state.screen).isEqualTo(VrScreen.SERVER_LIST)
        assertThat(d.state.browse.depth).isEqualTo(0)
    }
}
