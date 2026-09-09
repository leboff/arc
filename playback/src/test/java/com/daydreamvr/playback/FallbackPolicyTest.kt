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

    // ---- F2: engine switching (docs/FORMAT_SUPPORT_PLAN.md §8.4) ----------

    @Test
    fun unsupportedContainer_switchesToVlcWhenVlcUntried() {
        assertThat(
            FallbackPolicy.decide(
                PlaybackFailure.UnsupportedContainer("wmv3"),
                attempt = 0,
                remainingResources = 2,
                enginesTried = setOf(PlaybackEngine.MEDIA3),
            ),
        ).isEqualTo(FallbackAction.SwitchEngine(PlaybackEngine.VLC))
    }

    @Test
    fun unsupportedContainer_fallsToNextResourceOnceVlcTried() {
        assertThat(
            FallbackPolicy.decide(
                PlaybackFailure.UnsupportedContainer(),
                attempt = 0,
                remainingResources = 1,
                enginesTried = setOf(PlaybackEngine.MEDIA3, PlaybackEngine.VLC),
            ),
        ).isEqualTo(FallbackAction.NextResource)
    }

    @Test
    fun unsupportedContainer_givesUpWhenBothEnginesTriedAndNoResources() {
        val giveUp = FallbackPolicy.decide(
            PlaybackFailure.UnsupportedContainer(),
            attempt = 0,
            remainingResources = 0,
            enginesTried = setOf(PlaybackEngine.MEDIA3, PlaybackEngine.VLC),
        )
        assertThat(giveUp).isInstanceOf(FallbackAction.GiveUp::class.java)
    }

    @Test
    fun malformedContainer_routesIdenticallyToUnsupportedContainer() {
        assertThat(
            FallbackPolicy.decide(
                PlaybackFailure.MalformedContainer,
                attempt = 0,
                remainingResources = 0,
                enginesTried = setOf(PlaybackEngine.MEDIA3),
            ),
        ).isEqualTo(FallbackAction.SwitchEngine(PlaybackEngine.VLC))
    }

    @Test
    fun unknownContainerNotSupportedMessage_switchesToVlc() {
        assertThat(
            FallbackPolicy.decide(
                PlaybackFailure.Unknown("Video container not supported by device decoder."),
                attempt = 0,
                remainingResources = 0,
                enginesTried = setOf(PlaybackEngine.MEDIA3),
            ),
        ).isEqualTo(FallbackAction.SwitchEngine(PlaybackEngine.VLC))
    }

    @Test
    fun genericUnknown_isUnaffectedByEngineSwitching() {
        assertThat(
            FallbackPolicy.decide(
                PlaybackFailure.Unknown("something else entirely"),
                attempt = 0,
                remainingResources = 1,
                enginesTried = setOf(PlaybackEngine.MEDIA3),
            ),
        ).isEqualTo(FallbackAction.NextResource)
    }

    @Test
    fun preExistingCasesKeepTheirActionWithDefaultEnginesTried() {
        // Guards the §8.4 signature change: the added parameter must not disturb
        // the F0/F1 ladder for callers that do not pass it.
        assertThat(FallbackPolicy.decide(PlaybackFailure.NetworkTimeout, attempt = 0, remainingResources = 0))
            .isEqualTo(FallbackAction.RetrySameAfter(1_000L))
        assertThat(FallbackPolicy.decide(PlaybackFailure.DecoderInitFailed, attempt = 0, remainingResources = 1))
            .isEqualTo(FallbackAction.NextResource)
        assertThat(FallbackPolicy.decide(PlaybackFailure.AudioTrackInitFailed, attempt = 0, remainingResources = 3))
            .isEqualTo(FallbackAction.ForceSoftwareAudio)
    }
}
