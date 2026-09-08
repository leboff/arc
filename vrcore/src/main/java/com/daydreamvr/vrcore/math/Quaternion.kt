package com.daydreamvr.vrcore.math

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Unit-quaternion orientation math, Hamilton convention, active rotation,
 * `q ⊗ p` meaning "apply `p` then `q`" (ARCHITECTURE.md §5.3).
 *
 * Storage is a `FloatArray(4)` laid out `[w, x, y, z]`. Every function takes an
 * explicit `out` buffer so the render / tracking hot paths never allocate
 * (zero-allocation is enforced in Phase 6). `out` may alias an input.
 *
 * Matrices are column-major `FloatArray(16)` to feed `android.opengl.Matrix` and
 * `glUniformMatrix4fv` directly; element `(row, col)` lives at `m[col * 4 + row]`.
 */
object Quat {

    fun identity(out: FloatArray) {
        out[0] = 1f
        out[1] = 0f
        out[2] = 0f
        out[3] = 0f
    }

    /**
     * Quaternion for a rotation of [radians] about the axis `(ax, ay, az)`. The
     * axis is normalised here; a zero-length axis yields the identity.
     */
    fun fromAxisAngle(ax: Float, ay: Float, az: Float, radians: Float, out: FloatArray) {
        val len = sqrt(ax * ax + ay * ay + az * az)
        if (len < 1e-12f) {
            identity(out)
            return
        }
        val half = radians * 0.5f
        val s = sin(half) / len
        out[0] = cos(half)
        out[1] = ax * s
        out[2] = ay * s
        out[3] = az * s
    }

    /** `out = a ⊗ b`. Safe when `out` aliases `a` and/or `b`. */
    fun multiply(a: FloatArray, b: FloatArray, out: FloatArray) {
        val aw = a[0]
        val ax = a[1]
        val ay = a[2]
        val az = a[3]
        val bw = b[0]
        val bx = b[1]
        val by = b[2]
        val bz = b[3]
        out[0] = aw * bw - ax * bx - ay * by - az * bz
        out[1] = aw * bx + ax * bw + ay * bz - az * by
        out[2] = aw * by - ax * bz + ay * bw + az * bx
        out[3] = aw * bz + ax * by - ay * bx + az * bw
    }

    fun conjugate(q: FloatArray, out: FloatArray) {
        out[0] = q[0]
        out[1] = -q[1]
        out[2] = -q[2]
        out[3] = -q[3]
    }

    /** Normalises [q] in place; a (near) zero quaternion becomes the identity. */
    fun normalize(q: FloatArray) {
        val n = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        if (n < 1e-12f) {
            identity(q)
            return
        }
        val inv = 1f / n
        q[0] *= inv
        q[1] *= inv
        q[2] *= inv
        q[3] *= inv
    }

    /** Column-major 4×4 rotation matrix for the (assumed unit) quaternion [q]. */
    fun toMatrix(q: FloatArray, out: FloatArray) {
        val w = q[0]
        val x = q[1]
        val y = q[2]
        val z = q[3]
        val xx = x * x
        val yy = y * y
        val zz = z * z
        val xy = x * y
        val xz = x * z
        val yz = y * z
        val wx = w * x
        val wy = w * y
        val wz = w * z

        out[0] = 1f - 2f * (yy + zz)
        out[1] = 2f * (xy + wz)
        out[2] = 2f * (xz - wy)
        out[3] = 0f

        out[4] = 2f * (xy - wz)
        out[5] = 1f - 2f * (xx + zz)
        out[6] = 2f * (yz + wx)
        out[7] = 0f

        out[8] = 2f * (xz + wy)
        out[9] = 2f * (yz - wx)
        out[10] = 1f - 2f * (xx + yy)
        out[11] = 0f

        out[12] = 0f
        out[13] = 0f
        out[14] = 0f
        out[15] = 1f
    }

    /** Quaternion from the rotation block of a column-major 4×4 matrix [m]. */
    fun fromMatrix(m: FloatArray, out: FloatArray) {
        val m00 = m[0]
        val m10 = m[1]
        val m20 = m[2]
        val m01 = m[4]
        val m11 = m[5]
        val m21 = m[6]
        val m02 = m[8]
        val m12 = m[9]
        val m22 = m[10]

        val trace = m00 + m11 + m22
        when {
            trace > 0f -> {
                var s = sqrt(trace + 1f)
                out[0] = s * 0.5f
                s = 0.5f / s
                out[1] = (m21 - m12) * s
                out[2] = (m02 - m20) * s
                out[3] = (m10 - m01) * s
            }
            m00 >= m11 && m00 >= m22 -> {
                var s = sqrt(1f + m00 - m11 - m22)
                out[1] = s * 0.5f
                s = 0.5f / s
                out[0] = (m21 - m12) * s
                out[2] = (m10 + m01) * s
                out[3] = (m02 + m20) * s
            }
            m11 >= m22 -> {
                var s = sqrt(1f + m11 - m00 - m22)
                out[2] = s * 0.5f
                s = 0.5f / s
                out[0] = (m02 - m20) * s
                out[1] = (m10 + m01) * s
                out[3] = (m21 + m12) * s
            }
            else -> {
                var s = sqrt(1f + m22 - m00 - m11)
                out[3] = s * 0.5f
                s = 0.5f / s
                out[0] = (m10 - m01) * s
                out[1] = (m02 + m20) * s
                out[2] = (m21 + m12) * s
            }
        }
        normalize(out)
    }

