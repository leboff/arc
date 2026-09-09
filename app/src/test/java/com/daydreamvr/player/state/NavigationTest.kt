package com.daydreamvr.player.state

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The end-to-end happy path (ARCHITECTURE.md §12): server list -> browse a
 * folder -> descend into a nested folder -> start the player -> back out one
 * level at a time to the server list.
 */
class NavigationTest {

    private val server = Fx.server()

    @Test
    fun serverList_browse_nestedBrowse_player_thenBackOut() {
        val d = Driver()

        // Discovery delivers one server.
        d.send(Event.ServersChanged(listOf(server)))
        assertThat(d.state.screen).isEqualTo(VrScreen.SERVER_LIST)

        // Open it -> BROWSE root, with a Browse effect for the effect runner.
        d.input(Fx.confirm)
        assertThat(d.state.screen).isEqualTo(VrScreen.BROWSE)
        assertThat(d.state.browse.depth).isEqualTo(1)
        assertThat(d.stepEffects).contains(Effect.Browse(server, "0", com.daydreamvr.upnp.model.PageRequest.DEFAULT))

        // Root listing arrives: one folder, one video.
        d.send(Event.BrowseLoaded("0", Fx.result("0", containers = listOf(Fx.container("c1")), items = listOf(Fx.video("v1")))))
        assertThat(d.state.browse.top!!.rows).hasSize(2)
        assertThat(d.state.browse.top!!.focusIndex).isEqualTo(0)

        // Right on the focused folder descends one level.
        d.input(Fx.right)
        assertThat(d.state.browse.depth).isEqualTo(2)
        assertThat(d.state.browse.top!!.objectId).isEqualTo("c1")

        // Nested listing arrives.
        d.send(Event.BrowseLoaded("c1", Fx.result("c1", items = listOf(Fx.video("v2")))))
        assertThat(d.state.browse.top!!.rows).hasSize(1)

        // Confirm on the video asks the effect runner to play it.
        d.input(Fx.confirm)
        val play = d.stepEffects.filterIsInstance<Effect.Play>().single()
        assertThat(play.item.id).isEqualTo("v2")
        assertThat(play.serverUdn).isEqualTo(server.udn)

        // The player publishes its first snapshot -> screen flips to PLAYER.
        d.send(Event.PlayerStateChanged(Fx.playing("v2")))
        assertThat(d.state.screen).isEqualTo(VrScreen.PLAYER)
        assertThat(d.state.hud.visible).isFalse()

        // Long-cancel in the player returns to the browser it came from.
        d.input(Fx.longCancel)
        assertThat(d.state.screen).isEqualTo(VrScreen.BROWSE)
        assertThat(d.state.browse.depth).isEqualTo(2)
        assertThat(d.stepEffects).contains(Effect.StopPlayback)
        assertThat(d.state.playback.itemKey).isNull()

        // Left backs out of the nested folder...
        d.input(Fx.left)
        assertThat(d.state.browse.depth).isEqualTo(1)
        assertThat(d.state.screen).isEqualTo(VrScreen.BROWSE)

        // ...and once more lands back on the server list with a cleared stack.
        d.input(Fx.left)
        assertThat(d.state.screen).isEqualTo(VrScreen.SERVER_LIST)
        assertThat(d.state.browse.depth).isEqualTo(0)
    }
}
