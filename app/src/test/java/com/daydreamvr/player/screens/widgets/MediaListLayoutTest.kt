package com.daydreamvr.player.screens.widgets

import com.daydreamvr.player.state.GazeTarget
import com.daydreamvr.vrcore.render.ProjectionMode
import com.daydreamvr.vrcore.ui.AngularMetrics
import com.daydreamvr.vrcore.ui.PanelMetrics
import com.daydreamvr.vrcore.ui.TextMeasure
import com.daydreamvr.vrcore.ui.Theme
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MediaListLayoutTest {

    private val metrics = PanelMetrics.curved(1536, 800, 2.80f, 2.50f)
    private val measure = TextMeasure.fixed(perCharPx = 12f)
    private val widget = MediaListWidget(Theme.DEFAULT, metrics, measure)
    private val bounds = PixRect(418f, 0f, 1118f, 800f)

    private fun cards(n: Int) =
        List(n) {
            MediaGridWidget.Model.Card(
                title = "Video Clip $it",
                meta = "3840×2160 · 4.2 GB",
                durationLabel = "12:30",
                projection = ProjectionMode.EQUIRECT_180,
                qualityLabel = "4K",
            )
        }

    @Test
    fun sixRowsAreMeasured() {
        val layout = widget.measureLayout(MediaListWidget.Model(cards(6)), bounds)
        assertThat(layout.rows).hasSize(6)
    }

    @Test
    fun rowBoxesArePairwiseDisjointAndInsideListBand() {
        val layout = widget.measureLayout(MediaListWidget.Model(cards(6)), bounds)
        for (r in layout.rows) {
            assertThat(r.box.left).isAtLeast(bounds.left)
            assertThat(r.box.right).isAtMost(bounds.right)
            assertThat(r.box.top).isAtLeast(layout.listBand.top - 0.5f)
            assertThat(r.box.bottom).isAtMost(layout.listBand.bottom + 0.5f)
        }
        for (i in layout.rows.indices) {
            for (j in i + 1 until layout.rows.size) {
                assertThat(layout.rows[i].box.overlaps(layout.rows[j].box)).isFalse()
            }
        }
    }

    @Test
    fun hitRegionsSpanTheWholeRowBox() {
        val layout = widget.measureLayout(MediaListWidget.Model(cards(6)), bounds)
        val regions = widget.hitRegions(layout)
        assertThat(regions).hasSize(6)
        for (i in 0 until 6) {
            val r = regions[i]
            val row = layout.rows[i]
            assertThat(r.left).isEqualTo(row.box.left)
            assertThat(r.top).isEqualTo(row.box.top)
            assertThat(r.right).isEqualTo(row.box.right)
            assertThat(r.bottom).isEqualTo(row.box.bottom)
            assertThat(r.id).isEqualTo(GazeTarget.GridCell(i))
        }
    }

    @Test
    fun absoluteIndexOnPageOneStartsAtSix() {
        val layout = widget.measureLayout(MediaListWidget.Model(cards(12), page = 1), bounds)
        val regions = widget.hitRegions(layout)
        assertThat(regions.first().id).isEqualTo(GazeTarget.GridCell(6))
        assertThat(regions.last().id).isEqualTo(GazeTarget.GridCell(11))
    }

    @Test
    fun hitTargetSizeSatisfiesLegibilityContract() {
        val layout = widget.measureLayout(MediaListWidget.Model(cards(6)), bounds)
        val regions = widget.hitRegions(layout)
        for (r in regions) {
            val minDeg = minOf(metrics.deg(r.right - r.left), metrics.deg(r.bottom - r.top))
            assertThat(minDeg).isAtLeast(AngularMetrics.MIN_TARGET_DEGREES)
        }
    }
}
