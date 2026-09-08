package com.daydreamvr.vrcore.math

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.min
import kotlin.math.sqrt

class QuaternionTest {

    private fun randomUnitQuat(r: Random): FloatArray {
        val q = floatArrayOf(
            r.nextGaussian().toFloat(),
            r.nextGaussian().toFloat(),
            r.nextGaussian().toFloat(),
            r.nextGaussian().toFloat(),
        )
        Quat.normalize(q)
        return q
    }

    /** Angle between two rotations, accounting for the q ~ -q double cover. */
    private fun angleBetween(a: FloatArray, b: FloatArray): Float {
        val dot = abs(a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3])
        return 2f * acos(min(1f, dot))
    }

    /**
     * Component distance between two quaternions, taking the nearer of `b` / `-b`
     * (double cover). Robust where `angleBetween` would hit `acos` blow-up near 1.
     */
    private fun quatDistance(a: FloatArray, b: FloatArray): Float {
        var plus = 0f
        var minus = 0f
        for (i in 0 until 4) {
            plus += (a[i] - b[i]) * (a[i] - b[i])
            minus += (a[i] + b[i]) * (a[i] + b[i])
        }
        return sqrt(min(plus, minus))
    }

    @Test
    fun matrixRoundTrip_1000RandomQuats() {
        val r = Random(42)
        val m = FloatArray(16)
        val back = FloatArray(4)
        repeat(1000) {
            val q = randomUnitQuat(r)
            Quat.toMatrix(q, m)
            Quat.fromMatrix(m, back)
            assertThat(quatDistance(q, back)).isLessThan(1e-5f)
        }
    }

    @Test
    fun toMatrix_rotatesBasisVectors() {
        val q = FloatArray(4)
        Quat.fromAxisAngle(0f, 1f, 0f, (PI / 2).toFloat(), q)
        val v = floatArrayOf(0f, 0f, -1f)
        val out = FloatArray(3)
        Quat.rotateVector(q, v, out)
        // +90° about +Y takes -Z to -X.
        assertThat(out[0]).isWithin(1e-6f).of(-1f)
        assertThat(out[1]).isWithin(1e-6f).of(0f)
        assertThat(out[2]).isWithin(1e-6f).of(0f)
    }

    @Test
    fun slerp_endpointsAreExact() {
        val r = Random(7)
        val a = randomUnitQuat(r)
        val b = randomUnitQuat(r)
        val out = FloatArray(4)
        Quat.slerp(a, b, 0f, out)
        assertThat(quatDistance(out, a)).isLessThan(1e-5f)
        Quat.slerp(a, b, 1f, out)
        assertThat(quatDistance(out, b)).isLessThan(1e-5f)
    }

    @Test
    fun slerp_takesShortestPath_whenDotNegative() {
        val a = floatArrayOf(1f, 0f, 0f, 0f)
        // b is the same rotation as a, but with every component negated.
        val b = floatArrayOf(-1f, 0f, 0f, 0f)
        val out = FloatArray(4)
        Quat.slerp(a, b, 0.5f, out)
        // Interpolating identity with itself must stay identity (no long way round).
        assertThat(angleBetween(out, a)).isLessThan(1e-4f)
    }

    @Test
    fun slerp_halfwayMatchesHalfAngle() {
        val a = floatArrayOf(1f, 0f, 0f, 0f)
        val b = FloatArray(4)
        Quat.fromAxisAngle(0f, 0f, 1f, (PI / 2).toFloat(), b)
        val out = FloatArray(4)
        Quat.slerp(a, b, 0.5f, out)
        assertThat(Quat.angle(out)).isWithin(1e-4f).of((PI / 4).toFloat())
    }

    @Test
    fun integrateGyro_matchesAnalyticRotation_forConstantOmega() {
        val q = floatArrayOf(1f, 0f, 0f, 0f)
        val omega = floatArrayOf(0.3f, -0.7f, 1.1f) // rad/s
        val speed = sqrt(0.3f * 0.3f + 0.7f * 0.7f + 1.1f * 1.1f)

        // 1000 small steps over 1 s.
        val stepped = q.copyOf()
        repeat(1000) { Quat.integrateGyro(stepped, omega, 0.001f, stepped) }

        val analytic = FloatArray(4)
        Quat.fromAxisAngle(omega[0], omega[1], omega[2], speed * 1f, analytic)

        assertThat(angleBetween(stepped, analytic)).isLessThan(1e-4f)

        // Single exact step must also match (exp map is exact for constant ω).
        val oneShot = FloatArray(4)
        Quat.integrateGyro(q, omega, 1f, oneShot)
        assertThat(angleBetween(oneShot, analytic)).isLessThan(1e-5f)
    }

    @Test
    fun twistAbout_recomposesToOriginal() {
        val r = Random(99)
        val axis = floatArrayOf(0f, 1f, 0f)
        val twist = FloatArray(4)
        val twistInv = FloatArray(4)
        val swing = FloatArray(4)
        val recomposed = FloatArray(4)
        repeat(200) {
            val q = randomUnitQuat(r)
            Quat.twistAbout(q, axis, twist)
            Quat.conjugate(twist, twistInv)
            Quat.multiply(twistInv, q, swing) // swing = twist⁻¹ ⊗ q
            Quat.multiply(twist, swing, recomposed) // twist ⊗ swing == q
            assertThat(quatDistance(recomposed, q)).isLessThan(1e-4f)

            // The twist's rotation axis is parallel to the requested axis.
            val axialLen = sqrt(twist[1] * twist[1] + twist[2] * twist[2] + twist[3] * twist[3])
            if (axialLen > 1e-4f) {
                assertThat(abs(twist[1]) / axialLen).isLessThan(1e-4f)
                assertThat(abs(twist[3]) / axialLen).isLessThan(1e-4f)
            }
        }
    }

    @Test
    fun conjugate_isInverseForUnitQuat() {
        val r = Random(3)
        val q = randomUnitQuat(r)
        val inv = FloatArray(4)
        val prod = FloatArray(4)
        Quat.conjugate(q, inv)
        Quat.multiply(q, inv, prod)
        assertThat(prod[0]).isWithin(1e-5f).of(1f)
        assertThat(prod[1]).isWithin(1e-5f).of(0f)
        assertThat(prod[2]).isWithin(1e-5f).of(0f)
        assertThat(prod[3]).isWithin(1e-5f).of(0f)
    }
}
