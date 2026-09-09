package com.daydreamvr.player.screens

/**
 * Canonical spatial constants for the three-column browse panel and the detached
 * system dock (UI_REDESIGN_REVIEWED_PLAN.md §2.8, §18).
 *
 * PURE — no Android imports. Every dimension is authored in degrees of visual
 * angle and converted to metres by `x_m = R · θ_rad`; the radius `R` is retained
 * from the existing `PanelAnchor.distanceM` default so no on-device optics
 * calibration changes.
 */
object BrowseLayout {
    /** Panel cylinder radius = anchor distance. F7 requires curveRadiusM == distanceM. */
    const val RADIUS_M = 2.50f

    /** Arc width: `W = R · θ_total` with `θ_total = 1.12 rad = 64.1713°`. */
    const val WIDTH_M = 2.80f
    const val WIDTH_PX = 1536
    const val HEIGHT_PX = 800

    /** Derived from isotropy: `heightM / heightPx == widthM / widthPx`. 1.458333 m. */
    const val HEIGHT_M = HEIGHT_PX * WIDTH_M / WIDTH_PX

    /** Panel vertical centre sits 2.0° below the eye line (true): `-R·tan(2°)`. */
    const val VERTICAL_OFFSET_M = -0.087302f

    /** Raised from PanelAnchor's default 35° so reading a sidebar edge (±32.086°) never trips follow (R12). */
    const val FOLLOW_THRESHOLD_DEG = 45f

    const val GUTTER_PX = 24
    const val CENTRE_PX = 700
    const val SIDEBAR_PX = (WIDTH_PX - 2 * GUTTER_PX - CENTRE_PX) / 2 // 394

    val leftX: IntRange = 0 until SIDEBAR_PX
    val centreX: IntRange = (SIDEBAR_PX + GUTTER_PX) until (SIDEBAR_PX + GUTTER_PX + CENTRE_PX)
    val rightX: IntRange = (WIDTH_PX - SIDEBAR_PX) until WIDTH_PX

    /** `θ_total` in degrees — the full horizontal arc the panel subtends. */
    const val TOTAL_H_DEGREES = 64.171253f

    /** Azimuth of a panel pixel, degrees, `az(x) = (x/W_px − 0.5) · θ_total`. */
    fun azimuthDeg(xPx: Float): Float = (xPx / WIDTH_PX - 0.5f) * TOTAL_H_DEGREES
}

/**
 * The detached system dock: a separate `PanelSurface` at a smaller radius, which
 * is the entire point of "detached" and is why it cannot be a region of the
 * browse texture (UI_REDESIGN_REVIEWED_PLAN.md §2.6, §3.1).
 */
object DockLayout {
    const val RADIUS_M = 2.05f
    const val WIDTH_M = 1.00f
    const val WIDTH_PX = 672
    const val HEIGHT_PX = 176

    /** 0.261905 m. */
    const val HEIGHT_M = HEIGHT_PX * WIDTH_M / WIDTH_PX

    /** `-R_dock·tan(24°)` — dock centre 24° below the eye line (true). */
    const val VERTICAL_OFFSET_M = -0.912719f

    const val BUTTONS = 6
}
