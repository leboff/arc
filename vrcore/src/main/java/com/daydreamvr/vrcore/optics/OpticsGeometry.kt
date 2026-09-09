package com.daydreamvr.vrcore.optics

import com.daydreamvr.vrcore.render.Eye
import com.daydreamvr.vrcore.render.Viewport
import kotlin.math.min
import kotlin.math.tan

/** The only radial convention accepted by the compositor. */
enum class RadialConvention { SCREEN_TANGENT_TO_RAY_TANGENT_V1 }
enum class ParameterConfidence { VERIFIED, PROVISIONAL, USER_CALIBRATED }
enum class VerticalAlignment { CENTER, BOTTOM }

data class RadialCoefficients(val k1: Double, val k2: Double)
data class TangentBounds(val left: Double, val right: Double, val bottom: Double, val top: Double) {
    init { require(left < 0.0 && right > 0.0 && bottom < 0.0 && top > 0.0) }
}
data class PixelInsets(val left: Int = 0, val right: Int = 0, val bottom: Int = 0, val top: Int = 0)
data class Affine2D(val sx: Double = 1.0, val sy: Double = 1.0, val tx: Double = 0.0, val ty: Double = 0.0) {
    fun map(x: Double, y: Double): Vec2 = Vec2(sx * x + tx, sy * y + ty)
}
data class Vec2(val x: Double, val y: Double)
data class DisplayGeometry(
    val panelWidthM: Double, val panelHeightM: Double,
    val surfaceWidthPx: Int, val surfaceHeightPx: Int,
    val surfaceToPanel: Affine2D = Affine2D(), val usableInsets: PixelInsets = PixelInsets(),
    val measurementSource: String = "estimated", val measurementRevision: Int = 1,
)
data class MaxFov(val outer: Double, val inner: Double, val up: Double, val down: Double)
data class ViewerOptics(
    val profileId: String, val profileRevision: Int = 1,
    val lensSeparationM: Double, val horizontalOffsetM: Double = 0.0,
    val verticalAlignment: VerticalAlignment = VerticalAlignment.CENTER,
    val verticalOffsetM: Double = 0.0, val trayToActiveBottomM: Double? = null,
    val screenToLensM: Double, val coefficients: RadialCoefficients,
    val convention: RadialConvention = RadialConvention.SCREEN_TANGENT_TO_RAY_TANGENT_V1,
    val maxFov: MaxFov, val confidence: ParameterConfidence = ParameterConfidence.PROVISIONAL,
    val dividerPx: Int = 8,
)
data class ObserverGeometry(val ipdM: Double = 0.064)
data class EyeOptics(
    val eye: Eye, val viewport: Viewport, val lensCenterPanelM: Vec2,
    val screenToLensM: Double, val coefficients: RadialCoefficients, val sourceBounds: TangentBounds,
    val panelBottomLeftM: Vec2, val panelTopRightM: Vec2,
)
data class StereoOptics(val left: EyeOptics, val right: EyeOptics, val geometryKey: Any)

/** Pure forward/inverse mapping. Radius always means screen/ray tangent radius. */
object RadialDistortion {
    fun screenToRay(screen: Vec2, coefficients: RadialCoefficients): Vec2 {
        validateCoefficients(coefficients)
        val r2 = screen.x * screen.x + screen.y * screen.y
        val factor = 1.0 + coefficients.k1 * r2 + coefficients.k2 * r2 * r2
        require(factor.isFinite()) { "non-finite radial result" }
        return Vec2(screen.x * factor, screen.y * factor)
    }

    fun rayToScreen(ray: Vec2, coefficients: RadialCoefficients): Vec2 {
        validateCoefficients(coefficients)
        val target = kotlin.math.hypot(ray.x, ray.y)
        if (target == 0.0) return Vec2(0.0, 0.0)
        var lo = 0.0
        var hi = target
        repeat(64) {
            val mid = (lo + hi) / 2.0
            val mapped = mid * (1.0 + coefficients.k1 * mid * mid + coefficients.k2 * mid * mid * mid * mid)
            if (mapped < target) lo = mid else hi = mid
        }
        val radius = (lo + hi) / 2.0
        return Vec2(ray.x * radius / target, ray.y * radius / target)
    }

    internal fun validateCoefficients(c: RadialCoefficients) {
        require(c.k1.isFinite() && c.k2.isFinite() && c.k1 in 0.0..1.0 && c.k2 in 0.0..1.0) { "unsupported radial coefficients" }
    }
}

