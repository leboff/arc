package com.daydreamvr.player.state

import com.daydreamvr.upnp.model.PageRequest
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The browse-screen reducer in isolation (ARCHITECTURE.md §9.4, §12): focus
 * clamping, per-frame cursor restore when popping the stack, and lazy
 * pagination when the cursor nears the end of the loaded rows.
 */
class BrowseReducerTest {

    private val server = Fx.server()

    /** Drives to BROWSE root and returns the driver. */
    private fun browsing(): Driver = Driver()
        .send(Event.ServersChanged(listOf(server)))
        .input(Fx.confirm)

    @Test
    fun navClampsFocusAtBothEnds() {
        val d = browsing()
        d.send(Event.BrowseLoaded("0", Fx.result("0", items = Fx.videos(3))))

        d.input(Fx.up) // already at 0
        assertThat(d.state.browse.top!!.focusIndex).isEqualTo(0)

        repeat(10) { d.input(Fx.down) } // only 3 rows
        assertThat(d.state.browse.top!!.focusIndex).isEqualTo(2)
    }

    @Test
    fun reloadWithFewerRowsClampsThePersistedFocus() {
        val d = browsing()
        d.send(Event.BrowseLoaded("0", Fx.result("0", items = Fx.videos(10))))
        repeat(7) { d.input(Fx.down) }
        assertThat(d.state.browse.top!!.focusIndex).isEqualTo(7)

        // A refresh returns a shorter listing.
        d.send(Event.BrowseLoaded("0", Fx.result("0", items = Fx.videos(3))))
        assertThat(d.state.browse.top!!.focusIndex).isEqualTo(2)
    }

    @Test
    fun poppingTheStackRestoresTheParentCursor() {
        val d = browsing()
        d.send(
            Event.BrowseLoaded(
                "0",
                Fx.result("0", containers = listOf(Fx.container("c1"), Fx.container("c2"), Fx.container("c3"))),
            ),
        )
        d.input(Fx.down) // focus parent row 1 (c2)
        assertThat(d.state.browse.top!!.focusIndex).isEqualTo(1)

        d.input(Fx.right) // descend into c2
        d.send(Event.BrowseLoaded("c2", Fx.result("c2", items = Fx.videos(2))))
        d.input(Fx.down) // move the child cursor
        assertThat(d.state.browse.top!!.focusIndex).isEqualTo(1)

        d.input(Fx.left) // pop back to the parent
        assertThat(d.state.browse.depth).isEqualTo(1)
        assertThat(d.state.browse.top!!.objectId).isEqualTo("0")
        assertThat(d.state.browse.top!!.focusIndex).isEqualTo(1)
    }

    @Test
    fun pageDownScrollsThenFetchesTheNextPageWhenNearTheEnd() {
        val d = browsing()
        d.send(Event.BrowseLoaded("0", Fx.result("0", items = Fx.videos(10), total = 300)))

        // First page-down just jumps the cursor within the loaded rows.
        d.input(com.daydreamvr.vrcore.input.InputAction.PageDown)
        assertThat(d.state.browse.top!!.focusIndex).isEqualTo(8)
        assertThat(d.stepEffects).isEmpty()

        // The next one is close enough to the end to trigger a fetch.
        d.input(com.daydreamvr.vrcore.input.InputAction.PageDown)
        val fetch = d.stepEffects.filterIsInstance<Effect.Browse>().single()
        assertThat(fetch.objectId).isEqualTo("0")
        assertThat(fetch.page).isEqualTo(PageRequest(10, AppStateMachine.PAGE_FETCH))
        assertThat(d.state.browse.top!!.loading).isTrue()

        // The appended page grows the frame without disturbing the cursor.
        d.send(Event.BrowseLoaded("0", Fx.result("0", items = Fx.videos(5, from = 11), total = 300), append = true))
        assertThat(d.state.browse.top!!.loadedCount).isEqualTo(15)
        assertThat(d.state.browse.top!!.focusIndex).isEqualTo(8)
        assertThat(d.state.browse.top!!.loading).isFalse()
    }
}
