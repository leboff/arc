package com.daydreamvr.player.state

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Controller navigation between the sidebar, the grid, the inspector and the
 * detached dock (UI_REDESIGN_REVIEWED_PLAN.md §10.5, M4 acceptance 4).
 */
class ColumnNavTest {

    private val frame = Fx.localFrame(
        folders = (0 until 3).map { Fx.folderNode("f$it") },
        videos = (0 until 6).map { Fx.videoNode("v$it") },
    )

    private fun from(focus: BrowseFocus): Driver =
        Driver(Fx.browsing(frame.copy(focus = focus, gridReturn = (focus as? BrowseFocus.Grid)?.index ?: 0)))

    private fun focus(d: Driver) = d.state.browse.top!!.focus

    @Test
    fun rightFromSidebarEntersTheGrid() {
        val d = from(BrowseFocus.Sidebar(1)).input(Fx.right)
        assertThat(focus(d)).isInstanceOf(BrowseFocus.Grid::class.java)
    }

    @Test
    fun rightFromTheGridsRightColumnEntersTheInspector() {
        val d = from(BrowseFocus.Grid(2)).input(Fx.right) // index 2 -> column 2
        assertThat(focus(d)).isEqualTo(BrowseFocus.Inspector(GazeTarget.Action.PLAY))
    }

    @Test
    fun leftFromTheInspectorReturnsToTheGrid() {
        val d = from(BrowseFocus.Inspector(GazeTarget.Action.PLAY)).input(Fx.left)
        assertThat(focus(d)).isInstanceOf(BrowseFocus.Grid::class.java)
    }

    @Test
    fun leftFromTheGridsLeftColumnEntersTheSidebar() {
        val d = from(BrowseFocus.Grid(3)).input(Fx.left) // index 3 -> column 0
        assertThat(focus(d)).isInstanceOf(BrowseFocus.Sidebar::class.java)
    }

    @Test
    fun downFromTheBottomGridRowAdvancesItemNotDock() {
        val d = from(BrowseFocus.Grid(1)).input(Fx.down) // top row -> bottom row (idx 4)
        assertThat(focus(d)).isEqualTo(BrowseFocus.Grid(4))
        d.input(Fx.down) // advances to next item within media content, never dock
        assertThat(focus(d)).isEqualTo(BrowseFocus.Grid(5))
        d.input(Fx.down) // at end of list, stays within media content
        assertThat(focus(d)).isEqualTo(BrowseFocus.Grid(5))
    }

    @Test
    fun upFromTheDockReturnsToThePreviousGridCell() {
        val d = Driver(Fx.browsing(frame.copy(focus = BrowseFocus.Dock(GazeTarget.Dock.RECENTER), gridReturn = 4)))
        d.input(Fx.up)
        assertThat(focus(d)).isEqualTo(BrowseFocus.Grid(4))
    }

    @Test
    fun downAndUpTraversesPagesAcrossItemBoundaries() {
        val multiPageFrame = Fx.localFrame(
            videos = (0 until 12).map { Fx.videoNode("v$it") },
        )
        val d = Driver(Fx.browsing(multiPageFrame.copy(focus = BrowseFocus.Grid(4), gridReturn = 4)))
        // From Page 0 row 1, down advances across page boundary to Page 1 row 0
        d.input(Fx.down)
        assertThat(focus(d)).isEqualTo(BrowseFocus.Grid(7))
        assertThat(d.state.browse.top!!.gridPage).isEqualTo(1)

        // Up crosses back to Page 0 row 1
        d.input(Fx.up)
        assertThat(focus(d)).isEqualTo(BrowseFocus.Grid(4))
        assertThat(d.state.browse.top!!.gridPage).isEqualTo(0)

        // Down to Page 1 row 0 (7) then row 1 (10)
        d.input(Fx.down)
        assertThat(focus(d)).isEqualTo(BrowseFocus.Grid(7))
        d.input(Fx.down)
        assertThat(focus(d)).isEqualTo(BrowseFocus.Grid(10))

        // Down at bottom of page advances to next item (11)
        d.input(Fx.down)
        assertThat(focus(d)).isEqualTo(BrowseFocus.Grid(11))

        // At end of content, stays on item and never drops to Dock
        d.input(Fx.down)
        assertThat(focus(d)).isEqualTo(BrowseFocus.Grid(11))
    }

