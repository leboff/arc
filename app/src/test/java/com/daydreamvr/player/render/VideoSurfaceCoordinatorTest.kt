package com.daydreamvr.player.render

import android.graphics.SurfaceTexture
import android.view.Surface
import com.daydreamvr.playback.PlayRequest
import com.daydreamvr.playback.PlaybackSnapshot
import com.daydreamvr.playback.VideoPlayer
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Test

class VideoSurfaceCoordinatorTest {

    @Test
    fun onSurfaceCreatedMarshalsAttachToMainExecutorWithSurfaceAndGeneration() {
        val player = RecordingPlayer()
        val queued = mutableListOf<Runnable>()
        val coordinator = VideoSurfaceCoordinator(player) { queued += it }
        val surface = surface()

        coordinator.onSurfaceCreated(surface, generation = 4)

        assertThat(player.events).isEmpty()
        queued.single().run()
        assertThat(player.events).containsExactly("attach:$surface")
        assertThat(coordinator.generation).isEqualTo(4L)
    }

    @Test
    fun onSurfaceCreatedWithOldSurfaceDetachesBeforeAttachingReplacement() {
        val player = RecordingPlayer()
        val coordinator = VideoSurfaceCoordinator(player) { it.run() }
        coordinator.onSurfaceCreated(surface(), generation = 1)

        val replacement = surface()
        coordinator.onSurfaceCreated(replacement, generation = 2, oldSurface = surface())

        assertThat(player.events).containsExactly(
            player.events[0], "detach", "attach:$replacement",
        ).inOrder()
    }

    @Test
    fun staleGenerationCallbacksAreDiscarded() {
        val player = RecordingPlayer()
        val coordinator = VideoSurfaceCoordinator(player) { it.run() }
        coordinator.onSurfaceCreated(surface(), generation = 2)

        coordinator.onSurfaceCreated(surface(), generation = 1)

        assertThat(player.events).hasSize(1)
        assertThat(coordinator.generation).isEqualTo(2L)
    }

    @Test
    fun onSurfaceDestroyedDetachesPlayerAndClearsActiveSurface() {
        val player = RecordingPlayer()
        val coordinator = VideoSurfaceCoordinator(player) { it.run() }
        val surface = surface()
        coordinator.onSurfaceCreated(surface, generation = 1)

        coordinator.onSurfaceDestroyed(surface, generation = 1)

        assertThat(player.events).containsExactly("attach:$surface", "detach").inOrder()
    }

    @Test
    fun releaseMarksCoordinatorReleasedAndDetachesPlayer() {
        val player = RecordingPlayer()
        val coordinator = VideoSurfaceCoordinator(player) { it.run() }
        coordinator.onSurfaceCreated(surface(), generation = 1)

        coordinator.release()
        coordinator.onSurfaceCreated(surface(), generation = 2)

        assertThat(player.events).containsExactly(player.events[0], "detach").inOrder()
        assertThat(coordinator.generation).isEqualTo(Long.MAX_VALUE)
    }

    private class RecordingPlayer : VideoPlayer {
        override val snapshot: StateFlow<PlaybackSnapshot> = MutableStateFlow(PlaybackSnapshot.EMPTY)
        val events = mutableListOf<String>()

        override fun attach(surface: Surface) { events += "attach:$surface" }
        override fun detach() { events += "detach" }
        override fun play(request: PlayRequest) = Unit
        override fun playPause() = Unit
        override fun pause() = Unit
        override fun stop() = Unit
        override fun seekBy(deltaMs: Long) = Unit
        override fun seekTo(positionMs: Long, exact: Boolean) = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun selectAudioTrack(id: String?) = Unit
        override fun selectSubtitleTrack(id: String?) = Unit
        override fun release() = Unit
    }

    private fun surface() = Surface(SurfaceTexture(0))
}
