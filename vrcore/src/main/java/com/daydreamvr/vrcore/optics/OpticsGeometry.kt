package com.daydreamvr.vrcore.optics

import com.daydreamvr.vrcore.render.Eye
import com.daydreamvr.vrcore.render.Viewport
import kotlin.math.min
import kotlin.math.tan

/**
 * Computes deterministic physical display coordinates, viewport splitting,
 * fixed lens centers, and source tangent bounds from an immutable configuration snapshot
 * (DISTORTION_REMEDIATION_PLAN §2.1–§2.3, §3).
 */
object OpticsGeometry {

    /**
     * Computes [StereoOptics] from physical display, viewer optics, and observer geometry.
     */
    fun compute(display: DisplayGeometry, viewer: ViewerOptics, observer: ObserverGeometry): StereoOptics {
        validateInputs(display, viewer, observer)

        // Split display pixels deterministically: floor((Nx - D) / 2)
        val dividerLeft = (display.surfaceWidthPx - viewer.dividerPx) / 2
        val dividerRight = dividerLeft + viewer.dividerPx
        val insets = display.usableInsets

        val leftVp = Viewport(
            x = insets.left,
            y = insets.bottom,
            width = dividerLeft - insets.left,
            height = display.surfaceHeightPx - insets.bottom - insets.top,
        )
        val rightVp = Viewport(
            x = dividerRight,
            y = insets.bottom,
            width = (display.surfaceWidthPx - insets.right) - dividerRight,
            height = display.surfaceHeightPx - insets.bottom - insets.top,
        )

        if (leftVp.width <= 0 || rightVp.width <= 0 || leftVp.height <= 0 || rightVp.height <= 0) {
            throw OpticsValidationException("Eye viewport dimensions must be positive (left=$leftVp, right=$rightVp)")
        }

        val cy = when (viewer.verticalAlignment) {
            VerticalAlignment.CENTER -> display.panelHeightM / 2.0 + viewer.verticalOffsetM
            VerticalAlignment.BOTTOM -> {
                val trayToLens = viewer.trayToLensHeightM ?: viewer.verticalOffsetM
                val trayToBottom = requireNotNull(viewer.trayToActiveBottomM) {
                    "Bottom alignment requires measured trayToActiveBottomM"
                }
                trayToLens - trayToBottom
            }
        }

        val leftCenter = Vec2(
            display.panelWidthM / 2.0 + viewer.horizontalOffsetM - viewer.lensSeparationM / 2.0,
            cy,
        )
        val rightCenter = Vec2(
            display.panelWidthM / 2.0 + viewer.horizontalOffsetM + viewer.lensSeparationM / 2.0,
            cy,
        )

        val left = makeEye(Eye.LEFT, leftVp, leftCenter, display, viewer, viewer.maxFov.outer, viewer.maxFov.inner)
        val right = makeEye(Eye.RIGHT, rightVp, rightCenter, display, viewer, viewer.maxFov.inner, viewer.maxFov.outer)

        // Cache key excludes observer IPD, head pose, neck model, and render scale (§3).
        val key = listOf(
            display,
            viewer,
            left.viewport,
            right.viewport,
            left.lensCenterPanelM,
            right.lensCenterPanelM,
            left.sourceBounds,
            right.sourceBounds,
        )

        return StereoOptics(left, right, key)
    }

    /**
     * Overload taking [RenderConfiguration].
     * If [RenderConfiguration.distortionEnabled] is false, evaluates the identity
     * physical geometry so direct rendering shares the exact unwarped frustum.
     */
    fun compute(configuration: RenderConfiguration): StereoOptics {
        val effectiveViewer = if (configuration.distortionEnabled) {
            configuration.viewer
        } else {
            configuration.viewer.copy(coefficients = RadialCoefficients(0.0, 0.0))
        }
        return compute(configuration.display, effectiveViewer, configuration.observer)
    }

