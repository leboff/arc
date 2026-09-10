package com.daydreamvr.vrcore.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GazeStabilizerTest {

    private val dt = 0.016f // ~60 Hz

    private fun <T> GazeStabilizer<T>.hold(target: T?, frames: Int): T? {
        var last: T? = null
        repeat(frames) { last = update(target, dt) }
        return last
    }

    @Test
    fun aTargetHeldPastHoldSecondsBecomesCurrent() {
        val s = GazeStabilizer<String>(holdSeconds = 0.06f)
        assertThat(s.hold("a", 10)).isEqualTo("a")
    }

    @Test
    fun aOneFrameBlipDoesNotSwitch() {
        val s = GazeStabilizer<String>(holdSeconds = 0.06f)
        s.hold("a", 10)
        assertThat(s.update("b", dt)).isEqualTo("a")
        assertThat(s.update("a", dt)).isEqualTo("a")
    }

    @Test
    fun nullIsALegitimateTarget() {
        val s = GazeStabilizer<String>(holdSeconds = 0.06f)
        s.hold("a", 10)
        assertThat(s.hold(null, 10)).isNull()
    }

    @Test
    fun resetClears() {
        val s = GazeStabilizer<String>(holdSeconds = 0.06f)
        s.hold("a", 10)
        s.reset()
        // After reset, "b" must earn its place again rather than snapping instantly.
        assertThat(s.update("b", dt)).isNull()
    }

    @Test
    fun continuousControlStabilizesAndKeepsItsLatestValue() {
        data class Timeline(val fraction: Float)
        val s = GazeStabilizer<Timeline>(holdSeconds = 0.06f) { a, b ->
            a != null && b != null
        }

        assertThat(s.update(Timeline(0.50f), dt)).isNull()
        assertThat(s.update(Timeline(0.501f), dt)).isNull()
        assertThat(s.update(Timeline(0.502f), dt)).isNull()
        assertThat(s.update(Timeline(0.503f), dt)).isNull()
        assertThat(s.update(Timeline(0.504f), dt)).isEqualTo(Timeline(0.504f))
        assertThat(s.update(Timeline(0.51f), dt)).isEqualTo(Timeline(0.51f))
    }
}
