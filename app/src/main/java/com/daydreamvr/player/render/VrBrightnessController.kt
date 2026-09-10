package com.daydreamvr.player.render

import android.view.Window
import android.view.WindowManager

/**
 * Manages VR screen brightness lifecycle for a window attributes target.
 *
 * Immersive VR through lenses requires high brightness (~90%) to compensate for lens
 * attenuation and peripheral fall-off. On pause, exit, or destroy, the window brightness
 * must be restored cleanly to BRIGHTNESS_OVERRIDE_NONE (-1.0f) so the user's system
 * brightness preference is never permanently modified.
 */
class VrBrightnessController(
    private val applier: WindowBrightnessApplier,
    private val vrBrightness: Float = VR_BRIGHTNESS,
) {

    constructor(window: Window, vrBrightness: Float = VR_BRIGHTNESS) : this(
        applier = object : WindowBrightnessApplier {
            override fun getBrightness(): Float = window.attributes.screenBrightness
            override fun setBrightness(brightness: Float) {
                val lp = window.attributes
                lp.screenBrightness = brightness
                window.attributes = lp
            }
        },
        vrBrightness = vrBrightness,
    )

    private var previousBrightness: Float = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    var isVrBrightnessActive: Boolean = false
        private set

    /** Applies VR brightness override and remembers previous window brightness. */
    fun applyVrBrightness() {
        if (isVrBrightnessActive) return
        previousBrightness = applier.getBrightness()
        applier.setBrightness(vrBrightness)
        isVrBrightnessActive = true
    }

    /** Restores previous window brightness (or resets to default system tracking). */
    fun restoreBrightness() {
        if (!isVrBrightnessActive) return
        applier.setBrightness(previousBrightness)
        isVrBrightnessActive = false
    }

    interface WindowBrightnessApplier {
        fun getBrightness(): Float
        fun setBrightness(brightness: Float)
    }

    companion object {
        const val VR_BRIGHTNESS = 0.90f
    }
}
