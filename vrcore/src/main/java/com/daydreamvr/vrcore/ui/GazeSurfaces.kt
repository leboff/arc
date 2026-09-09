package com.daydreamvr.vrcore.ui

/**
 * Nearest-hit resolution across N interactive surfaces at different depths
 * (UI_REDESIGN_REVIEWED_PLAN.md §3.3, R1).
 *
 * Pure — the same closed-form [PanelRaycast] the single-surface pass used, run
 * once per surface with the nearest bounded hit winning. Three properties it
 * guarantees, each an acceptance test:
 *
 *  * **Nearest wins** — a ray through two bounded quads reports the smaller `distanceM`.
 *  * **On-panel-off-target** — a hit with no matching region yields a `null`
 *    target that does **not** fall through to a farther surface.
 *  * It never allocates per frame beyond the small [Resolved] holder.
 */
object GazeSurfaces {

    /** One candidate surface: where it is, and how to turn a pixel into a target. */
    class Surface<T>(
        val geometry: PanelGeometry,
        val hitTest: (xPx: Float, yPx: Float) -> T?,
    )

    data class Resolved<T>(val hit: PanelHit?, val target: T?)

    fun <T> resolve(ray: Ray, surfaces: List<Surface<T>>): Resolved<T> {
        var best: PanelHit? = null
        var bestTarget: T? = null
        for (s in surfaces) {
            val h = PanelRaycast.intersect(ray, s.geometry) ?: continue
            val current = best
            if (current != null && h.distanceM >= current.distanceM) continue
            best = h
            bestTarget = s.hitTest(h.xPx, h.yPx)
        }
        return Resolved(best, bestTarget)
    }
}
