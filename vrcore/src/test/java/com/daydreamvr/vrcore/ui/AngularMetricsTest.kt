package com.daydreamvr.vrcore.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AngularMetricsTest {

    @Test
    fun onePointFiveDegreesOnA60DegWidePanelGivesAtLeast25pxText() {
        val px = AngularMetrics.textSizePx(
            angularDegrees = 1.5f,
            panelWidthPx = 1024,
            panelWidthDegrees = 60f,
        )
        assertThat(px).isAtLeast(25f)
        assertThat(AngularMetrics.isLegible(1.5f)).isTrue()
    }

    @Test
    fun textSizeAndDegreesAreInverses() {
        val px = AngularMetrics.textSizePx(2f, 1024, 50f)
        assertThat(AngularMetrics.degreesForPx(px, 1024, 50f)).isWithin(1e-3f).of(2f)
    }

    @Test
    fun comfortBoxRejectsAPointAt30DegreesHorizontal() {
        assertThat(AngularMetrics.isWithinComfortBox(30f, 0f)).isFalse()
        assertThat(AngularMetrics.isWithinComfortBox(-30f, 0f)).isFalse()
    }

    @Test
    fun comfortBoxAcceptsInsideTheLimits() {
        assertThat(AngularMetrics.isWithinComfortBox(24f, 19f)).isTrue()
        assertThat(AngularMetrics.isWithinComfortBox(0f, 0f)).isTrue()
        // Just outside the vertical limit.
        assertThat(AngularMetrics.isWithinComfortBox(0f, 21f)).isFalse()
    }

    @Test
    fun asymmetricComfortBoxIsLenientDownwardAndOnHeadTurn() {
        // Eyes-only: 30° horizontal is out; head-turn: it is in.
        assertThat(AngularMetrics.isWithinComfortBoxAsym(30f, 0f)).isFalse()
        assertThat(AngularMetrics.isWithinComfortBoxAsym(30f, 0f, allowHeadTurn = true)).isTrue()
        // −27° down is comfortable; +27° up is not.
        assertThat(AngularMetrics.isWithinComfortBoxAsym(0f, -27f)).isTrue()
        assertThat(AngularMetrics.isWithinComfortBoxAsym(0f, 27f)).isFalse()
    }

    @Test
    fun degenerateInputsReturnZeroRatherThanNaN() {
        assertThat(AngularMetrics.textSizePx(1.5f, 0, 60f)).isEqualTo(0f)
        assertThat(AngularMetrics.textSizePx(1.5f, 1024, 0f)).isEqualTo(0f)
    }
}
