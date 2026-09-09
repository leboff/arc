package com.daydreamvr.upnp.model

/** A single page (or an accumulated set of pages) of a container's children. */
data class BrowseResult(
    val objectId: String,
    val containers: List<DidlContainer>,
    val items: List<DidlItem>,
    /** How many objects this response actually carried (drives pagination). */
    val numberReturned: Int,
    /** Server's claim of the container's total size; only trustworthy for the scrollbar. */
    val totalMatches: Int,
    val updateId: Long = 0L,
) {
    /** Containers first, then items — the order the browser screen renders them. */
    val objects: List<DidlObject> get() = containers + items

    /** Playable video items only (ARCHITECTURE.md §9.5). */
    val playableItems: List<DidlItem> get() = items.filter { it.isPlayableVideo }

    companion object {
        fun empty(objectId: String) = BrowseResult(objectId, emptyList(), emptyList(), 0, 0)
    }
}

/** `StartingIndex` / `RequestedCount` for a Browse. Never request count 0 (ARCHITECTURE.md §9.4). */
data class PageRequest(val start: Int, val count: Int) {
    init {
        require(start >= 0) { "start must be >= 0" }
        require(count > 0) { "count must be > 0 (0 is ambiguous across servers)" }
    }

    companion object {
        val DEFAULT = PageRequest(0, 200)
    }
}
