package com.daydreamvr.vrcore.optics

import com.daydreamvr.vrcore.render.Eye
import com.daydreamvr.vrcore.render.StereoLayout
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.hypot

class OpticsGeometryTest {

    private fun asymmetricFixture(
        divider: Int = 0,
        insets: PixelInsets = PixelInsets(),
        surfaceW: Int = 2000,
        surfaceH: Int = 1000,
        coeffs: RadialCoefficients = RadialCoefficients(0.0, 0.0),
        capsDeg: Double = 80.0,
        verticalOffsetM: Double = 0.0,
        ipdM: Double = 0.064,
    ): StereoOptics {
        val display = DisplayGeometry(
            panelWidthM = 0.140,
            panelHeightM = 0.070,
            surfaceWidthPx = surfaceW,
            surfaceHeightPx = surfaceH,
            usableInsets = insets,
        )
        val viewer = ViewerOptics(
            profileId = "fixture",
            lensSeparationM = 0.064,
            screenToLensM = 0.040,
            verticalAlignment = VerticalAlignment.CENTER,
            verticalOffsetM = verticalOffsetM,
            coefficients = coeffs,
            maxFov = MaxFov(capsDeg, capsDeg, capsDeg, capsDeg),
            dividerPx = divider,
        )
        val observer = ObserverGeometry(ipdM)
        return OpticsGeometry.compute(display, viewer, observer)
    }

    @Test
    fun asymmetricFullPhysicalFixtureMatchesExactAnalyticValues() {
        val s = asymmetricFixture()

        // Panel lens centers
        assertThat(s.left.lensCenterPanelM.x).isWithin(1e-9).of(0.038)
        assertThat(s.left.lensCenterPanelM.y).isWithin(1e-9).of(0.035)
        assertThat(s.right.lensCenterPanelM.x).isWithin(1e-9).of(0.102)
        assertThat(s.right.lensCenterPanelM.y).isWithin(1e-9).of(0.035)

        // Tangent source bounds
        assertThat(s.left.sourceBounds.left).isWithin(1e-9).of(-0.95)
        assertThat(s.left.sourceBounds.right).isWithin(1e-9).of(0.80)
        assertThat(s.left.sourceBounds.bottom).isWithin(1e-9).of(-0.875)
        assertThat(s.left.sourceBounds.top).isWithin(1e-9).of(0.875)

        assertThat(s.right.sourceBounds.left).isWithin(1e-9).of(-0.80)
        assertThat(s.right.sourceBounds.right).isWithin(1e-9).of(0.95)
        assertThat(s.right.sourceBounds.bottom).isWithin(1e-9).of(-0.875)
        assertThat(s.right.sourceBounds.top).isWithin(1e-9).of(0.875)

        // Center UVs
        val leftUvX = -s.left.sourceBounds.left / (s.left.sourceBounds.right - s.left.sourceBounds.left)
        val leftUvY = -s.left.sourceBounds.bottom / (s.left.sourceBounds.top - s.left.sourceBounds.bottom)
        assertThat(leftUvX).isWithin(1e-9).of(19.0 / 35.0)
        assertThat(leftUvY).isWithin(1e-9).of(0.5)

        val rightUvX = -s.right.sourceBounds.left / (s.right.sourceBounds.right - s.right.sourceBounds.left)
        val rightUvY = -s.right.sourceBounds.bottom / (s.right.sourceBounds.top - s.right.sourceBounds.bottom)
        assertThat(rightUvX).isWithin(1e-9).of(16.0 / 35.0)
        assertThat(rightUvY).isWithin(1e-9).of(0.5)

        // Center surface X coordinates
        val leftSurfX = 2000.0 * (s.left.lensCenterPanelM.x / 0.140)
        val rightSurfX = 2000.0 * (s.right.lensCenterPanelM.x / 0.140)
        assertThat(leftSurfX).isWithin(1e-6).of(542.857142857)
        assertThat(rightSurfX).isWithin(1e-6).of(1457.142857143)
    }

    @Test
    fun independentNonzeroLookupMatchesAnalyticForwardUV() {
        // Physical fixture with k=(0.34, 0.55), caps 45° -> source bounds [-1, 1]²
        val s = asymmetricFixture(coeffs = RadialCoefficients(0.34, 0.55), capsDeg = 45.0)
        assertThat(s.left.sourceBounds.left).isWithin(1e-9).of(-1.0)
        assertThat(s.left.sourceBounds.right).isWithin(1e-9).of(1.0)
        assertThat(s.left.sourceBounds.bottom).isWithin(1e-9).of(-1.0)
        assertThat(s.left.sourceBounds.top).isWithin(1e-9).of(1.0)

        // Left physical point (0.058, 0.035) gives screen tangent (0.5, 0), source UV (0.77984375, 0.5)
        val p = Vec2(0.058, 0.035)
        val screen = Vec2(
            (p.x - s.left.lensCenterPanelM.x) / s.left.screenToLensM,
            (p.y - s.left.lensCenterPanelM.y) / s.left.screenToLensM,
        )
        assertThat(screen.x).isWithin(1e-9).of(0.5)
        assertThat(screen.y).isWithin(1e-9).of(0.0)

        val ray = RadialDistortion.screenToRay(screen, s.left.coefficients)
        val uvX = (ray.x - s.left.sourceBounds.left) / (s.left.sourceBounds.right - s.left.sourceBounds.left)
        val uvY = (ray.y - s.left.sourceBounds.bottom) / (s.left.sourceBounds.top - s.left.sourceBounds.bottom)

        assertThat(uvX).isWithin(1e-8).of(0.77984375)
        assertThat(uvY).isWithin(1e-8).of(0.5)
    }

