package com.daydreamvr.player.screens

import com.daydreamvr.player.state.Settings
import com.daydreamvr.vrcore.profile.DeviceProfiles
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The live-calibration → [com.daydreamvr.vrcore.profile.DeviceProfile] fold
 * (ARCHITECTURE.md §6.6). The renderer and the distortion mesh must see the
 * user's tweaked IPD / screen-to-lens / k1 / k2 / divider, not the table values.
 */
class CalibrationScreenTest {

    @Test
    fun overrideProfileAppliesEveryCalibrationField() {
        val s = Settings(
            ipdMm = 66f,
            screenToLensMm = 41.5f,
            lensK1 = 0.4f,
            lensK2 = 0.2f,
            dividerPx = 12,
        )
        val p = CalibrationScreen.overrideProfile(DeviceProfiles.CARDBOARD_V2, s)

        assertThat(p.interLensDistanceM).isWithin(1e-6f).of(0.066f)
        assertThat(p.screenToLensDistanceM).isWithin(1e-6f).of(0.0415f)
        assertThat(p.distortionK).usingExactEquality().containsExactly(0.4f, 0.2f).inOrder()
        assertThat(p.dividerPx).isEqualTo(12)
        // Untouched fields survive.
        assertThat(p.maxFovDegrees).isEqualTo(DeviceProfiles.CARDBOARD_V2.maxFovDegrees)
    }

    @Test
    fun calibrationKeyChangesWithAnyField() {
        val base = Settings()
        val key = CalibrationScreen.calibrationKey(base)
        assertThat(CalibrationScreen.calibrationKey(base.copy(lensK1 = base.lensK1 + 0.01f))).isNotEqualTo(key)
        assertThat(CalibrationScreen.calibrationKey(base.copy(dividerPx = base.dividerPx + 2))).isNotEqualTo(key)
        assertThat(CalibrationScreen.calibrationKey(base.copy(distortionCorrection = !base.distortionCorrection)))
            .isNotEqualTo(key)
        assertThat(CalibrationScreen.calibrationKey(base.copy())).isEqualTo(key)
    }
}
