package com.daydreamvr.vrcore.tracking

import com.daydreamvr.vrcore.math.Quat
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.sqrt

class RecenterTest {

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

    private fun gaze(q: FloatArray): FloatArray {
        val out = FloatArray(3)
        Quat.rotateVector(q, floatArrayOf(0f, 0f, -1f), out)
        return out
    }

    private fun up(q: FloatArray): FloatArray {
        val out = FloatArray(3)
        Quat.rotateVector(q, floatArrayOf(0f, 1f, 0f), out)
        return out
    }

    private fun norm(v: FloatArray) = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    /** Roll of the head about its gaze axis, relative to world up. NaN near gimbal. */
    private fun roll(q: FloatArray): Float {
        val f = gaze(q)
        val worldUp = floatArrayOf(0f, 1f, 0f)
        val right0 = cross(f, worldUp)
        val rLen = norm(right0)
        if (rLen < 1e-4f) return Float.NaN
        right0[0] /= rLen; right0[1] /= rLen; right0[2] /= rLen
        val trueUp = cross(right0, f)
        val u = up(q)
        return atan2(dot(u, right0), dot(u, trueUp))
    }

    private fun settle(controller: RecenterController) {
        // Slew many frames (60 Hz) — well past 10 × TAU.
        repeat(200) { controller.update(1f / 60f) }
    }

    @Test
    fun recenter_flattensYawToMinusZ_andPreservesPitchAndRoll_for200Orientations() {
        val r = Random(1234)
        val controller = RecenterController()
        val recentered = FloatArray(4)
        repeat(200) {
            val q = randomUnitQuat(r)
            val f = gaze(q)
            // Skip near-gimbal starts for the pitch/roll-preservation assertions.
            if (abs(f[1]) > 0.985f) return@repeat

            controller.reset()
            controller.recenterTo(q)
            settle(controller)
            controller.apply(q, recentered)

            val g = gaze(recentered)
            // Gaze projected onto XZ is −Z within 0.5°.
            val yawErr = Math.toDegrees(abs(atan2(g[0], -g[2])).toDouble())
            assertThat(yawErr).isLessThan(0.5)

            // Pitch (asin of gaze.y) unchanged.
            assertThat(g[1]).isWithin(1e-3f).of(f[1])

            // Roll unchanged.
            val rollBefore = roll(q)
            val rollAfter = roll(recentered)
            if (!rollBefore.isNaN() && !rollAfter.isNaN()) {
                var d = rollAfter - rollBefore
                while (d > Math.PI) d -= (2 * Math.PI).toFloat()
                while (d < -Math.PI) d += (2 * Math.PI).toFloat()
                assertThat(abs(d)).isLessThan(1e-3f)
            }
        }
    }

    @Test
    fun recenter_isSlewedNotSnapped() {
        val controller = RecenterController()
        val q = FloatArray(4)
        Quat.fromAxisAngle(0f, 1f, 0f, Math.toRadians(90.0).toFloat(), q) // 90° yaw
        controller.recenterTo(q)

        val correction = FloatArray(4)
        controller.currentCorrection(correction)
        assertThat(Quat.angle(correction)).isWithin(1e-4f).of(0f) // nothing yet

        // One 90 Hz frame in: a fast ease-out has moved but nowhere near settled.
        controller.update(1f / 90f)
        controller.currentCorrection(correction)
        val afterOneFrame = Math.toDegrees(Quat.angle(correction).toDouble())
        assertThat(afterOneFrame).isGreaterThan(5.0)
        assertThat(afterOneFrame).isLessThan(80.0)

        // Many time constants later: essentially settled on the full 90°.
        repeat(60) { controller.update(1f / 90f) }
        controller.currentCorrection(correction)
        assertThat(Math.toDegrees(Quat.angle(correction).toDouble())).isWithin(1.0).of(90.0)
    }

    @Test
    fun recenter_handlesGimbalCase_withoutNaN_slewedNotSnapped_pitchPreserved() {
        val r = Random(55)
        val controller = RecenterController()
        val recentered = FloatArray(4)
        val frame = FloatArray(4)
        repeat(50) {
            // Gaze within 8° of straight up.
            val nearUp = FloatArray(4)
            Quat.fromAxisAngle(1f, 0f, 0f, Math.toRadians(-85.0).toFloat(), nearUp)
            val tiltAxis = floatArrayOf(r.nextGaussian().toFloat(), 0f, r.nextGaussian().toFloat())
            val wobble = FloatArray(4)
            Quat.fromAxisAngle(tiltAxis[0], tiltAxis[1], tiltAxis[2], Math.toRadians(3.0).toFloat(), wobble)
            val q = FloatArray(4)
            Quat.multiply(wobble, nearUp, q)
            Quat.normalize(q)

            val gazeBefore = gaze(q)
            controller.reset()
            controller.recenterTo(q)

            // No single slew step snaps the gaze more than 5°.
            var prev = gaze(q)
            repeat(300) {
                controller.update(1f / 90f)
                controller.apply(q, frame)
                val g = gaze(frame)
                for (c in g) assertThat(c.isNaN()).isFalse()
                val step = Math.toDegrees(
                    acos(min(1f, dot(prev, g) / (norm(prev) * norm(g))).toDouble()),
                )
                assertThat(step).isLessThan(5.0)
                prev = g
            }

            controller.apply(q, recentered)
            for (c in recentered) assertThat(c.isNaN()).isFalse()
            // A yaw-only correction preserves elevation (gaze.y) exactly.
            assertThat(gaze(recentered)[1]).isWithin(1e-3f).of(gazeBefore[1])
        }
    }
}
