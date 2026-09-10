package com.daydreamvr.player.state

import com.daydreamvr.vrcore.input.InputAction
import com.daydreamvr.vrcore.profile.DeviceProfiles
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OpticsSettingsTest {

    @Test
    fun cyclingProfileInStateMachinePreservesObserverIpd() {
        val initial = AppState(
            screen = VrScreen.SETTINGS,
            settings = Settings(
                deviceProfileId = "daydream_view_2017",
                ipdMm = 65.5f,
            ),
            hud = HudState(focusIndex = Settings.ROWS.indexOf("Viewer profile")),
        )
        val sm = AppStateMachine(initial)

        // Nav RIGHT or Confirm on "Viewer profile" cycles to the next profile
        sm.dispatch(Event.Input(InputAction.Confirm(long = false)))

        val state = sm.state.value
        assertThat(state.settings.deviceProfileId).isNotEqualTo("daydream_view_2017")
        // Observer IPD must NOT be reset by viewer profile switch (§4, §5)
        assertThat(state.settings.ipdMm).isEqualTo(65.5f)
    }

    @Test
    fun adjustingIpdUpdatesOnlyObserverIpd() {
        val initial = AppState(
            screen = VrScreen.SETTINGS,
            settings = Settings(
                deviceProfileId = "cardboard_v2",
                ipdMm = 64.0f,
                screenToLensMm = 39.0f,
                lensK1 = 0.34f,
                lensK2 = 0.55f,
            ),
            hud = HudState(focusIndex = Settings.ROWS.indexOf("IPD")),
        )
        val sm = AppStateMachine(initial)

        sm.dispatch(Event.Input(InputAction.Nav(InputAction.Dir.RIGHT)))

        val s = sm.state.value.settings
        assertThat(s.ipdMm).isEqualTo(64.5f)
        // Optical calibration fields remain untouched
        assertThat(s.screenToLensMm).isEqualTo(39.0f)
        assertThat(s.lensK1).isEqualTo(0.34f)
        assertThat(s.lensK2).isEqualTo(0.55f)
    }

    @Test
    fun adjustingLensK1AndK2ModifiesOnlyDistortionCoefficients() {
        val initial = AppState(
            screen = VrScreen.SETTINGS,
            settings = Settings(
                ipdMm = 64.0f,
                lensK1 = 0.36f,
                lensK2 = 0.42f,
            ),
            hud = HudState(focusIndex = Settings.ROWS.indexOf("Lens k1")),
        )
        val sm = AppStateMachine(initial)

        sm.dispatch(Event.Input(InputAction.Nav(InputAction.Dir.RIGHT)))
        assertThat(sm.state.value.settings.lensK1).isEqualTo(0.37f)
        assertThat(sm.state.value.settings.ipdMm).isEqualTo(64.0f)

        // Move to Lens k2
        val sm2 = AppStateMachine(
            sm.state.value.copy(hud = HudState(focusIndex = Settings.ROWS.indexOf("Lens k2")))
        )
        sm2.dispatch(Event.Input(InputAction.Nav(InputAction.Dir.LEFT)))
        assertThat(sm2.state.value.settings.lensK2).isEqualTo(0.41f)
    }
}
