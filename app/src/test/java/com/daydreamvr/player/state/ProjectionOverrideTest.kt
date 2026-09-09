package com.daydreamvr.player.state

import com.daydreamvr.player.media.MediaKey
import com.daydreamvr.vrcore.render.ProjectionMode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The inspector projection cycle: FLAT -> SBS_HALF -> TOPBOTTOM_HALF ->
 * EQUIRECT_180 -> EQUIRECT_360 -> Auto (UI_REDESIGN_REVIEWED_PLAN.md §10.4,
 * M4 acceptance 8).
 */
class ProjectionOverrideTest {

    @Test
    fun cyclingReachesAutoAfterFiveStepsAndAlwaysPersists() {
        val video = Fx.videoNode("100", projection = ProjectionMode.FLAT)
        val d = Driver(Fx.browsing(Fx.localFrame(videos = listOf(video), focus = BrowseFocus.Grid(0))))
        val key = MediaKey("local", "100")

        val modes = mutableListOf<ProjectionMode?>()
        repeat(5) {
            d.send(Event.Ui(UiIntent.OverrideProjection))
            val fx = d.stepEffects.filterIsInstance<Effect.PersistProjectionOverride>().single()
            assertThat(fx.key).isEqualTo(key)
            modes += fx.mode
        }

        assertThat(modes).containsExactly(
            ProjectionMode.SBS_HALF,
            ProjectionMode.TOPBOTTOM_HALF,
            ProjectionMode.EQUIRECT_180,
            ProjectionMode.EQUIRECT_360,
            null,
        ).inOrder()
        // Auto is the absence of an override entry.
        assertThat(d.state.projectionOverrides).doesNotContainKey(key.storageKey())
    }
}
