package com.daydreamvr.vrcore.ui

import kotlin.math.abs

/**
 * Turns "how big should this be in the headset" (degrees of visual angle) into
 * panel pixels, and checks whether a point sits inside the comfortable field of
 * view (ARCHITECTURE.md §11.3).
 *
 * Pure — no Android, JVM-testable.
 */
object AngularMetrics {

    /** Horizontal comfort limit, degrees from the recentre direction. */
    const val COMFORT_H_DEGREES = 25f

    /** Vertical comfort limit, degrees from the recentre direction. */
    const val COMFORT_V_DEGREES = 20f

    /** Minimum legible text height through a passive viewer (ARCHITECTURE.md §11.3). */
    const val MIN_TEXT_DEGREES = 1.5f

    /**
     * Pixels a feature of [angularDegrees] visual angle occupies on a panel that
     * is [panelWidthPx] wide and subtends [panelWidthDegrees] of arc. Linear: the
     * panel maps its angular width uniformly across its pixels.
     */
    fun textSizePx(angularDegrees: Float, panelWidthPx: Int, panelWidthDegrees: Float): Float {
        if (panelWidthPx <= 0 || panelWidthDegrees <= 0f) return 0f
        return angularDegrees / panelWidthDegrees * panelWidthPx
    }

    /** Inverse of [textSizePx]: the visual angle a [px]-tall feature subtends. */
    fun degreesForPx(px: Float, panelWidthPx: Int, panelWidthDegrees: Float): Float {
        if (panelWidthPx <= 0) return 0f
        return px / panelWidthPx * panelWidthDegrees
    }

    /** True when a feature of [angularDegrees] is at least [MIN_TEXT_DEGREES]. */
    fun isLegible(angularDegrees: Float): Boolean = angularDegrees >= MIN_TEXT_DEGREES

    /**
     * True when a point [xDeg] right / [yDeg] up of the recentre direction is
     * inside the ±25° h, ±20° v comfort box.
     */
    fun isWithinComfortBox(xDeg: Float, yDeg: Float): Boolean =
        abs(xDeg) <= COMFORT_H_DEGREES && abs(yDeg) <= COMFORT_V_DEGREES
}
