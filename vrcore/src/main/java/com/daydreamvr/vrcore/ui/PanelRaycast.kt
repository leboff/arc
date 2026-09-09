package com.daydreamvr.vrcore.ui

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Where a gaze [Ray] lands on a panel (UI_GAZE_PLAN.md §2.3). */
data class PanelHit(
    val u: Float,
    val v: Float,
    val xPx: Float,
    val yPx: Float,
    /** `t` along the ray — the reticle's depth. */
    val distanceM: Float,
    val wx: Float,
    val wy: Float,
    val wz: Float,
)

/**
 * Ray × [PanelGeometry] → pixel. Pure closed-form geometry, no `Matrix`
 * (UI_GAZE_PLAN.md §1.4–§1.7). For `R = d` a curved panel lies exactly on a
 * vertical cylinder of radius `d` about the world origin, which is what makes
 * this a quadratic rather than a mesh test.
 */
object PanelRaycast {

    private const val EPS = 1e-4f

    /** Null when the ray misses the quad's `[0,1]²` extent. */
    fun intersect(ray: Ray, panel: PanelGeometry): PanelHit? =
        solve(ray, panel)?.takeIf { it.u in 0f..1f && it.v in 0f..1f }

    /** Same, but returns the surface point even outside the extent (reticle fallback). */
    fun intersectUnbounded(ray: Ray, panel: PanelGeometry): PanelHit? = solve(ray, panel)

    private fun solve(ray: Ray, panel: PanelGeometry): PanelHit? =
        if (panel.curved) solveCurved(ray, panel) else solveFlat(ray, panel)

    private fun solveCurved(ray: Ray, panel: PanelGeometry): PanelHit? {
        val d = panel.distanceM
        val a = ray.dx * ray.dx + ray.dz * ray.dz
        if (a < EPS * EPS) return null // looking straight up / down
        val b = 2f * (ray.ox * ray.dx + ray.oz * ray.dz)
        val c = ray.ox * ray.ox + ray.oz * ray.oz - d * d
        val disc = b * b - 4f * a * c
        if (disc <= 0f) return null
        val t = (-b + sqrt(disc)) / (2f * a)
        if (t <= 0f) return null

        val px = ray.ox + t * ray.dx
        val py = ray.oy + t * ray.dy
        val pz = ray.oz + t * ray.dz

        val psiHit = atan2(px, -pz)
        val theta = wrapPi(psiHit + panel.modelYawRad)
        val arc = panel.widthM / d
        val u = theta / arc + 0.5f
        val v = (py - panel.verticalOffsetM) / panel.heightM + 0.5f
        return toHit(u, v, t, px, py, pz, panel)
    }

    private fun solveFlat(ray: Ray, panel: PanelGeometry): PanelHit? {
        val a = panel.modelYawRad
        val ca = cos(a)
        val sa = sin(a)
        // Centre C = R_y(a)·(0, y0, -d); normal N = R_y(a)·(0,0,1); right = R_y(a)·(1,0,0)
        val cx = -panel.distanceM * sa
        val cy = panel.verticalOffsetM
        val cz = -panel.distanceM * ca
        val nx = sa
        val nz = ca
        val den = nx * ray.dx + nz * ray.dz
        if (kotlin.math.abs(den) < 1e-6f) return null
        val tNum = nx * (cx - ray.ox) + nz * (cz - ray.oz)
        val t = tNum / den
        if (t <= 0f) return null
        val px = ray.ox + t * ray.dx
        val py = ray.oy + t * ray.dy
        val pz = ray.oz + t * ray.dz
        val rx = ca
        val rz = -sa
        val u = ((px - cx) * rx + (pz - cz) * rz) / panel.widthM + 0.5f
        val v = (py - cy) / panel.heightM + 0.5f
        return toHit(u, v, t, px, py, pz, panel)
    }

    private fun toHit(u: Float, v: Float, t: Float, px: Float, py: Float, pz: Float, panel: PanelGeometry): PanelHit =
        PanelHit(
            u = u,
            v = v,
            xPx = u * panel.widthPx,
            yPx = (1f - v) * panel.heightPx,
            distanceM = t,
            wx = px,
            wy = py,
            wz = pz,
        )

    private fun wrapPi(a: Float): Float {
        var x = a
        val twoPi = (2.0 * PI).toFloat()
        val pi = PI.toFloat()
        while (x > pi) x -= twoPi
        while (x < -pi) x += twoPi
        return x
    }
}
