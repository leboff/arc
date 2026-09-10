package com.daydreamvr.vrcore.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class FisheyeMappingTest {
    @Test fun forwardAndRimMapToDisk() {
        val center = FisheyeMapping.directionToEyeUv(0.0, 0.0, -1.0, 180)!!
        assertEquals(0.5, center.u, 1e-6); assertEquals(0.5, center.v, 1e-6)
        val rim = FisheyeMapping.directionToEyeUv(1.0, 0.0, 0.0, 180)!!
        assertEquals(1.0, rim.u, 1e-6); assertEquals(0.5, rim.v, 1e-6)
    }

    @Test fun widerLensRetainsBehindLateralDirections() {
        val theta = Math.toRadians(100.0)
        val uv = FisheyeMapping.directionToEyeUv(sin(theta), 0.0, -cos(theta), 220)!!
        assertEquals(0.5 + 0.5 * 100.0 / 110.0, uv.u, 1e-6)
        val outside = Math.toRadians(101.0)
        assertNull(FisheyeMapping.directionToEyeUv(sin(outside), 0.0, -cos(outside), 200))
    }
}
