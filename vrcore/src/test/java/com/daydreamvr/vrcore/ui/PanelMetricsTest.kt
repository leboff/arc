package com.daydreamvr.vrcore.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PanelMetricsTest {

    @Test
    fun curvedArcIsWidthOverRadiusInDegrees() {
        val m = PanelMetrics.curved(widthPx = 1280, heightPx = 800, widthM = 2.4f, radiusM = 2.5f)
        // 2.4 / 2.5 rad = 0.96 rad = 55.0°
        assertThat(m.widthDegrees).isWithin(1e-3f).of(Math.toDegrees(0.96).toFloat())
    }

    @Test
    fun pxAndDegRoundTrip() {
        val m = PanelMetrics.curved(1024, 676, 2.2f, 2.5f)
        assertThat(m.deg(m.px(1.85f))).isWithin(1e-4f).of(1.85f)
        assertThat(m.px(m.deg(120f))).isWithin(1e-3f).of(120f)
    }

    @Test
    fun heightDegreesUsesTheSameScaleAsWidth() {
        val m = PanelMetrics.curved(1280, 800, 2.4f, 2.5f)
        // 800 px at 1280 px / 55.0°
        assertThat(m.heightDegrees).isWithin(1e-3f).of(800f / (1280f / m.widthDegrees))
    }

    @Test
    fun degenerateInputsDoNotDivideByZero() {
        val zero = PanelMetrics.curved(0, 0, 0f, 0f)
        assertThat(zero.pxPerDegree).isEqualTo(0f)
        assertThat(zero.px(2f)).isEqualTo(0f)
        assertThat(zero.deg(100f)).isEqualTo(0f)
        assertThat(zero.heightDegrees).isEqualTo(0f)
        assertThat(PanelMetrics.curved(100, 100, 1f, 0f).widthDegrees).isEqualTo(0f)
    }
}
