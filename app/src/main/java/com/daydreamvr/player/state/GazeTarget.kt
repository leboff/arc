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

    data class KeyboardKey(val row: Int, val col: Int) : GazeTarget
}
