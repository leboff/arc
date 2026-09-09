package com.daydreamvr.vrcore.optics
import com.google.common.truth.Truth.assertThat
import org.junit.Test
class RadialDistortionTest {
    private val k = RadialCoefficients(.34, .55)
    @Test fun forwardDirectionHasIndependentAnalyticFixtures() {
        assertThat(RadialDistortion.screenToRay(Vec2(.5, 0.0), k).x).isWithin(1e-12).of(.5596875)
        val y = RadialDistortion.screenToRay(Vec2(.3, .4), k)
        assertThat(y.x).isWithin(1e-12).of(.3358125); assertThat(y.y).isWithin(1e-12).of(.44775)
    }
    @Test fun inverseIsAccurateAndZeroExact() {
        assertThat(RadialDistortion.rayToScreen(Vec2(0.0, 0.0), k)).isEqualTo(Vec2(0.0, 0.0))
        for (i in 0..1000) { val r = i / 500.0; val ray = RadialDistortion.screenToRay(RadialDistortion.rayToScreen(Vec2(r, 0.0), k), k); assertThat(ray.x).isWithin(1e-9 * maxOf(1.0, r)).of(r) }
    }
}
