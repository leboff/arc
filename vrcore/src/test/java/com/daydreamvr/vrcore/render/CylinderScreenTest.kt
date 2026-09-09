package com.daydreamvr.vrcore.render

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.sqrt

class CylinderScreenTest {

    private val stride = CylinderScreen.FLOATS_PER_VERTEX

    @Test
    fun meshHasTwoTrianglesPerQuad() {
        val verts = CylinderScreen().buildVertices(hSegments = 48, vSegments = 24)
        assertThat(verts.size / stride).isEqualTo(48 * 24 * 6)
    }

    @Test
    fun everyVertexLiesOnTheCylinderRadius() {
        val screen = CylinderScreen(radiusM = 4f, widthDegrees = 90f)
        screen.setAspect(1.85f)
        val verts = screen.buildVertices(24, 12)

        var v = 0
        while (v < verts.size) {
            val x = verts[v]
            val z = verts[v + 2]
            assertThat(sqrt(x * x + z * z)).isWithin(1e-3f).of(4f)
            v += stride
        }
    }

    @Test
    fun allTrianglesWindTowardTheViewer() {
        val verts = CylinderScreen(radiusM = 4f, widthDegrees = 60f).buildVertices(12, 6)

        var t = 0
        while (t < verts.size) {
            val ax = verts[t]; val ay = verts[t + 1]; val az = verts[t + 2]
            val bx = verts[t + stride]; val by = verts[t + stride + 1]; val bz = verts[t + stride + 2]
            val cx = verts[t + 2 * stride]; val cy = verts[t + 2 * stride + 1]; val cz = verts[t + 2 * stride + 2]

            // face normal = (b - a) x (c - a)
            val e1x = bx - ax; val e1y = by - ay; val e1z = bz - az
            val e2x = cx - ax; val e2y = cy - ay; val e2z = cz - az
            val nx = e1y * e2z - e1z * e2y
            val ny = e1z * e2x - e1x * e2z
            val nz = e1x * e2y - e1y * e2x

            // centroid → origin direction
            val gx = -(ax + bx + cx) / 3f
            val gy = -(ay + by + cy) / 3f
            val gz = -(az + bz + cz) / 3f

            assertThat(nx * gx + ny * gy + nz * gz).isGreaterThan(0f)
            t += 3 * stride
        }
    }

    @Test
    fun setAspectKeepsHorizontalDegreesAndShrinksVerticalExtent() {
        fun ranges(aspect: Float): Pair<Float, Float> {
            val s = CylinderScreen(radiusM = 4f, widthDegrees = 60f)
            s.setAspect(aspect)
            val verts = s.buildVertices(32, 16)
            var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            var v = 0
            while (v < verts.size) {
                minX = minOf(minX, verts[v]); maxX = maxOf(maxX, verts[v])
                minY = minOf(minY, verts[v + 1]); maxY = maxOf(maxY, verts[v + 1])
                v += stride
            }
            return (maxX - minX) to (maxY - minY)
        }

        val (wideX, wideY) = ranges(1.0f)
        val (narrowX, narrowY) = ranges(2.35f)

        assertThat(narrowX).isWithin(1e-3f).of(wideX) // horizontal extent unchanged
        assertThat(wideY / narrowY).isWithin(1e-2f).of(2.35f) // vertical shrinks proportionally
    }
}
