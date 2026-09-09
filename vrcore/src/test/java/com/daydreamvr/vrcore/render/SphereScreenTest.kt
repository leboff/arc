package com.daydreamvr.vrcore.render

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.sqrt

class SphereScreenTest {
    @Test fun domesHaveSelectedEdgeAzimuthsAndCorrectRadius() {
        for (fov in DomeFov.entries) {
            val sphere = SphereScreen(radiusM = 7f, domeFovDegrees = fov.degrees)
            for (mode in ProjectionMode.entries.filter { it.domeFov != null }) {
                val vertices = sphere.buildVertices(mode, 8, 2).toList().chunked(5)
                val equator = vertices.filter { it[1] == 0f }
                val azimuths = equator.map { Math.toDegrees(atan2(it[0], -it[2]).toDouble()) }
                assertEquals(-fov.degrees / 2.0, azimuths.min(), 0.001)
                assertEquals(fov.degrees / 2.0, azimuths.max(), 0.001)
                for (v in vertices) assertEquals(7f, sqrt(v[0]*v[0]+v[1]*v[1]+v[2]*v[2]), 0.00001f)
            }
        }
    }

    @Test fun fullSphereIgnoresDomeFov() {
        val vertices = SphereScreen(domeFovDegrees = 220)
            .buildVertices(ProjectionMode.EQUIRECT_360, 8, 2).toList().chunked(5)
        val edges = vertices.filter { it[1] == 0f && (it[3] == 0f || it[3] == 1f) }
        for (v in edges) {
            assertEquals(0f, v[0], 0.0001f)
            assertEquals(50f, v[2], 0.0001f)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedFov() { SphereScreen(domeFovDegrees = 360) }
}
