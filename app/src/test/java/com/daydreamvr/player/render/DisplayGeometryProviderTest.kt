package com.daydreamvr.player.render

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DisplayGeometryProviderTest {

    @Test
    fun validDisplayMetricsProducesValidGeometry() {
        // 6.3" 1080x2400 phone at ~400 dpi
        val res = DisplayGeometryProvider.fromDisplayMetrics(
            widthPx = 2400,
            heightPx = 1080,
            xdpi = 420f,
            ydpi = 420f,
        )

        assertThat(res).isInstanceOf(DisplayGeometryProvider.Result.Valid::class.java)
        val valid = res as DisplayGeometryProvider.Result.Valid
        assertThat(valid.geometry.panelWidthM).isWithin(1e-4).of(2400.0 / 420.0 * 0.0254)
        assertThat(valid.geometry.panelHeightM).isWithin(1e-4).of(1080.0 / 420.0 * 0.0254)
        assertThat(valid.geometry.measurementSource).isEqualTo("android_metrics")
    }

    @Test
    fun nonPositiveResolutionProducesInvalidResult() {
        val resZeroW = DisplayGeometryProvider.fromDisplayMetrics(0, 1080, 400f, 400f)
        assertThat(resZeroW).isInstanceOf(DisplayGeometryProvider.Result.Invalid::class.java)

        val resNegH = DisplayGeometryProvider.fromDisplayMetrics(1920, -1080, 400f, 400f)
        assertThat(resNegH).isInstanceOf(DisplayGeometryProvider.Result.Invalid::class.java)
    }

    @Test
    fun nonFiniteOrNonPositiveDpiProducesInvalidResult() {
        val resZeroDpi = DisplayGeometryProvider.fromDisplayMetrics(1920, 1080, 0f, 400f)
        assertThat(resZeroDpi).isInstanceOf(DisplayGeometryProvider.Result.Invalid::class.java)

        val resNanDpi = DisplayGeometryProvider.fromDisplayMetrics(1920, 1080, Float.NaN, 400f)
        assertThat(resNanDpi).isInstanceOf(DisplayGeometryProvider.Result.Invalid::class.java)

        val resInfDpi = DisplayGeometryProvider.fromDisplayMetrics(1920, 1080, 400f, Float.POSITIVE_INFINITY)
        assertThat(resInfDpi).isInstanceOf(DisplayGeometryProvider.Result.Invalid::class.java)
    }

    @Test
    fun panelDimensionsOutsideValidatedBoundsProduceInvalidWithoutSilentClamping() {
        // Huge display (e.g. tablet width > 0.20m)
        val resTooWide = DisplayGeometryProvider.fromDisplayMetrics(3840, 2160, 200f, 200f) // ~0.48m
        assertThat(resTooWide).isInstanceOf(DisplayGeometryProvider.Result.Invalid::class.java)

        // Tiny display (watch width < 0.08m)
        val resTooSmall = DisplayGeometryProvider.fromDisplayMetrics(400, 400, 400f, 400f) // ~0.025m
        assertThat(resTooSmall).isInstanceOf(DisplayGeometryProvider.Result.Invalid::class.java)
    }

    @Test
    fun measuredPanelOverrideTakesPrecedence() {
        val res = DisplayGeometryProvider.fromDisplayMetrics(
            widthPx = 2400,
            heightPx = 1080,
            xdpi = 400f,
            ydpi = 400f,
            panelOverrideWidthM = 0.145,
            panelOverrideHeightM = 0.065,
        )

        assertThat(res).isInstanceOf(DisplayGeometryProvider.Result.Valid::class.java)
        val valid = res as DisplayGeometryProvider.Result.Valid
        assertThat(valid.geometry.panelWidthM).isWithin(1e-9).of(0.145)
        assertThat(valid.geometry.panelHeightM).isWithin(1e-9).of(0.065)
        assertThat(valid.geometry.measurementSource).isEqualTo("measured_override")
    }
}
