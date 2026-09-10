package com.daydreamvr.player.state

import com.daydreamvr.upnp.model.DidlContainer
import com.daydreamvr.upnp.model.DidlItem
import com.daydreamvr.upnp.model.DidlObject
import com.daydreamvr.player.media.MediaNode
import com.daydreamvr.player.media.MediaSource
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
    /** The media sources on offer and which one the browser is showing (§10.1). */
    val sources: SourcesState = SourcesState(),
    /** The scanned local video library (§8). */
    val localMedia: LocalMedia = LocalMedia(),
    /** User projection overrides, keyed by [com.daydreamvr.player.media.MediaKey.storageKey]. */
    val projectionOverrides: Map<String, ProjectionMode> = emptyMap(),
    /** Bumped by the thumbnail coalescer; part of the browse-panel render key. */
    val thumbGeneration: Int = 0,
    /** What the gaze reticle is currently over; drives the hover style (UI_GAZE_PLAN.md §3.3). */
    val gaze: GazeTarget? = null,
    /** Scroll offsets the renderer honours so focused rows are always drawn (F3/F4). */
    val settingsScrollTop: Int = 0,
    val serverScrollTop: Int = 0,
    /** Visible row counts measured by the renderer, so `reduce()` scrolls what is drawn (F5). */
    val listWindow: ListWindow = ListWindow(),
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

/**
 * The whole local video library, loaded in one shot by `LocalMediaRepository`
 * (§8). `byFolder` is keyed by `MediaNode.Folder.id` (the MediaStore bucket id).
 */
data class LocalMedia(
    val folders: List<MediaNode.Folder> = emptyList(),
    val byFolder: Map<String, List<MediaNode.Video>> = emptyMap(),
    val loaded: Boolean = false,
)

/** Visible row counts the renderer measured for each scrolling list (UI_GAZE_PLAN.md §3.3, F5). */
data class ListWindow(val browse: Int = 8, val settings: Int = 8, val servers: Int = 5)

enum class DiscoveryState { IDLE, RUNNING, FAILED }

