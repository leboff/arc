package com.daydreamvr.player.render

import com.daydreamvr.player.state.VrScreen

/**
 * When the ground grid is drawn and how bright (UI_REDESIGN_REVIEWED_PLAN.md
 * §12.4, R17). Pure — JVM-testable, no GL.
 */
object GroundGridPolicy {

    /** Drawn while browsing / configuring; never during playback. */
    fun visibleOn(screen: VrScreen): Boolean = when (screen) {
        VrScreen.SERVER_LIST, VrScreen.BROWSE, VrScreen.SETTINGS -> true
        VrScreen.PLAYER -> false
    }

    /** `uIntensity` from `PowerManager.THERMAL_STATUS_*`: 1.0 / 0.5 / 0.0. */
    fun intensityFor(thermalStatus: Int): Float = when {
        thermalStatus >= 3 -> 0f     // SEVERE / CRITICAL and worse — grid off
        thermalStatus == 2 -> 0.5f   // MODERATE
        else -> 1f                   // NONE / LIGHT
    }

    /** The horizon glow term is skipped at MODERATE and above. */
    fun glowFor(thermalStatus: Int): Boolean = thermalStatus < 2
}
