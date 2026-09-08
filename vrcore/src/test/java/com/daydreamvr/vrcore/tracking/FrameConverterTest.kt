package com.daydreamvr.vrcore.tracking

import com.daydreamvr.vrcore.math.Matrix4
import com.daydreamvr.vrcore.math.Quat
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.min
import kotlin.math.sqrt

class FrameConverterTest {

    private val converter = FrameConverter()

    /**
     * Remapped-head → Android-world rotation for "facing north, head level":
     * head +X (right ear) → East, head +Y (up) → Up, head −Z (gaze) → North.
     * Column-major 4×4.
     */
    private val baseRemappedHead = floatArrayOf(
        1f, 0f, 0f, 0f, // col 0: head X → East
        0f, 0f, 1f, 0f, // col 1: head Y → Up (Z_A)
        0f, -1f, 0f, 0f, // col 2: head Z → South (−North)
        0f, 0f, 0f, 1f,
    )

    /** Remap basis M(rot): remapped-device axes expressed in natural-device axes. */
    private fun remapBasis(displayRotation: Int): FloatArray = when (displayRotation) {
        FrameConverter.ROTATION_90 -> floatArrayOf(
            0f, -1f, 0f, 0f,
            1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )
        FrameConverter.ROTATION_180 -> floatArrayOf(
            -1f, 0f, 0f, 0f,
            0f, -1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )
        FrameConverter.ROTATION_270 -> floatArrayOf(
            0f, 1f, 0f, 0f,
            -1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )
        else -> floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )
    }

    /**
     * `SensorEvent.values` (rotation vector `[x, y, z, w]`) for the device sitting
     * at `worldHead ⊗ base`, given the display rotation, where `worldHead` is an
     * extra rotation applied in the Android world frame.
     */
    private fun rotationVector(displayRotation: Int, worldHead: FloatArray?): FloatArray {
        val basisT = FloatArray(16)
        Matrix4.transpose(basisT, remapBasis(displayRotation))
        val rAD = FloatArray(16)
        Matrix4.multiplyMM(rAD, baseRemappedHead, basisT) // R_A_D = R'_A_H · Mᵀ

        if (worldHead != null) {
            val rotated = FloatArray(16)
            Matrix4.multiplyMM(rotated, worldHead, rAD)
            System.arraycopy(rotated, 0, rAD, 0, 16)
        }

        val q = FloatArray(4)
        Quat.fromMatrix(rAD, q)
        return floatArrayOf(q[1], q[2], q[3], q[0])
    }

    private fun worldRotation(ax: Float, ay: Float, az: Float, radians: Float): FloatArray {
        val q = FloatArray(4)
        Quat.fromAxisAngle(ax, ay, az, radians, q)
        val m = FloatArray(16)
        Quat.toMatrix(q, m)
        return m
    }

    private fun gazeOf(rWH: FloatArray) = floatArrayOf(-rWH[8], -rWH[9], -rWH[10])
    private fun upOf(rWH: FloatArray) = floatArrayOf(rWH[4], rWH[5], rWH[6])

    private fun angleDegBetween(a: FloatArray, b: FloatArray): Float {
        val dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        val na = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
        val nb = sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2])
        return Math.toDegrees(acos(min(1f, dot / (na * nb)).toDouble()).toDouble()).toFloat()
    }

    @Test
    fun backFacingNorthLevel_gazesDownMinusZ_forEveryDisplayRotation() {
        val rotations = intArrayOf(
            FrameConverter.ROTATION_0,
            FrameConverter.ROTATION_90,
            FrameConverter.ROTATION_180,
            FrameConverter.ROTATION_270,
        )
        val out = FloatArray(16)
        for (rot in rotations) {
            converter.sensorToGlWorld(rotationVector(rot, null), rot, out)
            assertThat(angleDegBetween(gazeOf(out), floatArrayOf(0f, 0f, -1f))).isLessThan(0.5f)
            assertThat(angleDegBetween(upOf(out), floatArrayOf(0f, 1f, 0f))).isLessThan(0.5f)
        }
    }

    @Test
    fun yawRight30_rotatesGaze30DegreesAboutY() {
        // Turning right = gaze North → East = −30° about Android world +Z (up).
        val yaw = worldRotation(0f, 0f, 1f, Math.toRadians(-30.0).toFloat())
        val out = FloatArray(16)
        converter.sensorToGlWorld(rotationVector(FrameConverter.ROTATION_90, yaw), FrameConverter.ROTATION_90, out)

        val gaze = gazeOf(out)
        assertThat(angleDegBetween(gaze, floatArrayOf(0f, 0f, -1f))).isWithin(0.3f).of(30f)
        assertThat(gaze[1]).isWithin(0.01f).of(0f) // pure yaw: no vertical component
        assertThat(gaze[0]).isGreaterThan(0f) // gaze turned toward +X (right)
    }

    @Test
    fun pitchUp_producesPositiveYGazeComponent() {
        // Pitch up = gaze North → Up = +30° about Android world +X (East).
        val pitch = worldRotation(1f, 0f, 0f, (PI / 6).toFloat())
        val out = FloatArray(16)
        converter.sensorToGlWorld(rotationVector(FrameConverter.ROTATION_90, pitch), FrameConverter.ROTATION_90, out)

        val gaze = gazeOf(out)
        assertThat(gaze[1]).isGreaterThan(0f) // looking up ⇒ gaze has +Y — catches inverted tracking
        assertThat(angleDegBetween(gaze, floatArrayOf(0f, 0f, -1f))).isWithin(0.3f).of(30f)
    }

    @Test
    fun handlesThreeElementRotationVector_byDerivingW() {
        val rv4 = rotationVector(FrameConverter.ROTATION_90, null)
        val rv3 = floatArrayOf(rv4[0], rv4[1], rv4[2])
        val out4 = FloatArray(16)
        val out3 = FloatArray(16)
        converter.sensorToGlWorld(rv4, FrameConverter.ROTATION_90, out4)
        converter.sensorToGlWorld(rv3, FrameConverter.ROTATION_90, out3)
        for (i in 0 until 16) assertThat(out3[i]).isWithin(1e-4f).of(out4[i])
    }

    @Test
    fun output_isOrthonormalRotation() {
        val out = FloatArray(16)
        converter.sensorToGlWorld(
            rotationVector(FrameConverter.ROTATION_270, worldRotation(0f, 1f, 0f, 0.9f)),
            FrameConverter.ROTATION_270,
            out,
        )
        val c0 = floatArrayOf(out[0], out[1], out[2])
        val c1 = floatArrayOf(out[4], out[5], out[6])
        val c2 = floatArrayOf(out[8], out[9], out[10])
        fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        assertThat(dot(c0, c0)).isWithin(1e-4f).of(1f)
        assertThat(dot(c1, c1)).isWithin(1e-4f).of(1f)
        assertThat(dot(c2, c2)).isWithin(1e-4f).of(1f)
        assertThat(dot(c0, c1)).isWithin(1e-4f).of(0f)
        assertThat(dot(c0, c2)).isWithin(1e-4f).of(0f)
        assertThat(dot(c1, c2)).isWithin(1e-4f).of(0f)
    }
}
