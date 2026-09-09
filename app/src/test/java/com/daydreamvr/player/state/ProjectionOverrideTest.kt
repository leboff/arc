package com.daydreamvr.player.state

import com.daydreamvr.player.media.MediaKey
import com.daydreamvr.vrcore.render.ProjectionMode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProjectionOverrideTest {

    @Test
    fun cyclingReachesEveryModeThenAutoAndAlwaysPersists() {
        val video = Fx.videoNode("100", projection = ProjectionMode.FLAT)
        val d = Driver(Fx.browsing(Fx.localFrame(videos = listOf(video), focus = BrowseFocus.Grid(0))))
        val key = MediaKey("local", "100")

        val modes = mutableListOf<ProjectionMode?>()
        repeat(ProjectionMode.entries.size) {
            d.send(Event.Ui(UiIntent.OverrideProjection))
            val fx = d.stepEffects.filterIsInstance<Effect.PersistProjectionOverride>().single()
            assertThat(fx.key).isEqualTo(key)
            modes += fx.mode
        }

        assertThat(modes).containsExactlyElementsIn(ProjectionMode.entries.drop(1) + null).inOrder()
        // Auto is the absence of an override entry.
        assertThat(d.state.projectionOverrides).doesNotContainKey(key.storageKey())
    }
}
