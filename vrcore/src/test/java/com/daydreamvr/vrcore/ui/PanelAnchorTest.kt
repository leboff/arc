package com.daydreamvr.vrcore.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class PanelAnchorTest {

    private fun deg(d: Double) = (d * PI / 180.0).toFloat()

    @Test
    fun panelDoesNotMoveWhileHeadStaysWithin35Degrees() {
        val anchor = PanelAnchor()
        anchor.snapTo(0f)
        for (headDeg in listOf(0.0, 10.0, -20.0, 34.0, -34.0)) {
            val yaw = anchor.update(deg(headDeg), 0.016f)
            assertThat(yaw).isEqualTo(0f)
        }
    }

    @Test
    fun beyondThresholdItEasesTowardTheHeadWithA0_5sTimeConstant() {
        val anchor = PanelAnchor(followTauSeconds = 0.5f)
        anchor.snapTo(0f)
        val head = deg(60.0)

        // One time-constant of simulation in small steps.
        var yaw = 0f
        var t = 0f
        while (t < 0.5f) {
            yaw = anchor.update(head, 0.01f)
            t += 0.01f
        }
        // After one tau the panel has covered ~63% of the gap and never overshot.
        assertThat(yaw).isGreaterThan(head * 0.5f)
        assertThat(yaw).isLessThan(head)
        assertThat(yaw).isWithin(deg(6.0)).of(head * (1f - kotlin.math.exp(-1f)))
    }

    @Test
    fun itNeverOvershootsTheHead() {
        val anchor = PanelAnchor(followTauSeconds = 0.2f)
        anchor.snapTo(0f)
        val head = deg(80.0)
        repeat(500) {
            val yaw = anchor.update(head, 0.05f)
            assertThat(yaw).isAtMost(head + 1e-4f)
        }
    }

    @Test
    fun snapToIsInstant() {
        val anchor = PanelAnchor()
        anchor.snapTo(deg(90.0))
        assertThat(anchor.yawRad).isWithin(1e-5f).of(deg(90.0))
        // A recentre snap while the head is elsewhere still lands exactly.
        anchor.snapTo(deg(-45.0))
        assertThat(anchor.update(deg(-45.0), 0.016f)).isWithin(1e-5f).of(deg(-45.0))
    }

    @Test
    fun eventuallySettlesNearTheHeadOncePastTheThreshold() {
        val anchor = PanelAnchor(followTauSeconds = 0.3f)
        anchor.snapTo(0f)
        val head = deg(50.0)
        var yaw = 0f
        repeat(2000) { yaw = anchor.update(head, 0.016f) }
        assertThat(abs(head - yaw)).isLessThan(deg(3.0))
    }
}
