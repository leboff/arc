package com.daydreamvr.vrcore.ui

/**
 * The world placement + texture size of a panel, everything [PanelRaycast] needs
 * to turn a gaze [Ray] into a pixel on this panel (UI_GAZE_PLAN.md §2.3).
 *
 * [modelYawRad] is already sign-corrected (`ScreenPanel.modelYawRad`, §1.6) — it
 * is the `a` in `θ = wrapPi(ψ_hit + a)`.
 */
data class PanelGeometry(
    val modelYawRad: Float,
    val distanceM: Float,
    val widthM: Float,
    val heightM: Float,
    val verticalOffsetM: Float,
    val widthPx: Int,
    val heightPx: Int,
    val curved: Boolean = true,
) {
    /** Panel arc in radians (`w / d` for a curved panel). */
    val arcRad: Float get() = if (curved) widthM / distanceM else widthM
}
