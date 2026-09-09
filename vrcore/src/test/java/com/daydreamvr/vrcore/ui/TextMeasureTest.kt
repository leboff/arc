package com.daydreamvr.vrcore.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TextMeasureTest {

    @Test
    fun fixedMeasureIsProportionalToLengthAndSize() {
        val m = TextMeasure.fixed(10f)
        val a = m.width("abcd", 32f, bold = false)
        assertThat(a).isWithin(1e-4f).of(40f)
        // twice the characters → twice the width
        assertThat(m.width("abcdabcd", 32f, bold = false)).isWithin(1e-4f).of(2 * a)
        // half the size → half the width
        assertThat(m.width("abcd", 16f, bold = false)).isWithin(1e-4f).of(a / 2f)
    }

    @Test
    fun boldIsWiderThanRegular() {
        val m = TextMeasure.fixed(10f)
        assertThat(m.width("hello", 32f, bold = true))
            .isGreaterThan(m.width("hello", 32f, bold = false))
    }
}
