package com.daydreamvr.player.state

/**
 * What the gaze reticle can be pointed at (UI_GAZE_PLAN.md §3.2).
 *
 * Every index is **absolute** (`scrollTop + i`), so the reducer never has to
 * know anything about scrolling to turn a hover into a focus move.
 */
sealed interface GazeTarget {
    /** Server list, including the two trailing footer actions. */
    data class ServerRow(val index: Int) : GazeTarget

    /** Absolute browse row index, not screen-relative. */
    data class BrowseRow(val index: Int) : GazeTarget

    data class SettingsRow(val index: Int) : GazeTarget

    data class HudControl(val index: Int) : GazeTarget

    data class DialogButton(val index: Int) : GazeTarget

    /** Absolute row index in [Overlay.ProjectionChooser.OPTIONS]. */
    data class ProjectionOption(val index: Int) : GazeTarget

    data class KeyboardKey(val row: Int, val col: Int) : GazeTarget

    // ---- 3-column PLAY'A browse layout (UI_REDESIGN_REVIEWED_PLAN.md §6) ----

    /** Source switcher rows in the left sidebar: 0=Device 1=Network 2=Favourites. */
    data class SourceTab(val index: Int) : GazeTarget

    /** Absolute folder index in the left sidebar. */
    data class SidebarRow(val index: Int) : GazeTarget

    /** Absolute item index in the centre media grid (`page * 6 + slot`). */
    data class GridCell(val index: Int) : GazeTarget

    data class BreadcrumbSegment(val depth: Int) : GazeTarget

    data class ToolbarChip(val chip: Chip) : GazeTarget

    data class PageButton(val forward: Boolean) : GazeTarget

    data class InspectorAction(val action: Action) : GazeTarget

    data class DockButton(val button: Dock) : GazeTarget

    enum class Chip { SORT, FILTER, VIEW }
    enum class Action { PLAY, RESUME, PROJECTION }
    enum class Dock { RECENTER, SETTINGS, CALIBRATE, VIEW_MODE, RESCAN, EXIT }
}
