package com.daydreamvr.player.state

import com.daydreamvr.upnp.model.PageRequest
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Grid *display* paging is derived from a single divisor and never merges with
 * UPnP *fetch* paging (UI_REDESIGN_REVIEWED_PLAN.md §10.1, §10.4, R8,
 * M4 acceptance 2 & 3).
 */
class GridPageTest {

    private fun frame(size: Int, total: Int = size) = Fx.localFrame(
        videos = (0 until size).map { Fx.videoNode("v$it", title = "v%03d".format(it)) },
        totalMatches = total,
    )

    @Test
    fun gridPageIsFocusIndexOverSix() {
        val f = frame(41)
        for (i in 0..40) {
            assertThat(f.copy(focus = BrowseFocus.Grid(i)).gridPage).isEqualTo(i / 6)
        }
    }

    @Test
    fun pageCountRoundsUpAndIsAtLeastOne() {
        mapOf(0 to 1, 1 to 1, 6 to 1, 7 to 2, 12 to 2).forEach { (size, expected) ->
            assertThat(frame(size).pageCount).isEqualTo(expected)
        }
    }

    @Test
    fun pageNextAtLastPageIsAFocusNoOpButStillTopsUpTheFetch() {
        // 7 videos, last page, focus already on the last cell, more pages on the server.
        val d = Driver(Fx.browsing(frame(size = 7, total = 300).copy(focus = BrowseFocus.Grid(6))))
        d.send(Event.Ui(UiIntent.PageNext))

        assertThat(d.state.browse.top!!.gridFocusIndex).isEqualTo(6) // no-op on focus
        val fetch = d.stepEffects.filterIsInstance<Effect.BrowseNode>().single()
        assertThat(fetch.page).isEqualTo(PageRequest(7, AppStateMachine.PAGE_FETCH))
    }

    @Test
    fun pageNextDoesNotFetchWhenEverythingIsLoaded() {
        val d = Driver(Fx.browsing(frame(size = 7).copy(focus = BrowseFocus.Grid(6))))
        d.send(Event.Ui(UiIntent.PageNext))
        assertThat(d.stepEffects.filterIsInstance<Effect.BrowseNode>()).isEmpty()
    }

    @Test
    fun pageNextAdvancesAFullPageWithinLoadedVideos() {
        val d = Driver(Fx.browsing(frame(size = 20).copy(focus = BrowseFocus.Grid(0))))
        d.send(Event.Ui(UiIntent.PageNext))
        assertThat(d.state.browse.top!!.gridFocusIndex).isEqualTo(6)
        d.send(Event.Ui(UiIntent.PagePrev))
        assertThat(d.state.browse.top!!.gridFocusIndex).isEqualTo(0)
    }
}
