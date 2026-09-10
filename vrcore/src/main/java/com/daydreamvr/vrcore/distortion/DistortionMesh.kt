package com.daydreamvr.vrcore.distortion

import com.daydreamvr.vrcore.gl.Mesh
import com.daydreamvr.vrcore.optics.OpticsValidationException
import com.daydreamvr.vrcore.optics.RadialCoefficients
import com.daydreamvr.vrcore.optics.RadialDistortion
import com.daydreamvr.vrcore.optics.Vec2
import com.daydreamvr.vrcore.render.EyeParams
import kotlin.math.hypot

/**
 * Pre-warp destination mesh geometry for lens distortion correction
 * (ARCHITECTURE.md §6.6 / DISTORTION_REMEDIATION_PLAN §2.2, §8.1).
 *
 * Destination vertices form a regular grid in eye viewport NDC `[-1, 1]²`.
 * For each vertex, the forward screen-tangent-to-ray-tangent polynomial is applied
 * to determine the exact source UV coordinate from the eye's render target.
 */
object DistortionMesh {

    /** Interleaved layout: `pos.xy`, then `uv` for R, G, B channels. */
    const val FLOATS_PER_VERTEX = 8

    /** Builds the GL [Mesh]; GL thread only. */
    fun build(eye: EyeParams, gridSize: Int = 40): Mesh {
        val verts = buildVertices(eye, gridSize)
        return Mesh(verts, verts.size / FLOATS_PER_VERTEX, FLOATS_PER_VERTEX * Float.SIZE_BYTES)
    }

    /**
     * Pure, JVM-testable `[pos.xy, uvR, uvG, uvB]` triangle soup for one [eye].
     * Vertex positions tile the eye's clip rectangle `[-1, 1]²` on a regular grid;
     * texture coordinates are evaluated with forward [RadialDistortion.screenToRay].
     */
    fun buildVertices(eye: EyeParams, gridSize: Int = 40): FloatArray {
        require(gridSize >= 1) { "gridSize must be >= 1 (got $gridSize)" }
        val optics = requireNotNull(eye.optics) { "Physical EyeOptics is required on EyeParams" }
        val bounds = optics.sourceBounds
        val d = optics.screenToLensM
        val cx = optics.lensCenterPanelM.x
        val cy = optics.lensCenterPanelM.y
        val p0x = optics.panelBottomLeftM.x
        val p0y = optics.panelBottomLeftM.y
        val p1x = optics.panelTopRightM.x
        val p1y = optics.panelTopRightM.y
        val spanX = p1x - p0x
        val spanY = p1y - p0y
        val boundsSpanX = bounds.right - bounds.left
        val boundsSpanY = bounds.top - bounds.bottom

        val verts = FloatArray(gridSize * gridSize * 6 * FLOATS_PER_VERTEX)
        var w = 0

        fun emit(i: Int, j: Int) {
            val fu = i.toDouble() / gridSize.toDouble()
            val fv = j.toDouble() / gridSize.toDouble()
            val px = (2.0 * fu - 1.0).toFloat()
            val py = (2.0 * fv - 1.0).toFloat()

            val panelX = p0x + fu * spanX
            val panelY = p0y + fv * spanY
            val screen = Vec2((panelX - cx) / d, (panelY - cy) / d)
            val ray = RadialDistortion.screenToRay(screen, optics.coefficients)

            val u = ((ray.x - bounds.left) / boundsSpanX).toFloat()
            val v = ((ray.y - bounds.bottom) / boundsSpanY).toFloat()

            if (!px.isFinite() || !py.isFinite() || !u.isFinite() || !v.isFinite()) {
                throw OpticsValidationException("Non-finite vertex attribute emitted at ($i, $j): px=$px, py=$py, u=$u, v=$v")
            }

            verts[w++] = px
            verts[w++] = py
            for (c in 0..2) {
                verts[w++] = u
                verts[w++] = v
            }
        }

        for (i in 0 until gridSize) {
            for (j in 0 until gridSize) {
                // Quad with 2 triangles, counter-clockwise / positive winding
                emit(i, j); emit(i + 1, j); emit(i + 1, j + 1)
                emit(i, j); emit(i + 1, j + 1); emit(i, j + 1)
            }
        }
        return verts
    }

    data class MeshEvaluation(
        val maxTexelError: Double,
        val positiveWinding: Boolean,
        val noDegenerateTriangles: Boolean,
        val allFinite: Boolean,
    ) {
        val passesGate: Boolean get() = positiveWinding && noDegenerateTriangles && allFinite && maxTexelError <= 0.25
    }

