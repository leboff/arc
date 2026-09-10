package com.daydreamvr.vrcore.distortion

import com.daydreamvr.vrcore.optics.DisplayGeometry
import com.daydreamvr.vrcore.optics.MaxFov
import com.daydreamvr.vrcore.optics.ObserverGeometry
import com.daydreamvr.vrcore.optics.OpticsGeometry
import com.daydreamvr.vrcore.optics.RadialCoefficients
import com.daydreamvr.vrcore.optics.ViewerOptics
import com.daydreamvr.vrcore.profile.DeviceProfiles
import com.daydreamvr.vrcore.render.Eye
import com.daydreamvr.vrcore.render.EyeParams
import com.daydreamvr.vrcore.render.FovAngles
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DistortionMeshTest {

    private fun leftEye(k: RadialCoefficients): EyeParams {
        val s = OpticsGeometry.compute(
            DisplayGeometry(0.140, 0.070, 2000, 1000),
            ViewerOptics(
                profileId = "fixture",
                lensSeparationM = 0.064,
                screenToLensM = 0.040,
                coefficients = k,
                maxFov = MaxFov(45.0, 45.0, 45.0, 45.0),
                dividerPx = 0,
            ),
            ObserverGeometry(0.064),
        )
        return EyeParams(Eye.LEFT, s.left.viewport, FovAngles(45f, 45f, 45f, 45f), -0.032f, s.left)
    }

    @Test
    fun destinationMeshUsesForwardScreenToRayMap() {
        // Grid 70x70: vertex at i=58, j=35 corresponds to p=(0.058, 0.035)
        // With k=(0.34, 0.55), screen tangent is (0.5, 0), source UV is (0.77984375, 0.5)
        val eye = leftEye(RadialCoefficients(0.34, 0.55))
        val verts = DistortionMesh.buildVertices(eye, gridSize = 70)
        val stride = DistortionMesh.FLOATS_PER_VERTEX

        val base = (58 * 70 + 35) * 6 * stride
        assertThat(verts[base + 2]).isWithin(1e-6f).of(0.77984375f)
        assertThat(verts[base + 3]).isWithin(1e-6f).of(0.5f)
    }

    @Test
    fun meshHasPositiveWindingAndFiniteAttributes() {
        val eye = leftEye(RadialCoefficients(0.34, 0.55))
        val verts = DistortionMesh.buildVertices(eye, gridSize = 10)
        val stride = DistortionMesh.FLOATS_PER_VERTEX

        for (base in verts.indices step (stride * 3)) {
            val ax = verts[base]; val ay = verts[base + 1]
            val bx = verts[base + stride]; val by = verts[base + stride + 1]
            val cx = verts[base + stride * 2]; val cy = verts[base + stride * 2 + 1]

            val crossArea = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
            assertThat(crossArea).isGreaterThan(0f)

            for (k in 0 until (stride * 3)) {
                assertThat(verts[base + k].isFinite()).isTrue()
            }
        }
    }

    @Test
    fun meshPassesBarycentricLatticeGateWithAnalyticOracle() {
        // Standard FBO resolution for eye render target
        val eye = leftEye(RadialCoefficients(0.34, 0.55))
        val fboW = 1000
        val fboH = 1000

        // Select grid size starting from 40
        val selectedGrid = DistortionMesh.selectGridSize(eye, fboW, fboH)
        assertThat(selectedGrid).isIn(listOf(40, 80, 160, 320))

        val eval = DistortionMesh.evaluateMesh(eye, selectedGrid, fboW, fboH)
        assertThat(eval.passesGate).isTrue()
        assertThat(eval.maxTexelError).isAtMost(0.25)
        assertThat(eval.positiveWinding).isTrue()
        assertThat(eval.noDegenerateTriangles).isTrue()
        assertThat(eval.allFinite).isTrue()
    }

    @Test
    fun legacyScalarDistortAndUndistortRoundTrip() {
        for (profile in DeviceProfiles.ALL) {
            val k = profile.distortionK
            var r = 0f
            while (r <= 1.2f) {
                val rd = DistortionMesh.distort(r, k)
                assertThat(DistortionMesh.undistort(rd, k)).isWithin(1e-4f).of(r)
                r += 0.05f
            }
        }
    }
}
