package com.daydreamvr.vrcore.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PanelQuadTest {

    @Test
    fun verticesHaveCorrectTextureCoordinateOrientation() {
        val quad = PanelQuad(widthM = 2f, heightM = 1f, curved = false)
        val verts = quad.buildVertices(hSegments = 1, vSegments = 1)
        val stride = PanelQuad.FLOATS_PER_VERTEX

        // Search for bottom vertices (y < 0) and top vertices (y > 0)
        var sawBottom = false
        var sawTop = false
        var i = 0
        while (i < verts.size) {
            val y = verts[i + 1]
            val v = verts[i + 4]
            if (y < -0.4f) {
                // Bottom of quad in 3D must have v = 0 (before SurfaceTexture transform)
                assertThat(v).isWithin(1e-4f).of(0f)
                sawBottom = true
            } else if (y > 0.4f) {
                // Top of quad in 3D must have v = 1 (before SurfaceTexture transform)
                assertThat(v).isWithin(1e-4f).of(1f)
                sawTop = true
            }
            i += stride
        }
        assertThat(sawBottom).isTrue()
        assertThat(sawTop).isTrue()
    }
}