    /**
     * Independent Double analytic oracle evaluating triangle-interpolated UVs against
     * exact physical optics over a deterministic 9x9 barycentric lattice per triangle (§8.1).
     */
    fun evaluateMesh(eye: EyeParams, gridSize: Int, fboWidth: Int, fboHeight: Int): MeshEvaluation {
        val verts = buildVertices(eye, gridSize)
        val stride = FLOATS_PER_VERTEX
        val triangleCount = verts.size / (stride * 3)
        var maxTexelErr = 0.0
        var allWindingPositive = true
        var noDegenerate = true
        var allFinite = true

        val optics = requireNotNull(eye.optics)
        val bounds = optics.sourceBounds
        val d = optics.screenToLensM
        val cx = optics.lensCenterPanelM.x
        val cy = optics.lensCenterPanelM.y
        val p0x = optics.panelBottomLeftM.x
        val p0y = optics.panelBottomLeftM.y
        val p1x = optics.panelTopRightM.x
        val p1y = optics.panelTopRightM.y
        val spanX = p1x - p0x
        val spanY = p1y - p0y
        val boundsSpanX = bounds.right - bounds.left
        val boundsSpanY = bounds.top - bounds.bottom

        for (t in 0 until triangleCount) {
            val base = t * 3 * stride
            val ax = verts[base].toDouble(); val ay = verts[base + 1].toDouble()
            val au = verts[base + 2].toDouble(); val av = verts[base + 3].toDouble()

            val bx = verts[base + stride].toDouble(); val by = verts[base + stride + 1].toDouble()
            val bu = verts[base + stride + 2].toDouble(); val bv = verts[base + stride + 3].toDouble()

            val cxPos = verts[base + 2 * stride].toDouble(); val cyPos = verts[base + 2 * stride + 1].toDouble()
            val cu = verts[base + 2 * stride + 2].toDouble(); val cv = verts[base + 2 * stride + 3].toDouble()

            // 2D cross product for winding area
            val area2 = (bx - ax) * (cyPos - ay) - (by - ay) * (cxPos - ax)
            if (area2 <= 1e-9) {
                if (area2 <= 0.0) allWindingPositive = false
                noDegenerate = false
            }

            // 9x9 barycentric lattice
            for (stepA in 0..8) {
                for (stepB in 0..(8 - stepA)) {
                    val wA = stepA / 8.0
                    val wB = stepB / 8.0
                    val wC = 1.0 - wA - wB

                    val interpPx = wA * ax + wB * bx + wC * cxPos
                    val interpPy = wA * ay + wB * by + wC * cyPos
                    val interpU = wA * au + wB * bu + wC * cu
                    val interpV = wA * av + wB * bv + wC * cv

                    val fu = (interpPx + 1.0) / 2.0
                    val fv = (interpPy + 1.0) / 2.0
                    val panX = p0x + fu * spanX
                    val panY = p0y + fv * spanY
                    val screen = Vec2((panX - cx) / d, (panY - cy) / d)
                    val ray = RadialDistortion.screenToRay(screen, optics.coefficients)
                    val exactU = (ray.x - bounds.left) / boundsSpanX
                    val exactV = (ray.y - bounds.bottom) / boundsSpanY

                    if (!interpU.isFinite() || !interpV.isFinite() || !exactU.isFinite() || !exactV.isFinite()) {
                        allFinite = false
                        continue
                    }

                    // Only check texel error inside the valid sampling region [0, 1]²
                    if (exactU in 0.0..1.0 && exactV in 0.0..1.0) {
                        val errX = (interpU - exactU) * fboWidth.toDouble()
                        val errY = (interpV - exactV) * fboHeight.toDouble()
                        val texelErr = hypot(errX, errY)
                        if (texelErr > maxTexelErr) maxTexelErr = texelErr
                    }
                }
            }
        }

        return MeshEvaluation(maxTexelErr, allWindingPositive, noDegenerate, allFinite)
    }

    /**
     * Deterministic tessellation selection: starts at 40x40, doubling to 80, 160, 320 until
     * the <= 0.25 texel precision gate passes (§8.1).
     */
    fun selectGridSize(eye: EyeParams, fboWidth: Int, fboHeight: Int): Int {
        val candidates = intArrayOf(40, 80, 160, 320)
        for (grid in candidates) {
            val result = evaluateMesh(eye, grid, fboWidth, fboHeight)
            if (result.passesGate) return grid
        }
        throw OpticsValidationException(
            "Unsupported precision: 320x320 tessellation failed 0.25 texel gate for FBO ${fboWidth}x${fboHeight}"
        )
    }

    // ---- Legacy helpers for backward compatibility with existing tests ----
    fun distort(r: Float, k: FloatArray): Float {
        val r2 = r * r
        return r * (1f + k[0] * r2 + k[1] * r2 * r2)
    }

    fun undistort(rDistorted: Float, k: FloatArray, maxSteps: Int = 6): Float {
        val ray = Vec2(rDistorted.toDouble(), 0.0)
        val coeffs = RadialCoefficients(k[0].toDouble(), k[1].toDouble())
        return RadialDistortion.rayToScreen(ray, coeffs).x.toFloat()
    }
}
