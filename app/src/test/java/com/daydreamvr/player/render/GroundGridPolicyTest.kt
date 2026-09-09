package com.daydreamvr.player.render

import com.daydreamvr.player.state.VrScreen
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GroundGridPolicyTest {

    @Test
    fun visibleOnBrowsingScreensOnly() {
        assertThat(GroundGridPolicy.visibleOn(VrScreen.SERVER_LIST)).isTrue()
        assertThat(GroundGridPolicy.visibleOn(VrScreen.BROWSE)).isTrue()
        assertThat(GroundGridPolicy.visibleOn(VrScreen.SETTINGS)).isTrue()

        // Never visible during video playback
        assertThat(GroundGridPolicy.visibleOn(VrScreen.PLAYER)).isFalse()
    }

    @Test
    fun intensityScalesWithThermalStatus() {
        // NONE (0) or LIGHT (1) -> 1.0
        assertThat(GroundGridPolicy.intensityFor(0)).isEqualTo(1f)
        assertThat(GroundGridPolicy.intensityFor(1)).isEqualTo(1f)

        // MODERATE (2) -> 0.5
        assertThat(GroundGridPolicy.intensityFor(2)).isEqualTo(0.5f)

        // SEVERE (3) or CRITICAL (4+) -> 0.0
        assertThat(GroundGridPolicy.intensityFor(3)).isEqualTo(0f)
        assertThat(GroundGridPolicy.intensityFor(4)).isEqualTo(0f)
    }

    @Test
    fun glowGatedByThermalStatus() {
        assertThat(GroundGridPolicy.glowFor(0)).isTrue()
        assertThat(GroundGridPolicy.glowFor(1)).isTrue()
        assertThat(GroundGridPolicy.glowFor(2)).isFalse()
        assertThat(GroundGridPolicy.glowFor(3)).isFalse()
    }
}
