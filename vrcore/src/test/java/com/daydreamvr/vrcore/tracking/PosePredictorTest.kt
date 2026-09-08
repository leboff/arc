package com.daydreamvr.vrcore.tracking

import com.daydreamvr.vrcore.math.Quat
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PosePredictorTest {

    private val predictor = PosePredictor()

    private fun identityMatrix(): FloatArray =
        FloatArray(16).also { Quat.toMatrix(floatArrayOf(1f, 0f, 0f, 0f), it) }

    private fun angleDegOf(matrix: FloatArray): Double {
        val q = FloatArray(4)
        Quat.fromMatrix(matrix, q)
        return Math.toDegrees(Quat.angle(q).toDouble())
    }

    private fun yAxisComponent(matrix: FloatArray): Float {
        val q = FloatArray(4)
        Quat.fromMatrix(matrix, q)
        // Normalised rotation axis y-component.
        val len = kotlin.math.sqrt(q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        return if (len < 1e-6f) 0f else q[2] / len
    }

    private val yawRate = Math.toRadians(90.0).toFloat() // 90°/s about +Y

    @Test
    fun constantYaw_30msAhead_is2Point7DegreesAboutY() {
        val sample = PoseSample(
            timestampNs = 0L,
            rotation = identityMatrix(),
            omega = floatArrayOf(0f, yawRate, 0f),
        )
        val out = FloatArray(16)
        predictor.predict(sample, targetTimeNs = 30_000_000L, out = out)

        assertThat(angleDegOf(out)).isWithin(0.1).of(2.7)
        assertThat(yAxisComponent(out)).isWithin(1e-3f).of(1f)
    }

    @Test
    fun prediction_clampsAt50ms() {
        val sample = PoseSample(0L, identityMatrix(), floatArrayOf(0f, yawRate, 0f))
        val out = FloatArray(16)
        predictor.predict(sample, targetTimeNs = 200_000_000L, out = out)
        // 90°/s × 50 ms = 4.5°, not 18°.
        assertThat(angleDegOf(out)).isWithin(0.1).of(4.5)
    }

    @Test
    fun staleSample_olderThan200ms_fallsBackToNoPrediction() {
        val sample = PoseSample(0L, identityMatrix(), floatArrayOf(0f, yawRate, 0f))
        val out = FloatArray(16)
        predictor.predict(sample, targetTimeNs = 300_000_000L, out = out)
        assertThat(angleDegOf(out)).isWithin(1e-3).of(0.0) // unchanged identity
    }

    @Test
    fun noGyro_usesFiniteDifferencesFromPreviousSample() {
        // Two samples 10 ms apart, 90°/s yaw between them, no omega on either.
        val q0 = floatArrayOf(1f, 0f, 0f, 0f)
        val m0 = FloatArray(16).also { Quat.toMatrix(q0, it) }

        val q1 = FloatArray(4)
        Quat.fromAxisAngle(0f, 1f, 0f, yawRate * 0.010f, q1)
        val m1 = FloatArray(16).also { Quat.toMatrix(q1, it) }

        val prev = PoseSample(0L, m0, omega = null)
        val latest = PoseSample(10_000_000L, m1, omega = null)

        val out = FloatArray(16)
        predictor.predict(prev, latest, targetTimeNs = 40_000_000L, out = out)

        // latest is at 0.9°; +30 ms at 90°/s ⇒ +2.7° ⇒ ~3.6° total about +Y.
        assertThat(angleDegOf(out)).isWithin(0.2).of(3.6)
        assertThat(yAxisComponent(out)).isWithin(1e-2f).of(1f)
    }

    @Test
    fun noGyro_noPrevious_fallsBackToNoPrediction() {
        val sample = PoseSample(0L, identityMatrix(), omega = null)
        val out = FloatArray(16)
        predictor.predict(sample, targetTimeNs = 20_000_000L, out = out)
        assertThat(angleDegOf(out)).isWithin(1e-3).of(0.0)
    }
}
