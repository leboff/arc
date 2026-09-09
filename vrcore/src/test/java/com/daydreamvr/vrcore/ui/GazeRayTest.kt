package com.daydreamvr.vrcore.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class GazeRayTest {

    /** Column-major R_W_H for a head yawed [thetaRad] about +Y. */
    private fun rY(thetaRad: Float): FloatArray {
        val c = cos(thetaRad)
        val s = sin(thetaRad)
        return floatArrayOf(
            c, 0f, -s, 0f,
            0f, 1f, 0f, 0f,
            s, 0f, c, 0f,
            0f, 0f, 0f, 1f,
        )
    }

    @Test
    fun identityPoseLooksDownNegativeZ() {
        val r = GazeRay.fromPose(rY(0f))
        assertThat(r.dx).isWithin(1e-6f).of(0f)
        assertThat(r.dy).isWithin(1e-6f).of(0f)
        assertThat(r.dz).isWithin(1e-6f).of(-1f)
        assertThat(r.ox).isEqualTo(0f)
    }

    @Test
    fun yawedPoseRotatesTheDirection() {
        val theta = (30.0 * PI / 180.0).toFloat()
        val r = GazeRay.fromPose(rY(theta))
        assertThat(r.dx).isWithin(1e-5f).of(-sin(theta))
        assertThat(r.dz).isWithin(1e-5f).of(-cos(theta))
        // azimuth matches VrActivity's atan2(-pose[8], pose[10]) = -theta
        assertThat(GazeRay.azimuthRad(r)).isWithin(1e-5f).of(-theta)
    }

    @Test
    fun directionIsUnitLength() {
        val theta = (57.0 * PI / 180.0).toFloat()
        val r = GazeRay.fromPose(rY(theta), floatArrayOf(0f, -0.075f, 0.08f))
        val len = sqrt(r.dx * r.dx + r.dy * r.dy + r.dz * r.dz)
        assertThat(len).isWithin(1e-5f).of(1f)
    }

    @Test
    fun neckOffsetRotatesWithTheHead() {
        val n = floatArrayOf(0f, -0.075f, 0.08f)
        val straight = GazeRay.fromPose(rY(0f), n)
        assertThat(straight.ox).isWithin(1e-6f).of(0f)
        assertThat(straight.oy).isWithin(1e-6f).of(-0.075f)
        assertThat(straight.oz).isWithin(1e-6f).of(0.08f)

        val theta = (90.0 * PI / 180.0).toFloat()
        val turned = GazeRay.fromPose(rY(theta), n)
        // R_y(90°)·(0,-0.075,0.08) = (0.08, -0.075, 0)
        assertThat(turned.ox).isWithin(1e-5f).of(0.08f)
        assertThat(turned.oy).isWithin(1e-5f).of(-0.075f)
        assertThat(turned.oz).isWithin(1e-5f).of(0f)
    }

    @Test
    fun pitchIsAsinOfDy() {
        val r = Ray(0f, 0f, 0f, 0f, 0.5f, -sqrt(0.75f))
        assertThat(GazeRay.pitchRad(r)).isWithin(1e-5f).of((PI / 6).toFloat())
    }
}
