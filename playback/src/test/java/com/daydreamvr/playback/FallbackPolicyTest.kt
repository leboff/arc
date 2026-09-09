package com.daydreamvr.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FallbackPolicyTest {

    @Test
    fun networkTimeout_backsOffThreeTimesThenGivesUp() {
        val f = PlaybackFailure.NetworkTimeout

        assertThat(FallbackPolicy.decide(f, attempt = 0, remainingResources = 0))
            .isEqualTo(FallbackAction.RetrySameAfter(1_000L))
        assertThat(FallbackPolicy.decide(f, attempt = 1, remainingResources = 0))
            .isEqualTo(FallbackAction.RetrySameAfter(3_000L))
        assertThat(FallbackPolicy.decide(f, attempt = 2, remainingResources = 0))
            .isEqualTo(FallbackAction.RetrySameAfter(7_000L))
        assertThat(FallbackPolicy.decide(f, attempt = 3, remainingResources = 0))
            .isInstanceOf(FallbackAction.GiveUp::class.java)
    }

    @Test
    fun networkTimeout_fallsToNextResourceWhenOneRemains() {
        assertThat(FallbackPolicy.decide(PlaybackFailure.NetworkConnectionFailed, attempt = 3, remainingResources = 1))
            .isEqualTo(FallbackAction.NextResource)
    }

    @Test
    fun badHttpStatus_refreshesUrlOnceThenNextResource() {
        val f = PlaybackFailure.BadHttpStatus(404)

        assertThat(FallbackPolicy.decide(f, attempt = 0, remainingResources = 1))
            .isEqualTo(FallbackAction.RefreshUrlFromServer)
        assertThat(FallbackPolicy.decide(f, attempt = 1, remainingResources = 1))
            .isEqualTo(FallbackAction.NextResource)
        assertThat(FallbackPolicy.decide(f, attempt = 1, remainingResources = 0))
            .isInstanceOf(FallbackAction.GiveUp::class.java)
    }

    @Test
    fun unsupportedCodec_triesNextThenGivesUpNamingTheCodec() {
        val f = PlaybackFailure.UnsupportedVideoCodec("hevc/dvhe")

        assertThat(FallbackPolicy.decide(f, attempt = 0, remainingResources = 2))
            .isEqualTo(FallbackAction.NextResource)

        val giveUp = FallbackPolicy.decide(f, attempt = 0, remainingResources = 0)
        assertThat(giveUp).isInstanceOf(FallbackAction.GiveUp::class.java)
        assertThat((giveUp as FallbackAction.GiveUp).userMessage).contains("hevc/dvhe")
    }

    @Test
    fun audioInitFailure_forcesSoftwareAudioBeforeGivingUp() {
        val f = PlaybackFailure.AudioTrackInitFailed

        assertThat(FallbackPolicy.decide(f, attempt = 0, remainingResources = 3))
            .isEqualTo(FallbackAction.ForceSoftwareAudio)
        assertThat(FallbackPolicy.decide(f, attempt = 1, remainingResources = 3))
            .isInstanceOf(FallbackAction.GiveUp::class.java)
    }

    @Test
    fun decoderInitFailure_advancesResourcesThenGivesUp() {
        val f = PlaybackFailure.DecoderInitFailed
        assertThat(FallbackPolicy.decide(f, attempt = 0, remainingResources = 1))
            .isEqualTo(FallbackAction.NextResource)
        assertThat(FallbackPolicy.decide(f, attempt = 0, remainingResources = 0))
            .isInstanceOf(FallbackAction.GiveUp::class.java)
    }
}
