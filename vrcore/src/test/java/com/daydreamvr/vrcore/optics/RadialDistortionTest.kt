package com.daydreamvr.vrcore.optics

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class RadialDistortionTest {

    private val k = RadialCoefficients(0.34, 0.55)

    @Test
    fun forwardDirectionMatchesMandatoryAnalyticFixtures() {
        // From DISTORTION_REMEDIATION_PLAN §8.1:
        // k=(0.34, 0.55), s=(0.5, 0) gives factor=1.119375, t=(0.5596875, 0).
        val t1 = RadialDistortion.screenToRay(Vec2(0.5, 0.0), k)
        assertThat(t1.x).isWithin(1e-12).of(0.5596875)
        assertThat(t1.y).isWithin(1e-12).of(0.0)

        // At s=(0.3, 0.4) the same radius gives t=(0.3358125, 0.44775).
        val t2 = RadialDistortion.screenToRay(Vec2(0.3, 0.4), k)
        assertThat(t2.x).isWithin(1e-12).of(0.3358125)
        assertThat(t2.y).isWithin(1e-12).of(0.44775)
    }

    @Test
    fun inverseIsAccurateAcross1001SampleRadiiAndZeroExact() {
        // Exactly (0, 0) at origin
        assertThat(RadialDistortion.rayToScreen(Vec2(0.0, 0.0), k)).isEqualTo(Vec2(0.0, 0.0))

        // 1001 uniformly spaced radii from 0 to 2.0 (well above maximum physical corner radius)
        for (i in 0..1000) {
            val r = i * (2.0 / 1000.0)
            val ray = Vec2(r, 0.0)
            val screen = RadialDistortion.rayToScreen(ray, k)
            val roundTrip = RadialDistortion.screenToRay(screen, k)
            // Residual must be <= 1e-9 * max(1, R)
            val maxResidual = 1e-9 * maxOf(1.0, r)
            assertThat(roundTrip.x).isWithin(maxResidual).of(r)
            assertThat(roundTrip.y).isWithin(1e-12).of(0.0)
        }
    }

    @Test
    fun identityPolynomialPreservesScreenAndRayVectorsExactly() {
        val identity = RadialCoefficients(0.0, 0.0)
        val sample = Vec2(0.35, 0.72)
        assertThat(RadialDistortion.screenToRay(sample, identity)).isEqualTo(sample)
        assertThat(RadialDistortion.rayToScreen(sample, identity)).isEqualTo(sample)
    }

    @Test
    fun nonFiniteOrOutOfDomainInputsThrowOpticsValidationException() {
        assertThrows(OpticsValidationException::class.java) {
            RadialDistortion.screenToRay(Vec2(Double.NaN, 0.0), k)
        }
        assertThrows(OpticsValidationException::class.java) {
            RadialDistortion.screenToRay(Vec2(0.5, Double.POSITIVE_INFINITY), k)
        }
        assertThrows(OpticsValidationException::class.java) {
            RadialDistortion.rayToScreen(Vec2(Double.NEGATIVE_INFINITY, 0.0), k)
        }
    }
}
