package com.daydreamvr.player.state

import com.daydreamvr.playback.PlaybackSnapshot
import com.daydreamvr.upnp.model.BrowseResult
import com.daydreamvr.upnp.model.DidlItem
import com.daydreamvr.upnp.model.MediaServer
import com.daydreamvr.vrcore.input.InputAction
import com.daydreamvr.vrcore.render.ProjectionMode

/**
 * Everything that can change [AppState] (ARCHITECTURE.md §12). Gamepad input,
 * network results, player callbacks and the frame clock all arrive as [Event]s
 * so [AppStateMachine.reduce] can stay pure.
 */
sealed interface Event {

    /** A decoded gamepad action. */
    data class Input(val action: InputAction) : Event

    /** The gaze reticle moved onto a new target (or off everything). Edge-triggered. */
    data class GazeMoved(val target: GazeTarget?) : Event

    /** Emitted once by `AppScene` at GL-create from the real measured layouts (fixes F5). */
    data class ListWindowMeasured(val window: ListWindow) : Event

    /** The frame clock. Drives HUD auto-hide and toast expiry — no clock is read in `reduce`. */
    data class Tick(val nowMs: Long) : Event

    data class ServersChanged(val servers: List<MediaServer>) : Event

    data class DiscoveryStateChanged(val state: DiscoveryState) : Event

    /** A page (or accumulated pages) for [objectId] arrived. */
    data class BrowseLoaded(val objectId: String, val result: BrowseResult, val append: Boolean = false) : Event

    data class BrowseFailed(val objectId: String, val message: String) : Event

    data class PlayerStateChanged(val snapshot: PlaybackSnapshot) : Event

    /** Preview position while a trigger scrub is held; null on release. */
    data class ScrubPreview(val previewPositionMs: Long?) : Event

    /** A blocking failure to surface as an [Overlay.Error]. */
    data class Failure(val title: String, val message: String, val canRetry: Boolean = false) : Event

    /** A non-blocking notice. */
    data class Notice(val message: String) : Event

    /** A manual server was added (or failed) from the keyboard overlay. */
    data class ManualServerResult(val ok: Boolean, val message: String) : Event

    /** The controller went away / came back (ARCHITECTURE.md §8.5). */
    data class ControllerConnected(val connected: Boolean) : Event

    /** [EffectRunner] resolved the projection for the item now playing (auto-detect or override). */
    data class ProjectionChanged(val mode: ProjectionMode) : Event

    /** Persisted settings were read at startup and should replace the defaults. */
    data class SettingsLoaded(val settings: Settings) : Event

    /** A partially-watched item was opened — offer resume / start over (ARCHITECTURE.md §10.4). */
    data class ResumePrompt(
        val item: DidlItem,
        val serverUdn: String,
        val positionMs: Long,
        val durationMs: Long,
    ) : Event
}
