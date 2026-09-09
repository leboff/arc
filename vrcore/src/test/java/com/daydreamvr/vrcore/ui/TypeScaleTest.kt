package com.daydreamvr.vrcore.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Every type token, resolved on every interactive panel, must clear the
 * legibility floor (UI_GAZE_PLAN.md §4.1, ARCHITECTURE.md §11.3).
 */
class TypeScaleTest {

    private val panels = listOf(
        PanelMetrics.curved(1024, 676, 2.20f, 2.5f), // ServerList
        PanelMetrics.curved(1280, 800, 2.40f, 2.5f), // Browse
        PanelMetrics.curved(1024, 700, 2.20f, 2.5f), // Settings
        PanelMetrics.curved(1280, 332, 2.40f, 2.5f), // PlayerHud
        PanelMetrics.curved(1024, 668, 2.00f, 2.5f), // Overlay
        PanelMetrics.curved(1024, 512, 2.20f, 2.5f), // GamepadCal
    )

    @Test
    fun everyTokenIsLegibleOnEveryPanel() {
        for (m in panels) {
            for (token in Type.ALL) {
                val deg = m.deg(m.px(token.degrees))
                assertThat(deg).isAtLeast(AngularMetrics.MIN_TEXT_DEGREES)
            }
        }
    }

    @Test
    fun tokenDegreesAreDeclaredAboveTheFloor() {
        for (token in Type.ALL) {
            assertThat(token.degrees).isAtLeast(AngularMetrics.MIN_TEXT_DEGREES)
        }
    }
}
