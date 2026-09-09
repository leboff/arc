package com.daydreamvr.vrcore.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

/**
 * The dock anchor is slaved to the browse anchor — `dock.snapTo(browse.yawRad)`
 * every frame after the browse anchor moves — so the two surfaces never drift
 * apart (UI_REDESIGN_REVIEWED_PLAN.md §3.1, M2 acceptance 4).
 */
class AnchorSlavingTest {

    @Test
    fun slavedAnchorTracksTheLeadAnchorExactlyOverAnArbitraryYawSequence() {
        val browse = PanelAnchor(distanceM = 2.50f, followThresholdDeg = 45f)
        val dock = PanelAnchor(distanceM = 2.05f)
        val rng = Random(20260909)

        var headYaw = 0f
        repeat(400) {
            headYaw += (rng.nextFloat() - 0.5f) * 0.3f
            browse.update(headYaw, dtSeconds = 1f / 72f)
            dock.snapTo(browse.yawRad)
            assertThat(dock.yawRad).isEqualTo(browse.yawRad)
        }
    }

    @Test
    fun aRaisedFollowThresholdKeepsThePanelStillAcrossTheReachableSpan() {
        val browse = PanelAnchor(distanceM = 2.50f, followThresholdDeg = 45f)
        // Reading the far edge of a sidebar: ~32° of head turn, well inside 45°.
        repeat(200) { browse.update(Math.toRadians(32.0).toFloat(), 1f / 72f) }
        assertThat(browse.yawRad).isEqualTo(0f)
    }
}
