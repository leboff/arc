package com.daydreamvr.vrcore.distortion

import com.daydreamvr.vrcore.profile.DeviceProfile
import com.daydreamvr.vrcore.profile.DeviceProfiles
import com.daydreamvr.vrcore.render.Eye
import com.daydreamvr.vrcore.render.EyeParams
import com.daydreamvr.vrcore.render.FovAngles
import com.daydreamvr.vrcore.render.Viewport
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Phase 6 (docs/PLAN.md §6.3): the radial model round-trips, the warp mesh does
 * not sample far outside the eye texture, and the grid never folds.
 */
class DistortionMeshTest {

    private val profiles: List<DeviceProfile> = DeviceProfiles.ALL

    private fun eye(which: Eye) = EyeParams(
        eye = which,
        viewport = Viewport(0, 0, 1200, 1080),
        fov = FovAngles(45f, 40f, 45f, 45f),
        eyeOffsetX = if (which == Eye.LEFT) -0.032f else 0.032f,
    )

    @Test
    fun undistortInvertsDistortWithin1e4() {
        for (profile in profiles) {
            val k = profile.distortionK
            var r = 0f
            while (r <= 1.2f) {
                val rd = DistortionMesh.distort(r, k)
                assertThat(DistortionMesh.undistort(rd, k)).isWithin(1e-4f).of(r)
                r += 0.01f
            }
        }
    }

    @Test
    fun undistortConvergesInAtMostSixSteps() {
        for (profile in profiles) {
            val k = profile.distortionK
            val rd = DistortionMesh.distort(1.2f, k)
            val exact = DistortionMesh.undistort(rd, k, maxSteps = 40)
            assertThat(DistortionMesh.undistort(rd, k, maxSteps = 6)).isWithin(1e-4f).of(exact)
        }
    }

    @Test
    fun meshUvsStayWithinTheAllowedOverscan() {
        val stride = DistortionMesh.FLOATS_PER_VERTEX
        for (profile in profiles) {
            for (which in Eye.entries) {
                val v = DistortionMesh.buildVertices(eye(which), profile, gridSize = 40)
                var i = 0
                while (i < v.size) {
                    for (channel in 0..2) {
                        val cu = v[i + 2 + channel * 2]
                        val cv = v[i + 3 + channel * 2]
                        assertThat(cu).isAtLeast(-0.1f)
                        assertThat(cu).isAtMost(1.1f)
                        assertThat(cv).isAtLeast(-0.1f)
                        assertThat(cv).isAtMost(1.1f)
                    }
                    i += stride
                }
            }
        }
    }

    @Test
    fun gridCornersMapMonotonicallyWithNoFolding() {
        val stride = DistortionMesh.FLOATS_PER_VERTEX
        val grid = 12
        val v = DistortionMesh.buildVertices(eye(Eye.LEFT), DeviceProfiles.CARDBOARD_V2, gridSize = grid)

        // Each 6-vertex quad starts at its (i, j) corner (see the emit order).
        val u = Array(grid) { FloatArray(grid) }
        val w = Array(grid) { FloatArray(grid) }
        var q = 0
        for (i in 0 until grid) {
            for (j in 0 until grid) {
                val base = q * 6 * stride
                u[i][j] = v[base + 4] // green-channel u
                w[i][j] = v[base + 5] // green-channel v
                q++
            }
        }
        for (j in 0 until grid) {
            for (i in 1 until grid) {
                assertThat(u[i][j]).isGreaterThan(u[i - 1][j])
            }
        }
        for (i in 0 until grid) {
            for (j in 1 until grid) {
                assertThat(w[i][j]).isGreaterThan(w[i][j - 1])
            }
        }
    }
}