/** One folder in the browse path. Each frame keeps its own cursor (ARCHITECTURE.md §12). */
data class BrowseFrame(
    /** The UPnP server this frame browses. Null for the local / favourites sources. */
    val server: MediaServer? = null,
    val objectId: String,
    val title: String,
    val containers: List<DidlContainer> = emptyList(),
    val items: List<DidlItem> = emptyList(),
    /**
     * The unified view of [containers] / [items], populated alongside them from
     * the same event (strangler S2, UI_REDESIGN_REVIEWED_PLAN.md §10.7). Consumers
     * migrate to these; the DIDL lists are removed in S6.
     */
    val folders: List<MediaNode.Folder> = emptyList(),
    val videos: List<MediaNode.Video> = emptyList(),
    val focusIndex: Int = 0,
    val scrollTop: Int = 0,
    /** Which media source this frame belongs to; null means "derive `Upnp(server)`". */
    val source: MediaSource? = null,
    /** Grid sort order (§10.4, R18). */
    val sort: SortOrder = SortOrder.TITLE_ASC,
    /** Which region of the 3-column panel the controller is driving (§10.1). */
    val focus: BrowseFocus = BrowseFocus.Grid(0),
    /** Last grid cell the focus sat on — where `UP` from the dock / `RIGHT` from the sidebar returns. */
    val gridReturn: Int = 0,
    /** Independent scroll cursor for the left sidebar. */
    val sidebarScrollTop: Int = 0,
    val totalMatches: Int = 0,
    val loading: Boolean = true,
    val error: String? = null,
    /** Centre column view mode: thumbnail cards vs compact textual list (kanban t_4c330258). */
    val viewMode: BrowseViewMode = BrowseViewMode.GRID,
) {
    val rows: List<DidlObject> get() = containers + items
    val loadedCount: Int get() = maxOf(containers.size + items.size, folders.size + videos.size)
    val focusedRow: DidlObject? get() = rows.getOrNull(focusIndex)
    val hasMorePages: Boolean get() = totalMatches > loadedCount

    /** The source this frame browses — falls back to the UPnP server it was opened from. */
    val mediaSource: MediaSource
        get() = source ?: server?.let { MediaSource.Upnp(it) } ?: MediaSource.Local

    /**
     * Sorted VIEW of [videos]. Recomputed, never stored — storing it would let it
     * drift from [sort] (§10.1).
     */
    val sortedVideos: List<MediaNode.Video>
        get() = when (sort) {
            SortOrder.TITLE_ASC -> videos.sortedBy { it.title.lowercase() }
            SortOrder.DATE_DESC -> videos.sortedByDescending { it.dateModifiedMs ?: 0L }
            SortOrder.DURATION_DESC -> videos.sortedByDescending { it.durationMs ?: 0L }
            SortOrder.SIZE_DESC -> videos.sortedByDescending { it.sizeBytes ?: 0L }
        }

    val gridFocusIndex: Int get() = (focus as? BrowseFocus.Grid)?.index ?: 0

    /** SINGLE DIVISOR — page and cell can never disagree (§10.1). */
    val gridPage: Int get() = gridFocusIndex / GRID_PAGE_SIZE
    val pageCount: Int
        get() = ((sortedVideos.size + GRID_PAGE_SIZE - 1) / GRID_PAGE_SIZE).coerceAtLeast(1)
    val focusedVideo: MediaNode.Video? get() = sortedVideos.getOrNull(gridFocusIndex)

    companion object {
        /** 3 columns × 2 rows per grid page (§5.2). */
        const val GRID_PAGE_SIZE = 6
        const val GRID_COLS = 3
    }
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
        val CONTROLS = listOf("Back", "Projection", "Speed", "Screen size")
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

    /**
     * A selectable list of every [ProjectionMode] plus a leading "Auto" entry
     * (kanban t_af6bc99f). Replaces the old click-to-cycle HUD/inspector control:
     * confirming an entry applies it directly instead of stepping through every
     * mode one at a time. [returnTo] tells the reducer whether the selection came
     * from the player HUD ([ProjectionChooserOrigin.PLAYER] → [Effect.SetProjection])
     * or the browse inspector ([ProjectionChooserOrigin.BROWSE_OVERRIDE] →
     * [Effect.PersistProjectionOverride]).
     */
    data class ProjectionChooser(
        val current: ProjectionMode?,
        val returnTo: ProjectionChooserOrigin,
        /** Set only for [ProjectionChooserOrigin.BROWSE_OVERRIDE]; the video the choice applies to. */
        val targetKey: String? = null,
        val focusIndex: Int = 0,
    ) : Overlay {
        companion object {
            /** Auto (null) first, then every mode in declaration order. */
            val OPTIONS: List<ProjectionMode?> = listOf(null) + ProjectionMode.entries

            fun labelFor(mode: ProjectionMode?): String = mode?.label ?: "Auto (Default)"

            fun initialFocusIndex(current: ProjectionMode?): Int =
                OPTIONS.indexOf(current).coerceAtLeast(0)
        }
    }
}

/** Where a [Overlay.ProjectionChooser] selection should be written back to. */
enum class ProjectionChooserOrigin { PLAYER, BROWSE_OVERRIDE }

enum class KeyboardPurpose { MANUAL_SERVER, SEARCH }

data class Settings(
    val deviceProfileId: String = "daydream_view_2017",
    val predictionEnabled: Boolean = true,
    val neckModelEnabled: Boolean = true,
    val autoRecenterIdleSeconds: Int = 0,
    val ipdMm: Float = 64f,
    val screenDistanceM: Float = 4f,
    val screenWidthDegrees: Float = 60f,
    /** Live optics calibration (ARCHITECTURE.md §6.6, Phase 6) — per viewer profile. */
    val screenToLensMm: Float = 40f,
    val lensK1: Float = 0.36f,
    val lensK2: Float = 0.42f,
    val dividerPx: Int = 8,
    val distortionCorrection: Boolean = true,
    /** 8BitDo-in-Switch-mode and friends: swap A/B (and X/Y). Persisted so it survives a reconnect. */
    val gamepadAbSwapped: Boolean = false,
    val subnetPrefix: String? = null,
) {
    companion object {
        /** Setting rows in the Settings screen, top to bottom. */
        val ROWS = listOf(
            "Viewer profile",
            "IPD",
            "Screen distance",
            "Screen size",
            "Screen-to-lens",
            "Lens k1",
            "Lens k2",
            "Divider width",
            "Distortion correction",
            "Gamepad buttons",
            "Motion prediction",
            "Neck model",
            "Auto-recenter",
            "Forget servers",
        )

        /** Rows that put the live calibration grid up behind the panel. */
        val CALIBRATION_ROWS = setOf(
            "IPD", "Screen-to-lens", "Lens k1", "Lens k2", "Divider width", "Distortion correction",
        )
    }
}
