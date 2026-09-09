package com.daydreamvr.vrcore.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class WorldYawTest {
    @Test fun integratesRateIndependentlyOfFrameRateAndStopsImmediately() {
        val a = WorldYaw()
        val b = WorldYaw()
        repeat(60) { a.update(1f, 1f / 60) }
        repeat(120) { b.update(1f, 1f / 120) }
        assertEquals(Math.toRadians(40.0).toFloat(), a.offsetRad, 0.0001f)
        assertEquals(a.offsetRad, b.offsetRad, 0.0001f)
        val stopped = a.offsetRad
        a.update(0f, 1f)
        assertEquals(stopped, a.offsetRad, 0.000001f)
        a.update(-1f, 1f)
        assertEquals(0f, a.offsetRad, 0.0001f)
    }

    @Test fun boundsRateAndWrapsBothDirections() {
        for (rate in listOf(-10f, 10f)) {
            val yaw = WorldYaw()
            repeat(1000) {
                yaw.update(rate, 0.1f)
                assertTrue(yaw.offsetRad >= -PI && yaw.offsetRad <= PI)
            }
        }
    }
}
