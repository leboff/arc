package com.daydreamvr.player.screens

import com.daydreamvr.vrcore.ui.AngularMetrics
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.atan
import kotlin.math.max

/**
 * Placement law from UI_REDESIGN_REVIEWED_PLAN.md §2.5 / §2.6, asserted:
 * column centres are eyes-only comfortable, panel edges are head-turn
 * comfortable, nothing above the eye line exceeds +20° and nothing below
 * exceeds −30° (true visual angle).
 */
class ComfortTest {

    private fun deg(rad: Double) = Math.toDegrees(rad).toFloat()

    private val leftCentre = BrowseLayout.azimuthDeg(BrowseLayout.SIDEBAR_PX / 2f)
    private val midCentre = BrowseLayout.azimuthDeg(BrowseLayout.WIDTH_PX / 2f)
    private val rightCentre = BrowseLayout.azimuthDeg(BrowseLayout.WIDTH_PX - BrowseLayout.SIDEBAR_PX / 2f)
    private val maxEdge = BrowseLayout.azimuthDeg(BrowseLayout.WIDTH_PX.toFloat())

    // True (atan) vertical subtense of the surfaces.
    private val panelTop = deg(
        atan((BrowseLayout.VERTICAL_OFFSET_M + BrowseLayout.HEIGHT_M / 2f).toDouble() / BrowseLayout.RADIUS_M),
    )
    private val dockBottom = deg(
        atan((DockLayout.VERTICAL_OFFSET_M - DockLayout.HEIGHT_M / 2f).toDouble() / DockLayout.RADIUS_M),
    )

    @Test
    fun everyColumnCentreIsEyesOnlyComfortable() {
        for (c in listOf(leftCentre, midCentre, rightCentre)) {
            assertThat(kotlin.math.abs(c)).isAtMost(AngularMetrics.COMFORT_H_DEGREES)
        }
    }

    @Test
    fun everyPanelEdgeIsHeadTurnComfortable() {
        assertThat(kotlin.math.abs(maxEdge)).isAtMost(AngularMetrics.COMFORT_H_HEAD_DEGREES)
    }

    @Test
    fun panelTopIsWithinUpwardComfort() {
        assertThat(panelTop).isLessThan(AngularMetrics.COMFORT_V_DEGREES)
        assertThat(AngularMetrics.isWithinComfortBoxAsym(midCentre, panelTop)).isTrue()
    }

    @Test
    fun dockBottomIsWithinDownwardComfort() {
        assertThat(dockBottom).isGreaterThan(-AngularMetrics.COMFORT_V_DOWN_DEGREES)
        assertThat(AngularMetrics.isWithinComfortBoxAsym(0f, dockBottom, allowHeadTurn = true)).isTrue()
    }

    @Test
    fun followThresholdClearsTheReachableSpanWithMargin() {
        assertThat(BrowseLayout.FOLLOW_THRESHOLD_DEG).isGreaterThan(max(kotlin.math.abs(maxEdge), 0f) + 10f)
    }
}
