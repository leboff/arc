package com.daydreamvr.playback

import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The only [VideoPlayer] the app constructs from Phase F2 onward
 * (docs/FORMAT_SUPPORT_PLAN.md §3.3, §6). Owns both engines, forwards every
 * transport call to the active one, and republishes its snapshot so the UI never
 * learns which engine produced a frame.
 *
 * Routing, in order (§6.2):
 *  1. sticky per-item hint from [engineStore] — "the file that failed yesterday"
 *     opens straight on the engine that worked;
 *  2. static [EnginePreRoute] on the top-ranked resource — a `.wmv` never wastes a
 *     Media3 open;
 *  3. reactive failover — a container failure classified engine-fatal by
 *     [FallbackPolicy] re-opens on VLC at the last position, **at most once, in one
 *     direction** (Media3 → VLC).
 *
 * Both engines share the one [Surface]; only one is prepared at a time. The
 * inactive engine is stopped so it holds no MediaCodec instance.
 */
class PlaybackEngineRouter(
    private val media3: VideoPlayer,
    private val vlcFactory: () -> VideoPlayer,
    private val engineStore: PlaybackEngineStore = InMemoryPlaybackEngineStore(),
    private val onFatalError: (String) -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : VideoPlayer {

    private val _snapshot = MutableStateFlow(PlaybackSnapshot.EMPTY)
    override val snapshot: StateFlow<PlaybackSnapshot> = _snapshot.asStateFlow()

    private var vlc: VideoPlayer? = null

    private var active: VideoPlayer = media3
    private var activeEngine = PlaybackEngine.MEDIA3
    private val enginesTried = mutableSetOf<PlaybackEngine>()

    private var currentSurface: Surface? = null
    private var currentRequest: PlayRequest? = null
    private var mirrorJob: Job? = null
    private var switching = false

    // ---- VideoPlayer ------------------------------------------------------

    override fun attach(surface: Surface) {
        currentSurface = surface
        active.attach(surface)
    }

    override fun detach() {
        currentSurface = null
        active.detach()
    }

    override fun play(request: PlayRequest) {
        currentRequest = request
        enginesTried.clear()

        val first = request.rankedResources.firstOrNull()
        val engine = engineStore.preferred(request.itemKey)
            ?: first?.let { EnginePreRoute.decide(it.uri.toString(), it.mimeType) }
            ?: PlaybackEngine.MEDIA3

        startOn(engine, request, request.startAtMs)
    }

    override fun playPause() = active.playPause()

    override fun pause() = active.pause()

    override fun stop() {
        active.stop()
        currentRequest = null
    }

    override fun seekBy(deltaMs: Long) = active.seekBy(deltaMs)

    override fun seekTo(positionMs: Long, exact: Boolean) = active.seekTo(positionMs, exact)

    override fun setSpeed(speed: Float) = active.setSpeed(speed)

    override fun selectAudioTrack(id: String?) = active.selectAudioTrack(id)

    override fun selectSubtitleTrack(id: String?) = active.selectSubtitleTrack(id)

    override fun release() {
        mirrorJob?.cancel()
        scope.cancel()
        media3.release()
        vlc?.release()
    }

    // ---- engine routing ------------------------------------------------

    private fun engine(which: PlaybackEngine): VideoPlayer = when (which) {
        PlaybackEngine.MEDIA3 -> media3
        PlaybackEngine.VLC -> vlc ?: vlcFactory().also { vlc = it }
    }

    private fun startOn(which: PlaybackEngine, request: PlayRequest, startAtMs: Long) {
        val target = engine(which)
        if (target !== active) {
            active.detach()
            active.stop()
        }
        active = target
        activeEngine = which
        enginesTried += which

        currentSurface?.let { target.attach(it) }
        target.play(request.copy(startAtMs = startAtMs))
        mirror(target)
    }

    private fun mirror(player: VideoPlayer) {
        mirrorJob?.cancel()
        mirrorJob = scope.launch {
            player.snapshot.collect { snap -> onSnapshot(player, snap) }
        }
    }

    private fun onSnapshot(source: VideoPlayer, snap: PlaybackSnapshot) {
        if (source !== active) return

        val failure = snap.failure
        if (failure != null && !switching) {
            val action = FallbackPolicy.decide(
                failure = failure,
                attempt = 0,
                remainingResources = 0,
                enginesTried = enginesTried,
            )
            if (action is FallbackAction.SwitchEngine) {
                // Suppress the intermediate failure on the router's public snapshot so the UI
                // never displays a transient error dialog while we fail over to the fallback engine.
                _snapshot.value = _snapshot.value.copy(
                    isBuffering = true,
                    failure = null,
                )
                switchTo(action.to, from = snap)
                return
            }
            if (action is FallbackAction.GiveUp) {
                _snapshot.value = snap
                onFatalError(action.userMessage)
                return
            }
        }

        _snapshot.value = snap

        if (snap.state == PlaybackState.READY && snap.failure == null) {
            currentRequest?.let { engineStore.remember(it.itemKey, activeEngine) }
        }
    }

    private fun switchTo(to: PlaybackEngine, from: PlaybackSnapshot) {
        val request = currentRequest ?: return
        switching = true
        mirrorJob?.cancel()
        val resumeAt = if (from.positionMs > 0L) from.positionMs else request.startAtMs
        startOn(to, request, resumeAt)
        if (from.speed != 1f) {
            active.setSpeed(from.speed)
        }
        from.audioTracks.firstOrNull { it.isSelected }?.id?.let {
            active.selectAudioTrack(it)
        }
        from.subtitleTracks.firstOrNull { it.isSelected }?.id?.let {
            active.selectSubtitleTrack(it)
        }
        if (!from.isPlaying && from.state != PlaybackState.IDLE) {
            active.pause()
        }
        switching = false
    }
}
