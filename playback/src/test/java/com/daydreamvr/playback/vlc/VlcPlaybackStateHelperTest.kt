package com.daydreamvr.playback.vlc

import com.daydreamvr.playback.PlaybackState
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VlcPlaybackStateHelperTest {

    @Test
    fun isBuffering_isStrictlyFalse_whenPlayingNowIsTrue() {
        assertThat(VlcPlaybackStateHelper.isBuffering(PlaybackState.BUFFERING, isPlayingNow = true)).isFalse()
        assertThat(VlcPlaybackStateHelper.isBuffering(PlaybackState.READY, isPlayingNow = true)).isFalse()
        assertThat(VlcPlaybackStateHelper.isBuffering(PlaybackState.IDLE, isPlayingNow = true)).isFalse()
    }

    @Test
    fun isBuffering_isTrue_onlyWhenBufferingStateAndNotPlaying() {
        assertThat(VlcPlaybackStateHelper.isBuffering(PlaybackState.BUFFERING, isPlayingNow = false)).isTrue()
        assertThat(VlcPlaybackStateHelper.isBuffering(PlaybackState.READY, isPlayingNow = false)).isFalse()
    }

    @Test
    fun onBufferingEvent_transitionsToBuffering_onlyWhenNotPlaying() {
        // Stalled/buffering before or during pause -> BUFFERING
        val s1 = VlcPlaybackStateHelper.onBufferingEvent(PlaybackState.READY, isPlayingNow = false, percent = 50f)
        assertThat(s1).isEqualTo(PlaybackState.BUFFERING)

        // Stale or periodic buffering percent while actively rendering frames -> stays in currentState
        val s2 = VlcPlaybackStateHelper.onBufferingEvent(PlaybackState.READY, isPlayingNow = true, percent = 99f)
        assertThat(s2).isEqualTo(PlaybackState.READY)
    }

    @Test
    fun onBufferingEvent_clearsBufferingAtHundredPercent() {
        val s = VlcPlaybackStateHelper.onBufferingEvent(PlaybackState.BUFFERING, isPlayingNow = false, percent = 100f)
        assertThat(s).isEqualTo(PlaybackState.READY)
    }

    @Test
    fun effectiveState_resolvesToReady_whenPlayingAndBuffering() {
        val s = VlcPlaybackStateHelper.effectiveState(PlaybackState.BUFFERING, isPlayingNow = true)
        assertThat(s).isEqualTo(PlaybackState.READY)

        val s2 = VlcPlaybackStateHelper.effectiveState(PlaybackState.BUFFERING, isPlayingNow = false)
        assertThat(s2).isEqualTo(PlaybackState.BUFFERING)
    }
}
