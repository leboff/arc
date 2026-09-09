package com.daydreamvr.vrcore.distortion

import com.daydreamvr.vrcore.gl.Mesh
import com.daydreamvr.vrcore.profile.DeviceProfile
import com.daydreamvr.vrcore.render.Eye
import com.daydreamvr.vrcore.render.EyeParams
import kotlin.math.hypot

/**
 * The pre-warp geometry for lens distortion correction (ARCHITECTURE.md §6.6).
 *
 * Pure — no GL. [build] returns an interleaved [Mesh] whose vertex positions are a
 * regular grid in the eye's clip space `[-1, 1]²` and whose per-channel texture
 * coordinates are pulled toward the lens optical centre by the *inverse* of the
 * lens radial distortion, so sampling the rendered (undistorted) eye texture
 * through this mesh and letting the optics act cancels the pincushion.
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

    /** `1 + k1 r² + k2 r⁴` scaled by `r`. [k] is `[k1, k2]`. */
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
    fun build(eye: EyeParams, profile: DeviceProfile, gridSize: Int = 40): Mesh {
        val verts = buildVertices(eye, profile, gridSize)
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
    fun buildVertices(eye: EyeParams, profile: DeviceProfile, gridSize: Int = 40): FloatArray {
        require(gridSize >= 1)
        val k = profile.distortionK
        val chroma = profile.chromaticScale ?: NO_CHROMA

        // Lens optical centre in the eye's clip space. It sits inboard (toward the
        // divider) of the viewport centre by `1 - 2·ILD/W` of the half-width
        // (ARCHITECTURE.md §6.4); the sign flips between eyes. Vertical centre is
        // treated as the viewport centre — the profile carries no display height.
        val inboard = (1f - 2f * profile.interLensDistanceM / DISPLAY_WIDTH_REF_M)
            .coerceIn(-0.4f, 0.4f)
        val lensCx = if (eye.eye == Eye.LEFT) inboard else -inboard
        val lensCy = 0f

        val verts = FloatArray(gridSize * gridSize * 6 * FLOATS_PER_VERTEX)
        var w = 0

        fun emit(i: Int, j: Int) {
            val fu = i.toFloat() / gridSize
            val fv = j.toFloat() / gridSize
            val px = 2f * fu - 1f
            val py = 2f * fv - 1f

            val dx = px - lensCx
            val dy = py - lensCy
            val r = hypot(dx, dy)
            val rTex = undistort(r, k)
            val invR = if (r > 1e-6f) 1f / r else 0f

            verts[w++] = px
            verts[w++] = py
            for (c in 0..2) {
                val rc = rTex * chroma[c]
                val tx = lensCx + dx * invR * rc
                val ty = lensCy + dy * invR * rc
                verts[w++] = (tx + 1f) * 0.5f
                verts[w++] = (ty + 1f) * 0.5f
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

    /** Reference display width for the lens-offset fraction (a 6.3" 20:9 panel, §6.4). */
    private const val DISPLAY_WIDTH_REF_M = 0.1406f

    private val NO_CHROMA = floatArrayOf(1f, 1f, 1f)
}
