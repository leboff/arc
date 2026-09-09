package com.daydreamvr.player.state

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
                is GazeTarget.DialogButton, is GazeTarget.KeyboardKey -> s
            } to noFx()
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
            val updated = frame.copy(
                containers = containers,
                items = items,
                folders = containers.map { com.daydreamvr.player.media.UpnpAdapter.folder(it) },
                videos = items.map { com.daydreamvr.player.media.UpnpAdapter.video(it) },
                totalMatches = maxOf(e.result.totalMatches, loaded),
                focusIndex = focus,
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
                            browse = BrowseState(listOf(BrowseFrame(server, "0", server.friendlyName))),
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

        private fun reduceBrowse(state: AppState, action: InputAction): Pair<AppState, List<Effect>> {
            val frame = state.browse.top ?: return backToServerList(state)
            return when (action) {
                is InputAction.Nav -> when (action.dir) {
                    InputAction.Dir.LEFT ->
                        if (state.browse.depth > 1) state.copy(browse = state.browse.pop()) to noFx()
                        else backToServerList(state)
                    InputAction.Dir.RIGHT -> openFocused(state, frame)
                    InputAction.Dir.UP, InputAction.Dir.DOWN -> {
                        val fi = moveFocus(frame.focusIndex, action.dir, frame.rows.size)
                        state.copy(
                            browse = state.browse.replaceTop(
                                frame.copy(focusIndex = fi, scrollTop = clampScroll(fi, frame.scrollTop, frame.rows.size)),
                            ),
                        ) to noFx()
                    }
                }
                is InputAction.Confirm -> openFocused(state, frame)
                InputAction.PageDown -> pageBrowse(state, frame, forward = true)
                InputAction.PageUp -> pageBrowse(state, frame, forward = false)
                is InputAction.Seek -> pageBrowse(state, frame, forward = action.deltaSeconds > 0)
                InputAction.Menu, InputAction.ToggleHud -> state.copy(screen = VrScreen.SETTINGS) to noFx()
                else -> state to noFx()
            }
        }

        private fun openFocused(state: AppState, frame: BrowseFrame): Pair<AppState, List<Effect>> {
            return when (val row = frame.focusedRow) {
                is DidlContainer ->
                    state.copy(browse = state.browse.push(BrowseFrame(frame.server, row.id, row.title))) to
                        listOf(Effect.Browse(frame.server, row.id, PageRequest.DEFAULT))
                is DidlItem ->
                    if (!row.isPlayableVideo) {
                        state.copy(overlay = Overlay.Toast("Not a playable video", state.nowMs + TOAST_MS)) to noFx()
                    } else {
                        state to listOf(Effect.Play(row, frame.server.udn, startAtMs = 0L, projectionOverride = null))
                    }
                else -> state to noFx()
            }
        }

        private fun pageBrowse(state: AppState, frame: BrowseFrame, forward: Boolean): Pair<AppState, List<Effect>> {
            if (forward) {
                if (frame.focusIndex + BROWSE_VISIBLE_ROWS >= frame.loadedCount && frame.hasMorePages) {
                    return state.copy(browse = state.browse.replaceTop(frame.copy(loading = true))) to
                        listOf(Effect.Browse(frame.server, frame.objectId, PageRequest(frame.loadedCount, PAGE_FETCH)))
                }
                val fi = (frame.focusIndex + BROWSE_VISIBLE_ROWS).coerceAtMost(maxOf(0, frame.rows.size - 1))
                return state.copy(
                    browse = state.browse.replaceTop(
                        frame.copy(focusIndex = fi, scrollTop = clampScroll(fi, frame.scrollTop, frame.rows.size)),
                    ),
                ) to noFx()
            }
            val fi = (frame.focusIndex - BROWSE_VISIBLE_ROWS).coerceAtLeast(0)
            return state.copy(
                browse = state.browse.replaceTop(
                    frame.copy(focusIndex = fi, scrollTop = clampScroll(fi, frame.scrollTop, frame.rows.size)),
                ),
            ) to noFx()
        }

        private fun reducePlayerInput(state: AppState, action: InputAction): Pair<AppState, List<Effect>> {
            val hud = state.hud
            if (!hud.visible) {
                val shown = state.copy(hud = hud.copy(visible = true, lastInputAtMs = state.nowMs))
                return when (action) {
                    is InputAction.Confirm, is InputAction.Cancel -> shown to noFx()
                    else -> applyPlayerAction(shown, action)
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
            val f = state.browse.top
            return if (f != null) {
                listOf(Effect.Browse(f.server, f.objectId, PageRequest.DEFAULT))
            } else {
                listOf(Effect.StartDiscovery(force = true))
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
