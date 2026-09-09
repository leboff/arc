package com.daydreamvr.vrcore.ui

/**
 * A pixel rectangle on a panel bound to a target id (UI_GAZE_PLAN.md §2.4).
 *
 * The single idea that makes gaze hit-testing trustworthy: the code that draws a
 * row is the code that publishes its rectangle, from the same `measureLayout`
 * call, so drawing and hit-testing cannot drift.
 *
 * Half-open on all sides: `[left, right) × [top, bottom)`.
 */
data class HitRegion<out T>(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val id: T,
) {
    fun contains(x: Float, y: Float): Boolean =
        x >= left && x < right && y >= top && y < bottom
}

/** An ordered set of [HitRegion]s. Later regions sit on top (last match wins). */
class HitMap<T>(val regions: List<HitRegion<T>> = emptyList()) {

    fun hitTest(xPx: Float, yPx: Float): T? {
        for (i in regions.indices.reversed()) {
            if (regions[i].contains(xPx, yPx)) return regions[i].id
        }
        return null
    }

    val isEmpty: Boolean get() = regions.isEmpty()

    companion object {
        private val EMPTY = HitMap<Any?>(emptyList())

        @Suppress("UNCHECKED_CAST")
        fun <T> empty(): HitMap<T> = EMPTY as HitMap<T>
    }
}
