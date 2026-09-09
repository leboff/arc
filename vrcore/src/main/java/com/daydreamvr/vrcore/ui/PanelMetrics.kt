package com.daydreamvr.vrcore.ui

/**
 * The angular ↔ pixel scale of one panel. Replaces `Theme.panelWidthDegrees`,
 * which assumed every panel subtended the same arc (UI_GAZE_PLAN.md §0 F1).
 *
 * A curved panel of arc length [widthM] on a cylinder of radius `radiusM`
 * subtends `widthM / radiusM` radians; that — not a global constant — is what
 * maps its texture pixels to degrees of visual angle.
 *
 * Pure: no Android, JVM-testable.
 */
data class PanelMetrics(
    val widthPx: Int,
    val heightPx: Int,
    val widthDegrees: Float,
) {
    val pxPerDegree: Float get() = if (widthDegrees > 0f && widthPx > 0) widthPx / widthDegrees else 0f

    /** Pixels a feature of [degrees] visual angle occupies horizontally on this panel. */
    fun px(degrees: Float): Float = degrees * pxPerDegree

    /** Visual angle, in degrees, a [px]-sized feature subtends on this panel. */
    fun deg(px: Float): Float {
        val ppd = pxPerDegree
        return if (ppd > 0f) px / ppd else 0f
    }

    /** The panel's vertical extent in degrees (uses the same px/° as the width). */
    val heightDegrees: Float get() = deg(heightPx.toFloat())

    companion object {
        /** Curved panel: arc length [widthM] on a cylinder of [radiusM]. */
        fun curved(widthPx: Int, heightPx: Int, widthM: Float, radiusM: Float): PanelMetrics {
            val deg = if (radiusM > 0f) Math.toDegrees((widthM / radiusM).toDouble()).toFloat() else 0f
            return PanelMetrics(widthPx, heightPx, deg)
        }
    }
}
