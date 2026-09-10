package com.daydreamvr.player.render

import android.view.WindowManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VrBrightnessControllerTest {

    private class FakeApplier(var current: Float = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) :
        VrBrightnessController.WindowBrightnessApplier {
        override fun getBrightness(): Float = current
        override fun setBrightness(brightness: Float) {
            current = brightness
        }
    }

    @Test
    fun applyVrBrightness_setsBrightnessTo90PercentAndRemembersPrevious() {
        val applier = FakeApplier(current = 0.45f)
        val controller = VrBrightnessController(applier, vrBrightness = 0.90f)

        controller.applyVrBrightness()
        assertThat(controller.isVrBrightnessActive).isTrue()
        assertThat(applier.current).isWithin(0.001f).of(0.90f)

        // Calling again while active is idempotent
        controller.applyVrBrightness()
        assertThat(applier.current).isWithin(0.001f).of(0.90f)

        // Restore returns to previous 0.45f
        controller.restoreBrightness()
        assertThat(controller.isVrBrightnessActive).isFalse()
        assertThat(applier.current).isWithin(0.001f).of(0.45f)
    }

    @Test
    fun restoreBrightness_whenInactiveIsNoOp() {
        val applier = FakeApplier(current = -1f)
        val controller = VrBrightnessController(applier)

        controller.restoreBrightness()
        assertThat(controller.isVrBrightnessActive).isFalse()
        assertThat(applier.current).isEqualTo(-1f)
    }
}
