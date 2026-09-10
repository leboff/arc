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

    /**
     * Eyes-only horizontal comfort limit — the arc scannable without rotating the
     * head. Primary targets (column centres) must live inside this. Unchanged.
     */
    const val COMFORT_H_DEGREES = 25f

    /**
     * Head-turn horizontal comfort — reachable with a small, natural neck
     * rotation. Secondary targets (panel edges) may live here
     * (UI_REDESIGN_REVIEWED_PLAN.md §2.5, R13).
     */
    const val COMFORT_H_HEAD_DEGREES = 35f

    /** Upward gaze is the costly direction. Unchanged. */
    const val COMFORT_V_DEGREES = 20f

    /**
     * Downward gaze is the cheap direction — resting gaze already sits ~12° below
     * horizontal (UI_REDESIGN_REVIEWED_PLAN.md §2.5, R13).
     */
    const val COMFORT_V_DOWN_DEGREES = 30f

    /** Minimum legible text height through a passive viewer (ARCHITECTURE.md §11.3). */
    const val MIN_TEXT_DEGREES = 1.0f

    /**
     * Smallest reliable gaze target given head-tracking jitter plus the 60 ms
     * stabiliser (UI_REDESIGN_REVIEWED_PLAN.md §2.5, §6).
     */
    const val MIN_TARGET_DEGREES = 2.0f

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

    /**
     * Asymmetric comfort test ([yDeg] positive is up). The horizontal limit
     * relaxes to [COMFORT_H_HEAD_DEGREES] when [allowHeadTurn] is set; the
     * vertical limit is [COMFORT_V_DEGREES] looking up and
     * [COMFORT_V_DOWN_DEGREES] looking down (UI_REDESIGN_REVIEWED_PLAN.md §2.5).
     */
    fun isWithinComfortBoxAsym(xDeg: Float, yDeg: Float, allowHeadTurn: Boolean = false): Boolean {
        val hLimit = if (allowHeadTurn) COMFORT_H_HEAD_DEGREES else COMFORT_H_DEGREES
        val vOk = if (yDeg >= 0f) yDeg <= COMFORT_V_DEGREES else -yDeg <= COMFORT_V_DOWN_DEGREES
        return abs(xDeg) <= hLimit && vOk
    }
}
