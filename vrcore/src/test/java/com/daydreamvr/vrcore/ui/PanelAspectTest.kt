package com.daydreamvr.vrcore.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Every panel texture must preserve physical aspect: horizontal px/m within 1%
 * of vertical px/m, or everything drawn on it is stretched (UI_GAZE_PLAN.md
 * §0 F8, §2.2). The table here is the source of truth mirrored by `AppScene`
 * and each `ScreenPanel` subclass.
 */
class PanelAspectTest {

    private data class Panel(
        val name: String,
        val widthPx: Int,
        val heightPx: Int,
        val widthM: Float,
        val heightM: Float,
    )

    private val panels = listOf(
        Panel("ServerListScreen", 1024, 676, 2.20f, 1.45f),
        Panel("BrowseScreen", 1280, 800, 2.40f, 1.50f),
        Panel("SettingsScreen", 1024, 700, 2.20f, 1.50f),
        Panel("PlayerHud", 1280, 332, 2.40f, 0.62f),
        Panel("OverlayRenderer", 1024, 668, 2.00f, 1.30f),
        Panel("GamepadCalibrationScreen", 1024, 512, 2.20f, 1.10f),
        Panel("CalibrationScreen", 1536, 1024, 6.00f, 4.00f),
    )

    @Test
    fun texturesPreservePhysicalAspectWithinOnePercent() {
        for (p in panels) {
            val hPxPerM = p.widthPx / p.widthM
            val vPxPerM = p.heightPx / p.heightM
            val ratio = hPxPerM / vPxPerM
            assertThat(ratio).isWithin(0.01f).of(1.0f)
        }
    }
}
