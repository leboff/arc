package com.daydreamvr.player.state

import com.daydreamvr.player.media.MediaKey
import com.daydreamvr.player.media.MediaSource
import com.daydreamvr.player.media.local.MediaPermission
import com.daydreamvr.player.screens.VrKeyboard
import com.daydreamvr.upnp.model.DidlContainer
import com.daydreamvr.upnp.model.DidlItem
import com.daydreamvr.upnp.model.PageRequest
import com.daydreamvr.vrcore.input.InputAction
import com.daydreamvr.vrcore.render.ProjectionMode
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Extra state that has to survive between a resume prompt and its answer. */
data class PendingResume(
    val item: DidlItem,
    val serverUdn: String,
    val positionMs: Long,
    val durationMs: Long,
)

/**
 * Single-writer holder of [AppState] (ARCHITECTURE.md §4 R2). [dispatch] is called
 * from `Dispatchers.Main.immediate`; the pure [reduce] does all the work and
 * returns the effects for [EffectRunner] to execute off the reducer.
 */
class AppStateMachine(initial: AppState = AppState.INITIAL) {

    private val _state = MutableStateFlow(initial)
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<Effect>(extraBufferCapacity = 128)
    val effects: SharedFlow<Effect> = _effects.asSharedFlow()

    fun dispatch(event: Event) {
        val (next, fx) = reduce(_state.value, event)
        _state.value = next
        fx.forEach { _effects.tryEmit(it) }
    }

