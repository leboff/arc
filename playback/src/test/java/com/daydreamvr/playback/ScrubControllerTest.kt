package com.daydreamvr.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScrubControllerTest {

    private val hourMs = 3_600_000L

    @Test
    fun fullTriggerPullForFiveSeconds_movesPreviewAboutTenMinutes() {
        val sc = ScrubController()
        sc.onScrub(1f, 0L, 0L, hourMs)

        var out = ScrubOutput(0L, false)
        var t = 0L
        while (t < 5_000L) {
            t += 100L
            out = sc.onScrub(1f, t, 0L, hourMs)
        }

        // rate² · 120 s/s · 5 s ≈ 600 s.
        assertThat(out.previewPositionMs).isWithin(2_000L).of(600_000L)
    }

    @Test
    fun commitSeeksFireAtMostEvery250ms() {
        val sc = ScrubController()
        sc.onScrub(1f, 0L, 0L, hourMs)

        val commitTimes = mutableListOf<Long>()
        var t = 0L
        while (t < 5_000L) {
            t += 100L
            if (sc.onScrub(1f, t, 0L, hourMs).commitSeek) commitTimes += t
        }

        assertThat(commitTimes).isNotEmpty()
        commitTimes.zipWithNext().forEach { (a, b) ->
            assertThat(b - a).isAtLeast(ScrubController.COMMIT_INTERVAL_MS)
        }
    }

    @Test
    fun releaseReturnsExactFinalTarget_thenNullUntilNextGesture() {
        val sc = ScrubController()
        sc.onScrub(0.5f, 0L, 100_000L, hourMs)
        val mid = sc.onScrub(0.5f, 1_000L, 100_000L, hourMs)

        val target = sc.onRelease()
        assertThat(target).isEqualTo(mid.previewPositionMs)
        assertThat(sc.onRelease()).isNull()
    }

    @Test
    fun releaseWithoutScrubbing_isNull() {
        assertThat(ScrubController().onRelease()).isNull()
    }

    @Test
    fun previewClampsAtZeroAndDuration() {
        val back = ScrubController()
        back.onScrub(-1f, 0L, 0L, 100_000L)
        assertThat(back.onScrub(-1f, 1_000L, 0L, 100_000L).previewPositionMs).isEqualTo(0L)

        val fwd = ScrubController()
        fwd.onScrub(1f, 0L, 90_000L, 100_000L)
        assertThat(fwd.onScrub(1f, 1_000L, 90_000L, 100_000L).previewPositionMs).isEqualTo(100_000L)
    }

    @Test
    fun rateZeroEndsTheGesture() {
        val sc = ScrubController()
        sc.onScrub(0.8f, 0L, 10_000L, hourMs)
        sc.onScrub(0.8f, 500L, 10_000L, hourMs)

        val ended = sc.onScrub(0f, 700L, 10_000L, hourMs)
        assertThat(ended.commitSeek).isFalse()

        // A fresh pull after release starts again from the supplied position.
        assertThat(sc.onRelease()).isNotNull()
        val restart = sc.onScrub(1f, 800L, 42_000L, hourMs)
        assertThat(restart.previewPositionMs).isEqualTo(42_000L)
    }
}
