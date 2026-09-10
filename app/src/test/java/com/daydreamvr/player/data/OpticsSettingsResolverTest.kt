package com.daydreamvr.player.data

import com.daydreamvr.player.state.Settings
import com.daydreamvr.vrcore.optics.ParameterConfidence
import com.daydreamvr.vrcore.profile.DeviceProfiles
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OpticsSettingsResolverTest {

    @Test
    fun switchProfilePreservesObserverIpd() {
        val initial = Settings(
            deviceProfileId = "daydream_view_2017",
            ipdMm = 67.5f,
        )

        val switched = OpticsSettingsResolver.switchProfile(initial, "cardboard_v1")

        assertThat(switched.deviceProfileId).isEqualTo("cardboard_v1")
        // Observer IPD must NOT change when switching profile (§4)
        assertThat(switched.ipdMm).isEqualTo(67.5f)
        assertThat(switched.screenToLensMm).isEqualTo(DeviceProfiles.CARDBOARD_V1.screenToLensDistanceM * 1000f)
        assertThat(switched.lensK1).isEqualTo(DeviceProfiles.CARDBOARD_V1.distortionK[0])
    }

    @Test
    fun switchProfileLoadsPerViewerOverrideWhenPresent() {
        val overrides = mapOf(
            "cardboard_v2" to ViewerOverride(
                screenToLensMm = 38.5f,
                lensK1 = 0.33f,
                lensK2 = 0.52f,
            ),
        )
        val initial = Settings(deviceProfileId = "daydream_view_2017", ipdMm = 62.0f)

        val switched = OpticsSettingsResolver.switchProfile(initial, "cardboard_v2", overrides)
        assertThat(switched.screenToLensMm).isEqualTo(38.5f)
        assertThat(switched.lensK1).isEqualTo(0.33f)
        assertThat(switched.lensK2).isEqualTo(0.52f)
        assertThat(switched.ipdMm).isEqualTo(62.0f)
    }

    @Test
    fun resolveViewerOpticsMaintainsFixedPhysicalSeparationDecoupledFromIpd() {
        val s = Settings(
            deviceProfileId = "cardboard_v1",
            ipdMm = 72.0f, // Large observer IPD
        )

        val optics = OpticsSettingsResolver.resolveViewerOptics(s)

        // Physical lens separation comes from Cardboard v1 baseline (0.060 m), NOT 0.072 m
        assertThat(optics.lensSeparationM).isWithin(1e-9).of(DeviceProfiles.CARDBOARD_V1.interLensDistanceM.toDouble())
        assertThat(optics.confidence).isEqualTo(ParameterConfidence.USER_CALIBRATED)
    }

    @Test
    fun resolveDeviceProfileKeepsInterLensDistanceMInvariant() {
        val s = Settings(
            deviceProfileId = "daydream_view_2016",
            ipdMm = 55.0f,
            screenToLensMm = 39.0f,
            lensK1 = 0.34f,
            lensK2 = 0.55f,
        )

        val p = OpticsSettingsResolver.resolveDeviceProfile(s)
        assertThat(p.interLensDistanceM).isWithin(1e-6f).of(DeviceProfiles.DAYDREAM_VIEW_2016.interLensDistanceM)
        assertThat(p.screenToLensDistanceM).isWithin(1e-6f).of(0.039f)
    }

    @Test
    fun cycleProfileStepsThroughAllProfilesPreservingIpd() {
        var s = Settings(deviceProfileId = DeviceProfiles.ALL.first().id, ipdMm = 63.5f)
        for (i in 1 until DeviceProfiles.ALL.size) {
            s = OpticsSettingsResolver.cycleProfile(s, 1)
            assertThat(s.ipdMm).isEqualTo(63.5f)
            assertThat(s.deviceProfileId).isEqualTo(DeviceProfiles.ALL[i].id)
        }
    }
}
