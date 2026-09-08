package com.daydreamvr.vrcore.math

/**
 * Pure-Kotlin column-major 4×4 matrix helpers, mirroring the subset of
 * `android.opengl.Matrix` the tracking code needs, so [FrameConverter] and its
 * tests run off-device (ARCHITECTURE.md §5.3, §7.2).
 *
 * Column-major: element `(row, col)` is at `m[col * 4 + row]`; `multiplyMM`
 * computes `out = lhs · rhs` with the same operand order as
 * `android.opengl.Matrix.multiplyMM`.
 */
object Matrix4 {

    fun identity(out: FloatArray) {
        for (i in 0 until 16) out[i] = 0f
        out[0] = 1f
        out[5] = 1f
        out[10] = 1f
        out[15] = 1f
    }

    /** `out = lhs · rhs`. [out] must not alias [lhs] or [rhs]. */
    fun multiplyMM(out: FloatArray, lhs: FloatArray, rhs: FloatArray) {
        for (col in 0 until 4) {
            val rc = col * 4
            for (row in 0 until 4) {
                out[rc + row] =
                    lhs[row] * rhs[rc] +
                    lhs[4 + row] * rhs[rc + 1] +
                    lhs[8 + row] * rhs[rc + 2] +
                    lhs[12 + row] * rhs[rc + 3]
            }
        }
    }

    /** `out = m · v`, both 4-vectors. [out] must not alias [v]. */
    fun multiplyMV(out: FloatArray, m: FloatArray, v: FloatArray) {
        for (row in 0 until 4) {
            out[row] = m[row] * v[0] + m[4 + row] * v[1] + m[8 + row] * v[2] + m[12 + row] * v[3]
        }
    }

    /** `out = mᵀ`. Safe only when [out] does not alias [m]. */
    fun transpose(out: FloatArray, m: FloatArray) {
        for (col in 0 until 4) {
            for (row in 0 until 4) {
                out[col * 4 + row] = m[row * 4 + col]
            }
        }
    }

    /**
     * Writes the 3×3 [rowMajor3x3] rotation (9 elements, `r[row * 3 + col]`) into
     * a column-major 4×4 [out], with a zero translation.
     */
    fun fromRowMajor3x3(out: FloatArray, rowMajor3x3: FloatArray) {
        identity(out)
        for (row in 0 until 3) {
            for (col in 0 until 3) {
                out[col * 4 + row] = rowMajor3x3[row * 3 + col]
            }
        }
    }
}