/** Computes viewport, fixed physical lens centres and source bounds from one snapshot. */
object OpticsGeometry {
    fun compute(display: DisplayGeometry, viewer: ViewerOptics, observer: ObserverGeometry): StereoOptics {
        validate(display, viewer, observer)
        val dividerLeft = (display.surfaceWidthPx - viewer.dividerPx) / 2
        val dividerRight = dividerLeft + viewer.dividerPx
        val insets = display.usableInsets
        val leftVp = Viewport(insets.left, insets.bottom, dividerLeft - insets.left, display.surfaceHeightPx - insets.bottom - insets.top)
        val rightVp = Viewport(dividerRight, insets.bottom, display.surfaceWidthPx - insets.right - dividerRight, display.surfaceHeightPx - insets.bottom - insets.top)
        require(leftVp.width > 0 && rightVp.width > 0 && leftVp.height > 0 && rightVp.height > 0) { "empty eye viewport" }
        val cy = when (viewer.verticalAlignment) {
            VerticalAlignment.CENTER -> display.panelHeightM / 2.0 + viewer.verticalOffsetM
            VerticalAlignment.BOTTOM -> requireNotNull(viewer.trayToActiveBottomM) { "bottom alignment needs measured tray offset" }.let { viewer.verticalOffsetM - it }
        }
        val leftCenter = Vec2(display.panelWidthM / 2.0 + viewer.horizontalOffsetM - viewer.lensSeparationM / 2.0, cy)
        val rightCenter = Vec2(display.panelWidthM / 2.0 + viewer.horizontalOffsetM + viewer.lensSeparationM / 2.0, cy)
        val left = makeEye(Eye.LEFT, leftVp, leftCenter, display, viewer, viewer.maxFov.outer, viewer.maxFov.inner)
        val right = makeEye(Eye.RIGHT, rightVp, rightCenter, display, viewer, viewer.maxFov.inner, viewer.maxFov.outer)
        val key = listOf(display, viewer, left.viewport, right.viewport, left.lensCenterPanelM, right.lensCenterPanelM, left.sourceBounds, right.sourceBounds)
        return StereoOptics(left, right, key)
    }

    fun panelPoint(display: DisplayGeometry, xPx: Double, yPx: Double): Vec2 =
        display.surfaceToPanel.map(display.panelWidthM * xPx / display.surfaceWidthPx, display.panelHeightM * yPx / display.surfaceHeightPx)

    private fun makeEye(eye: Eye, viewport: Viewport, center: Vec2, display: DisplayGeometry, viewer: ViewerOptics, maxLeft: Double, maxRight: Double): EyeOptics {
        val p0 = panelPoint(display, viewport.x.toDouble(), viewport.y.toDouble())
        val p1 = panelPoint(display, (viewport.x + viewport.width).toDouble(), (viewport.y + viewport.height).toDouble())
        require(center.x in p0.x..p1.x && center.y in p0.y..p1.y) { "lens center outside usable viewport" }
        fun f(a: Double) = a * (1.0 + viewer.coefficients.k1 * a * a + viewer.coefficients.k2 * a * a * a * a)
        fun cap(deg: Double) = tan(Math.toRadians(deg))
        val d = viewer.screenToLensM
        return EyeOptics(eye, viewport, center, d, viewer.coefficients, TangentBounds(
            -min(f((center.x - p0.x) / d), cap(maxLeft)), min(f((p1.x - center.x) / d), cap(maxRight)),
            -min(f((center.y - p0.y) / d), cap(viewer.maxFov.down)), min(f((p1.y - center.y) / d), cap(viewer.maxFov.up)),
        ), p0, p1)
    }

    private fun validate(d: DisplayGeometry, v: ViewerOptics, o: ObserverGeometry) {
        require(d.panelWidthM.isFinite() && d.panelWidthM in 0.08..0.20 && d.panelHeightM.isFinite() && d.panelHeightM in 0.03..0.12)
        require(d.surfaceWidthPx > 0 && d.surfaceHeightPx > 0 && v.dividerPx in 0..40)
        require(v.lensSeparationM.isFinite() && v.screenToLensM.isFinite() && v.screenToLensM in 0.030..0.060)
        require(o.ipdM.isFinite() && o.ipdM in 0.052..0.074)
        RadialDistortion.validateCoefficients(v.coefficients)
        listOf(v.maxFov.outer, v.maxFov.inner, v.maxFov.up, v.maxFov.down).forEach { require(it > 0.0 && it < 89.0) }
    }
}
