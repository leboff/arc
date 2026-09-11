package com.daydreamvr.player.state

import com.daydreamvr.player.data.ServerStore
import com.daydreamvr.player.data.SettingsStore
import com.daydreamvr.playback.PlayRequest
import com.daydreamvr.playback.ResumeStore
import com.daydreamvr.playback.VideoPlayer
import com.daydreamvr.player.media.MediaSource
import com.daydreamvr.player.media.PlaybackRef
import com.daydreamvr.upnp.model.Resource
import java.net.URI
import com.daydreamvr.upnp.MediaServerDirectory
import com.daydreamvr.upnp.MediaServerDirectoryImpl
import com.daydreamvr.upnp.cds.ContentDirectoryClient
import com.daydreamvr.upnp.cds.DecoderCaps
import com.daydreamvr.upnp.cds.ResourceRanker
import com.daydreamvr.upnp.model.MediaServer
import com.daydreamvr.vrcore.render.ProjectionMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Executes the [Effect]s emitted by [AppStateMachine.reduce] (ARCHITECTURE.md
 * §12). The reducer stays pure; every piece of IO — UPnP discovery / browse,
 * transport commands, persistence, head-tracker side effects — happens here and
 * feeds results back in as [Event]s via [dispatch].
 *
 * [start] also bridges the two external state sources the reducer needs — the
 * discovered-server list and the player snapshot — into events.
 */
