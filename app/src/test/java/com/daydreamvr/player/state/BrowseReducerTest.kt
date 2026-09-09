package com.daydreamvr.player.state

import com.daydreamvr.upnp.model.PageRequest
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The browse-screen reducer in isolation over the 3-column model
 * (UI_REDESIGN_REVIEWED_PLAN.md §10.4): grid-focus clamping, per-frame cursor
 * restore when popping the stack, and background top-up paging.
 */
class BrowseReducerTest {

    private val server = Fx.server()

    /** Drives to BROWSE root and returns the driver. */
    private fun browsing(): Driver = Driver()
        .send(Event.ServersChanged(listOf(server)))
        .input(Fx.confirm)

    private fun gridIndex(d: Driver) = d.state.browse.top!!.gridFocusIndex

    @Test
    fun gridNavClampsFocusAtBothEnds() {
        val d = browsing()
        d.send(Event.BrowseLoaded("0", Fx.result("0", items = Fx.videos(2))))
        d.send(Event.GazeMoved(GazeTarget.GridCell(0)))

        d.input(Fx.up) // top grid row, no move
        assertThat(gridIndex(d)).isEqualTo(0)

        repeat(10) { d.input(Fx.right) } // only 2 videos, both in the first row
        assertThat(gridIndex(d)).isEqualTo(1)
    }

    @Test
    fun reloadWithFewerRowsClampsThePersistedGridFocus() {
        val d = browsing()
        d.send(Event.BrowseLoaded("0", Fx.result("0", items = Fx.videos(10))))
        d.send(Event.GazeMoved(GazeTarget.GridCell(7)))
        assertThat(gridIndex(d)).isEqualTo(7)

        // A refresh returns a shorter listing.
        d.send(Event.BrowseLoaded("0", Fx.result("0", items = Fx.videos(3))))
        assertThat(gridIndex(d)).isEqualTo(2)
    }

    @Test
    fun poppingTheStackRestoresTheParentSidebarCursor() {
        val d = browsing()
        d.send(
            Event.BrowseLoaded(
                "0",
                Fx.result("0", containers = listOf(Fx.container("c1"), Fx.container("c2"), Fx.container("c3"))),
            ),
        )
        d.input(Fx.down) // sidebar focus -> c2
        assertThat(d.state.browse.top!!.focus).isEqualTo(BrowseFocus.Sidebar(1))

        d.input(Fx.confirm) // descend into c2
        d.send(Event.BrowseLoaded("c2", Fx.result("c2", items = Fx.videos(2))))
        d.send(Event.GazeMoved(GazeTarget.GridCell(1))) // move the child cursor

        d.input(Fx.cancel) // pop back to the parent
        assertThat(d.state.browse.depth).isEqualTo(1)
        assertThat(d.state.browse.top!!.objectId).isEqualTo("0")
        assertThat(d.state.browse.top!!.focus).isEqualTo(BrowseFocus.Sidebar(1))
    }

    @Test
    fun pageNextTopsUpTheFetchWhenNearTheEndOfTheLoadedGrid() {
        val d = browsing()
        d.send(Event.BrowseLoaded("0", Fx.result("0", items = Fx.videos(10), total = 300)))

        // First page-next jumps a page within the loaded videos, no fetch yet.
        d.input(com.daydreamvr.vrcore.input.InputAction.PageDown)
        assertThat(d.state.browse.top!!.gridFocusIndex).isEqualTo(6)
        val fetch = d.stepEffects.filterIsInstance<Effect.BrowseNode>().single()
        assertThat(fetch.objectId).isEqualTo("0")
        assertThat(fetch.page).isEqualTo(PageRequest(10, AppStateMachine.PAGE_FETCH))

        // The appended page grows the frame without disturbing the cursor.
        d.send(Event.BrowseLoaded("0", Fx.result("0", items = Fx.videos(5, from = 11), total = 300), append = true))
        assertThat(d.state.browse.top!!.loadedCount).isEqualTo(15)
        assertThat(d.state.browse.top!!.gridFocusIndex).isEqualTo(6)
    }
}
