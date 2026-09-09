package com.daydreamvr.player.state

import com.daydreamvr.playback.PlaybackFailure
import com.daydreamvr.playback.PlaybackSnapshot
import com.daydreamvr.playback.PlaybackState
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ErrorOverlayClearTest {

    @Test
    fun errorOverlayIsDismissedWhenPlaybackBecomesReadyOrPlaying() {
        val stateWithError = AppState(
            screen = VrScreen.PLAYER,
            playback = PlaybackSlice(itemKey = "v1"),
            overlay = Overlay.Error("Playback problem", "Failed to decode", canRetry = true),
        )
        val d = Driver(stateWithError)
        assertThat(d.state.overlay).isInstanceOf(Overlay.Error::class.java)

        // Player emits snapshot that is ready/playing with no failure (e.g. fallback engine started)
        d.send(
            Event.PlayerStateChanged(
                PlaybackSnapshot(
                    itemKey = "v1",
                    title = "Video v1",
                    state = PlaybackState.READY,
                    isPlaying = true,
                    failure = null,
                )
            )
        )

        // Error overlay must be dismissed
        assertThat(d.state.overlay).isNull()
    }

    @Test
    fun failureSnapshotSetsErrorOverlay() {
        val d = Driver(AppState(screen = VrScreen.PLAYER, playback = PlaybackSlice(itemKey = "v1")))
        assertThat(d.state.overlay).isNull()

        d.send(
            Event.PlayerStateChanged(
                PlaybackSnapshot(
                    itemKey = "v1",
                    failure = PlaybackFailure.UnsupportedContainer("wmv3"),
                )
            )
        )

        assertThat(d.state.overlay).isInstanceOf(Overlay.Error::class.java)
        val err = d.state.overlay as Overlay.Error
        assertThat(err.message).contains("wmv3")
    }
}
