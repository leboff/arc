package com.daydreamvr.player.state

import com.daydreamvr.upnp.model.DidlItem
import com.daydreamvr.upnp.model.MediaServer
import com.daydreamvr.upnp.model.PageRequest
import com.daydreamvr.vrcore.render.ProjectionMode

/**
 * Side effects returned by [AppStateMachine.reduce] and executed by
 * [EffectRunner] off the reducer (ARCHITECTURE.md §12). The reducer itself never
 * performs IO.
 */
sealed interface Effect {

    data class StartDiscovery(val force: Boolean = false) : Effect

    data class Browse(val server: MediaServer, val objectId: String, val page: PageRequest) : Effect

    data class AddManualServer(val hostPort: String) : Effect

    data class Play(
        val item: DidlItem,
        val serverUdn: String,
        val startAtMs: Long,
        val projectionOverride: ProjectionMode?,
        /**
         * True once the user has answered a resume prompt (or none was warranted):
         * [EffectRunner] then plays [item] straight from [startAtMs] instead of
         * checking [com.daydreamvr.playback.ResumeStore] again.
         */
        val skipResumeCheck: Boolean = false,
    ) : Effect

    data class Seek(val toMs: Long, val exact: Boolean) : Effect

    data class SeekRelative(val deltaMs: Long) : Effect

    /** null = toggle. */
    data class SetPlayWhenReady(val play: Boolean?) : Effect

    data object StopPlayback : Effect

    data class SetProjection(val mode: ProjectionMode) : Effect

    data class SetPlaybackSpeed(val speed: Float) : Effect

    data class SelectAudioTrack(val id: String?) : Effect

    data class SelectSubtitleTrack(val id: String?) : Effect

    data object Recenter : Effect

    data class ApplySettings(val settings: Settings) : Effect

    data class Persist(val key: String, val value: String) : Effect

    data object QuitToLobby : Effect
}
