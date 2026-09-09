package com.daydreamvr.vrcore.render

import kotlin.math.PI

/** GL-thread-owned alignment, independent of headset calibration. */
class WorldYaw {
    var offsetRad = 0f
        private set

    fun update(rate: Float, dtSeconds: Float) {
        if (rate == 0f || !rate.isFinite() || !dtSeconds.isFinite() || dtSeconds <= 0f) return
        val pi = PI.toFloat()
        val next = offsetRad + rate.coerceIn(-1f, 1f) * SPEED_RAD_PER_SECOND * dtSeconds
        offsetRad = ((next + pi) % (2f * pi) + 2f * pi) % (2f * pi) - pi
    }

    companion object {
        val SPEED_RAD_PER_SECOND = Math.toRadians(40.0).toFloat()
    }
}
