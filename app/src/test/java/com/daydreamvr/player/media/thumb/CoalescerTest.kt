package com.daydreamvr.player.media.thumb

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CoalescerTest {

    @Test
    fun aBurstInsideTheWindowBumpsOnceAndTheNextBurstBumpsAgain() {
        val c = RepaintCoalescer(windowMs = 120L)
        var bumps = 0

        // 6 arrivals across 0..100 ms
        for (t in longArrayOf(0, 10, 30, 60, 90, 100)) {
            c.signal()
            if (c.poll(t)) bumps++
        }
        assertThat(bumps).isEqualTo(1)

        // 7th arrival at 130 ms — outside the window
        c.signal()
        if (c.poll(130)) bumps++
        assertThat(bumps).isEqualTo(2)
    }

    @Test
    fun pollWithNoSignalNeverBumps() {
        val c = RepaintCoalescer(120L)
        assertThat(c.poll(0)).isFalse()
        assertThat(c.poll(1000)).isFalse()
    }
}
