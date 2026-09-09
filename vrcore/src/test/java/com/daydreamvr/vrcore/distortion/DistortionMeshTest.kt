package com.daydreamvr.vrcore.distortion

import com.daydreamvr.vrcore.optics.*
import com.daydreamvr.vrcore.render.Eye
import com.daydreamvr.vrcore.render.EyeParams
import com.daydreamvr.vrcore.render.FovAngles
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DistortionMeshTest {
    private fun left(k: RadialCoefficients): EyeParams {
        val s = OpticsGeometry.compute(DisplayGeometry(.140, .070, 2000, 1000),
            ViewerOptics("fixture", lensSeparationM = .064, screenToLensM = .040, coefficients = k,
                maxFov = MaxFov(45.0, 45.0, 45.0, 45.0), dividerPx = 0), ObserverGeometry())
        return EyeParams(Eye.LEFT, s.left.viewport, FovAngles(1f, 1f, 1f, 1f), -.032f, s.left)
    }

    @Test fun destinationMeshUsesForwardScreenToRayMap() {
        val fine = DistortionMesh.buildVertices(left(RadialCoefficients(.34, .55)), 70)
        val stride = DistortionMesh.FLOATS_PER_VERTEX
        val base = (58 * 70 + 35) * 6 * stride
        assertThat(fine[base + 2]).isWithin(1e-6f).of(.77984375f)
        assertThat(fine[base + 3]).isWithin(1e-6f).of(.5f)
    }

    @Test fun meshHasPositiveWindingAndFiniteUvs() {
        val v = DistortionMesh.buildVertices(left(RadialCoefficients(.34, .55)), 8)
        val stride = DistortionMesh.FLOATS_PER_VERTEX
        for (base in v.indices step (stride * 3)) {
            val ax = v[base]; val ay = v[base + 1]; val bx = v[base + stride]; val by = v[base + stride + 1]
            val cx = v[base + stride * 2]; val cy = v[base + stride * 2 + 1]
            assertThat((bx - ax) * (cy - ay) - (by - ay) * (cx - ax)).isGreaterThan(0f)
        }
    }
}
