package com.daydreamvr.vrcore.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI

/**
 * Nearest-hit resolution across two surfaces at different depths
 * (UI_REDESIGN_REVIEWED_PLAN.md §3.3, M2 acceptance 1–3).
 */
class MultiSurfaceGazeTest {

    private fun panel(distanceM: Float, yawRad: Float = 0f, widthM: Float = 2.8f) = PanelGeometry(
        modelYawRad = yawRad,
        distanceM = distanceM,
        widthM = widthM,
        heightM = 1.45f,
        verticalOffsetM = 0f,
        widthPx = 1000,
        heightPx = 500,
        curved = true,
    )

    private val forward = Ray(0f, 0f, 0f, 0f, 0f, -1f)

    private fun surface(g: PanelGeometry, target: String?) =
        GazeSurfaces.Surface(g) { _, _ -> target }

    @Test
    fun aRayThroughBothQuadsResolvesToTheNearer() {
        val near = surface(panel(2.05f), "dock")
        val far = surface(panel(2.50f), "browse")

        val a = GazeSurfaces.resolve(forward, listOf(far, near))
        val b = GazeSurfaces.resolve(forward, listOf(near, far))

        assertThat(a.target).isEqualTo("dock")
        assertThat(b.target).isEqualTo("dock")
        assertThat(a.hit!!.distanceM).isWithin(1e-3f).of(2.05f)
    }

    @Test
    fun aRayHittingOnlyTheFarQuadResolvesToIt() {
        // Near panel rotated 90° away — the forward ray still meets its cylinder
        // but lands off the [0,1] extent, so intersect() returns null for it.
        val near = surface(panel(2.05f, yawRad = (PI / 2).toFloat()), "dock")
        val far = surface(panel(2.50f), "browse")

        val r = GazeSurfaces.resolve(forward, listOf(near, far))

        assertThat(r.target).isEqualTo("browse")
        assertThat(r.hit!!.distanceM).isWithin(1e-3f).of(2.50f)
    }

    @Test
    fun onNearPanelButOffEveryRegionYieldsNullNotTheFarTarget() {
        val near = surface(panel(2.05f), null)   // hit the panel, no region there
        val far = surface(panel(2.50f), "browse")

        val r = GazeSurfaces.resolve(forward, listOf(near, far))

        assertThat(r.hit!!.distanceM).isWithin(1e-3f).of(2.05f)
        assertThat(r.target).isNull()
    }

    @Test
    fun aRayMissingEverythingResolvesToNothing() {
        val up = Ray(0f, 0f, 0f, 0f, 1f, 0f)
        val r = GazeSurfaces.resolve(up, listOf(surface(panel(2.05f), "a"), surface(panel(2.5f), "b")))
        assertThat(r.hit).isNull()
        assertThat(r.target).isNull()
    }
}