    @Test
    fun dockIsExplicitTargetViaGaze() {
        val d = from(BrowseFocus.Grid(4))
        // Gaze explicitly targets the dock
        d.send(Event.GazeMoved(GazeTarget.DockButton(GazeTarget.Dock.RECENTER)))
        assertThat(focus(d)).isEqualTo(BrowseFocus.Dock(GazeTarget.Dock.RECENTER))
        // Up returns to media grid
        d.input(Fx.up)
        assertThat(focus(d)).isEqualTo(BrowseFocus.Grid(4))
    }

    @Test
    fun leftRightWalksTheDockButtons() {
        val d = from(BrowseFocus.Dock(GazeTarget.Dock.RECENTER)).input(Fx.right)
        assertThat(focus(d)).isEqualTo(BrowseFocus.Dock(GazeTarget.Dock.SETTINGS))
        d.input(Fx.left)
        assertThat(focus(d)).isEqualTo(BrowseFocus.Dock(GazeTarget.Dock.RECENTER))
    }

    @Test
    fun downAdvancesToIrregularLastPage() {
        val irregularFrame = Fx.localFrame(
            videos = (0 until 7).map { Fx.videoNode("v$it") },
        )
        // From col 0, 1, 2 on Page 0 row 1, down reaches the single item on Page 1 (item 6)
        val d0 = Driver(Fx.browsing(irregularFrame.copy(focus = BrowseFocus.Grid(3))))
        d0.input(Fx.down)
        assertThat(focus(d0)).isEqualTo(BrowseFocus.Grid(6))

        val d1 = Driver(Fx.browsing(irregularFrame.copy(focus = BrowseFocus.Grid(4))))
        d1.input(Fx.down)
        assertThat(focus(d1)).isEqualTo(BrowseFocus.Grid(6))

        val d2 = Driver(Fx.browsing(irregularFrame.copy(focus = BrowseFocus.Grid(5))))
        d2.input(Fx.down)
        assertThat(focus(d2)).isEqualTo(BrowseFocus.Grid(6))

        // From item 6, up goes back to col 0 on Page 0 row 1 (item 3)
        d2.input(Fx.up)
        assertThat(focus(d2)).isEqualTo(BrowseFocus.Grid(3))
    }

    @Test
    fun downAtEndOfLoadedGridFetchesMoreWhenServerHasPages() {
        val frameWithMore = Fx.localFrame(
            videos = (0 until 6).map { Fx.videoNode("v$it") },
            totalMatches = 50,
        )
        val d = Driver(Fx.browsing(frameWithMore.copy(focus = BrowseFocus.Grid(5))))
        // At last item of loaded batch, pressing down stays in content and triggers fetch
        d.input(Fx.down)
        assertThat(focus(d)).isEqualTo(BrowseFocus.Grid(5))
        val fetch = d.stepEffects.filterIsInstance<Effect.BrowseNode>().single()
        assertThat(fetch.page.start).isEqualTo(6)
    }

    @Test
    fun singleItemGridClampsWithoutDroppingToDock() {
        val singleFrame = Fx.localFrame(
            videos = listOf(Fx.videoNode("v0")),
        )
        val d = Driver(Fx.browsing(singleFrame.copy(focus = BrowseFocus.Grid(0))))
        d.input(Fx.down)
        assertThat(focus(d)).isEqualTo(BrowseFocus.Grid(0))
        d.input(Fx.up)
        assertThat(focus(d)).isEqualTo(BrowseFocus.Grid(0))
    }
}
