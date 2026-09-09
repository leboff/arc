package com.daydreamvr.vrcore.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TextMeasureTest {

    @Test
    fun fixedMeasureIsProportionalToLengthAndSize() {
        val m = TextMeasure.fixed(10f)
        val a = m.width("abcd", 32f, bold = false)
        assertThat(a).isWithin(1e-4f).of(40f)
        assertThat(m.width("abcdabcd", 32f, bold = false)).isWithin(1e-4f).of(2 * a)
        assertThat(m.width("abcd", 16f, bold = false)).isWithin(1e-4f).of(a / 2f)
    }

    @Test
    fun boldIsWiderThanRegular() {
        val m = TextMeasure.fixed(10f)
        assertThat(m.width("hello", 32f, bold = true))
            .isGreaterThan(m.width("hello", 32f, bold = false))
    }

    @Test
    fun verticalMetricsScaleWithSizeAndHaveTheRightSign() {
        val m = TextMeasure.fixed(10f)
        assertThat(m.ascentPx(40f, bold = false)).isLessThan(0f)
        assertThat(m.descentPx(40f, bold = false)).isGreaterThan(0f)
        assertThat(m.ascentPx(20f, bold = false)).isWithin(1e-4f).of(m.ascentPx(40f, bold = false) / 2f)
    }
}
