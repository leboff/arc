package com.daydreamvr.player.state

/**
 * A user intent, produced by EITHER a gaze-activate or a controller Confirm
 * (UI_REDESIGN_REVIEWED_PLAN.md §10.2). One path, two producers: the controller
 * path runs its focus through [AppStateMachine.intentForFocus] so an A-press and
 * a gaze-activate are literally the same reducer code.
 */
sealed interface UiIntent {
    data class SelectSource(val sourceId: String) : UiIntent
    data class SelectFolder(val folderId: String) : UiIntent
    data class SelectGridCell(val index: Int) : UiIntent

    /** Cycle the grid sort order, keeping the focused video fixed (R18). */
    data object SelectSort : UiIntent

    data object PageNext : UiIntent
    data object PagePrev : UiIntent

    data class NavigateBreadcrumb(val depth: Int) : UiIntent

    /** Opens the selectable [Overlay.ProjectionChooser] for the focused video (kanban t_af6bc99f). */
    data object OpenProjectionChooser : UiIntent

    data class PlayVideo(val fromStart: Boolean) : UiIntent

    data class DockAction(val button: GazeTarget.Dock) : UiIntent

    /** Toggles between thumbnail grid and compact textual list view (kanban t_4c330258). */
    data object ToggleViewMode : UiIntent
}
