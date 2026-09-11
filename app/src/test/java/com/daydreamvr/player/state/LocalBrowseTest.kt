package com.daydreamvr.player.state

import com.daydreamvr.player.media.MediaSource
import com.daydreamvr.player.media.local.MediaPermission
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The local-storage source path through the reducer
 * (UI_REDESIGN_REVIEWED_PLAN.md §10.4, M4 acceptance 6).
 */
class LocalBrowseTest {

    private fun driver(grant: MediaPermission.Grant) =
        Driver(AppState(sources = SourcesState(localPermission = grant)))

    @Test
    fun deniedPermissionEntersATerminalStateWithNoEffect() {
        val d = driver(MediaPermission.Grant.DENIED)
        d.send(Event.Ui(UiIntent.SelectSource(MediaSource.Local.id)))

        assertThat(d.state.screen).isEqualTo(VrScreen.BROWSE)
        assertThat(d.state.sources.selectedId).isEqualTo("local")
        assertThat(d.state.browse.top!!.error).isNotNull()
        assertThat(d.stepEffects).isEmpty()
    }

    @Test
    fun fullPermissionLoadsTheLibraryThenFillsTheGridWithNoFurtherEffect() {
        val d = driver(MediaPermission.Grant.FULL)

        d.send(Event.Ui(UiIntent.SelectSource(MediaSource.Local.id)))
        assertThat(d.stepEffects).containsExactly(Effect.LoadLocalMedia)

        val camera = Fx.folderNode("42", "Camera", childCount = 2)
        val clips = listOf(Fx.videoNode("100"), Fx.videoNode("101"))
        d.send(Event.LocalMediaLoaded(listOf(camera), mapOf("42" to clips)))
        assertThat(d.state.browse.top!!.folders).containsExactly(camera)
        assertThat(d.state.localMedia.loaded).isTrue()

        d.send(Event.Ui(UiIntent.SelectFolder("42")))
        assertThat(d.state.browse.depth).isEqualTo(2)
        assertThat(d.state.browse.top!!.videos).hasSize(2)
        assertThat(d.state.browse.top!!.focus).isEqualTo(BrowseFocus.Grid(0))
        assertThat(d.stepEffects).isEmpty()
    }

    @Test
    fun aLoadFailureSurfacesOnTheFrame() {
        val d = driver(MediaPermission.Grant.FULL)
        d.send(Event.Ui(UiIntent.SelectSource(MediaSource.Local.id)))
        d.send(Event.LocalMediaFailed("disk on fire"))
        assertThat(d.state.browse.top!!.error).isEqualTo("disk on fire")
    }

    @Test
    fun permissionChangeFromDeniedToGrantedTriggersLoadLocalMediaIfViewingLocalSource() {
        val d = driver(MediaPermission.Grant.DENIED)
        d.send(Event.Ui(UiIntent.SelectSource(MediaSource.Local.id)))
        assertThat(d.state.browse.top!!.error).isNotNull()

        d.send(Event.LocalPermissionChanged(MediaPermission.Grant.FULL))

        assertThat(d.state.browse.top!!.loading).isTrue()
        assertThat(d.state.browse.top!!.error).isNull()
        assertThat(d.stepEffects).containsExactly(Effect.LoadLocalMedia)
    }

    @Test
    fun localMediaChangedTriggersLoadLocalMediaIfViewingLocalSource() {
        val d = driver(MediaPermission.Grant.FULL)
        d.send(Event.Ui(UiIntent.SelectSource(MediaSource.Local.id)))

        d.send(Event.LocalMediaChanged)

        assertThat(d.stepEffects).containsExactly(Effect.LoadLocalMedia)
    }
}