    /**
     * Shortest-path spherical linear interpolation. [t] is clamped to `[0, 1]`.
     * Handles the negated-`w` case (great-circle antipode). Safe when [out]
     * aliases [a] or [b].
     */
    fun slerp(a: FloatArray, b: FloatArray, t: Float, out: FloatArray) {
        val tc = t.coerceIn(0f, 1f)
        var bw = b[0]
        var bx = b[1]
        var by = b[2]
        var bz = b[3]
        var dot = a[0] * bw + a[1] * bx + a[2] * by + a[3] * bz
        if (dot < 0f) {
            bw = -bw
            bx = -bx
            by = -by
            bz = -bz
            dot = -dot
        }

        if (dot > 0.9995f) {
            out[0] = a[0] + tc * (bw - a[0])
            out[1] = a[1] + tc * (bx - a[1])
            out[2] = a[2] + tc * (by - a[2])
            out[3] = a[3] + tc * (bz - a[3])
            normalize(out)
            return
        }

        val theta0 = acos(dot.coerceIn(-1f, 1f))
        val theta = theta0 * tc
        val sinTheta0 = sin(theta0)
        val s0 = sin(theta0 - theta) / sinTheta0
        val s1 = sin(theta) / sinTheta0
        out[0] = s0 * a[0] + s1 * bw
        out[1] = s0 * a[1] + s1 * bx
        out[2] = s0 * a[2] + s1 * by
        out[3] = s0 * a[3] + s1 * bz
    }

    /** Rotates the 3-vector [v] by [q]. [out] is a `FloatArray(3)`; may alias [v]. */
    fun rotateVector(q: FloatArray, v: FloatArray, out: FloatArray) {
        val w = q[0]
        val x = q[1]
        val y = q[2]
        val z = q[3]
        val vx = v[0]
        val vy = v[1]
        val vz = v[2]

        // t = 2 * cross(q.xyz, v)
        val tx = 2f * (y * vz - z * vy)
        val ty = 2f * (z * vx - x * vz)
        val tz = 2f * (x * vy - y * vx)

        // v' = v + w * t + cross(q.xyz, t)
        out[0] = vx + w * tx + (y * tz - z * ty)
        out[1] = vy + w * ty + (z * tx - x * tz)
        out[2] = vz + w * tz + (x * ty - y * tx)
    }

    /**
     * Swing-twist decomposition: returns the **twist**, the component of [q]
     * about [axis] (a `FloatArray(3)`, normalised here). The swing is then
     * `twist⁻¹ ⊗ q`, and `q == twist ⊗ swing`.
     *
     * Degenerate case (rotation ~180° about an axis orthogonal to [axis]) yields
     * the identity twist.
     */
    fun twistAbout(q: FloatArray, axis: FloatArray, out: FloatArray) {
        val len = sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2])
        if (len < 1e-12f) {
            identity(out)
            return
        }
        val ax = axis[0] / len
        val ay = axis[1] / len
        val az = axis[2] / len

        val proj = q[1] * ax + q[2] * ay + q[3] * az
        out[0] = q[0]
        out[1] = ax * proj
        out[2] = ay * proj
        out[3] = az * proj
        val n = sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2] + out[3] * out[3])
        if (n < 1e-6f) {
            identity(out)
            return
        }
        val inv = 1f / n
        out[0] *= inv
        out[1] *= inv
        out[2] *= inv
        out[3] *= inv
        if (out[0] < 0f) {
            out[0] = -out[0]
            out[1] = -out[1]
            out[2] = -out[2]
            out[3] = -out[3]
        }
    }

    /**
     * Integrates the body-frame angular velocity [omega] (rad/s, `FloatArray(3)`)
     * forward by [dt] seconds using the exponential map — exact for constant ω.
     * `out = q ⊗ exp(½ ω dt)`. Safe when [out] aliases [q].
     */
    fun integrateGyro(q: FloatArray, omega: FloatArray, dt: Float, out: FloatArray) {
        val wx = omega[0]
        val wy = omega[1]
        val wz = omega[2]
        val speed = sqrt(wx * wx + wy * wy + wz * wz)
        val angle = speed * dt
        if (angle < 1e-9f) {
            if (out !== q) {
                out[0] = q[0]
                out[1] = q[1]
                out[2] = q[2]
                out[3] = q[3]
            }
            normalize(out)
            return
        }
        val half = angle * 0.5f
        val s = sin(half) / speed
        val dw = cos(half)
        val dx = wx * s
        val dy = wy * s
        val dz = wz * s

        val qw = q[0]
        val qx = q[1]
        val qy = q[2]
        val qz = q[3]
        out[0] = qw * dw - qx * dx - qy * dy - qz * dz
        out[1] = qw * dx + qx * dw + qy * dz - qz * dy
        out[2] = qw * dy - qx * dz + qy * dw + qz * dx
        out[3] = qw * dz + qx * dy - qy * dx + qz * dw
        normalize(out)
    }

    /** Rotation angle (radians, `[0, π]`) of the assumed-unit quaternion [q]. */
    fun angle(q: FloatArray): Float {
        val w = abs(q[0]).coerceAtMost(1f)
        return 2f * acos(w)
    }
}
