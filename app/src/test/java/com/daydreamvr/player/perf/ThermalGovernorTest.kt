package com.daydreamvr.player.perf

import com.daydreamvr.player.perf.ThermalGovernor.THERMAL_CRITICAL
import com.daydreamvr.player.perf.ThermalGovernor.THERMAL_LIGHT
import com.daydreamvr.player.perf.ThermalGovernor.THERMAL_MODERATE
import com.daydreamvr.player.perf.ThermalGovernor.THERMAL_NONE
import com.daydreamvr.player.perf.ThermalGovernor.THERMAL_SEVERE
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ThermalGovernorTest {

    @Test
    fun noneAndLightRunFullQuality() {
        for (status in listOf(THERMAL_NONE, THERMAL_LIGHT)) {
            val q = ThermalGovernor.qualityFor(status, batteryPercent = 90)
            assertThat(q.renderScale).isWithin(1e-4f).of(1.15f)
            assertThat(q.msaa).isEqualTo(4)
            assertThat(q.chromatic).isTrue()
        }
    }

    @Test
    fun moderateDropsSupersampleAndMsaaButKeepsChromatic() {
        val q = ThermalGovernor.qualityFor(THERMAL_MODERATE, batteryPercent = 90)
        assertThat(q.renderScale).isWithin(1e-4f).of(1.0f)
        assertThat(q.msaa).isEqualTo(0)
        assertThat(q.chromatic).isTrue()
    }

    @Test
    fun severeDropsRenderScaleAndChromatic() {
        val q = ThermalGovernor.qualityFor(THERMAL_SEVERE, batteryPercent = 90)
        assertThat(q.renderScale).isWithin(1e-4f).of(0.85f)
        assertThat(q.msaa).isEqualTo(0)
        assertThat(q.chromatic).isFalse()
    }

    @Test
    fun criticalIsTheFloor() {
        val q = ThermalGovernor.qualityFor(THERMAL_CRITICAL, batteryPercent = 90)
        assertThat(q.renderScale).isAtMost(0.7f)
        assertThat(q.msaa).isEqualTo(0)
        assertThat(q.chromatic).isFalse()
    }

    @Test
    fun theLadderNeverRaisesQualityAsItGetsHotter() {
        var previous = Float.MAX_VALUE
        for (status in THERMAL_NONE..ThermalGovernor.THERMAL_SHUTDOWN) {
            val scale = ThermalGovernor.qualityFor(status, batteryPercent = 90).renderScale
            assertThat(scale).isAtMost(previous)
            previous = scale
        }
    }

    @Test
    fun lowBatteryDropsRenderScaleIndependentlyOfThermalStatus() {
        val cool = ThermalGovernor.qualityFor(THERMAL_NONE, batteryPercent = 10)
        assertThat(cool.renderScale).isAtMost(0.85f)
        assertThat(cool.msaa).isEqualTo(0)

        // Still no worse than the thermal-only decision at the same status.
        val severeFull = ThermalGovernor.qualityFor(THERMAL_SEVERE, batteryPercent = 90)
        val severeLow = ThermalGovernor.qualityFor(THERMAL_SEVERE, batteryPercent = 5)
        assertThat(severeLow.renderScale).isAtMost(severeFull.renderScale)
    }

    @Test
    fun unknownBatteryLeavesTheThermalDecisionUntouched() {
        val q = ThermalGovernor.qualityFor(THERMAL_NONE, batteryPercent = -1)
        assertThat(q.renderScale).isWithin(1e-4f).of(1.15f)
    }
}
