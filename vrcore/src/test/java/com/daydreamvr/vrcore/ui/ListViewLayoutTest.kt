package com.daydreamvr.vrcore.ui

import com.daydreamvr.vrcore.ui.widgets.ListView
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the F2 collision class shut: a two-line row must fully contain its own
 * subtitle, no two rows may overlap, and the reducer's scroll window must match
 * what the renderer actually draws (UI_GAZE_PLAN.md §0 F2/F5, §4.7).
 *
 * All assertions run against `measureLayout`, which is pure and takes an
 * injected [TextMeasure] — no `Paint` on the assertion path.
 */
class ListViewLayoutTest {

    private val measure = TextMeasure.fixed(perCharPx = 14f)

    private fun panel() = PanelMetrics.curved(1024, 676, 2.20f, 2.5f)

    private fun twoLineEntries(n: Int): List<ListView.Entry> =
        (0 until n).map { ListView.Entry.Item(title = "Server $it", subtitle = "acme · 10.0.0.$it") }

    @Test
    fun twoLineSubtitleFitsInsideItsOwnRow() {
        val lv = ListView(Theme.DEFAULT, panel(), measure)
        val layout = lv.measureLayout(twoLineEntries(20), top = 60f, height = 500f, left = 20f, width = 900f, scrollTop = 0)

        assertThat(layout.twoLine).isTrue()
        val subSize = panel().px(Type.rowSubtitle.degrees)
        val padV = panel().px(Space.M)
        val subtitleBottom = layout.subtitleBaselineDy + measure.descentPx(subSize, bold = false)
        assertThat(subtitleBottom).isAtMost(layout.rowHeightPx - padV + 0.5f)
    }

    @Test
    fun noTwoBoxesOverlapAndAllStayInsideBody() {
        val lv = ListView(Theme.DEFAULT, panel(), measure)
        val top = 60f
        val height = 500f
        val layout = lv.measureLayout(twoLineEntries(40), top, height, left = 20f, width = 900f, scrollTop = 5)

        var prevBottom = top - 1f
        for (box in layout.boxes) {
            assertThat(box.top).isAtLeast(prevBottom - 0.01f)
            assertThat(box.bottom).isAtMost(top + height + 0.5f)
            prevBottom = box.bottom
        }
        assertThat(layout.visibleCount * layout.rowHeightPx).isAtMost(height + layout.rowHeightPx)
    }

    @Test
    fun visibleRowCountMatchesWhatMeasureLayoutDraws() {
        val lv = ListView(Theme.DEFAULT, panel(), measure)
        val height = 500f
        val layout = lv.measureLayout(twoLineEntries(40), top = 0f, height = height, left = 0f, width = 900f, scrollTop = 0)
        assertThat(lv.visibleRowCount(height, twoLine = true)).isEqualTo(layout.visibleCount)
    }

    @Test
    fun headerRowsAreNotFocusable() {
        val entries = listOf(
            ListView.Entry.Header("VIEWER"),
            ListView.Entry.Item("IPD", trailing = "63.0 mm"),
            ListView.Entry.Header("OPTICS"),
            ListView.Entry.Item("Lens k1", trailing = "0.34"),
        )
        val lv = ListView(Theme.DEFAULT, panel(), measure)
        val layout = lv.measureLayout(entries, top = 0f, height = 600f, left = 0f, width = 900f, scrollTop = 0)
        val headerBoxes = layout.boxes.filter { !it.focusable }
        assertThat(headerBoxes).hasSize(2)
        assertThat(layout.boxes.filter { it.focusable }).hasSize(2)
    }
}
