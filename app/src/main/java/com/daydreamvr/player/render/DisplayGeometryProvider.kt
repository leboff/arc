package com.daydreamvr.player.render

import com.daydreamvr.vrcore.optics.Affine2D
import com.daydreamvr.vrcore.optics.DisplayGeometry
import com.daydreamvr.vrcore.optics.PixelInsets

/**
 * Pure provider and validator for physical Android display geometry
 * (DISTORTION_REMEDIATION_PLAN §4, §6).
 *
 * Rejects invalid DPI or out-of-bounds phone dimensions with a typed result
 * rather than disguising bad DPI via silent clamping.
 */
object DisplayGeometryProvider {

    const val MIN_PANEL_WIDTH_M = 0.08
    const val MAX_PANEL_WIDTH_M = 0.20
    const val MIN_PANEL_HEIGHT_M = 0.03
    const val MAX_PANEL_HEIGHT_M = 0.12
    const val INCH_TO_METRES = 0.0254

    sealed interface Result {
        data class Valid(val geometry: DisplayGeometry) : Result
        data class Invalid(val reason: String, val estimatedWidthM: Double?, val estimatedHeightM: Double?) : Result
    }

    /**
     * Pure conversion from display metrics to [DisplayGeometry].
     */
    fun fromDisplayMetrics(
        widthPx: Int,
        heightPx: Int,
        xdpi: Float,
        ydpi: Float,
        cutoutInsetsPx: PixelInsets = PixelInsets(),
        panelOverrideWidthM: Double? = null,
        panelOverrideHeightM: Double? = null,
    ): Result {
        if (widthPx <= 0 || heightPx <= 0) {
            return Result.Invalid("Non-positive surface resolution: ${widthPx}x${heightPx}", null, null)
        }

        val estimatedW = if (panelOverrideWidthM != null) {
            panelOverrideWidthM
        } else {
            if (!xdpi.isFinite() || xdpi <= 0f) {
                return Result.Invalid("Non-finite or non-positive xdpi: $xdpi", null, null)
            }
            (widthPx.toDouble() / xdpi.toDouble()) * INCH_TO_METRES
        }

        val estimatedH = if (panelOverrideHeightM != null) {
            panelOverrideHeightM
        } else {
            if (!ydpi.isFinite() || ydpi <= 0f) {
                return Result.Invalid("Non-finite or non-positive ydpi: $ydpi", null, null)
            }
            (heightPx.toDouble() / ydpi.toDouble()) * INCH_TO_METRES
        }

        if (!estimatedW.isFinite() || estimatedW < MIN_PANEL_WIDTH_M || estimatedW > MAX_PANEL_WIDTH_M) {
            return Result.Invalid(
                "Panel width $estimatedW m outside supported bounds [$MIN_PANEL_WIDTH_M, $MAX_PANEL_WIDTH_M]",
                estimatedW, estimatedH,
            )
        }

        if (!estimatedH.isFinite() || estimatedH < MIN_PANEL_HEIGHT_M || estimatedH > MAX_PANEL_HEIGHT_M) {
            return Result.Invalid(
                "Panel height $estimatedH m outside supported bounds [$MIN_PANEL_HEIGHT_M, $MAX_PANEL_HEIGHT_M]",
                estimatedW, estimatedH,
            )
        }

        val source = if (panelOverrideWidthM != null && panelOverrideHeightM != null) "measured_override" else "android_metrics"
        val geom = DisplayGeometry(
            panelWidthM = estimatedW,
            panelHeightM = estimatedH,
            surfaceWidthPx = widthPx,
            surfaceHeightPx = heightPx,
            surfaceToPanel = Affine2D(),
            usableInsets = cutoutInsetsPx,
            measurementSource = source,
            measurementRevision = 1,
        )

        return Result.Valid(geom)
    }
}
