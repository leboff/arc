package com.daydreamvr.vrcore.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Invariant P1 (UI_GAZE_PLAN.md §1.6): for any azimuth ψ, after `anchor.snapTo(ψ)`
 * the panel's centre direction must satisfy `atan2(centre.x, −centre.z) == ψ`.
 *
 * The panel model transform is `R_y(a) · T(0, y₀, −d)` with `a = modelYawRad =
 * −anchor.yawRad`. Rebuilt here in pure Kotlin — no `android.opengl.Matrix`.
 */
class PanelYawConventionTest {

    /** Mirrors `ScreenPanel.modelYawRad`. */
    private fun modelYawRad(anchor: PanelAnchor): Float = -anchor.yawRad

    /** Centre of the panel quad (local origin) pushed through `R_y(a)·T(0,y₀,−d)`. */
    private fun centreDir(a: Float, d: Float, y0: Float): Triple<Float, Float, Float> {
        // local centre (0, y0, -d) after R_y(a): (x·cos a + z·sin a, y, −x·sin a + z·cos a)
        val x = 0f
        val z = -d
        val xw = x * cos(a) + z * sin(a)
        val zw = -x * sin(a) + z * cos(a)
        return Triple(xw, y0, zw)
    }

    @Test
    fun centreAzimuthEqualsRequestedAzimuth() {
        for (psiDeg in listOf(-140.0, -90.0, -30.0, 0.0, 10.0, 45.0, 120.0)) {
            val psi = (psiDeg * PI / 180.0).toFloat()
            val anchor = PanelAnchor()
            anchor.snapTo(psi)
            val (x, _, z) = centreDir(modelYawRad(anchor), anchor.distanceM, 0f)
            assertThat(atan2(x, -z)).isWithin(1e-4f).of(psi)
        }
    }

    @Test
    fun gazeStraightAtACenteredPanelLandsOnU0_5() {
        // Panel anchored at 30°, gaze azimuth 30° → θ = ψ_hit + modelYawRad = 30° − 30° = 0 → u = 0.5
        val psi = (30.0 * PI / 180.0).toFloat()
        val anchor = PanelAnchor()
        anchor.snapTo(psi)
        val theta = psi + modelYawRad(anchor)
        assertThat(theta).isWithin(1e-5f).of(0f)
    }
}
