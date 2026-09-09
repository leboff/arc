package com.daydreamvr.vrcore.distortion

import com.daydreamvr.vrcore.gl.Mesh
import com.daydreamvr.vrcore.render.EyeParams
import com.daydreamvr.vrcore.optics.RadialCoefficients
import com.daydreamvr.vrcore.optics.RadialDistortion
import com.daydreamvr.vrcore.optics.Vec2

/**
 * The pre-warp geometry for lens distortion correction (ARCHITECTURE.md §6.6).
 *
 * Pure — no GL. [build] returns an interleaved [Mesh] whose vertex positions are a
 * regular destination-screen grid in the eye's clip space `[-1, 1]²`. Each grid
 * point is converted to a physical panel point, then to a screen tangent and
 * finally through the *forward* screen-tangent-to-ray-tangent polynomial.
 *
 * Radial model is Brown–Conrady, even terms only:
 *
 * ```
 * r' = r · (1 + k1·r² + k2·r⁴)
 * ```
 *
 * with `r` measured from the lens optical centre, not the viewport centre. The
 * forward map [distort] takes a texture radius to a screen radius; [undistort]
 * inverts it with a damped Newton iteration (≤ 6 steps), which is what the mesh
 * needs: for each regular screen-space vertex it asks "which texture radius lands
 * here".
 */
object DistortionMesh {

    /** Interleaved layout: `pos.xy`, then `uv` for the R, G and B channels. */
    const val FLOATS_PER_VERTEX = 8

    /** Legacy scalar helper; production uses [RadialDistortion]. */
    fun distort(r: Float, k: FloatArray): Float {
        val r2 = r * r
        return r * (1f + k[0] * r2 + k[1] * r2 * r2)
    }

    /**
     * Inverse of [distort]: the texture radius whose [distort] is [rDistorted].
     *
     * Newton's method on `f(r) = distort(r) - rDistorted`, seeded at
     * `min(rDistorted, 1)` — the true radius never sits far above the normalised
     * edge, and that seed keeps every built-in profile inside 1e-4 in ≤ 6 steps
     * (the early over-shoot you get from seeding at `rDistorted` itself is what
     * blows the budget at `r → 1.2`).
     */
    fun undistort(rDistorted: Float, k: FloatArray, maxSteps: Int = 6): Float {
        if (rDistorted <= 0f) return 0f
        var r = if (rDistorted < 1f) rDistorted else 1f
        repeat(maxSteps) {
            val r2 = r * r
            val f = r * (1f + k[0] * r2 + k[1] * r2 * r2) - rDistorted
            val df = 1f + 3f * k[0] * r2 + 5f * k[1] * r2 * r2
            r -= f / df
            if (r < 0f) r = 0f
        }
        return r
    }

    /**
     * Builds the `gridSize × gridSize`-quad warp mesh for one [eye]. Wraps
     * [buildVertices] in a GL [Mesh]; needs a GL context.
     */
    fun build(eye: EyeParams, gridSize: Int = 40): Mesh {
        val verts = buildVertices(eye, gridSize)
        return Mesh(verts, verts.size / FLOATS_PER_VERTEX, FLOATS_PER_VERTEX * Float.SIZE_BYTES)
    }

    /**
     * Pure, JVM-testable `[pos.xy, uvR, uvG, uvB]` triangle soup for one [eye].
     *
     * Vertex positions tile the eye's clip rectangle `[-1, 1]²` on a regular grid;
     * texture coordinates are the radially-compressed sample points, computed once
     * per channel so a non-null [DeviceProfile.chromaticScale] gives the R/G/B
     * lookups their own radial scale (ARCHITECTURE.md §6.6).
     */
    fun buildVertices(eye: EyeParams, gridSize: Int = 40): FloatArray {
        require(gridSize >= 1)
        val optics = requireNotNull(eye.optics) { "physical EyeOptics is required" }
        val bounds = optics.sourceBounds

        val verts = FloatArray(gridSize * gridSize * 6 * FLOATS_PER_VERTEX)
        var w = 0

        fun emit(i: Int, j: Int) {
            val fu = i.toFloat() / gridSize
            val fv = j.toFloat() / gridSize
            val px = 2f * fu - 1f
            val py = 2f * fv - 1f

            val panel = Vec2(
                optics.panelBottomLeftM.x + fu * (optics.panelTopRightM.x - optics.panelBottomLeftM.x),
                optics.panelBottomLeftM.y + fv * (optics.panelTopRightM.y - optics.panelBottomLeftM.y),
            )
            val screen = Vec2((panel.x - optics.lensCenterPanelM.x) / optics.screenToLensM, (panel.y - optics.lensCenterPanelM.y) / optics.screenToLensM)
            val ray = RadialDistortion.screenToRay(screen, optics.coefficients)
            val u = (ray.x - bounds.left) / (bounds.right - bounds.left)
            val v = (ray.y - bounds.bottom) / (bounds.top - bounds.bottom)

            verts[w++] = px
            verts[w++] = py
            for (c in 0..2) {
                verts[w++] = u.toFloat()
                verts[w++] = v.toFloat()
            }
        }

        for (i in 0 until gridSize) {
            for (j in 0 until gridSize) {
                emit(i, j); emit(i + 1, j); emit(i + 1, j + 1)
                emit(i, j); emit(i + 1, j + 1); emit(i, j + 1)
            }
        }
        return verts
    }

}
