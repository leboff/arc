package com.daydreamvr.vrcore.ui

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.PI

/**
 * World-lock + lazy-follow for a UI panel (ARCHITECTURE.md §11.3).
 *
 * The panel sits at a fixed yaw and does not move while the head stays within
 * [followThresholdDeg] of it. Once the head yaw exceeds that, the panel eases
 * toward the head with a [followTauSeconds] time constant; it never overshoots.
 * [snapTo] jumps instantly (used when the world recentres).
 *
 * Pure — [update] takes the frame `dt`; no clock, no Android.
 */
class PanelAnchor(
    /**
     * Fixed viewing distance, metres. `val` on purpose: [com.daydreamvr.vrcore.ui.PanelQuad]
     * captures this as its curve radius at construction and the closed-form gaze
     * raycast (UI_GAZE_PLAN.md §1.4) depends on `curveRadiusM == distanceM` (F7).
     */
    val distanceM: Float = 2.5f,
    var followThresholdDeg: Float = 35f,
    var followTauSeconds: Float = 0.5f,
) {

    private var panelYawRad = 0f
    private var following = false

    /** The panel's current yaw, radians. */
    val yawRad: Float get() = panelYawRad

    /**
     * Advances the lazy-follow filter and returns the panel's new yaw.
     *
     * @param headYawRad the current head yaw
     * @param dtSeconds  frame time; <= 0 leaves the panel where it is
     */
    fun update(headYawRad: Float, dtSeconds: Float): Float {
        val err = wrapPi(headYawRad - panelYawRad)
        val thresholdRad = followThresholdDeg * PI.toFloat() / 180f
        if (!following && abs(err) > thresholdRad) following = true
        if (following && dtSeconds > 0f && followTauSeconds > 0f) {
            val a = (1f - exp(-dtSeconds / followTauSeconds)).coerceIn(0f, 1f)
            panelYawRad = wrapPi(panelYawRad + err * a)
            if (abs(wrapPi(headYawRad - panelYawRad)) < RELOCK_RAD) following = false
        }
        return panelYawRad
    }

    /** Jumps the panel straight to [yawRad] (recentre snap). */
    fun snapTo(yawRad: Float) {
        panelYawRad = wrapPi(yawRad)
        following = false
    }

    private companion object {
        /** Head-to-panel gap at which lazy-follow re-locks the panel. ~2°. */
        const val RELOCK_RAD = 0.035f
    }

    private fun wrapPi(a: Float): Float {
        var x = a
        val twoPi = (2.0 * PI).toFloat()
        while (x > PI.toFloat()) x -= twoPi
        while (x < -PI.toFloat()) x += twoPi
        return x
    }
}
