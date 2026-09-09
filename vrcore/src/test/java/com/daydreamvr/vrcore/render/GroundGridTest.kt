package com.daydreamvr.vrcore.render

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GroundGridTest {

    private val grid = GroundGrid()

    @Test
    fun buildVerticesReturnsSixVerticesAtGroundHeight() {
        val verts = grid.buildVertices()
        assertThat(verts.size).isEqualTo(18) // 6 vertices * 3 floats

        for (i in verts.indices step 3) {
            val x = verts[i]
            val y = verts[i + 1]
            val z = verts[i + 2]

            assertThat(y).isWithin(1e-4f).of(GroundGrid.GROUND_Y)
            assertThat(Math.abs(x)).isAtMost(GroundGrid.HALF_M)
            assertThat(Math.abs(z)).isAtMost(GroundGrid.HALF_M)
        }
    }

    @Test
    fun environmentEnvelopeFadesCorrectly() {
        // At or inside near hole (r <= 1.2m), envelope is 0
        assertThat(GroundGrid.environmentEnvelope(1.0f)).isWithin(1e-4f).of(0f)
        assertThat(GroundGrid.environmentEnvelope(GroundGrid.NEAR_M)).isWithin(1e-4f).of(0f)

        // Past near hole (around 2.5m - 3.0m), envelope is near full visibility (1.0)
        assertThat(GroundGrid.environmentEnvelope(2.5f)).isAtLeast(0.95f)
        assertThat(GroundGrid.environmentEnvelope(GroundGrid.FADE_START_M)).isAtLeast(0.95f)

        // Monotonic decrease between fadeStart and fadeEnd
        val midFade1 = GroundGrid.environmentEnvelope(5.0f)
        val midFade2 = GroundGrid.environmentEnvelope(8.0f)
        assertThat(midFade1).isGreaterThan(midFade2)

        // At or past fadeEnd (>= 12m), envelope is 0
        assertThat(GroundGrid.environmentEnvelope(GroundGrid.FADE_END_M)).isWithin(1e-4f).of(0f)
        assertThat(GroundGrid.environmentEnvelope(15.0f)).isWithin(1e-4f).of(0f)
    }

    @Test
    fun groundDistanceAtPitchMatchesGeometry() {
        // 90 degrees straight down: distance is exactly abs(GROUND_Y) = 1.2m
        val dist90 = GroundGrid.groundDistanceAtPitch(90f)
        assertThat(dist90).isWithin(1e-3f).of(1.2f)

        // 30 degrees down: sin(30) = 0.5 -> distance is 2.4m
        val dist30 = GroundGrid.groundDistanceAtPitch(30f)
        assertThat(dist30).isWithin(1e-3f).of(2.4f)
    }
}