class EffectRunner(
    private val directory: MediaServerDirectory,
    private val contentDirectory: ContentDirectoryClient,
    private val player: VideoPlayer,
    private val decoderCaps: () -> DecoderCaps,
    private val resumeStore: ResumeStore,
    private val serverStore: ServerStore,
    private val settingsStore: SettingsStore,
    private val scope: CoroutineScope,
    private val dispatch: (Event) -> Unit,
    private val onRecenter: () -> Unit = {},
    private val onApplySettings: (Settings) -> Unit = {},
    private val onQuit: () -> Unit = {},
    var localMediaLoader: suspend () -> Event = {
        Event.LocalMediaFailed("Local media not configured")
    },
) {

    /** Wires the server-list and player-snapshot flows into [dispatch]. Call once. */
    fun start() {
        scope.launch {
            directory.servers.collect { dispatch(Event.ServersChanged(it)) }
        }
        scope.launch {
            player.snapshot.collect { snap ->
                dispatch(Event.PlayerStateChanged(snap))
                if (snap.durationMs > 0L) {
                    resumeStore.put(
                        snap.itemKey ?: return@collect,
                        snap.positionMs,
                        snap.durationMs,
                        System.currentTimeMillis(),
                    )
                }
            }
        }
    }

    fun run(effect: Effect) {
        when (effect) {
            is Effect.StartDiscovery -> startDiscovery()
            is Effect.Browse -> browse(effect)
            is Effect.AddManualServer -> addManual(effect.hostPort)
            is Effect.Play -> play(effect)
            is Effect.Seek -> player.seekTo(effect.toMs, effect.exact)
            is Effect.SeekRelative -> player.seekBy(effect.deltaMs)
            is Effect.SetPlayWhenReady -> setPlayWhenReady(effect.play)
            Effect.StopPlayback -> stopPlayback()
            is Effect.SetProjection -> Unit // render-only: AppScene reads AppState.playback.projection
            is Effect.SetPlaybackSpeed -> player.setSpeed(effect.speed)
            is Effect.SelectAudioTrack -> player.selectAudioTrack(effect.id)
            is Effect.SelectSubtitleTrack -> player.selectSubtitleTrack(effect.id)
            Effect.Recenter -> onRecenter()
            is Effect.ApplySettings -> applySettings(effect.settings)
            is Effect.Persist -> Unit // settings/resume are persisted by their own effects
            Effect.QuitToLobby -> quit()
            Effect.LoadLocalMedia -> loadLocalMedia()
            is Effect.BrowseNode -> browseNode(effect)
            is Effect.PlayNode -> playNode(effect)
            is Effect.PersistProjectionOverride -> Unit // override store wiring lands with M8 integration
            is Effect.PrefetchThumbnails -> Unit // handled by the thumbnail pipeline (M6)
        }
    }

    private fun loadLocalMedia() {
        scope.launch {
            runCatching { localMediaLoader() }.fold(
                onSuccess = { event -> dispatch(event) },
                onFailure = { err ->
                    dispatch(Event.LocalMediaFailed(err.message ?: "Failed to read local media"))
                },
            )
        }
    }

    private fun browseNode(effect: Effect.BrowseNode) {
        val server = (effect.source as? MediaSource.Upnp)?.server ?: return
        scope.launch {
            contentDirectory.browse(server, effect.objectId, effect.page).fold(
                onSuccess = { dispatch(Event.BrowseLoaded(effect.objectId, it, append = effect.page.start > 0)) },
                onFailure = { dispatch(Event.BrowseFailed(effect.objectId, humanMessage(it))) },
            )
        }
    }

    private fun playNode(effect: Effect.PlayNode) {
        val node = effect.node
        val itemKey = effect.key.storageKey()

        val ranked: List<Resource> = when (val p = node.playback) {
            is PlaybackRef.Upnp -> ResourceRanker.rank(p.resources, decoderCaps())
            is PlaybackRef.Local -> listOf(
                Resource(
                    uri = URI(p.ref.value),
                    protocolInfo = "http-get:*:${node.mimeType ?: "video/*"}:*",
                    sizeBytes = node.sizeBytes,
                    durationMs = node.durationMs,
                    resolution = node.width.takeIf { it > 0 }
                        ?.let { com.daydreamvr.upnp.model.Size(it, node.height) },
                    bitrate = null,
                ),
            )
        }
        if (ranked.isEmpty()) {
            dispatch(Event.Failure("Can't play this", "\"${node.title}\" has no playable source.", canRetry = false))
            return
        }

        var startAt = effect.startAtMs
        if (!effect.skipResumeCheck && startAt == 0L) {
            val entry = resumeStore.get(itemKey)
            if (entry != null && !entry.isFinished && entry.positionMs >= RESUME_PROMPT_MIN_MS) {
                startAt = entry.positionMs
            }
        }

        val projection = effect.projectionOverride
            ?: ProjectionMode.detect(node.title, node.width, node.height)
        dispatch(Event.ProjectionChanged(projection))
        player.play(
            PlayRequest(
                itemKey = itemKey,
                title = node.title.ifBlank { "Video" },
                rankedResources = ranked,
                startAtMs = startAt,
                projection = projection,
            ),
        )
    }


    // ---- discovery / browse ------------------------------------------------

    private fun startDiscovery() {
        scope.launch {
            dispatch(Event.DiscoveryStateChanged(DiscoveryState.RUNNING))
            val known = runCatching { serverStore.descriptionUrls() }.getOrDefault(emptyList())
            if (known.isNotEmpty()) {
                runCatching { (directory as? MediaServerDirectoryImpl)?.reprobeKnown(known) }
            }
            val ok = runCatching { directory.discover() }.isSuccess
            dispatch(
                Event.DiscoveryStateChanged(
                    if (ok || directory.servers.value.isNotEmpty()) DiscoveryState.IDLE else DiscoveryState.FAILED,
                ),
            )
        }
    }

    private fun browse(effect: Effect.Browse) {
        scope.launch {
            contentDirectory.browse(effect.server, effect.objectId, effect.page).fold(
                onSuccess = {
                    dispatch(Event.BrowseLoaded(effect.objectId, it, append = effect.page.start > 0))
                },
                onFailure = {
                    dispatch(Event.BrowseFailed(effect.objectId, humanMessage(it)))
                },
            )
        }
    }

    private fun addManual(hostPort: String) {
        scope.launch {
            directory.addManual(hostPort).fold(
                onSuccess = { server ->
                    runCatching { serverStore.remember(server, manual = true) }
                    dispatch(Event.ManualServerResult(ok = true, message = "Added ${server.friendlyName}"))
                },
                onFailure = {
                    dispatch(Event.ManualServerResult(ok = false, message = humanMessage(it)))
                },
            )
        }
    }

    // ---- playback ---------------------------------------------------------

    private fun play(effect: Effect.Play) {
        val server: MediaServer? = directory.servers.value.firstOrNull { it.udn == effect.serverUdn }
        val itemKey = "${effect.serverUdn}|${effect.item.id}"

        if (!effect.skipResumeCheck && effect.startAtMs == 0L) {
            val entry = resumeStore.get(itemKey)
            if (entry != null && !entry.isFinished && entry.positionMs >= RESUME_PROMPT_MIN_MS) {
                dispatch(Event.ResumePrompt(effect.item, effect.serverUdn, entry.positionMs, entry.durationMs))
                return
            }
        }

        val ranked = ResourceRanker.rank(effect.item.resources, decoderCaps())
        if (ranked.isEmpty()) {
            dispatch(Event.Failure("Can't play this", "\"${effect.item.title}\" has no playable source.", canRetry = false))
            return
        }

        val projection = effect.projectionOverride ?: ProjectionMode.detect(
            effect.item.title,
            effect.item.resolution?.width ?: 0,
            effect.item.resolution?.height ?: 0,
        )
        dispatch(Event.ProjectionChanged(projection))
        player.play(
            PlayRequest(
                itemKey = itemKey,
                title = effect.item.title.ifBlank { server?.friendlyName ?: "Video" },
                rankedResources = ranked,
                startAtMs = effect.startAtMs,
                projection = projection,
            ),
        )
    }

    private fun setPlayWhenReady(play: Boolean?) {
        when (play) {
            null -> player.playPause()
            true -> if (!player.snapshot.value.isPlaying) player.playPause()
            false -> player.pause()
        }
    }

    private fun stopPlayback() {
        player.stop()
        scope.launch { runCatching { settingsStore.saveResume(resumeStore.all()) } }
    }

    // ---- settings -------------------------------------------------------

    private fun applySettings(settings: Settings) {
        onApplySettings(settings)
        scope.launch { runCatching { settingsStore.save(settings) } }
    }

    private fun quit() {
        player.stop()
        scope.launch { runCatching { settingsStore.saveResume(resumeStore.all()) } }
        onQuit()
    }

    private fun humanMessage(t: Throwable): String =
        t.message?.takeIf { it.isNotBlank() } ?: "Something went wrong."

    companion object {
        /** Below this a resume prompt is not worth the interruption (~15 s). */
        const val RESUME_PROMPT_MIN_MS = 15_000L
    }
}
