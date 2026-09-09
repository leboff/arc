package com.daydreamvr.player.screens.widgets

import com.daydreamvr.player.state.GazeTarget
import com.daydreamvr.vrcore.ui.PanelMetrics
import com.daydreamvr.vrcore.ui.TextMeasure
import com.daydreamvr.vrcore.ui.Theme
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The centre grid is derived, not hand-placed (UI_REDESIGN_REVIEWED_PLAN.md §5.2):
 * six 16:9 cards in a 3 × 2 tiling of the grid band, each card's *whole box* — not
 * just its poster — published as an absolutely-indexed hit region.
 */
class MediaGridLayoutTest {

    private val metrics = PanelMetrics.curved(1536, 800, 2.80f, 2.50f)
    private val measure = TextMeasure.fixed(perCharPx = 12f)
    private val widget = MediaGridWidget(Theme.DEFAULT, metrics, measure)
    private val bounds = PixRect(418f, 0f, 1118f, 800f)

    private fun cards(n: Int) =
        List(n) { MediaGridWidget.Model.Card(title = "Clip $it", meta = "3840×2160 · 4.2 GB") }

    @Test
    fun sixCellsAreMeasured() {
        val layout = widget.measureLayout(MediaGridWidget.Model(cards(6)), bounds)
        assertThat(layout.cards).hasSize(6)
    }

    @Test
    fun cardWidthMatchesTheDerivation() {
        val layout = widget.measureLayout(MediaGridWidget.Model(cards(6)), bounds)
        assertThat(layout.cardW).isWithin(0.5f).of(215.78f)
    }

    @Test
    fun posterAspectRatioIs16By9() {
        val layout = widget.measureLayout(MediaGridWidget.Model(cards(6)), bounds)
        val target = 16f / 9f
        for (c in layout.cards) {
            assertThat(c.poster.width / c.poster.height).isWithin(target * 0.005f).of(target)
        }
    }

    @Test
    fun cardBoxesArePairwiseDisjointAndInsideTheGridBand() {
        val layout = widget.measureLayout(MediaGridWidget.Model(cards(6)), bounds)
        for (c in layout.cards) {
            assertThat(c.box.top).isAtLeast(140.39f - 0.5f)
            assertThat(c.box.bottom).isAtMost(719.10f + 0.5f)
        }
        for (i in layout.cards.indices) {
            for (j in i + 1 until layout.cards.size) {
                assertThat(layout.cards[i].box.overlaps(layout.cards[j].box)).isFalse()
            }
        }
    }

    @Test
    fun hitRegionsSpanTheWholeCard() {
        val layout = widget.measureLayout(MediaGridWidget.Model(cards(6)), bounds)
        val regions = widget.hitRegions(layout)
        assertThat(regions).hasSize(6)
        for (r in regions) {
            assertThat(r.bottom - r.top).isWithin(0.5f).of(layout.cardH)
            assertThat(r.right - r.left).isWithin(0.5f).of(layout.cardW)
        }
    }

    @Test
    fun absoluteIndexOnPageOneStartsAtSix() {
        val layout = widget.measureLayout(MediaGridWidget.Model(cards(12), page = 1), bounds)
        assertThat(widget.hitRegions(layout).first().id).isEqualTo(GazeTarget.GridCell(6))
    }
}
