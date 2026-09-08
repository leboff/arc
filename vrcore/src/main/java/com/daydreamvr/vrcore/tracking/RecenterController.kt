package com.daydreamvr.vrcore.tracking

import com.daydreamvr.vrcore.math.Quat
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp

/**
 * Yaw-only recentre with a slew filter (ARCHITECTURE.md §7.3).
 *
 * Pressing **Y** re-aligns the virtual world so the screen is dead ahead. Only
 * **yaw** is cancelled — pitch and roll stay gravity-referenced so the horizon
 * matches the real world. The correction is slewed over ~150 ms
 * (`TAU = 0.06 s`) rather than snapped, because an instantaneous world rotation
 * is a strong vection trigger.
 *
 * Usage per frame: [update] with the frame `dt`, then [apply] to the raw head
 * quaternion. On a **Y** press call [recenterTo] with the current raw head
 * quaternion.
 */
class RecenterController(var tauSeconds: Float = DEFAULT_TAU) {

    private val target = floatArrayOf(1f, 0f, 0f, 0f)
    private val current = floatArrayOf(1f, 0f, 0f, 0f)

    private val gaze = FloatArray(3)
    private val skull = FloatArray(3)

    /** Sets the recentre goal from the current raw head orientation [headQuat]. */
    fun recenterTo(headQuat: FloatArray) {
        Quat.rotateVector(headQuat, FORWARD, gaze)
        val yaw = if (abs(gaze[1]) > GIMBAL_LIMIT) {
            // Gaze axis is ill-conditioned near ±Y; use the top-of-skull axis,
            // which is well-conditioned exactly there.
            Quat.rotateVector(headQuat, UP, skull)
            atan2(skull[0], -skull[2])
        } else {
            atan2(gaze[0], -gaze[2])
        }
        // Rotate the world by +yaw about GL up to bring the current gaze back to
        // −Z. (ARCHITECTURE.md §7.3 writes `-yaw`; that sign assumes the opposite
        // handedness for `quatFromAxisAngle` than this codebase's Hamilton /
        // active-rotation convention — see QuaternionTest.toMatrix_rotatesBasisVectors.)
        Quat.fromAxisAngle(0f, 1f, 0f, yaw, target)
    }

    /** Advances the slew filter by [dtSeconds]. */
    fun update(dtSeconds: Float) {
        if (dtSeconds <= 0f) return
        val a = (1f - exp(-dtSeconds / tauSeconds)).coerceIn(0f, 1f)
        Quat.slerp(current, target, a, current)
        Quat.normalize(current)
    }

    /** `out = recenter ⊗ raw`. Safe when [out] aliases [rawHeadQuat]. */
    fun apply(rawHeadQuat: FloatArray, out: FloatArray) {
        Quat.multiply(current, rawHeadQuat, out)
        Quat.normalize(out)
    }

    /** Current (partially slewed) correction quaternion, copied into [out]. */
    fun currentCorrection(out: FloatArray) {
        out[0] = current[0]
        out[1] = current[1]
        out[2] = current[2]
        out[3] = current[3]
    }

    /** Jumps the slew filter straight to the goal (test / instant-snap helper). */
    fun settle() {
        current[0] = target[0]
        current[1] = target[1]
        current[2] = target[2]
        current[3] = target[3]
    }

    /** Clears the correction back to identity (cold start). */
    fun reset() {
        Quat.identity(target)
        Quat.identity(current)
    }

    companion object {
        const val DEFAULT_TAU = 0.06f
        private const val GIMBAL_LIMIT = 0.99f
        private val FORWARD = floatArrayOf(0f, 0f, -1f)
        private val UP = floatArrayOf(0f, 1f, 0f)
    }
}
