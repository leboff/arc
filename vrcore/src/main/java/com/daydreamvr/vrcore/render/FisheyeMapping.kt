package com.daydreamvr.vrcore.render

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/** Pure equidistant-lens math shared by tests and the fisheye renderer. */
object FisheyeMapping {
    data class Uv(val u: Double, val v: Double)

    fun directionToEyeUv(dx: Double, dy: Double, dz: Double, fovDegrees: Int): Uv? {
        val length = sqrt(dx * dx + dy * dy + dz * dz)
        if (!length.isFinite() || length == 0.0) return null
        val x = dx / length; val y = dy / length; val z = dz / length
        val s = hypot(x, y)
        val theta = atan2(s, -z)
        val max = Math.toRadians(fovDegrees / 2.0)
        if (theta > max) return null
        val rho = if (s == 0.0) 0.0 else theta / max
        return Uv(0.5 + 0.5 * rho * x / if (s == 0.0) 1.0 else s,
            0.5 + 0.5 * rho * y / if (s == 0.0) 1.0 else s)
    }
}
