package com.daydreamvr.vrcore.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class PanelRaycastTest {

    private val browse = PanelGeometry(
        modelYawRad = 0f,
        distanceM = 2.5f,
        widthM = 2.4f,
        heightM = 1.5f,
        verticalOffsetM = 0f,
        widthPx = 1280,
        heightPx = 800,
        curved = true,
    )

    /** Unit ray from azimuth/pitch, origin at the world origin. */
    private fun ray(azDeg: Double, pitchDeg: Double): Ray {
        val a = azDeg * PI / 180.0
        val p = pitchDeg * PI / 180.0
        val k = cos(p)
        return Ray(0f, 0f, 0f, (k * sin(a)).toFloat(), sin(p).toFloat(), (-k * cos(a)).toFloat())
    }

    @Test
    fun workedVectorFromThePlan() {
        val hit = PanelRaycast.intersect(ray(10.0, -5.0), browse)!!
        assertThat(hit.u).isWithin(2e-4f).of(0.681806f)
        assertThat(hit.v).isWithin(2e-4f).of(0.354188f)
        assertThat(hit.xPx).isWithin(0.3f).of(872.7f)
        assertThat(hit.yPx).isWithin(0.3f).of(516.7f)
        assertThat(hit.distanceM).isWithin(1e-3f).of(2.509549f)
    }

    @Test
    fun straightAheadIsPanelCentre() {
        val hit = PanelRaycast.intersect(ray(0.0, 0.0), browse)!!
        assertThat(hit.u).isWithin(1e-5f).of(0.5f)
        assertThat(hit.v).isWithin(1e-5f).of(0.5f)
        assertThat(hit.xPx).isWithin(1e-3f).of(640f)
        assertThat(hit.yPx).isWithin(1e-3f).of(400f)
    }

    @Test
    fun theRightEdgeIsInclusiveAndBeyondItMisses() {
        val arcHalfDeg = Math.toDegrees((browse.widthM / browse.distanceM).toDouble()) / 2.0 // 27.5°
        assertThat(PanelRaycast.intersect(ray(arcHalfDeg, 0.0), browse)!!.u).isWithin(1e-4f).of(1f)
        assertThat(PanelRaycast.intersect(ray(arcHalfDeg + 0.5, 0.0), browse)).isNull()
    }

    @Test
    fun uIsMonotonicInAzimuthAndVMonotonicInPitch() {
        val u0 = PanelRaycast.intersect(ray(-12.0, 0.0), browse)!!.u
        val u1 = PanelRaycast.intersect(ray(0.0, 0.0), browse)!!.u
        val u2 = PanelRaycast.intersect(ray(12.0, 0.0), browse)!!.u
        assertThat(u0).isLessThan(u1)
        assertThat(u1).isLessThan(u2)

        val vDown = PanelRaycast.intersect(ray(0.0, -8.0), browse)!!.v
        val vUp = PanelRaycast.intersect(ray(0.0, 8.0), browse)!!.v
        assertThat(vDown).isLessThan(0.5f)
        assertThat(vUp).isGreaterThan(0.5f)
    }

    @Test
    fun anchorYawShiftsTheHitByTheSameAngle() {
        val anchored = browse.copy(modelYawRad = Math.toRadians(-10.0).toFloat())
        // Panel centre azimuth is -modelYawRad = +10°; gaze at +10° → u = 0.5
        val hit = PanelRaycast.intersect(ray(10.0, 0.0), anchored)!!
        assertThat(hit.u).isWithin(1e-4f).of(0.5f)
    }

    @Test
    fun raysBehindOrStraightUpMiss() {
        assertThat(PanelRaycast.intersect(ray(180.0, 0.0), browse)).isNull()
        assertThat(PanelRaycast.intersect(ray(0.0, 85.0), browse)).isNull()
    }

    @Test
    fun wrapPiHandlesTheSeam() {
        val nearSeam = browse.copy(modelYawRad = 3.0f) // centre azimuth ≈ -171.9°
        val hit = PanelRaycast.intersect(ray(-171.887, 0.0), nearSeam)!!
        assertThat(hit.u).isWithin(2e-3f).of(0.5f)
    }

    @Test
    fun unboundedReturnsASurfacePointOffPanel() {
        assertThat(PanelRaycast.intersect(ray(40.0, 0.0), browse)).isNull()
        assertThat(PanelRaycast.intersectUnbounded(ray(40.0, 0.0), browse)).isNotNull()
    }
}
