package com.daydreamvr.vrcore.tracking

import kotlin.math.sqrt

/**
 * Converts a fused rotation-vector sensor reading into the head orientation
 * `R_W_H` the renderer uses (ARCHITECTURE.md §5.2, §7.2).
 *
 * Pipeline:
 * 1. `getRotationMatrixFromVector` — rotation vector → `R_A_D` (device → Android
 *    world, Z-up), a pure-Kotlin reimplementation of the `SensorManager` call.
 * 2. `remapCoordinateSystem` — rotate the device axes for the locked landscape
 *    display rotation, also reimplemented in pure Kotlin.
 * 3. `C_W_A` (`Rx(-90°)`) — Android world (Z-up) → GL world (Y-up, −Z forward).
 *
 * Both `SensorManager` reimplementations are byte-for-byte ports of the platform
 * algorithm; an instrumented test cross-checks them against the real calls.
 */
class FrameConverter {

    private val rAD = FloatArray(9)
    private val rRemapped = FloatArray(9)
    private val rWH = FloatArray(9)

    /**
     * @param rotationVectorValues `SensorEvent.values` from a rotation-vector
     *   sensor; length 3 (derive `w`), 4, or 5 (trailing accuracy ignored).
     * @param displayRotation one of `Surface.ROTATION_0/90/180/270`.
     * @param out column-major 4×4, receives `R_W_H`.
     */
    fun sensorToGlWorld(rotationVectorValues: FloatArray, displayRotation: Int, out: FloatArray) {
        rotationMatrixFromVector(rotationVectorValues, rAD)
        remapForDisplayRotation(rAD, displayRotation, rRemapped)
        applyWorldConversion(rRemapped, rWH)
        writeColumnMajor4x4(rWH, out)
    }

    // region SensorManager reimplementations

    /** Port of `SensorManager.getRotationMatrixFromVector` (3×3, row-major). */
    private fun rotationMatrixFromVector(rv: FloatArray, r: FloatArray) {
        val q1 = rv[0]
        val q2 = rv[1]
        val q3 = rv[2]
        val q0 = if (rv.size >= 4) {
            rv[3]
        } else {
            val t = 1f - q1 * q1 - q2 * q2 - q3 * q3
            if (t > 0f) sqrt(t) else 0f
        }

        val sq1 = 2f * q1 * q1
        val sq2 = 2f * q2 * q2
        val sq3 = 2f * q3 * q3
        val q1q2 = 2f * q1 * q2
        val q3q0 = 2f * q3 * q0
        val q1q3 = 2f * q1 * q3
        val q2q0 = 2f * q2 * q0
        val q2q3 = 2f * q2 * q3
        val q1q0 = 2f * q1 * q0

        r[0] = 1f - sq2 - sq3
        r[1] = q1q2 - q3q0
        r[2] = q1q3 + q2q0
        r[3] = q1q2 + q3q0
        r[4] = 1f - sq1 - sq3
        r[5] = q2q3 - q1q0
        r[6] = q1q3 - q2q0
        r[7] = q2q3 + q1q0
        r[8] = 1f - sq1 - sq2
    }

    private fun remapForDisplayRotation(inR: FloatArray, displayRotation: Int, outR: FloatArray) {
        when (displayRotation) {
            ROTATION_90 -> remapCoordinateSystem(inR, AXIS_Y, AXIS_MINUS_X, outR)
            ROTATION_180 -> remapCoordinateSystem(inR, AXIS_MINUS_X, AXIS_MINUS_Y, outR)
            ROTATION_270 -> remapCoordinateSystem(inR, AXIS_MINUS_Y, AXIS_X, outR)
            else -> remapCoordinateSystem(inR, AXIS_X, AXIS_Y, outR)
        }
    }

    /** Port of `SensorManager.remapCoordinateSystem` for 3×3 row-major matrices. */
    private fun remapCoordinateSystem(inR: FloatArray, x: Int, y: Int, outR: FloatArray) {
        var zAxis = x xor y
        val xi = (x and 0x3) - 1
        val yi = (y and 0x3) - 1
        val zi = (zAxis and 0x3) - 1

        val axisY = (zi + 1) % 3
        val axisZ = (zi + 2) % 3
        if (((xi xor axisY) or (yi xor axisZ)) != 0) {
            zAxis = zAxis xor 0x80
        }

        val sx = x >= 0x80
        val sy = y >= 0x80
        val sz = zAxis >= 0x80

        for (j in 0 until 3) {
            val offset = j * 3
            for (i in 0 until 3) {
                if (xi == i) outR[offset + i] = if (sx) -inR[offset + 0] else inR[offset + 0]
                if (yi == i) outR[offset + i] = if (sy) -inR[offset + 1] else inR[offset + 1]
                if (zi == i) outR[offset + i] = if (sz) -inR[offset + 2] else inR[offset + 2]
            }
        }
    }

    // endregion

    /**
     * `out = C_W_A · inR`, with
     * ```
     *         ⎡ 1  0  0 ⎤
     * C_W_A = ⎢ 0  0  1 ⎥   (Android East→+X, Up→+Y, North→−Z)
     *         ⎣ 0 -1  0 ⎦
     * ```
     */
    private fun applyWorldConversion(inR: FloatArray, out: FloatArray) {
        for (col in 0 until 3) {
            val a = inR[0 * 3 + col]
            val b = inR[1 * 3 + col]
            val c = inR[2 * 3 + col]
            out[0 * 3 + col] = a
            out[1 * 3 + col] = c
            out[2 * 3 + col] = -b
        }
    }

    private fun writeColumnMajor4x4(rowMajor3x3: FloatArray, out: FloatArray) {
        for (i in 0 until 16) out[i] = 0f
        out[15] = 1f
        for (row in 0 until 3) {
            for (col in 0 until 3) {
                out[col * 4 + row] = rowMajor3x3[row * 3 + col]
            }
        }
    }

    companion object {
        const val ROTATION_0 = 0
        const val ROTATION_90 = 1
        const val ROTATION_180 = 2
        const val ROTATION_270 = 3

        // SensorManager.AXIS_* constants.
        private const val AXIS_X = 1
        private const val AXIS_Y = 2
        private const val AXIS_MINUS_X = 0x80 or 1
        private const val AXIS_MINUS_Y = 0x80 or 2
    }
}
