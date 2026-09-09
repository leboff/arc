package com.daydreamvr.player.state

import com.daydreamvr.player.media.MediaSource
import com.daydreamvr.player.media.local.MediaPermission

/**
 * Which region of the 3-column browse panel the controller is currently driving
 * (UI_REDESIGN_REVIEWED_PLAN.md §10.1). Replaces the bare `focusIndex` for the
 * PLAY'A layout; the legacy flat list keeps its own `BrowseFrame.focusIndex`
 * during the strangler.
 */
sealed interface BrowseFocus {
    /** The vertical source switcher at the top of the left sidebar. */
    data class Source(val index: Int) : BrowseFocus

    /** A folder row in the left sidebar. */
    data class Sidebar(val index: Int) : BrowseFocus

    /** A card in the centre media grid — [index] is absolute. */
    data class Grid(val index: Int) : BrowseFocus

    /** An action button in the right inspector. */
    data class Inspector(val action: GazeTarget.Action) : BrowseFocus

    /** A button on the detached system dock. */
    data class Dock(val button: GazeTarget.Dock) : BrowseFocus

    /** A chip in the toolbar band (sort / filter / view). */
    data class Toolbar(val chip: GazeTarget.Chip) : BrowseFocus
}

/** Grid sort order. [label] is what the toolbar chip shows. */
enum class SortOrder(val label: String) {
    TITLE_ASC("Name"),
    DATE_DESC("Date"),
    DURATION_DESC("Length"),
    SIZE_DESC("Size"),
    ;

    fun next(): SortOrder = entries[(ordinal + 1) % entries.size]
}

/** The available media sources and which one is selected (§10.1). */
data class SourcesState(
    val available: List<MediaSource> = emptyList(),
    val selectedId: String? = null,
    val localPermission: MediaPermission.Grant = MediaPermission.Grant.DENIED,
    val focusIndex: Int = 0,
)
