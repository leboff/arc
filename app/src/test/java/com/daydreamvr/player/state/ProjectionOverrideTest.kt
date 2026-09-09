package com.daydreamvr.player.state

import com.daydreamvr.player.media.MediaKey
import com.daydreamvr.vrcore.input.InputAction
import com.daydreamvr.vrcore.render.ProjectionMode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The projection chooser (kanban t_af6bc99f): a selectable list replacing the
 * old click-to-cycle behavior. Opening shows every [ProjectionMode] plus a
 * leading "Auto" entry, focused on the current selection; Nav moves the list
 * focus; Confirm applies the highlighted entry and persists it as an override
 * keyed by [MediaKey.storageKey].
 */
class ProjectionOverrideTest {

    @Test
    fun openingFocusesTheCurrentModeAndConfirmingPersistsTheOverride() {
        val video = Fx.videoNode("100", projection = ProjectionMode.FLAT)
        val d = Driver(Fx.browsing(Fx.localFrame(videos = listOf(video), focus = BrowseFocus.Grid(0))))
        val key = MediaKey("local", "100")

        d.send(Event.Ui(UiIntent.OpenProjectionChooser))
        val opened = d.state.overlay
        assertThat(opened).isInstanceOf(Overlay.ProjectionChooser::class.java)
        opened as Overlay.ProjectionChooser
        assertThat(opened.current).isEqualTo(ProjectionMode.FLAT)
        assertThat(opened.returnTo).isEqualTo(ProjectionChooserOrigin.BROWSE_OVERRIDE)
        assertThat(opened.targetKey).isEqualTo(key.storageKey())
        // Focus starts on the currently-detected mode, not row 0.
        assertThat(Overlay.ProjectionChooser.OPTIONS[opened.focusIndex]).isEqualTo(ProjectionMode.FLAT)

        // Move down one row and confirm.
        d.input(Fx.down)
        val focusedMode = Overlay.ProjectionChooser.OPTIONS[d.state.overlay.let { (it as Overlay.ProjectionChooser).focusIndex }]
        d.input(Fx.confirm)

        assertThat(d.state.overlay).isNull()
        val fx = d.stepEffects.filterIsInstance<Effect.PersistProjectionOverride>().single()
        assertThat(fx.key).isEqualTo(key)
        assertThat(fx.mode).isEqualTo(focusedMode)
        assertThat(d.state.projectionOverrides[key.storageKey()]).isEqualTo(focusedMode)
    }

    @Test
    fun selectingAutoClearsTheOverride() {
        val video = Fx.videoNode("100", projection = ProjectionMode.FLAT)
        val d = Driver(
            Fx.browsing(Fx.localFrame(videos = listOf(video), focus = BrowseFocus.Grid(0)))
                .copy(projectionOverrides = mapOf(MediaKey("local", "100").storageKey() to ProjectionMode.EQUIRECT_360)),
        )
        val key = MediaKey("local", "100")

        d.send(Event.Ui(UiIntent.OpenProjectionChooser))
        // Focus starts on the current override (EQUIRECT_360), not Auto.
        val opened = d.state.overlay as Overlay.ProjectionChooser
        assertThat(Overlay.ProjectionChooser.OPTIONS[opened.focusIndex]).isEqualTo(ProjectionMode.EQUIRECT_360)

        // Navigate all the way up to row 0 ("Auto").
        repeat(Overlay.ProjectionChooser.OPTIONS.size) { d.input(Fx.up) }
        d.input(Fx.confirm)

        val fx = d.stepEffects.filterIsInstance<Effect.PersistProjectionOverride>().single()
        assertThat(fx.mode).isNull()
        assertThat(d.state.projectionOverrides).doesNotContainKey(key.storageKey())
    }

    @Test
    fun cancelDismissesWithoutPersistingAnything() {
        val video = Fx.videoNode("100", projection = ProjectionMode.FLAT)
        val d = Driver(Fx.browsing(Fx.localFrame(videos = listOf(video), focus = BrowseFocus.Grid(0))))

        d.send(Event.Ui(UiIntent.OpenProjectionChooser))
        assertThat(d.state.overlay).isInstanceOf(Overlay.ProjectionChooser::class.java)

        d.input(Fx.cancel)
        assertThat(d.state.overlay).isNull()
        assertThat(d.stepEffects.filterIsInstance<Effect.PersistProjectionOverride>()).isEmpty()
    }

    @Test
    fun playerHudOpensChooserAgainstThePlaybackSliceAndAppliesViaSetProjection() {
        val d = Driver(
            AppState(
                screen = VrScreen.PLAYER,
                playback = PlaybackSlice(itemKey = "v1", title = "Video v1", projection = ProjectionMode.EQUIRECT_180),
                hud = HudState(visible = true, focusIndex = HudState.CONTROLS.indexOf("Projection")),
            ),
        )

        d.input(Fx.confirm)
        val opened = d.state.overlay as Overlay.ProjectionChooser
        assertThat(opened.returnTo).isEqualTo(ProjectionChooserOrigin.PLAYER)
        assertThat(opened.current).isEqualTo(ProjectionMode.EQUIRECT_180)
        assertThat(Overlay.ProjectionChooser.OPTIONS[opened.focusIndex]).isEqualTo(ProjectionMode.EQUIRECT_180)

        d.input(Fx.down)
        val focusedMode = Overlay.ProjectionChooser.OPTIONS[(d.state.overlay as Overlay.ProjectionChooser).focusIndex]
        d.input(Fx.confirm)

        assertThat(d.state.overlay).isNull()
        assertThat(d.state.playback.projection).isEqualTo(focusedMode)
        val fx = d.stepEffects.filterIsInstance<Effect.SetProjection>().single()
        assertThat(fx.mode).isEqualTo(focusedMode)
    }
}
