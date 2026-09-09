package com.daydreamvr.vrcore.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.sqrt

class ReticleTest {

    private fun len(x: Float, y: Float, z: Float) = sqrt(x * x + y * y + z * z)
    private fun dot(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float) = ax * bx + ay * by + az * bz

    @Test
    fun angularSizeIsConstantAcrossDepth() {
        val near = Reticle.radiusMForDistance(2.5f, 0.55f)
        val far = Reticle.radiusMForDistance(4.0f, 0.55f)
        assertThat(far / near).isWithin(1e-4f).of(4.0f / 2.5f)
        assertThat(near).isWithin(1e-6f).of(2.5f * Math.tan(Math.toRadians(0.55)).toFloat())
    }

    @Test
    fun billboardBasisIsOrthonormalScaledAndFacesCamera() {
        val out = FloatArray(16)
        val radius = 0.04f
        Reticle.billboardModel(px = -0.15f, py = 0.2f, pz = -2.49f, camX = 0f, camY = 0f, camZ = 0f, radiusM = radius, out = out)

        // col0, col1 length == radius; col2 unit
        assertThat(len(out[0], out[1], out[2])).isWithin(1e-5f).of(radius)
        assertThat(len(out[4], out[5], out[6])).isWithin(1e-5f).of(radius)
        assertThat(len(out[8], out[9], out[10])).isWithin(1e-5f).of(1f)

        // mutually orthogonal
        assertThat(dot(out[0], out[1], out[2], out[4], out[5], out[6])).isWithin(1e-4f).of(0f)
        assertThat(dot(out[0], out[1], out[2], out[8], out[9], out[10])).isWithin(1e-4f).of(0f)

        // normal points from the hit point toward the camera
        val fx = 0f - -0.15f
        val fy = 0f - 0.2f
        val fz = 0f - -2.49f
        val fl = len(fx, fy, fz)
        assertThat(dot(out[8], out[9], out[10], fx / fl, fy / fl, fz / fl)).isWithin(1e-4f).of(1f)

        // translation == p
        assertThat(out[12]).isEqualTo(-0.15f)
        assertThat(out[13]).isEqualTo(0.2f)
        assertThat(out[14]).isEqualTo(-2.49f)
        assertThat(out[15]).isEqualTo(1f)
    }

    @Test
    fun degenerateStraightUpDoesNotProduceNaN() {
        val out = FloatArray(16)
        Reticle.billboardModel(0f, 0f, 0f, 0f, 5f, 0f, 0.03f, out)
        for (v in out) assertThat(v.isNaN()).isFalse()
        assertThat(len(out[0], out[1], out[2])).isWithin(1e-5f).of(0.03f)
    }
}
