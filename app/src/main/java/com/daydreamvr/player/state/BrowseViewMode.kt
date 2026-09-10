package com.daydreamvr.player.state

/**
 * Centre column layout in the browse screen (kanban t_4c330258).
 * [GRID] displays 3x2 thumbnail cards with posters.
 * [LIST] displays a compact textual list with metadata and badges.
 */
enum class BrowseViewMode {
    GRID,
    LIST;

    fun toggle(): BrowseViewMode = when (this) {
        GRID -> LIST
        LIST -> GRID
    }
}
