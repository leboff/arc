package com.daydreamvr.player.screens.widgets

import com.daydreamvr.player.state.GazeTarget
import com.daydreamvr.player.state.GazeTarget.Action
import com.daydreamvr.vrcore.ui.PanelMetrics
import com.daydreamvr.vrcore.ui.TextMeasure
import com.daydreamvr.vrcore.ui.Theme
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The inspector (UI_REDESIGN_REVIEWED_PLAN.md §5.3): actions are bottom-anchored
 * and always present; the metadata band above them is clipped, never the other
 * way round. RESUME appears only when a resume position exists, and when it does
 * not, PLAY drops into that bottom slot.
 */
class MediaInspectorLayoutTest {

    private val metrics = PanelMetrics.curved(1536, 800, 2.80f, 2.50f)
    private val measure = TextMeasure.fixed(perCharPx = 12f)
    private val widget = MediaInspectorWidget(Theme.DEFAULT, metrics, measure)
    private val bounds = PixRect(1142f, 0f, 1536f, 800f)

    private fun model(metaRows: Int, resume: String?) = MediaInspectorWidget.Model(
        title = "Some Video",
        metadata = List(metaRows) { MediaInspectorWidget.Model.Row("Field $it", "Value $it") },
        resumeLabel = resume,
        projectionLabel = "Auto (VR180)",
    )

    @Test
    fun fortyMetadataRowsStillLeavePlayAndProjectionOnThePanel() {
        val layout = widget.measureLayout(model(metaRows = 40, resume = null), bounds)

        // Metadata is clipped...
        assertThat(layout.metaRows.size).isLessThan(40)

        // ...but PLAY and PROJECTION survive, fully inside the panel.
        for (r in listOf(layout.play, layout.projection)) {
            assertThat(r.top).isAtLeast(bounds.top)
            assertThat(r.bottom).isAtMost(bounds.bottom)
            assertThat(r.left).isAtLeast(bounds.left)
            assertThat(r.right).isAtMost(bounds.right)
        }
        val ids = widget.hitRegions(layout).map { it.id }
        assertThat(ids).contains(GazeTarget.InspectorAction(Action.PLAY))
        assertThat(ids).contains(GazeTarget.InspectorAction(Action.PROJECTION))
    }

    @Test
    fun resumeButtonIsPresentOnlyWithAResumeEntry() {
        val withResume = widget.measureLayout(model(metaRows = 4, resume = "01:24:50"), bounds)
        assertThat(withResume.resume).isNotNull()
        assertThat(widget.hitRegions(withResume).map { it.id })
            .contains(GazeTarget.InspectorAction(Action.RESUME))
    }

    @Test
    fun withoutResumePlayTakesTheBottomActionSlot() {
        val withResume = widget.measureLayout(model(metaRows = 4, resume = "01:24:50"), bounds)
        val without = widget.measureLayout(model(metaRows = 4, resume = null), bounds)

        assertThat(without.resume).isNull()
        assertThat(without.play.top).isWithin(0.5f).of(withResume.resume!!.top)
        assertThat(widget.hitRegions(without).map { it.id })
            .doesNotContain(GazeTarget.InspectorAction(Action.RESUME))
    }
}
