package com.daydreamvr.player.state

import com.daydreamvr.vrcore.input.InputAction
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Player HUD behaviour (ARCHITECTURE.md §11.4): it appears on the first input,
 * auto-hides 4s after the last one unless pinned, and cancel/confirm dismiss or
 * activate it rather than leaking through to the escape hatch.
 */
class HudReducerTest {

    private fun inPlayer(nowMs: Long = 1_000L) = Driver(
        AppState(
            screen = VrScreen.PLAYER,
            playback = PlaybackSlice(itemKey = "v1", title = "Video v1", durationMs = 60_000L),
            nowMs = nowMs,
        ),
    )

    @Test
    fun projectionControlVisitsEveryDomePackingAndFov() {
        val d = inPlayer()
        val modes = com.daydreamvr.vrcore.render.ProjectionMode.entries
        for (expected in modes.drop(1) + modes.first()) {
            d.input(InputAction.CycleProjection)
            assertThat(d.state.playback.projection).isEqualTo(expected)
            assertThat(d.stepEffects).contains(Effect.SetProjection(expected))
        }
    }

    @Test
    fun firstInputShowsTheHudWithoutActingOnIt() {
        val d = inPlayer()
        d.input(InputAction.Confirm(long = false))
        assertThat(d.state.hud.visible).isTrue()
        assertThat(d.stepEffects).isEmpty()
    }

    @Test
    fun hudAutoHidesExactlyFourSecondsAfterLastInput() {
        val d = inPlayer(nowMs = 1_000L)
        d.input(Fx.right) // shows HUD, stamps lastInputAtMs = 1_000
        assertThat(d.state.hud.visible).isTrue()

        d.tick(1_000L + HudState.AUTO_HIDE_MS - 1)
        assertThat(d.state.hud.visible).isTrue()

        d.tick(1_000L + HudState.AUTO_HIDE_MS)
        assertThat(d.state.hud.visible).isFalse()
    }

    @Test
    fun pinnedHudNeverAutoHides() {
        val d = inPlayer(nowMs = 1_000L)
        d.input(InputAction.ToggleHud) // shows + pins
        assertThat(d.state.hud.visible).isTrue()
        assertThat(d.state.hud.pinned).isTrue()

        d.tick(1_000L + 10 * HudState.AUTO_HIDE_MS)
        assertThat(d.state.hud.visible).isTrue()
    }

    @Test
    fun cancelOnAVisibleHudDismissesTheHud() {
        val d = inPlayer()
        d.input(Fx.right)
        assertThat(d.state.hud.visible).isTrue()

        // B with the HUD up dismisses the controls so video is clean fullscreen.
        d.input(Fx.cancel)
        assertThat(d.state.screen).isEqualTo(VrScreen.PLAYER)
        assertThat(d.state.hud.visible).isFalse()
    }

    @Test
    fun cancelOnAHiddenHudLeavesThePlayer() {
        val d = inPlayer()
        assertThat(d.state.hud.visible).isFalse()

        // B with no HUD is the escape hatch back to the list.
        d.input(Fx.cancel)
        assertThat(d.state.screen).isNotEqualTo(VrScreen.PLAYER)
        assertThat(d.stepEffects).contains(Effect.StopPlayback)
    }

    @Test
    fun confirmOnAVisibleHudActivatesTheFocusedControl() {
        val d = inPlayer()
        d.input(Fx.right) // show HUD, focus -> 1
        d.input(Fx.right) // focus -> 2 == "Speed"
        assertThat(HudState.CONTROLS[d.state.hud.focusIndex]).isEqualTo("Speed")

        d.input(Fx.confirm)
        assertThat(d.state.playback.speed).isWithin(0.001f).of(1.25f)
        assertThat(d.stepEffects).contains(Effect.SetPlaybackSpeed(1.25f))
    }

    @Test
    fun gazeOnTimelineUpdatesPreviewAndConfirmSeeksDirectly() {
        val d = inPlayer()
        // Gaze at 50% along the timeline scrubber (30_000ms of 60_000ms duration)
        d.send(Event.GazeMoved(GazeTarget.HudTimeline(0.50f)))
        assertThat(d.state.playback.previewPositionMs).isEqualTo(30_000L)

        // Confirm while gazing at timeline emits Seek effect to that exact time
        d.input(Fx.confirm)
        assertThat(d.state.playback.previewPositionMs).isNull()
        assertThat(d.stepEffects).contains(Effect.Seek(30_000L, exact = true))
    }

    @Test
    fun timelineSeekIgnoresNonPositiveDuration() {
        val d = Driver(
            AppState(
                screen = VrScreen.PLAYER,
                playback = PlaybackSlice(itemKey = "live", title = "Live Stream", durationMs = 0L),
            )
        )
        d.send(Event.GazeMoved(GazeTarget.HudTimeline(0.50f)))
        assertThat(d.state.playback.previewPositionMs).isNull()

        d.input(Fx.confirm)
        assertThat(d.stepEffects.filterIsInstance<Effect.Seek>()).isEmpty()
    }
}
