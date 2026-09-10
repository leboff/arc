package com.daydreamvr.player.state

import com.daydreamvr.vrcore.input.InputAction
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for browse view mode toggle (grid <-> list) and list navigation (kanban t_4c330258).
 */
class BrowseViewModeTest {

    private val frame = Fx.localFrame(
        folders = (0 until 3).map { Fx.folderNode("f$it") },
        videos = (0 until 12).map { Fx.videoNode("v$it", title = "Video $it") },
    )

    private fun from(focus: BrowseFocus = BrowseFocus.Grid(2), viewMode: BrowseViewMode = BrowseViewMode.GRID): Driver =
        Driver(Fx.browsing(frame.copy(focus = focus, gridReturn = (focus as? BrowseFocus.Grid)?.index ?: 0, viewMode = viewMode)))

    @Test
    fun defaultViewModeIsGrid() {
        val d = from()
        assertThat(d.state.browse.top!!.viewMode).isEqualTo(BrowseViewMode.GRID)
    }

    @Test
    fun toggleViewModeFlipsGridAndList() {
        val d = from()
        d.send(Event.Ui(UiIntent.ToggleViewMode))
        assertThat(d.state.browse.top!!.viewMode).isEqualTo(BrowseViewMode.LIST)

        d.send(Event.Ui(UiIntent.ToggleViewMode))
        assertThat(d.state.browse.top!!.viewMode).isEqualTo(BrowseViewMode.GRID)
    }

    @Test
    fun dockViewModeButtonTogglesMode() {
        val d = from(focus = BrowseFocus.Dock(GazeTarget.Dock.VIEW_MODE))
        d.input(Fx.confirm)
        assertThat(d.state.browse.top!!.viewMode).isEqualTo(BrowseViewMode.LIST)

        d.input(Fx.confirm)
        assertThat(d.state.browse.top!!.viewMode).isEqualTo(BrowseViewMode.GRID)
    }

    @Test
    fun r3CycleProjectionKeyTogglesModeInBrowser() {
        val d = from()
        d.input(InputAction.CycleProjection)
        assertThat(d.state.browse.top!!.viewMode).isEqualTo(BrowseViewMode.LIST)

        d.input(InputAction.CycleProjection)
        assertThat(d.state.browse.top!!.viewMode).isEqualTo(BrowseViewMode.GRID)
    }

    @Test
    fun focusAndSelectionAreRetainedAcrossToggle() {
        val d = from(focus = BrowseFocus.Grid(4))
        val targetVideoId = d.state.browse.top!!.focusedVideo?.id

        d.send(Event.Ui(UiIntent.ToggleViewMode))
        val topList = d.state.browse.top!!
        assertThat(topList.viewMode).isEqualTo(BrowseViewMode.LIST)
        assertThat(topList.focus).isEqualTo(BrowseFocus.Grid(4))
        assertThat(topList.focusedVideo?.id).isEqualTo(targetVideoId)

        d.send(Event.Ui(UiIntent.ToggleViewMode))
        val topGrid = d.state.browse.top!!
        assertThat(topGrid.viewMode).isEqualTo(BrowseViewMode.GRID)
        assertThat(topGrid.focus).isEqualTo(BrowseFocus.Grid(4))
        assertThat(topGrid.focusedVideo?.id).isEqualTo(targetVideoId)
    }

    @Test
    fun listModeNavigatesItemByItemVertically() {
        val d = from(focus = BrowseFocus.Grid(0), viewMode = BrowseViewMode.LIST)

        // Down advances by 1 item in list mode
        d.input(Fx.down)
        assertThat(d.state.browse.top!!.focus).isEqualTo(BrowseFocus.Grid(1))
        d.input(Fx.down)
        assertThat(d.state.browse.top!!.focus).isEqualTo(BrowseFocus.Grid(2))

        // Up retreats by 1 item
        d.input(Fx.up)
        assertThat(d.state.browse.top!!.focus).isEqualTo(BrowseFocus.Grid(1))

        // Up at index 0 stays at 0
        d.input(Fx.up)
        assertThat(d.state.browse.top!!.focus).isEqualTo(BrowseFocus.Grid(0))
        d.input(Fx.up)
        assertThat(d.state.browse.top!!.focus).isEqualTo(BrowseFocus.Grid(0))
    }

    @Test
    fun listModeTraversesAcrossPageBoundaries() {
        val d = from(focus = BrowseFocus.Grid(5), viewMode = BrowseViewMode.LIST)
        assertThat(d.state.browse.top!!.gridPage).isEqualTo(0)

        // Down from bottom of page 0 (item 5) advances to page 1 top (item 6)
        d.input(Fx.down)
        assertThat(d.state.browse.top!!.focus).isEqualTo(BrowseFocus.Grid(6))
        assertThat(d.state.browse.top!!.gridPage).isEqualTo(1)

        // Up retreats across page boundary
        d.input(Fx.up)
        assertThat(d.state.browse.top!!.focus).isEqualTo(BrowseFocus.Grid(5))
        assertThat(d.state.browse.top!!.gridPage).isEqualTo(0)
    }

    @Test
    fun listModeLeftGoesToSidebarRightGoesToInspector() {
        val d = from(focus = BrowseFocus.Grid(3), viewMode = BrowseViewMode.LIST)

        // Left enters sidebar
        d.input(Fx.left)
        assertThat(d.state.browse.top!!.focus).isInstanceOf(BrowseFocus.Sidebar::class.java)

        // Return to list
        d.input(Fx.right)
        assertThat(d.state.browse.top!!.focus).isEqualTo(BrowseFocus.Grid(3))

        // Right enters inspector
        d.input(Fx.right)
        assertThat(d.state.browse.top!!.focus).isEqualTo(BrowseFocus.Inspector(GazeTarget.Action.PLAY))

        // Left returns to list
        d.input(Fx.left)
        assertThat(d.state.browse.top!!.focus).isEqualTo(BrowseFocus.Grid(3))
    }

    @Test
    fun subfolderPreservesActiveViewMode() {
        val localMedia = LocalMedia(
            folders = listOf(Fx.folderNode("f0", "Folder 0", childCount = 2)),
            byFolder = mapOf("f0" to listOf(Fx.videoNode("sub0"), Fx.videoNode("sub1"))),
            loaded = true,
        )
        val rootFrame = Fx.localFrame(
            folders = listOf(Fx.folderNode("f0")),
            videos = emptyList(),
            viewMode = BrowseViewMode.LIST,
        )
        val d = Driver(Fx.browsing(rootFrame).copy(localMedia = localMedia))

        d.send(Event.Ui(UiIntent.SelectFolder("f0")))
        assertThat(d.state.browse.top!!.viewMode).isEqualTo(BrowseViewMode.LIST)
    }
}