    @Test
    fun centerOffsetPreservesPhysicalVFractionWithoutSilentRecentering() {
        // cy = 0.030 m (panel height = 0.070 m, center = 0.035 m, offset = -0.005 m)
        val s = asymmetricFixture(verticalOffsetM = -0.005)
        assertThat(s.left.sourceBounds.bottom).isWithin(1e-9).of(-0.75)
        assertThat(s.left.sourceBounds.top).isWithin(1e-9).of(1.0)

        val centerV = -s.left.sourceBounds.bottom / (s.left.sourceBounds.top - s.left.sourceBounds.bottom)
        assertThat(centerV).isWithin(1e-9).of(3.0 / 7.0)
    }

    @Test
    fun cropFixtureValidatesDividerSplittingAndAsymmetricInsets() {
        // Divider 8 px in 2000x1000 surface
        val s = asymmetricFixture(divider = 8)
        assertThat(s.left.viewport.x).isEqualTo(0)
        assertThat(s.left.viewport.width).isEqualTo(996)
        assertThat(s.right.viewport.x).isEqualTo(1004)
        assertThat(s.right.viewport.width).isEqualTo(996)

        // Inner physical tangent magnitude is 0.793, outer stays 0.95
        assertThat(s.left.sourceBounds.right).isWithin(1e-9).of(0.793)
        assertThat(s.left.sourceBounds.left).isWithin(1e-9).of(-0.95)

        // Extra left outer inset of 10 px
        val sInset = asymmetricFixture(divider = 8, insets = PixelInsets(left = 10))
        assertThat(sInset.left.viewport.x).isEqualTo(10)
        assertThat(sInset.left.viewport.width).isEqualTo(986)
        assertThat(sInset.left.sourceBounds.left).isWithin(1e-9).of(-0.9325)
        // Physical lens centers remain unchanged
        assertThat(sInset.left.lensCenterPanelM).isEqualTo(s.left.lensCenterPanelM)

        // Odd width Nx=2001, D=9 -> divider [996, 1005)
        val sOdd = asymmetricFixture(divider = 9, surfaceW = 2001)
        assertThat(sOdd.left.viewport.width).isEqualTo(996)
        assertThat(sOdd.right.viewport.x).isEqualTo(1005)
        assertThat(sOdd.right.viewport.width).isEqualTo(996)
    }

    @Test
    fun metricIsotropyProducesEqualTangentRadiusForEqualMetreOffsets() {
        val s = asymmetricFixture(coeffs = RadialCoefficients(0.34, 0.55))
        val delta = 0.012 // 12 mm offset
        val cx = s.left.lensCenterPanelM.x
        val cy = s.left.lensCenterPanelM.y
        val d = s.left.screenToLensM

        val rayX = RadialDistortion.screenToRay(Vec2(delta / d, 0.0), s.left.coefficients)
        val rayY = RadialDistortion.screenToRay(Vec2(0.0, delta / d), s.left.coefficients)

        assertThat(hypot(rayX.x, rayX.y)).isWithin(1e-12).of(hypot(rayY.x, rayY.y))

        // Doubling surface resolution to 4000x2000 on same 0.140x0.070m panel leaves physical bounds invariant
        val sHiRes = asymmetricFixture(surfaceW = 4000, surfaceH = 2000, coeffs = RadialCoefficients(0.34, 0.55))
        assertThat(sHiRes.left.sourceBounds.left).isWithin(1e-9).of(s.left.sourceBounds.left)
        assertThat(sHiRes.left.sourceBounds.right).isWithin(1e-9).of(s.left.sourceBounds.right)
    }

    @Test
    fun ipdIsolationEnsuresObserverIpdDoesNotMoveFixedOpticsOrGeometryKey() {
        val s52 = asymmetricFixture(ipdM = 0.052)
        val s64 = asymmetricFixture(ipdM = 0.064)
        val s74 = asymmetricFixture(ipdM = 0.074)

        assertThat(s52.geometryKey).isEqualTo(s64.geometryKey)
        assertThat(s64.geometryKey).isEqualTo(s74.geometryKey)

        assertThat(s52.left.sourceBounds).isEqualTo(s64.left.sourceBounds)
        assertThat(s52.left.lensCenterPanelM).isEqualTo(s64.left.lensCenterPanelM)
    }

    @Test
    fun matrixCorrespondenceProjectsFrustumEdgesToNdcBounds() {
        val s = asymmetricFixture()
        val bounds = s.left.sourceBounds
        val proj = FloatArray(16)
        StereoLayout.projectionMatrix(bounds, near = 0.1f, far = 100f, out = proj)

        // Helper: project ray (tx, ty, -1) with z = -1
        fun project(tx: Double, ty: Double): Pair<Float, Float> {
            val near = 0.1
            val x = tx * near
            val y = ty * near
            val z = -near
            val clipX = proj[0] * x.toFloat() + proj[8] * z.toFloat()
            val clipY = proj[5] * y.toFloat() + proj[9] * z.toFloat()
            val clipW = -z.toFloat()
            return (clipX / clipW) to (clipY / clipW)
        }

        // Left edge
        val (ndcLeftX, _) = project(bounds.left, 0.0)
        assertThat(ndcLeftX).isWithin(1e-6f).of(-1.0f)

        // Right edge
        val (ndcRightX, _) = project(bounds.right, 0.0)
        assertThat(ndcRightX).isWithin(1e-6f).of(1.0f)

        // Bottom edge
        val (_, ndcBottomY) = project(0.0, bounds.bottom)
        assertThat(ndcBottomY).isWithin(1e-6f).of(-1.0f)

        // Top edge
        val (_, ndcTopY) = project(0.0, bounds.top)
        assertThat(ndcTopY).isWithin(1e-6f).of(1.0f)
    }
}
