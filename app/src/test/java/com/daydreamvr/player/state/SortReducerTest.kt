package com.daydreamvr.player.state

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Cycling the grid sort must keep the *same video* focused, even though its
 * index moves (UI_REDESIGN_REVIEWED_PLAN.md §10.4, R18, M4 acceptance 1).
 */
class SortReducerTest {

    private val videos = listOf(
        Fx.videoNode("a", title = "Banana", dateModifiedMs = 100, durationMs = 10, sizeBytes = 10),
        Fx.videoNode("b", title = "Apple", dateModifiedMs = 300, durationMs = 30, sizeBytes = 5),
        Fx.videoNode("c", title = "Cherry", dateModifiedMs = 200, durationMs = 20, sizeBytes = 30),
    )

    @Test
    fun cyclingSortKeepsTheSameVideoFocused() {
        // Under TITLE_ASC the order is Apple(b), Banana(a), Cherry(c); focus "a" at index 1.
        var d = Driver(Fx.browsing(Fx.localFrame(videos = videos, focus = BrowseFocus.Grid(1))))
        assertThat(d.state.browse.top!!.focusedVideo!!.id).isEqualTo("a")

        val seenIndices = mutableSetOf<Int>()
        repeat(SortOrder.entries.size) {
            d = d.send(Event.Ui(UiIntent.SelectSort))
            val top = d.state.browse.top!!
            assertThat(top.focusedVideo!!.id).isEqualTo("a")
            assertThat((top.focus as BrowseFocus.Grid).index).isEqualTo(top.gridFocusIndex)
            seenIndices += top.gridFocusIndex
        }

        // The focused index genuinely moved during the cycle.
        assertThat(seenIndices.size).isGreaterThan(1)
        // A full cycle returns to the starting sort order.
        assertThat(d.state.browse.top!!.sort).isEqualTo(SortOrder.TITLE_ASC)
    }
}
