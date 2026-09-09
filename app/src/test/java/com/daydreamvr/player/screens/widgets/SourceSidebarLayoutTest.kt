package com.daydreamvr.player.screens.widgets

import com.daydreamvr.player.state.GazeTarget
import com.daydreamvr.vrcore.ui.Icon
import com.daydreamvr.vrcore.ui.PanelMetrics
import com.daydreamvr.vrcore.ui.TextMeasure
import com.daydreamvr.vrcore.ui.Theme
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The left sidebar (UI_REDESIGN_REVIEWED_PLAN.md §5.1): a 3-row vertical source
 * switcher, a divider, then a measured folder list. `visibleRows()` is the exact
 * count the reducer must scroll by, and it must equal what the layout actually
 * emits.
 */
class SourceSidebarLayoutTest {

    private val metrics = PanelMetrics.curved(1536, 800, 2.80f, 2.50f)
    private val measure = TextMeasure.fixed(perCharPx = 12f)
    private val widget = SourceSidebarWidget(Theme.DEFAULT, metrics, measure)
    private val bounds = PixRect(0f, 0f, 394f, 800f)

    private val sources = listOf(
        SourceSidebarWidget.Model.Source("Device", Icon.PHONE, 12),
        SourceSidebarWidget.Model.Source("Network", Icon.NETWORK, 3),
        SourceSidebarWidget.Model.Source("Favourites", Icon.STAR, 8),
    )

    @Test
    fun threeSourceRowsThenDividerThenFolderRows() {
        val model = SourceSidebarWidget.Model(sources, List(10) { SourceSidebarWidget.Model.Folder("F$it", it) })
        val layout = widget.measureLayout(model, bounds)

        assertThat(layout.sourceBoxes).hasSize(3)
        // Source rows stacked directly under the header, contiguous.
        assertThat(layout.sourceBoxes[0].rect.top).isWithin(0.5f).of(layout.header.bottom)
        for (i in 0 until 2) {
            assertThat(layout.sourceBoxes[i].rect.bottom).isWithin(0.5f).of(layout.sourceBoxes[i + 1].rect.top)
        }
        // Divider sits below the switcher, folders below the divider.
        assertThat(layout.dividerY).isAtLeast(layout.sourceBoxes.last().rect.bottom - 0.5f)
        assertThat(layout.folderTop).isGreaterThan(layout.dividerY)
        assertThat(layout.folderBoxes).isNotEmpty()
        assertThat(layout.folderBoxes.first().index).isEqualTo(0)
    }

    @Test
    fun visibleRowsMatchesTheNumberOfBoxesEmitted() {
        // Far more folders than can fit, so the emitted count is the capacity.
        val model = SourceSidebarWidget.Model(sources, List(60) { SourceSidebarWidget.Model.Folder("F$it", it) })
        val layout = widget.measureLayout(model, bounds)

        assertThat(layout.visibleRows()).isEqualTo(layout.folderBoxes.size)
        assertThat(widget.hitRegions(layout).count { it.id is GazeTarget.SidebarRow })
            .isEqualTo(layout.visibleRows())
    }

    @Test
    fun folderRowsDoNotOverlapAndStayInsideBounds() {
        val model = SourceSidebarWidget.Model(sources, List(60) { SourceSidebarWidget.Model.Folder("F$it", it) })
        val layout = widget.measureLayout(model, bounds)
        var prevBottom = layout.folderTop - 0.01f
        for (b in layout.folderBoxes) {
            assertThat(b.rect.top).isAtLeast(prevBottom - 0.01f)
            assertThat(b.rect.bottom).isAtMost(bounds.bottom + 0.5f)
            prevBottom = b.rect.bottom
        }
    }
}
