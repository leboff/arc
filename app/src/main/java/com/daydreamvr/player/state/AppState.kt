package com.daydreamvr.player.state

import com.daydreamvr.upnp.model.DidlContainer
import com.daydreamvr.upnp.model.DidlItem
import com.daydreamvr.upnp.model.DidlObject
import com.daydreamvr.player.screens.VrKeyboard
import com.daydreamvr.upnp.model.MediaServer
import com.daydreamvr.vrcore.render.ProjectionMode

/**
 * The whole navigable state of the app (ARCHITECTURE.md §12). Immutable; the GL
 * thread reads `stateFlow.value` once per frame and never mutates it.
 * [AppStateMachine.reduce] is the only producer and is a pure function.
 */
data class AppState(
    val screen: VrScreen = VrScreen.SERVER_LIST,
    val servers: List<MediaServer> = emptyList(),
    val discovery: DiscoveryState = DiscoveryState.IDLE,
    val serverFocusIndex: Int = 0,
    val browse: BrowseState = BrowseState(),
    val playback: PlaybackSlice = PlaybackSlice(),
    val hud: HudState = HudState(),
    val overlay: Overlay? = null,
    val settings: Settings = Settings(),
    /** Carries an item + its resume position between a resume prompt and its answer. */
    val pendingResume: PendingResume? = null,
    /** Last known wall-clock ms, updated by [Event.Tick]; time never read directly. */
    val nowMs: Long = 0L,
) {
    /** Row count for the server list including the trailing "add manually" affordance. */
    val serverRowCount: Int get() = servers.size + EXTRA_SERVER_ROWS

    companion object {
        /** "Add server manually" + "Retry discovery". */
        const val EXTRA_SERVER_ROWS = 2

        val INITIAL = AppState()
    }
}

enum class VrScreen { SERVER_LIST, BROWSE, PLAYER, SETTINGS }

enum class DiscoveryState { IDLE, RUNNING, FAILED }

/** One folder in the browse path. Each frame keeps its own cursor (ARCHITECTURE.md §12). */
data class BrowseFrame(
    val server: MediaServer,
    val objectId: String,
    val title: String,
    val containers: List<DidlContainer> = emptyList(),
    val items: List<DidlItem> = emptyList(),
    val focusIndex: Int = 0,
    val scrollTop: Int = 0,
    val totalMatches: Int = 0,
    val loading: Boolean = true,
    val error: String? = null,
) {
    val rows: List<DidlObject> get() = containers + items
    val loadedCount: Int get() = containers.size + items.size
    val focusedRow: DidlObject? get() = rows.getOrNull(focusIndex)
    val hasMorePages: Boolean get() = totalMatches > loadedCount
}

data class BrowseState(val stack: List<BrowseFrame> = emptyList()) {
    val depth: Int get() = stack.size
    val top: BrowseFrame? get() = stack.lastOrNull()

    fun replaceTop(frame: BrowseFrame): BrowseState =
        if (stack.isEmpty()) this else copy(stack = stack.dropLast(1) + frame)

    fun push(frame: BrowseFrame): BrowseState = copy(stack = stack + frame)
    fun pop(): BrowseState = if (stack.isEmpty()) this else copy(stack = stack.dropLast(1))
}

/** The player-relevant slice, filled from `PlaybackSnapshot` by the effect runner. */
data class PlaybackSlice(
    val itemKey: String? = null,
    val title: String = "",
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1f,
    /** Non-null while a trigger scrub is held — the HUD shows this instead of position. */
    val previewPositionMs: Long? = null,
    val projection: ProjectionMode = ProjectionMode.FLAT,
    val failure: String? = null,
)

data class HudState(
    val visible: Boolean = false,
    val pinned: Boolean = false,
    val focusIndex: Int = 0,
    val lastInputAtMs: Long = 0L,
) {
    companion object {
        const val AUTO_HIDE_MS = 4_000L

        /** The focusable HUD controls, left to right (ARCHITECTURE.md §11.4). */
        val CONTROLS = listOf("Audio", "Subtitles", "Speed", "Projection", "Screen size")
    }
}

sealed interface Overlay {
    data class Error(val title: String, val message: String, val canRetry: Boolean) : Overlay
    data class Toast(val message: String, val expiresAtMs: Long) : Overlay
    data class Keyboard(val purpose: KeyboardPurpose, val kb: VrKeyboard.KeyboardState) : Overlay
    data class Confirm(
        val title: String,
        val options: List<String>,
        val focusIndex: Int,
        val tag: String,
    ) : Overlay
}

enum class KeyboardPurpose { MANUAL_SERVER, SEARCH }

data class Settings(
    val deviceProfileId: String = "cardboard_v2",
    val predictionEnabled: Boolean = true,
    val neckModelEnabled: Boolean = true,
    val autoRecenterIdleSeconds: Int = 0,
    val ipdMm: Float = 63f,
    val screenDistanceM: Float = 4f,
    val screenWidthDegrees: Float = 60f,
    val subnetPrefix: String? = null,
) {
    companion object {
        /** Setting rows in the Settings screen, top to bottom. */
        val ROWS = listOf(
            "Viewer profile",
            "IPD",
            "Screen distance",
            "Screen size",
            "Motion prediction",
            "Neck model",
            "Auto-recenter",
            "Forget servers",
        )
    }
}
