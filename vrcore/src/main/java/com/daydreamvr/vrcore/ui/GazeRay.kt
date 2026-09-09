package com.daydreamvr.vrcore.ui

import kotlin.math.asin
import kotlin.math.atan2

/** A world-space ray: origin + unit direction (UI_GAZE_PLAN.md §1.2). */
data class Ray(
    val ox: Float,
    val oy: Float,
    val oz: Float,
    val dx: Float,
    val dy: Float,
    val dz: Float,
)

/**
 * Turns a column-major head pose `R_W_H` into the cyclopean gaze ray. Pure —
 * no `android.opengl.Matrix` (UI_GAZE_PLAN.md §1).
 */
object GazeRay {

    /**
     * Forward ray from [pose]. Head `−Z` is the gaze direction, so the world
     * direction is the negated third column. [neckOffsetM] is a head-space
     * offset (`profile.neckModelM`, or null when the neck model is off); the
     * origin is `R_W_H · neckOffset`.
     */
    fun fromPose(pose: FloatArray, neckOffsetM: FloatArray? = null): Ray {
        val dx = -pose[8]
        val dy = -pose[9]
        val dz = -pose[10]
        if (neckOffsetM == null) return Ray(0f, 0f, 0f, dx, dy, dz)
        val nx = neckOffsetM[0]
        val ny = neckOffsetM[1]
        val nz = neckOffsetM[2]
        val ox = pose[0] * nx + pose[4] * ny + pose[8] * nz
        val oy = pose[1] * nx + pose[5] * ny + pose[9] * nz
        val oz = pose[2] * nx + pose[6] * ny + pose[10] * nz
        return Ray(ox, oy, oz, dx, dy, dz)
    }

    /** Azimuth: 0 = dead ahead, + = to the right. Matches `VrActivity`'s `headYawRad`. */
    fun azimuthRad(ray: Ray): Float = atan2(ray.dx, -ray.dz)

    /** Pitch: + = up. */
    fun pitchRad(ray: Ray): Float = asin(ray.dy.coerceIn(-1f, 1f))
}