    companion object {

        const val TOAST_MS = 2_500L
        const val BROWSE_VISIBLE_ROWS = 8
        const val SIDEBAR_VISIBLE_ROWS = 6
        const val PAGE_FETCH = 200
        const val SEEK_STEP_MS = 10_000L

        /**
         * PURE. No Android, no IO, no clock reads — time arrives via
         * [Event.Tick]. Returns the next state plus effects to run.
         */
        fun reduce(state: AppState, event: Event): Pair<AppState, List<Effect>> = when (event) {
            is Event.Tick -> reduceTick(state, event.nowMs)
            is Event.ServersChanged -> reduceServersChanged(state, event)
            is Event.DiscoveryStateChanged -> state.copy(discovery = event.state) to noFx()
            is Event.BrowseLoaded -> reduceBrowseLoaded(state, event)
            is Event.BrowseFailed -> reduceBrowseFailed(state, event)
            is Event.PlayerStateChanged -> reducePlayerSnapshot(state, event)
            is Event.ScrubPreview ->
                state.copy(playback = state.playback.copy(previewPositionMs = event.previewPositionMs)) to noFx()
            is Event.Failure ->
                state.copy(overlay = Overlay.Error(event.title, event.message, event.canRetry)) to noFx()
            is Event.Notice ->
                state.copy(overlay = Overlay.Toast(event.message, state.nowMs + TOAST_MS)) to noFx()
            is Event.ManualServerResult -> reduceManualServerResult(state, event)
            is Event.ControllerConnected -> reduceController(state, event.connected)
            is Event.ProjectionChanged ->
                state.copy(playback = state.playback.copy(projection = event.mode)) to noFx()
            is Event.SettingsLoaded ->
                state.copy(settings = event.settings) to noFx()
            is Event.ResumePrompt -> reduceResumePrompt(state, event)
            is Event.Input -> reduceInput(state, event.action)
            is Event.GazeMoved -> reduceGaze(state, event.target)
            is Event.ListWindowMeasured -> state.copy(listWindow = event.window) to noFx()
            is Event.Ui -> reduceUi(state, event.intent)
            is Event.LocalMediaLoaded -> reduceLocalMediaLoaded(state, event)
            is Event.LocalMediaFailed -> reduceLocalMediaFailed(state, event)
            is Event.LocalPermissionChanged ->
                state.copy(sources = state.sources.copy(localPermission = event.grant)) to noFx()
            is Event.LocalMediaChanged ->
                if (state.sources.selectedId == MediaSource.Local.id) state to listOf(Effect.LoadLocalMedia)
                else state to noFx()
            is Event.ThumbnailsArrived ->
                state.copy(thumbGeneration = state.thumbGeneration + 1) to noFx()
        }

        /**
         * Gaze moves focus; the controller still selects (UI_GAZE_PLAN.md §3.3).
         * Always stores [target] as the hover highlight, and — when it names the
         * active surface — moves that surface's focus index to it. Never touches a
         * scrollTop: gaze can only reach rows that are already drawn.
         */
        private fun reduceGaze(state: AppState, target: GazeTarget?): Pair<AppState, List<Effect>> {
            val s = state.copy(gaze = target)
            if (target == null) return s to noFx()

            val overlay = state.overlay
            if (overlay != null) {
                return when {
                    target is GazeTarget.DialogButton && overlay is Overlay.Confirm ->
                        s.copy(overlay = overlay.copy(focusIndex = target.index.coerceIn(0, overlay.options.size - 1)))
                    target is GazeTarget.KeyboardKey && overlay is Overlay.Keyboard ->
                        s.copy(overlay = overlay.copy(kb = overlay.kb.copy(cursorRow = target.row, cursorCol = target.col)))
                    else -> s // targets for the screen behind the overlay are ignored
                } to noFx()
            }

            return when (target) {
                is GazeTarget.ServerRow ->
                    if (state.screen == VrScreen.SERVER_LIST) {
                        s.copy(serverFocusIndex = target.index.coerceIn(0, maxOf(0, state.serverRowCount - 1)))
                    } else {
                        s
                    }
                is GazeTarget.BrowseRow -> {
                    val f = state.browse.top
                    if (state.screen == VrScreen.BROWSE && f != null) {
                        s.copy(
                            browse = state.browse.replaceTop(
                                f.copy(focusIndex = target.index.coerceIn(0, maxOf(0, f.rows.size - 1))),
                            ),
                        )
                    } else {
                        s
                    }
                }
                is GazeTarget.SettingsRow ->
                    if (state.screen == VrScreen.SETTINGS) {
                        s.copy(hud = state.hud.copy(focusIndex = target.index.coerceIn(0, Settings.ROWS.size - 1)))
                    } else {
                        s
                    }
                is GazeTarget.HudControl ->
                    if (state.screen == VrScreen.PLAYER) {
                        s.copy(
                            hud = state.hud.copy(
                                focusIndex = target.index.coerceIn(0, HudState.CONTROLS.size - 1),
                                lastInputAtMs = state.nowMs,
                            ),
                        )
                    } else {
                        s
                    }
                is GazeTarget.SourceTab -> browseGazeFocus(state, s, BrowseFocus.Source(target.index))
                is GazeTarget.SidebarRow -> browseGazeFocus(state, s, BrowseFocus.Sidebar(target.index))
                is GazeTarget.GridCell -> browseGazeFocus(state, s, BrowseFocus.Grid(target.index))
                is GazeTarget.InspectorAction -> browseGazeFocus(state, s, BrowseFocus.Inspector(target.action))
                is GazeTarget.DockButton -> browseGazeFocus(state, s, BrowseFocus.Dock(target.button))
                is GazeTarget.ToolbarChip -> browseGazeFocus(state, s, BrowseFocus.Toolbar(target.chip))
                is GazeTarget.BreadcrumbSegment, is GazeTarget.PageButton -> s
                is GazeTarget.DialogButton, is GazeTarget.KeyboardKey -> s
            } to noFx()
        }

        /** Gaze hover moves [BrowseFocus] only while the browse panel is the active surface (§10.4). */
        private fun browseGazeFocus(state: AppState, s: AppState, focus: BrowseFocus): AppState {
            val frame = state.browse.top ?: return s
            if (state.screen != VrScreen.BROWSE) return s
            return s.copy(browse = state.browse.replaceTop(applyFocus(frame, focus)))
        }

        // ---- non-input events ------------------------------------------------

        private fun reduceTick(state: AppState, nowMs: Long): Pair<AppState, List<Effect>> {
            var s = state.copy(nowMs = nowMs)
            val overlay = s.overlay
            if (overlay is Overlay.Toast && nowMs >= overlay.expiresAtMs) {
                s = s.copy(overlay = null)
            }
            if (s.screen == VrScreen.PLAYER && s.hud.visible && !s.hud.pinned &&
                nowMs - s.hud.lastInputAtMs >= HudState.AUTO_HIDE_MS
            ) {
                s = s.copy(hud = s.hud.copy(visible = false))
            }
            return s to noFx()
        }

        private fun reduceServersChanged(state: AppState, e: Event.ServersChanged): Pair<AppState, List<Effect>> {
            val maxFocus = maxOf(0, e.servers.size + AppState.EXTRA_SERVER_ROWS - 1)
            return state.copy(
                servers = e.servers,
                serverFocusIndex = state.serverFocusIndex.coerceIn(0, maxFocus),
            ) to noFx()
        }

        private fun reduceBrowseLoaded(state: AppState, e: Event.BrowseLoaded): Pair<AppState, List<Effect>> {
            val idx = state.browse.stack.indexOfLast { it.objectId == e.objectId }
            if (idx < 0) return state to noFx()
            val frame = state.browse.stack[idx]
            val containers = if (e.append) frame.containers + e.result.containers else e.result.containers
            val items = if (e.append) frame.items + e.result.items else e.result.items
            val loaded = containers.size + items.size
            val focus = if (e.append) frame.focusIndex else frame.focusIndex.coerceIn(0, maxOf(0, loaded - 1))
            val folders = containers.map { com.daydreamvr.player.media.UpnpAdapter.folder(it) }
            val videos = items.map { com.daydreamvr.player.media.UpnpAdapter.video(it) }
            val browseFocus = if (e.append) frame.focus else clampFocus(frame.focus, folders.size, videos.size)
            val updated = frame.copy(
                containers = containers,
                items = items,
                folders = folders,
                videos = videos,
                totalMatches = maxOf(e.result.totalMatches, loaded),
                focusIndex = focus,
                focus = browseFocus,
                scrollTop = clampScroll(focus, frame.scrollTop, loaded),
                loading = false,
                error = null,
            )
            val stack = state.browse.stack.toMutableList().also { it[idx] = updated }
            return state.copy(browse = state.browse.copy(stack = stack)) to noFx()
        }

        private fun reduceBrowseFailed(state: AppState, e: Event.BrowseFailed): Pair<AppState, List<Effect>> {
            val idx = state.browse.stack.indexOfLast { it.objectId == e.objectId }
            val stack = state.browse.stack.toMutableList()
            if (idx >= 0) stack[idx] = stack[idx].copy(loading = false, error = e.message)
            return state.copy(
                browse = state.browse.copy(stack = stack),
                overlay = Overlay.Error("Couldn't open folder", e.message, canRetry = true),
            ) to noFx()
        }

        private fun reducePlayerSnapshot(state: AppState, e: Event.PlayerStateChanged): Pair<AppState, List<Effect>> {
            val snap = e.snapshot
            val slice = state.playback.copy(
                itemKey = snap.itemKey ?: state.playback.itemKey,
                title = snap.title.ifBlank { state.playback.title },
                isPlaying = snap.isPlaying,
                isBuffering = snap.isBuffering,
                positionMs = snap.positionMs,
                bufferedMs = snap.bufferedMs,
                durationMs = snap.durationMs,
                speed = snap.speed,
                failure = snap.failure?.userMessage,
            )
            var s = state.copy(playback = slice)
            if (snap.failure != null) {
                s = s.copy(overlay = Overlay.Error("Playback problem", snap.failure!!.userMessage, canRetry = true))
            } else if (snap.itemKey != null && state.screen != VrScreen.PLAYER) {
                s = s.copy(
                    screen = VrScreen.PLAYER,
                    hud = HudState(visible = false, lastInputAtMs = state.nowMs),
                )
            }
            return s to noFx()
        }

        private fun reduceManualServerResult(state: AppState, e: Event.ManualServerResult): Pair<AppState, List<Effect>> =
            if (e.ok) {
                state.copy(overlay = Overlay.Toast(e.message, state.nowMs + TOAST_MS)) to noFx()
            } else {
                state.copy(overlay = Overlay.Error("Couldn't add server", e.message, canRetry = false)) to noFx()
            }

        private fun reduceResumePrompt(state: AppState, e: Event.ResumePrompt): Pair<AppState, List<Effect>> {
            val mmss = com.daydreamvr.vrcore.ui.widgets.Timeline.formatMs(e.positionMs)
            return state.copy(
                pendingResume = PendingResume(e.item, e.serverUdn, e.positionMs, e.durationMs),
                overlay = Overlay.Confirm(
                    "Resume \"${e.item.title}\"?",
                    listOf("Resume from $mmss", "Start over"),
                    0,
                    tag = "resume",
                ),
            ) to noFx()
        }

        private fun reduceController(state: AppState, connected: Boolean): Pair<AppState, List<Effect>> =
            if (connected) {
                if (state.overlay is Overlay.Error &&
                    (state.overlay as Overlay.Error).title == CONTROLLER_LOST_TITLE
                ) {
                    state.copy(overlay = null) to noFx()
                } else {
                    state to noFx()
                }
            } else {
                val fx = if (state.screen == VrScreen.PLAYER) listOf(Effect.SetPlayWhenReady(false)) else noFx()
                state.copy(
                    overlay = Overlay.Error(
                        CONTROLLER_LOST_TITLE,
                        "Reconnect it, or remove the headset to continue.",
                        canRetry = false,
                    ),
                ) to fx
            }

        // ---- input ----------------------------------------------------------

        private fun reduceInput(state: AppState, action: InputAction): Pair<AppState, List<Effect>> {
            if (action is InputAction.Cancel && action.long) {
                return if (state.screen == VrScreen.PLAYER) leavePlayer(state) else hardEscape(state)
            }

            val base = when {
                state.overlay != null -> reduceOverlay(state, state.overlay!!, action)
                else -> when (state.screen) {
                    VrScreen.SERVER_LIST -> reduceServerList(state, action)
                    VrScreen.BROWSE -> reduceBrowse(state, action)
                    VrScreen.PLAYER -> reducePlayerInput(state, action)
                    VrScreen.SETTINGS -> reduceSettings(state, action)
                }
            }

            if (action is InputAction.Cancel && !action.long && base.first == state) {
                return softEscape(state)
            }
            return base
        }

        /** ARCHITECTURE.md §15: long B from anywhere returns to the server list. */
        private fun hardEscape(state: AppState): Pair<AppState, List<Effect>> {
            val fx = if (state.playback.itemKey != null) listOf<Effect>(Effect.StopPlayback) else noFx()
            return AppState(
                servers = state.servers,
                discovery = state.discovery,
                settings = state.settings,
                nowMs = state.nowMs,
            ) to fx
        }

        /** Contextual long-B in the player: back to the browser it came from. */
        private fun leavePlayer(state: AppState): Pair<AppState, List<Effect>> {
            val target = if (state.browse.depth > 0) VrScreen.BROWSE else VrScreen.SERVER_LIST
            return state.copy(
                screen = target,
                overlay = null,
                hud = HudState(),
                playback = PlaybackSlice(),
            ) to listOf(Effect.StopPlayback)
        }

        /** Guarantees short-B changes state on every screen / overlay. */
        private fun softEscape(state: AppState): Pair<AppState, List<Effect>> {
            if (state.overlay != null) return state.copy(overlay = null) to noFx()
            return when (state.screen) {
                VrScreen.BROWSE ->
                    if (state.browse.depth > 1) state.copy(browse = state.browse.pop()) to noFx()
                    else backToServerList(state)
                VrScreen.SETTINGS -> state.copy(screen = VrScreen.SERVER_LIST) to noFx()
                VrScreen.PLAYER -> leavePlayer(state)
                VrScreen.SERVER_LIST ->
                    state.copy(
                        overlay = Overlay.Confirm("Leave the app?", listOf("Stay", "Exit"), 0, tag = "quit"),
                    ) to noFx()
            }
        }

        private fun backToServerList(state: AppState): Pair<AppState, List<Effect>> =
            state.copy(screen = VrScreen.SERVER_LIST, browse = BrowseState()) to noFx()

        private fun reduceServerList(state: AppState, action: InputAction): Pair<AppState, List<Effect>> = when (action) {
            is InputAction.Nav -> {
                val fi = moveFocus(state.serverFocusIndex, action.dir, state.serverRowCount)
                val scrollAnchor = fi.coerceAtMost(maxOf(0, state.servers.size - 1))
                state.copy(
                    serverFocusIndex = fi,
                    serverScrollTop = clampScroll(
                        scrollAnchor, state.serverScrollTop, state.servers.size, state.listWindow.servers,
                    ),
                ) to noFx()
            }

            is InputAction.Confirm -> {
                val i = state.serverFocusIndex
                when {
                    i < state.servers.size -> {
                        val server = state.servers[i]
                        state.copy(
                            screen = VrScreen.BROWSE,
                            sources = state.sources.copy(selectedId = "upnp:${server.udn}"),
                            browse = BrowseState(
                                listOf(
                                    BrowseFrame(
                                        server = server,
                                        objectId = "0",
                                        title = server.friendlyName,
                                        source = MediaSource.Upnp(server),
                                        focus = BrowseFocus.Sidebar(0),
                                    ),
                                ),
                            ),
                        ) to listOf(Effect.Browse(server, "0", PageRequest.DEFAULT))
                    }
                    i == state.servers.size ->
                        state.copy(
                            overlay = Overlay.Keyboard(
                                KeyboardPurpose.MANUAL_SERVER,
                                VrKeyboard.KeyboardState(text = state.settings.subnetPrefix ?: ""),
                            ),
                        ) to noFx()
                    else ->
                        state.copy(discovery = DiscoveryState.RUNNING) to listOf(Effect.StartDiscovery(force = true))
                }
            }

            InputAction.Menu, InputAction.ToggleHud -> state.copy(screen = VrScreen.SETTINGS) to noFx()
            else -> state to noFx()
        }

        // ---- 3-column PLAY'A browse navigation (UI_REDESIGN_REVIEWED_PLAN.md §10.4, §10.5) ----

        /** Source switcher rows: Device / Network / Favourites. */
        const val SOURCE_TAB_COUNT = 3
        private val GRID_COLS get() = BrowseFrame.GRID_COLS
        private val GRID_PAGE_SIZE get() = BrowseFrame.GRID_PAGE_SIZE
        private val GRID_ROWS_PER_PAGE get() = GRID_PAGE_SIZE / GRID_COLS

        private fun reduceBrowse(state: AppState, action: InputAction): Pair<AppState, List<Effect>> {
            val frame = state.browse.top ?: return backToServerList(state)
            return when (action) {
                is InputAction.Nav -> {
                    val next = nextFocus(frame, action.dir) ?: return state to noFx()
                    state.copy(browse = state.browse.replaceTop(applyFocus(frame, next))) to noFx()
                }
                is InputAction.Confirm -> intentForFocus(frame)?.let { reduceUi(state, it) } ?: (state to noFx())
                InputAction.PageDown -> reduceUi(state, UiIntent.PageNext)
                InputAction.PageUp -> reduceUi(state, UiIntent.PagePrev)
                is InputAction.Seek ->
                    reduceUi(state, if (action.deltaSeconds > 0) UiIntent.PageNext else UiIntent.PagePrev)
                InputAction.Menu, InputAction.ToggleHud -> state.copy(screen = VrScreen.SETTINGS) to noFx()
                else -> state to noFx()
            }
        }

        /** Where a directional press moves [BrowseFrame.focus]; null = "no move". */
        private fun nextFocus(frame: BrowseFrame, dir: InputAction.Dir): BrowseFocus? {
            val folders = frame.folders.size
            val videos = frame.sortedVideos.size
            val gridReturn = frame.gridReturn.coerceIn(0, maxOf(0, videos - 1))
            return when (val f = frame.focus) {
                is BrowseFocus.Source -> when (dir) {
                    InputAction.Dir.UP -> BrowseFocus.Source((f.index - 1).coerceAtLeast(0))
                    InputAction.Dir.DOWN ->
                        if (f.index + 1 < SOURCE_TAB_COUNT) BrowseFocus.Source(f.index + 1)
                        else if (folders > 0) BrowseFocus.Sidebar(0) else BrowseFocus.Grid(gridReturn)
                    InputAction.Dir.RIGHT -> BrowseFocus.Grid(gridReturn)
                    InputAction.Dir.LEFT -> null
                }
                is BrowseFocus.Sidebar -> when (dir) {
                    InputAction.Dir.UP ->
                        if (f.index == 0) BrowseFocus.Source(SOURCE_TAB_COUNT - 1)
                        else BrowseFocus.Sidebar(f.index - 1)
                    InputAction.Dir.DOWN -> BrowseFocus.Sidebar((f.index + 1).coerceAtMost(maxOf(0, folders - 1)))
                    InputAction.Dir.RIGHT -> BrowseFocus.Grid(gridReturn)
                    InputAction.Dir.LEFT -> null
                }
                is BrowseFocus.Grid -> gridFocusMove(frame, f.index, dir)
                is BrowseFocus.Inspector -> when (dir) {
                    InputAction.Dir.UP -> BrowseFocus.Inspector(cycleAction(f.action, -1))
                    InputAction.Dir.DOWN -> BrowseFocus.Inspector(cycleAction(f.action, +1))
                    InputAction.Dir.LEFT -> BrowseFocus.Grid(gridReturn)
                    InputAction.Dir.RIGHT -> null
                }
                is BrowseFocus.Dock -> when (dir) {
                    InputAction.Dir.LEFT -> BrowseFocus.Dock(cycleDock(f.button, -1))
                    InputAction.Dir.RIGHT -> BrowseFocus.Dock(cycleDock(f.button, +1))
                    InputAction.Dir.UP -> BrowseFocus.Grid(gridReturn)
                    InputAction.Dir.DOWN -> null
                }
                is BrowseFocus.Toolbar -> when (dir) {
                    InputAction.Dir.DOWN -> BrowseFocus.Grid(gridReturn)
                    InputAction.Dir.LEFT ->
                        if (f.chip.ordinal > 0) BrowseFocus.Toolbar(GazeTarget.Chip.entries[f.chip.ordinal - 1]) else null
                    InputAction.Dir.RIGHT ->
                        if (f.chip.ordinal < GazeTarget.Chip.entries.lastIndex)
                            BrowseFocus.Toolbar(GazeTarget.Chip.entries[f.chip.ordinal + 1]) else null
                    InputAction.Dir.UP -> null
                }
            }
        }

        private fun gridFocusMove(frame: BrowseFrame, index: Int, dir: InputAction.Dir): BrowseFocus? {
            val count = frame.sortedVideos.size
            if (count == 0) return when (dir) {
                InputAction.Dir.LEFT -> BrowseFocus.Sidebar(0).takeIf { frame.folders.isNotEmpty() }
                InputAction.Dir.RIGHT -> BrowseFocus.Inspector(GazeTarget.Action.PLAY)
                else -> null
            }
            val col = index % GRID_COLS
            val pageRow = (index % GRID_PAGE_SIZE) / GRID_COLS
            return when (dir) {
                InputAction.Dir.LEFT ->
                    if (col == 0) BrowseFocus.Sidebar(sidebarIndexFor(frame)) else BrowseFocus.Grid(index - 1)
                InputAction.Dir.RIGHT ->
                    if (col == GRID_COLS - 1) BrowseFocus.Inspector(GazeTarget.Action.PLAY)
                    else BrowseFocus.Grid((index + 1).coerceAtMost(count - 1))
                InputAction.Dir.DOWN ->
                    if (pageRow == GRID_ROWS_PER_PAGE - 1) BrowseFocus.Dock(GazeTarget.Dock.RECENTER)
                    else BrowseFocus.Grid((index + GRID_COLS).coerceAtMost(count - 1))
                InputAction.Dir.UP ->
                    if (pageRow == 0) null else BrowseFocus.Grid(index - GRID_COLS)
            }
        }

        private fun sidebarIndexFor(frame: BrowseFrame): Int =
            (frame.focus as? BrowseFocus.Sidebar)?.index?.coerceIn(0, maxOf(0, frame.folders.size - 1)) ?: 0

        private fun cycleAction(a: GazeTarget.Action, step: Int): GazeTarget.Action {
            val v = GazeTarget.Action.entries
            return v[((a.ordinal + step) % v.size + v.size) % v.size]
        }

        private fun cycleDock(d: GazeTarget.Dock, step: Int): GazeTarget.Dock {
            val v = GazeTarget.Dock.entries
            return v[((d.ordinal + step) % v.size + v.size) % v.size]
        }

        /** Writes [focus] onto the frame and keeps the legacy list cursors roughly in sync. */
        private fun applyFocus(frame: BrowseFrame, focus: BrowseFocus): BrowseFrame {
            val legacyIdx = when (focus) {
                is BrowseFocus.Sidebar -> focus.index
                is BrowseFocus.Grid -> frame.containers.size + focus.index
                else -> frame.focusIndex
            }.coerceIn(0, maxOf(0, frame.rows.size - 1))
            val sidebarTop = if (focus is BrowseFocus.Sidebar) {
                clampScroll(focus.index, frame.sidebarScrollTop, frame.folders.size, SIDEBAR_VISIBLE_ROWS)
            } else {
                frame.sidebarScrollTop
            }
            return frame.copy(
                focus = focus,
                gridReturn = (focus as? BrowseFocus.Grid)?.index ?: frame.gridReturn,
                focusIndex = legacyIdx,
                scrollTop = clampScroll(legacyIdx, frame.scrollTop, frame.rows.size),
                sidebarScrollTop = sidebarTop,
            )
        }

        private fun clampFocus(focus: BrowseFocus, folders: Int, videos: Int): BrowseFocus = when (focus) {
            is BrowseFocus.Grid -> BrowseFocus.Grid(focus.index.coerceIn(0, maxOf(0, videos - 1)))
            is BrowseFocus.Sidebar -> BrowseFocus.Sidebar(focus.index.coerceIn(0, maxOf(0, folders - 1)))
            else -> focus
        }

        /** The A-press / gaze-activate translation table (§10.4). */
        fun intentForFocus(frame: BrowseFrame): UiIntent? = when (val f = frame.focus) {
            is BrowseFocus.Source -> null // needs the source list; handled via Event.Ui(SelectSource)
            is BrowseFocus.Sidebar -> frame.folders.getOrNull(f.index)?.let { UiIntent.SelectFolder(it.id) }
            is BrowseFocus.Grid -> if (frame.focusedVideo != null) UiIntent.PlayVideo(fromStart = false) else null
            is BrowseFocus.Inspector -> when (f.action) {
                GazeTarget.Action.PLAY -> UiIntent.PlayVideo(fromStart = true)
                GazeTarget.Action.RESUME -> UiIntent.PlayVideo(fromStart = false)
                GazeTarget.Action.PROJECTION -> UiIntent.OverrideProjection
            }
            is BrowseFocus.Dock -> UiIntent.DockAction(f.button)
            is BrowseFocus.Toolbar -> when (f.chip) {
                GazeTarget.Chip.SORT -> UiIntent.SelectSort
                else -> null
            }
        }

        // ---- UI intents ----------------------------------------------------

        private fun reduceUi(state: AppState, intent: UiIntent): Pair<AppState, List<Effect>> = when (intent) {
            is UiIntent.SelectSource -> selectSource(state, intent.sourceId)
            is UiIntent.SelectFolder -> selectFolder(state, intent.folderId)
            is UiIntent.SelectGridCell -> {
                val f = state.browse.top ?: return state to noFx()
                state.copy(
                    browse = state.browse.replaceTop(
                        applyFocus(f, BrowseFocus.Grid(intent.index.coerceAtLeast(0))),
                    ),
                ) to noFx()
            }
            UiIntent.SelectSort -> selectSort(state)
            UiIntent.PageNext -> pageGrid(state, forward = true)
            UiIntent.PagePrev -> pageGrid(state, forward = false)
            is UiIntent.NavigateBreadcrumb -> {
                val stack = state.browse.stack
                if (intent.depth < 0 || intent.depth >= stack.size) state to noFx()
                else state.copy(browse = state.browse.copy(stack = stack.take(intent.depth + 1))) to noFx()
            }
            UiIntent.OverrideProjection -> overrideProjection(state)
            is UiIntent.PlayVideo -> playVideo(state, intent.fromStart)
            is UiIntent.DockAction -> dockAction(state, intent.button)
        }

        private fun resolveSource(state: AppState, id: String): MediaSource? =
            state.sources.available.firstOrNull { it.id == id }
                ?: when {
                    id == MediaSource.Local.id -> MediaSource.Local
                    id == MediaSource.Favourites.id -> MediaSource.Favourites
                    else -> state.servers.firstOrNull { "upnp:${it.udn}" == id }?.let { MediaSource.Upnp(it) }
                }

        private fun selectSource(state: AppState, id: String): Pair<AppState, List<Effect>> {
            val source = resolveSource(state, id) ?: return state to noFx()
            val sources = state.sources.copy(selectedId = id)
            fun open(frame: BrowseFrame, fx: List<Effect>) =
                state.copy(screen = VrScreen.BROWSE, sources = sources, browse = BrowseState(listOf(frame))) to fx
            return when (source) {
                is MediaSource.Local -> when {
                    state.sources.localPermission == MediaPermission.Grant.DENIED -> open(
                        BrowseFrame(
                            server = null, objectId = "local", title = source.title, source = source,
                            loading = false, focus = BrowseFocus.Sidebar(0),
                            error = "Arc needs permission to read videos on this phone. " +
                                "Take the headset off and reopen Arc to grant it.",
                        ),
                        noFx(),
                    )
                    state.localMedia.loaded -> open(
                        BrowseFrame(
                            server = null, objectId = "local", title = source.title, source = source,
                            folders = state.localMedia.folders, loading = false, focus = BrowseFocus.Sidebar(0),
                        ),
                        noFx(),
                    )
                    else -> open(
                        BrowseFrame(
                            server = null, objectId = "local", title = source.title, source = source,
                            loading = true, focus = BrowseFocus.Sidebar(0),
                        ),
                        listOf(Effect.LoadLocalMedia),
                    )
                }
                is MediaSource.Upnp -> open(
                    BrowseFrame(
                        server = source.server, objectId = "0", title = source.title, source = source,
                        focus = BrowseFocus.Sidebar(0),
                    ),
                    listOf(Effect.BrowseNode(source, "0", PageRequest.DEFAULT)),
                )
                is MediaSource.Favourites -> open(
                    BrowseFrame(
                        server = null, objectId = "favourites", title = source.title, source = source,
                        loading = false, focus = BrowseFocus.Grid(0),
                    ),
                    noFx(),
                )
            }
        }

        private fun selectFolder(state: AppState, folderId: String): Pair<AppState, List<Effect>> {
            val top = state.browse.top ?: return state to noFx()
            return when (val src = top.mediaSource) {
                is MediaSource.Local -> {
                    val vids = state.localMedia.byFolder[folderId].orEmpty()
                    val folder = state.localMedia.folders.firstOrNull { it.id == folderId }
                    val child = BrowseFrame(
                        server = null, objectId = folderId, title = folder?.title ?: "Folder",
                        source = src, videos = vids, loading = false,
                        totalMatches = vids.size, focus = BrowseFocus.Grid(0),
                    )
                    state.copy(browse = state.browse.push(child)) to noFx()
                }
                else -> {
                    val server = top.server ?: return state to noFx()
                    val child = BrowseFrame(
                        server = server, objectId = folderId, title = "Loading…",
                        source = src, focus = BrowseFocus.Grid(0),
                    )
                    state.copy(browse = state.browse.push(child)) to
                        listOf(Effect.BrowseNode(src, folderId, PageRequest.DEFAULT))
                }
            }
        }

        private fun selectSort(state: AppState): Pair<AppState, List<Effect>> {
            val frame = state.browse.top ?: return state to noFx()
            val currentId = frame.focusedVideo?.id
            val next = frame.copy(sort = frame.sort.next())
            val idx = next.sortedVideos.indexOfFirst { it.id == currentId }.let { if (it < 0) 0 else it }
            return state.copy(browse = state.browse.replaceTop(applyFocus(next, BrowseFocus.Grid(idx)))) to noFx()
        }

        private fun pageGrid(state: AppState, forward: Boolean): Pair<AppState, List<Effect>> {
            val frame = state.browse.top ?: return state to noFx()
            val last = frame.sortedVideos.lastIndex.coerceAtLeast(0)
            val target = if (forward) {
                (frame.gridFocusIndex + GRID_PAGE_SIZE).coerceAtMost(last)
            } else {
                (frame.gridFocusIndex - GRID_PAGE_SIZE).coerceAtLeast(0)
            }
            val moved = applyFocus(frame, BrowseFocus.Grid(target))
            val fx = if (forward && moved.gridPage == moved.pageCount - 1 && frame.hasMorePages) {
                listOf(Effect.BrowseNode(frame.mediaSource, frame.objectId, PageRequest(frame.loadedCount, PAGE_FETCH)))
            } else {
                noFx()
            }
            return state.copy(browse = state.browse.replaceTop(moved)) to fx
        }

        private fun overrideProjection(state: AppState): Pair<AppState, List<Effect>> {
            val frame = state.browse.top ?: return state to noFx()
            val video = frame.focusedVideo ?: return state to noFx()
            val key = MediaKey(frame.mediaSource.id, video.id)
            val storageKey = key.storageKey()
            val current = state.projectionOverrides[storageKey] ?: video.detectedProjection
            val nextMode = cycleProjectionOverride(current)
            val overrides = if (nextMode == null) {
                state.projectionOverrides - storageKey
            } else {
                state.projectionOverrides + (storageKey to nextMode)
            }
            return state.copy(projectionOverrides = overrides) to
                listOf(Effect.PersistProjectionOverride(key, nextMode))
        }

        private val PROJECTION_CYCLE = listOf(
            ProjectionMode.FLAT,
            ProjectionMode.SBS_HALF,
            ProjectionMode.TOPBOTTOM_HALF,
            ProjectionMode.EQUIRECT_180,
            ProjectionMode.EQUIRECT_360,
            null,
        )

        private fun cycleProjectionOverride(current: ProjectionMode?): ProjectionMode? {
            val i = PROJECTION_CYCLE.indexOf(current).let { if (it < 0) 0 else it }
            return PROJECTION_CYCLE[(i + 1) % PROJECTION_CYCLE.size]
        }

        private fun playVideo(state: AppState, fromStart: Boolean): Pair<AppState, List<Effect>> {
            val frame = state.browse.top ?: return state to noFx()
            val video = frame.focusedVideo ?: return state to noFx()
            val key = MediaKey(frame.mediaSource.id, video.id)
            val override = state.projectionOverrides[key.storageKey()]
            return state to listOf(
                Effect.PlayNode(
                    node = video,
                    key = key,
                    startAtMs = 0L,
                    projectionOverride = override,
                    skipResumeCheck = fromStart,
                ),
            )
        }

        private fun dockAction(state: AppState, button: GazeTarget.Dock): Pair<AppState, List<Effect>> = when (button) {
            GazeTarget.Dock.RECENTER -> state to listOf(Effect.Recenter)
            GazeTarget.Dock.SETTINGS -> state.copy(screen = VrScreen.SETTINGS) to noFx()
            GazeTarget.Dock.CALIBRATE ->
                state.copy(
                    screen = VrScreen.SETTINGS,
                    hud = state.hud.copy(focusIndex = Settings.ROWS.indexOf("Screen size").coerceAtLeast(0)),
                ) to noFx()
            GazeTarget.Dock.VIEW_MODE -> state to noFx() // grid/list toggle lands with the M5 widgets
            GazeTarget.Dock.RESCAN ->
                if (state.browse.top?.mediaSource is MediaSource.Local) {
                    state to listOf(Effect.LoadLocalMedia)
                } else {
                    state.copy(discovery = DiscoveryState.RUNNING) to listOf(Effect.StartDiscovery(force = true))
                }
            GazeTarget.Dock.EXIT ->
                state.copy(
                    overlay = Overlay.Confirm("Leave the app?", listOf("Stay", "Exit"), 0, tag = "quit"),
                ) to noFx()
        }

        private fun reduceLocalMediaLoaded(state: AppState, e: Event.LocalMediaLoaded): Pair<AppState, List<Effect>> {
            val lm = LocalMedia(folders = e.folders, byFolder = e.byFolder, loaded = true)
            val top = state.browse.top
            val browse = if (top != null && top.mediaSource is MediaSource.Local && state.browse.depth == 1) {
                state.browse.replaceTop(
                    top.copy(folders = e.folders, loading = false, error = null),
                )
            } else {
                state.browse
            }
            return state.copy(localMedia = lm, browse = browse) to noFx()
        }

        private fun reduceLocalMediaFailed(state: AppState, e: Event.LocalMediaFailed): Pair<AppState, List<Effect>> {
            val top = state.browse.top
            val browse = if (top != null && top.mediaSource is MediaSource.Local) {
                state.browse.replaceTop(top.copy(loading = false, error = e.message))
            } else {
                state.browse
            }
            return state.copy(browse = browse) to noFx()
        }

        private fun reducePlayerInput(state: AppState, action: InputAction): Pair<AppState, List<Effect>> {
            val hud = state.hud
            if (!hud.visible) {
                return when (action) {
                    is InputAction.Cancel -> leavePlayer(state)
                    is InputAction.Confirm -> state.copy(hud = hud.copy(visible = true, lastInputAtMs = state.nowMs)) to noFx()
                    else -> applyPlayerAction(state.copy(hud = hud.copy(visible = true, lastInputAtMs = state.nowMs)), action)
                }
            }
            return when (action) {
                is InputAction.Cancel -> state.copy(hud = hud.copy(visible = false)) to noFx()
                else -> applyPlayerAction(state.copy(hud = hud.copy(lastInputAtMs = state.nowMs)), action)
            }
        }

        private fun applyPlayerAction(state: AppState, action: InputAction): Pair<AppState, List<Effect>> = when (action) {
            InputAction.PlayPause -> state to listOf(Effect.SetPlayWhenReady(null))
            is InputAction.Seek -> state to listOf(Effect.SeekRelative(action.deltaSeconds * 1000L))
            InputAction.Recenter -> state to listOf(Effect.Recenter)
            InputAction.ToggleHud -> state.copy(hud = state.hud.copy(pinned = !state.hud.pinned)) to noFx()
            InputAction.CycleProjection -> {
                val next = nextProjection(state.playback.projection)
                state.copy(playback = state.playback.copy(projection = next)) to listOf(Effect.SetProjection(next))
            }
            is InputAction.Nav -> when (action.dir) {
                InputAction.Dir.LEFT, InputAction.Dir.RIGHT -> state.copy(
                    hud = state.hud.copy(
                        focusIndex = moveFocus(state.hud.focusIndex, action.dir, HudState.CONTROLS.size),
                    ),
                ) to noFx()
                else -> state to noFx()
            }
            is InputAction.Confirm -> activateHudControl(state)
            InputAction.Menu -> state to noFx()
            else -> state to noFx()
        }

        private fun activateHudControl(state: AppState): Pair<AppState, List<Effect>> =
            when (HudState.CONTROLS.getOrNull(state.hud.focusIndex)) {
                "Back" -> leavePlayer(state)
                "Speed" -> {
                    val next = nextSpeed(state.playback.speed)
                    state.copy(playback = state.playback.copy(speed = next)) to listOf(Effect.SetPlaybackSpeed(next))
                }
                "Projection" -> {
                    val next = nextProjection(state.playback.projection)
                    state.copy(playback = state.playback.copy(projection = next)) to listOf(Effect.SetProjection(next))
                }
                else -> state to noFx()
            }

        private fun reduceSettings(state: AppState, action: InputAction): Pair<AppState, List<Effect>> = when (action) {
            is InputAction.Nav -> when (action.dir) {
                InputAction.Dir.UP, InputAction.Dir.DOWN -> {
                    val fi = moveFocus(state.hud.focusIndex, action.dir, Settings.ROWS.size)
                    state.copy(
                        hud = state.hud.copy(focusIndex = fi),
                        settingsScrollTop = clampScroll(
                            fi, state.settingsScrollTop, Settings.ROWS.size, state.listWindow.settings,
                        ),
                    ) to noFx()
                }
                else -> adjustSetting(state, if (action.dir == InputAction.Dir.RIGHT) 1 else -1)
            }
            is InputAction.Confirm -> adjustSetting(state, 1)
            InputAction.Menu -> state.copy(screen = VrScreen.SERVER_LIST) to noFx()
            else -> state to noFx()
        }

        private fun adjustSetting(state: AppState, dir: Int): Pair<AppState, List<Effect>> {
            val s = state.settings
            val settings = when (Settings.ROWS.getOrNull(state.hud.focusIndex)) {
                "Viewer profile" -> cycleProfile(s, dir)
                "IPD" -> s.copy(ipdMm = (s.ipdMm + dir * 0.5f).coerceIn(52f, 74f))
                "Screen distance" -> s.copy(screenDistanceM = (s.screenDistanceM + dir * 0.5f).coerceIn(1.5f, 12f))
                "Screen size" -> s.copy(screenWidthDegrees = (s.screenWidthDegrees + dir * 5f).coerceIn(30f, 110f))
                "Screen-to-lens" -> s.copy(screenToLensMm = (s.screenToLensMm + dir * 0.5f).coerceIn(30f, 60f))
                "Lens k1" -> s.copy(lensK1 = roundHundredth(s.lensK1 + dir * 0.01f).coerceIn(0f, 1f))
                "Lens k2" -> s.copy(lensK2 = roundHundredth(s.lensK2 + dir * 0.01f).coerceIn(0f, 1f))
                "Divider width" -> s.copy(dividerPx = (s.dividerPx + dir * 2).coerceIn(0, 40))
                "Distortion correction" -> s.copy(distortionCorrection = !s.distortionCorrection)
                "Gamepad buttons" -> s.copy(gamepadAbSwapped = !s.gamepadAbSwapped)
                "Motion prediction" -> s.copy(predictionEnabled = !s.predictionEnabled)
                "Neck model" -> s.copy(neckModelEnabled = !s.neckModelEnabled)
                "Auto-recenter" -> s.copy(autoRecenterIdleSeconds = if (s.autoRecenterIdleSeconds == 0) 30 else 0)
                else -> return state to noFx()
            }
            return state.copy(settings = settings) to listOf(Effect.ApplySettings(settings))
        }

        /** Cycles the viewer profile and resets the optics overrides to that profile's table values. */
        private fun cycleProfile(s: Settings, dir: Int): Settings {
            val all = com.daydreamvr.vrcore.profile.DeviceProfiles.ALL
            val cur = all.indexOfFirst { it.id == s.deviceProfileId }.coerceAtLeast(0)
            val step = if (dir >= 0) 1 else all.size - 1
            val next = all[(cur + step) % all.size]
            return s.copy(
                deviceProfileId = next.id,
                ipdMm = next.interLensDistanceM * 1000f,
                screenToLensMm = next.screenToLensDistanceM * 1000f,
                lensK1 = next.distortionK.getOrElse(0) { 0f },
                lensK2 = next.distortionK.getOrElse(1) { 0f },
                dividerPx = next.dividerPx,
            )
        }

        private fun roundHundredth(v: Float): Float = kotlin.math.round(v * 100f) / 100f

        private fun reduceOverlay(state: AppState, overlay: Overlay, action: InputAction): Pair<AppState, List<Effect>> =
            when (overlay) {
                is Overlay.Keyboard -> reduceKeyboard(state, overlay, action)
                is Overlay.Confirm -> reduceConfirm(state, overlay, action)
                is Overlay.Error -> when (action) {
                    is InputAction.Confirm ->
                        if (overlay.canRetry) state.copy(overlay = null) to retryEffects(state)
                        else state.copy(overlay = null) to noFx()
                    else -> state to noFx()
                }
                is Overlay.Toast -> state to noFx()
            }

        private fun reduceKeyboard(state: AppState, overlay: Overlay.Keyboard, action: InputAction): Pair<AppState, List<Effect>> {
            val submit = (action is InputAction.Seek && action.deltaSeconds > 0) || action == InputAction.PageDown ||
                (action is InputAction.Confirm && VrKeyboard.keyAtCursor(overlay.kb) == "\n")
            if (submit) {
                val text = overlay.kb.text.trim()
                if (text.isEmpty()) return state.copy(overlay = null) to noFx()
                return when (overlay.purpose) {
                    KeyboardPurpose.MANUAL_SERVER ->
                        state.copy(overlay = null, discovery = DiscoveryState.RUNNING) to listOf(Effect.AddManualServer(text))
                    KeyboardPurpose.SEARCH -> state.copy(overlay = null) to noFx()
                }
            }
            val next = VrKeyboard.reduce(action, overlay.kb, state.settings.subnetPrefix)
            return state.copy(overlay = overlay.copy(kb = next)) to noFx()
        }

        private fun reduceConfirm(state: AppState, overlay: Overlay.Confirm, action: InputAction): Pair<AppState, List<Effect>> =
            when (action) {
                is InputAction.Nav ->
                    state.copy(overlay = overlay.copy(focusIndex = moveFocus(overlay.focusIndex, action.dir, overlay.options.size))) to noFx()
                is InputAction.Confirm -> applyConfirmChoice(state, overlay)
                else -> state to noFx()
            }

        private fun applyConfirmChoice(state: AppState, overlay: Overlay.Confirm): Pair<AppState, List<Effect>> {
            val choice = overlay.options.getOrNull(overlay.focusIndex).orEmpty()
            val cleared = state.copy(overlay = null)
            return when (overlay.tag) {
                "quit" -> if (choice == "Exit") cleared to listOf(Effect.QuitToLobby) else cleared to noFx()
                "resume" -> {
                    val pending = state.pendingResume ?: return cleared.copy(pendingResume = null) to noFx()
                    val start = if (choice.startsWith("Resume")) pending.positionMs else 0L
                    cleared.copy(pendingResume = null) to
                        listOf(Effect.Play(pending.item, pending.serverUdn, start, null, skipResumeCheck = true))
                }
                else -> cleared to noFx()
            }
        }

        private fun retryEffects(state: AppState): List<Effect> {
            val f = state.browse.top ?: return listOf(Effect.StartDiscovery(force = true))
            return when {
                f.mediaSource is MediaSource.Local -> listOf(Effect.LoadLocalMedia)
                else -> listOf(Effect.BrowseNode(f.mediaSource, f.objectId, PageRequest.DEFAULT))
            }
        }

        // ---- helpers -------------------------------------------------------

        private fun noFx(): List<Effect> = emptyList()

        private fun moveFocus(current: Int, dir: InputAction.Dir, count: Int): Int {
            if (count <= 0) return 0
            return when (dir) {
                InputAction.Dir.UP, InputAction.Dir.LEFT -> (current - 1).coerceAtLeast(0)
                InputAction.Dir.DOWN, InputAction.Dir.RIGHT -> (current + 1).coerceAtMost(count - 1)
            }
        }

        fun clampScroll(focus: Int, scrollTop: Int, total: Int, window: Int = BROWSE_VISIBLE_ROWS): Int {
            if (total <= window) return 0
            var st = scrollTop
            if (focus < st) st = focus
            if (focus >= st + window) st = focus - window + 1
            return st.coerceIn(0, maxOf(0, total - window))
        }

        private fun nextProjection(mode: ProjectionMode): ProjectionMode {
            val values = ProjectionMode.entries
            return values[(mode.ordinal + 1) % values.size]
        }

        private fun nextSpeed(speed: Float): Float {
            val steps = floatArrayOf(0.75f, 1f, 1.25f, 1.5f)
            val i = steps.indexOfFirst { kotlin.math.abs(it - speed) < 0.01f }
            return steps[if (i < 0) 1 else (i + 1) % steps.size]
        }

        private const val CONTROLLER_LOST_TITLE = "Controller disconnected"
    }
}
