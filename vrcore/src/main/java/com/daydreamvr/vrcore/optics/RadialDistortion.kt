package com.daydreamvr.vrcore.optics

import kotlin.math.hypot

/**
 * Pure forward and inverse radial distortion mapping (ARCHITECTURE.md / DISTORTION_REMEDIATION_PLAN §2.2, §2.4).
 *
 * All inputs and outputs are dimensionless tangent quantities:
 * `s = (p - c) / d` (screen tangent)
 * `t = D(s) = s * (1 + k1*r² + k2*r⁴)` (ray tangent)
 *
 * Convention: SCREEN_TANGENT_TO_RAY_TANGENT_V1.
 */
object RadialDistortion {

    /**
     * Maps screen tangent `s = (p - c) / d` to source ray tangent `t = (rayX / -rayZ, rayY / -rayZ)`.
     * Pure, forward Brown-Conrady polynomial, even terms only.
     */
    fun screenToRay(screen: Vec2, coefficients: RadialCoefficients): Vec2 {
        validateInputs(screen, coefficients)
        if (coefficients.k1 == 0.0 && coefficients.k2 == 0.0) return screen
        val r2 = screen.x * screen.x + screen.y * screen.y
        val factor = 1.0 + coefficients.k1 * r2 + coefficients.k2 * r2 * r2
        if (!factor.isFinite()) throw OpticsValidationException("Non-finite radial distortion factor: $factor")
        return Vec2(screen.x * factor, screen.y * factor)
    }

    /**
     * Inverts [screenToRay]: given a source ray tangent `t`, finds the screen tangent `s` such that `D(s) = t`.
     * Identity special case permitted (§2.4).
     * Uses 64-step bisection on the monotonic interval `[0, ||t||]`.
     * Midpoint residual is guaranteed <= `1e-9 * max(1, ||t||)` over the supported domain.
     */
    fun rayToScreen(ray: Vec2, coefficients: RadialCoefficients): Vec2 {
        validateInputs(ray, coefficients)
        if (coefficients.k1 == 0.0 && coefficients.k2 == 0.0) return ray
        val target = hypot(ray.x, ray.y)
        if (target == 0.0) return Vec2(0.0, 0.0)

        // For non-negative k1, k2 >= 0, s * (1 + k1*s² + k2*s⁴) >= s for all s >= 0.
        // Therefore at s = target, mapped >= target, and at s = 0, mapped = 0 <= target.
        var lo = 0.0
        var hi = target
        repeat(64) {
            val mid = (lo + hi) / 2.0
            val mid2 = mid * mid
            val mapped = mid * (1.0 + coefficients.k1 * mid2 + coefficients.k2 * mid2 * mid2)
            if (mapped < target) {
                lo = mid
            } else {
                hi = mid
            }
        }
        val radius = (lo + hi) / 2.0
        val scale = radius / target
        return Vec2(ray.x * scale, ray.y * scale)
    }

    private fun validateInputs(v: Vec2, c: RadialCoefficients) {
        if (!v.x.isFinite() || !v.y.isFinite()) {
            throw OpticsValidationException("Non-finite coordinates: x=${v.x}, y=${v.y}")
        }
        if (!c.k1.isFinite() || !c.k2.isFinite() || c.k1 !in 0.0..1.0 || c.k2 !in 0.0..1.0) {
            throw OpticsValidationException("Coefficients out of supported domain [0, 1]: k1=${c.k1}, k2=${c.k2}")
        }
    }
}