    /**
     * Maps continuous pixel edge coordinates [xPx, yPx] into illuminated panel coordinates in metres.
     */
    fun panelPoint(display: DisplayGeometry, xPx: Double, yPx: Double): Vec2 {
        val rawX = display.panelWidthM * xPx / display.surfaceWidthPx.toDouble()
        val rawY = display.panelHeightM * yPx / display.surfaceHeightPx.toDouble()
        return display.surfaceToPanel.map(rawX, rawY)
    }

    private fun makeEye(
        eye: Eye,
        viewport: Viewport,
        center: Vec2,
        display: DisplayGeometry,
        viewer: ViewerOptics,
        maxLeftDeg: Double,
        maxRightDeg: Double,
    ): EyeOptics {
        val p0 = panelPoint(display, viewport.x.toDouble(), viewport.y.toDouble())
        val p1 = panelPoint(display, (viewport.x + viewport.width).toDouble(), (viewport.y + viewport.height).toDouble())

        if (center.x < p0.x || center.x > p1.x || center.y < p0.y || center.y > p1.y) {
            throw OpticsValidationException(
                "Lens center ($center) outside eye viewport bounds: X in [${p0.x}, ${p1.x}], Y in [${p0.y}, ${p1.y}]"
            )
        }

        val d = viewer.screenToLensM
        val aLeft = (center.x - p0.x) / d
        val aRight = (p1.x - center.x) / d
        val aBottom = (center.y - p0.y) / d
        val aTop = (p1.y - center.y) / d

        fun f(a: Double): Double {
            val a2 = a * a
            return a * (1.0 + viewer.coefficients.k1 * a2 + viewer.coefficients.k2 * a2 * a2)
        }

        fun cap(deg: Double): Double = tan(Math.toRadians(deg))

        val left = -min(f(aLeft), cap(maxLeftDeg))
        val right = min(f(aRight), cap(maxRightDeg))
        val bottom = -min(f(aBottom), cap(viewer.maxFov.down))
        val top = min(f(aTop), cap(viewer.maxFov.up))

        val bounds = TangentBounds(left, right, bottom, top)
        return EyeOptics(
            eye = eye,
            viewport = viewport,
            lensCenterPanelM = center,
            screenToLensM = d,
            coefficients = viewer.coefficients,
            sourceBounds = bounds,
            panelBottomLeftM = p0,
            panelTopRightM = p1,
        )
    }

    private fun validateInputs(d: DisplayGeometry, v: ViewerOptics, o: ObserverGeometry) {
        if (!d.panelWidthM.isFinite() || d.panelWidthM !in 0.08..0.20) {
            throw OpticsValidationException("Invalid panel width ${d.panelWidthM} m (expected [0.08, 0.20])")
        }
        if (!d.panelHeightM.isFinite() || d.panelHeightM !in 0.03..0.12) {
            throw OpticsValidationException("Invalid panel height ${d.panelHeightM} m (expected [0.03, 0.12])")
        }
        if (d.surfaceWidthPx <= 0 || d.surfaceHeightPx <= 0) {
            throw OpticsValidationException("Invalid surface dimensions ${d.surfaceWidthPx}x${d.surfaceHeightPx}")
        }
        if (v.dividerPx !in 0..40) {
            throw OpticsValidationException("Invalid divider ${v.dividerPx} px (expected [0, 40])")
        }
        if (!v.screenToLensM.isFinite() || v.screenToLensM !in 0.030..0.060) {
            throw OpticsValidationException("Invalid screen-to-lens ${v.screenToLensM} m (expected [0.030, 0.060])")
        }
        if (!o.ipdM.isFinite() || o.ipdM !in 0.052..0.074) {
            throw OpticsValidationException("Invalid observer IPD ${o.ipdM} m (expected [0.052, 0.074])")
        }
        listOf(v.maxFov.outer, v.maxFov.inner, v.maxFov.up, v.maxFov.down).forEach {
            if (!it.isFinite() || it <= 0.0 || it >= 89.0) {
                throw OpticsValidationException("FOV angle cap $it° must be within (0, 89) degrees")
            }
        }
    }
}
