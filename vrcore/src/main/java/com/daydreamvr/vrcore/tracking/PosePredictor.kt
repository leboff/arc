package com.daydreamvr.vrcore.tracking

import com.daydreamvr.vrcore.math.Quat
import kotlin.math.acos
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Forward-predicts head orientation to the estimated photon time
 * (ARCHITECTURE.md §7.4).
 *
 * `q_predicted = integrateGyro(q_latest, ω, dt)` where `dt` is the time from the
 * latest sample to the target, **clamped to 50 ms** (over-prediction overshoots
 * worse than latency costs). ω comes from `TYPE_GYROSCOPE` when the sample
 * carries it, otherwise from finite differences of two fused quaternions. A
 * stale latest sample (older than [staleThresholdSeconds]) disables prediction.
 */
class PosePredictor(
    var maxPredictionSeconds: Float = 0.050f,
    var staleThresholdSeconds: Float = 0.200f,
) {

    private val qLatest = FloatArray(4)
    private val qPrev = FloatArray(4)
    private val qInv = FloatArray(4)
    private val dq = FloatArray(4)
    private val omega = FloatArray(3)
    private val qPredicted = FloatArray(4)

    /** Convenience overload with no previous sample (finite differences off). */
    fun predict(latest: PoseSample, targetTimeNs: Long, out: FloatArray) =
        predict(null, latest, targetTimeNs, out)

    /**
     * @param previous the sample before [latest], used only for the
     *   finite-difference ω fallback; may be `null`.
     * @param out column-major 4×4, receives the predicted `R_W_H`.
     */
    fun predict(previous: PoseSample?, latest: PoseSample, targetTimeNs: Long, out: FloatArray) {
        Quat.fromMatrix(latest.rotation, qLatest)

        val aheadSeconds = (targetTimeNs - latest.timestampNs) / 1e9f
        if (aheadSeconds <= 0f || aheadSeconds > staleThresholdSeconds) {
            // No prediction: hand back the latest orientation untouched.
            System.arraycopy(latest.rotation, 0, out, 0, 16)
            return
        }
        val dt = min(aheadSeconds, maxPredictionSeconds)

        val omegaVec = resolveOmega(previous, latest)
        if (omegaVec == null) {
            System.arraycopy(latest.rotation, 0, out, 0, 16)
            return
        }

        Quat.integrateGyro(qLatest, omegaVec, dt, qPredicted)
        Quat.toMatrix(qPredicted, out)
    }

    private fun resolveOmega(previous: PoseSample?, latest: PoseSample): FloatArray? {
        latest.omega?.let { return it }
        if (previous == null) return null
        val prevDt = (latest.timestampNs - previous.timestampNs) / 1e9f
        if (prevDt <= 1e-6f) return null

        Quat.fromMatrix(previous.rotation, qPrev)
        Quat.conjugate(qPrev, qInv)
        Quat.multiply(qInv, qLatest, dq) // body-frame delta: q_latest = q_prev ⊗ dq
        Quat.normalize(dq)

        var w = dq[0]
        if (w < 0f) {
            w = -w
            dq[1] = -dq[1]
            dq[2] = -dq[2]
            dq[3] = -dq[3]
        }
        val sinHalf = sqrt(dq[1] * dq[1] + dq[2] * dq[2] + dq[3] * dq[3])
        if (sinHalf < 1e-9f) {
            omega[0] = 0f
            omega[1] = 0f
            omega[2] = 0f
            return omega
        }
        val angle = 2f * acos(w.coerceAtMost(1f))
        val scale = angle / (sinHalf * prevDt)
        omega[0] = dq[1] * scale
        omega[1] = dq[2] * scale
        omega[2] = dq[3] * scale
        return omega
    }
}
