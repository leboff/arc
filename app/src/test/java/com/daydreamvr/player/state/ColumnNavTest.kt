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
    fun downFromTheBottomGridRowMovesToTheDock() {
        val d = from(BrowseFocus.Grid(1)).input(Fx.down) // top row -> bottom row (idx 4)
        assertThat(focus(d)).isEqualTo(BrowseFocus.Grid(4))
        d.input(Fx.down) // bottom row -> dock
        assertThat(focus(d)).isEqualTo(BrowseFocus.Dock(GazeTarget.Dock.RECENTER))
    }

    @Test
    fun upFromTheDockReturnsToThePreviousGridCell() {
        val d = from(BrowseFocus.Grid(1)).input(Fx.down).input(Fx.down) // -> Grid(4) -> Dock
        assertThat(focus(d)).isEqualTo(BrowseFocus.Dock(GazeTarget.Dock.RECENTER))
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
}
