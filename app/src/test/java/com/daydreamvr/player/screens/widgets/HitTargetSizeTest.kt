package com.daydreamvr.player.screens.widgets

import com.daydreamvr.player.state.GazeTarget
import com.daydreamvr.vrcore.render.ProjectionMode
import com.daydreamvr.vrcore.ui.AngularMetrics
import com.daydreamvr.vrcore.ui.HitRegion
import com.daydreamvr.vrcore.ui.Icon
import com.daydreamvr.vrcore.ui.PanelMetrics
import com.daydreamvr.vrcore.ui.TextMeasure
import com.daydreamvr.vrcore.ui.Theme
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The hit-target invariant (UI_REDESIGN_REVIEWED_PLAN.md §6): for every
 * `HitRegion` any of the four PLAY'A widgets publishes with a populated model,
 * `min(deg(w), deg(h)) >= MIN_TARGET_DEGREES`. Driven by `TextMeasure.fixed`, so
 * the assertion path never touches `Paint`.
 */
class HitTargetSizeTest {

    private val browse = PanelMetrics.curved(1536, 800, 2.80f, 2.50f)
    private val dock = PanelMetrics.curved(672, 176, 1.00f, 2.05f)
    private val measure = TextMeasure.fixed(perCharPx = 12f)

    private fun assertAllBigEnough(regions: List<HitRegion<GazeTarget>>, m: PanelMetrics) {
        assertThat(regions).isNotEmpty()
        for (r in regions) {
            val minDeg = minOf(m.deg(r.right - r.left), m.deg(r.bottom - r.top))
            assertThat(minDeg).isAtLeast(AngularMetrics.MIN_TARGET_DEGREES)
        }
    }

    @Test
    fun sourceSidebarRegions() {
        val w = SourceSidebarWidget(Theme.DEFAULT, browse, measure)
        val model = SourceSidebarWidget.Model(
            sources = listOf(
                SourceSidebarWidget.Model.Source("Device", Icon.PHONE, 12),
                SourceSidebarWidget.Model.Source("Network", Icon.NETWORK, 3),
                SourceSidebarWidget.Model.Source("Favourites", Icon.STAR, 8),
            ),
            folders = List(24) { SourceSidebarWidget.Model.Folder("Folder $it", it) },
        )
        assertAllBigEnough(w.hitRegions(w.measureLayout(model, PixRect(0f, 0f, 394f, 800f))), browse)
    }

    @Test
    fun mediaGridRegions() {
        val w = MediaGridWidget(Theme.DEFAULT, browse, measure)
        val model = MediaGridWidget.Model(
            List(6) {
                MediaGridWidget.Model.Card(
                    title = "Clip $it",
                    meta = "3840×2160 · 4.2 GB",
                    durationLabel = "12:30",
                    projection = ProjectionMode.EQUIRECT_180,
                    qualityLabel = "4K",
                )
            },
        )
        assertAllBigEnough(w.hitRegions(w.measureLayout(model, PixRect(418f, 0f, 1118f, 800f))), browse)
    }

    @Test
    fun mediaListRegions() {
        val w = MediaListWidget(Theme.DEFAULT, browse, measure)
        val model = MediaListWidget.Model(
            List(6) {
                MediaGridWidget.Model.Card(
                    title = "Clip $it",
                    meta = "3840×2160 · 4.2 GB",
                    durationLabel = "12:30",
                    projection = ProjectionMode.EQUIRECT_180,
                    qualityLabel = "4K",
                )
            },
        )
        assertAllBigEnough(w.hitRegions(w.measureLayout(model, PixRect(418f, 0f, 1118f, 800f))), browse)
    }

    @Test
    fun mediaInspectorRegions() {
        val w = MediaInspectorWidget(Theme.DEFAULT, browse, measure)
        val model = MediaInspectorWidget.Model(
            title = "A Long Documentary Title That Wraps",
            metadata = List(8) { MediaInspectorWidget.Model.Row("Field $it", "Value $it") },
            resumeLabel = "01:24:50",
            projectionLabel = "Auto (VR180)",
        )
        assertAllBigEnough(w.hitRegions(w.measureLayout(model, PixRect(1142f, 0f, 1536f, 800f))), browse)
    }

    @Test
    fun systemDockRegions() {
        val w = SystemDockWidget(Theme.DEFAULT, dock, measure)
        assertAllBigEnough(w.hitRegions(w.measureLayout(SystemDockWidget.Model(), PixRect(0f, 0f, 672f, 176f))), dock)
    }
}
