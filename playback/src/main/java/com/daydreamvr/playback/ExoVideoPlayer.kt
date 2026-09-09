package com.daydreamvr.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient

/**
 * The only [VideoPlayer]: an AndroidX Media3 / ExoPlayer wrapper (ARCHITECTURE.md
 * §10.1). Construct, use and [release] on the main thread.
 *
 * Responsibilities beyond a thin wrapper:
 *  - publishes an immutable [PlaybackSnapshot] on every observable change;
 *  - drives the ranked-resource + backoff recovery from [FallbackPolicy] on
 *    `onPlayerError`;
 *  - persists resume positions through [ResumeStore].
 */
@UnstableApi
class ExoVideoPlayer(
    private val context: Context,
    private val resumeStore: ResumeStore = InMemoryResumeStore(),
    private val onFatalError: (String) -> Unit = {},
) : VideoPlayer {

    private val main = Handler(Looper.getMainLooper())

    private val _snapshot = MutableStateFlow(PlaybackSnapshot.EMPTY)
    override val snapshot: StateFlow<PlaybackSnapshot> = _snapshot.asStateFlow()

    private var player: ExoPlayer? = null
    private var forcedSoftwareAudio = false
    private var pendingSurface: Surface? = null

    private var request: PlayRequest? = null
    private var resourceIndex = 0
    private var retryAttempt = 0
    private var refreshedOnce = false
    private var lastVideoCodec: String? = null

    private val listener = PlayerListener()

    private val ticker = object : Runnable {
        override fun run() {
            publish()
            main.postDelayed(this, POSITION_POLL_MS)
        }
    }

    // ---- VideoPlayer ------------------------------------------------------

    override fun attach(surface: Surface) {
        pendingSurface = surface
        player?.setVideoSurface(surface)
    }

    override fun detach() {
        pendingSurface = null
        player?.setVideoSurface(null)
    }

    override fun play(request: PlayRequest) {
        this.request = request
        resourceIndex = 0
        retryAttempt = 0
        refreshedOnce = false
        forcedSoftwareAudio = false
        ensurePlayer()
        loadCurrentResource(request.startAtMs.coerceAtLeast(resumeStartFor(request)))
    }

    override fun playPause() {
        val p = player ?: return
        p.playWhenReady = !p.playWhenReady
    }

    override fun pause() {
        player?.playWhenReady = false
        persistResume()
    }

    override fun stop() {
        persistResume()
        player?.stop()
        request = null
        _snapshot.value = PlaybackSnapshot.EMPTY
    }

    override fun seekBy(deltaMs: Long) {
        val p = player ?: return
        seekTo((p.currentPosition + deltaMs).coerceAtLeast(0L), exact = false)
    }

    override fun seekTo(positionMs: Long, exact: Boolean) {
        val p = player ?: return
        p.setSeekParameters(if (exact) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)
        p.seekTo(positionMs.coerceIn(0L, if (p.duration > 0) p.duration else Long.MAX_VALUE))
        publish()
    }

    override fun setSpeed(speed: Float) {
        player?.setPlaybackParameters(PlaybackParameters(speed.coerceIn(0.25f, 4f)))
        publish()
    }

    override fun selectAudioTrack(id: String?) = applyOverride(C.TRACK_TYPE_AUDIO, id)

    override fun selectSubtitleTrack(id: String?) = applyOverride(C.TRACK_TYPE_TEXT, id)

    override fun release() {
        persistResume()
        main.removeCallbacksAndMessages(null)
        player?.removeListener(listener)
        player?.release()
        player = null
        request = null
    }

    // ---- player construction -------------------------------------------------

    private fun ensurePlayer() {
        if (player != null) return
        val renderers = DefaultRenderersFactory(context)
            .setExtensionRendererMode(
                if (forcedSoftwareAudio) {
                    DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                } else {
                    DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                },
            )
            .setEnableDecoderFallback(true)

        val httpFactory = OkHttpDataSource.Factory(OkHttpClient())
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 30_000, 1_500, 3_000)
            .setTargetBufferBytes(32 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(0, false)
            .build()

        player = ExoPlayer.Builder(context)
            .setRenderersFactory(renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                setSeekParameters(SeekParameters.CLOSEST_SYNC)
                addListener(listener)
                pendingSurface?.let { setVideoSurface(it) }
            }
        main.removeCallbacks(ticker)
        main.postDelayed(ticker, POSITION_POLL_MS)
    }

    private fun rebuildPlayerForcingSoftwareAudio() {
        val resumeAt = player?.currentPosition ?: 0L
        player?.removeListener(listener)
        player?.release()
        player = null
        forcedSoftwareAudio = true
        ensurePlayer()
        loadCurrentResource(resumeAt)
    }

    private fun loadCurrentResource(positionMs: Long) {
        val req = request ?: return
        val resource = req.rankedResources.getOrNull(resourceIndex)
        if (resource == null) {
            giveUp(PlaybackFailure.Unknown("No playable source for \"${req.title}\".").userMessage)
            return
        }
        val p = ensurePlayerAndGet()
        val item = MediaItem.Builder()
            .setUri(resource.uri.toString())
            .apply {
                val mime = resource.mimeType
                if (!mime.isNullOrBlank() && mime != "*") {
                    setMimeType(mime)
                }
            }
            .build()
        p.setMediaItem(item, positionMs.coerceAtLeast(0L))
        p.prepare()
        p.playWhenReady = true
        _snapshot.value = _snapshot.value.copy(
            itemKey = req.itemKey,
            title = req.title,
            failure = null,
            resourceIndex = resourceIndex,
        )
    }

    private fun ensurePlayerAndGet(): ExoPlayer {
        ensurePlayer()
        return player!!
    }

    // ---- fallback handling -------------------------------------------------

    private fun handleError(error: PlaybackException) {
        val failure = classify(error)
        val remaining = (request?.rankedResources?.size ?: 0) - resourceIndex - 1
        when (val action = FallbackPolicy.decide(failure, retryAttempt, remaining)) {
            is FallbackAction.RetrySameAfter -> {
                retryAttempt++
                val at = player?.currentPosition ?: 0L
                _snapshot.value = _snapshot.value.copy(isBuffering = true, failure = failure)
                main.postDelayed({ loadCurrentResource(at) }, action.delayMs)
            }

            FallbackAction.NextResource -> {
                resourceIndex++
                retryAttempt = 0
                refreshedOnce = false
                loadCurrentResource(player?.currentPosition ?: 0L)
            }

            FallbackAction.RefreshUrlFromServer -> {
                // Phase 5 wires the actual re-Browse; here we retry the same URL once.
                refreshedOnce = true
                retryAttempt++
                main.postDelayed({ loadCurrentResource(player?.currentPosition ?: 0L) }, 500L)
            }

            FallbackAction.ForceSoftwareAudio -> {
                retryAttempt++
                rebuildPlayerForcingSoftwareAudio()
            }

            is FallbackAction.GiveUp -> giveUp(action.userMessage)
        }
    }

    private fun classify(error: PlaybackException): PlaybackFailure = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            PlaybackFailure.NetworkTimeout

        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
            PlaybackFailure.NetworkConnectionFailed

        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
            PlaybackFailure.BadHttpStatus(
                (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode ?: 0,
            )

        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        ->
            PlaybackFailure.UnsupportedVideoCodec(lastVideoCodec)

        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
            PlaybackFailure.DecoderInitFailed

        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
            PlaybackFailure.Unknown("Video container not supported by device decoder.")

        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
            PlaybackFailure.Unknown("Video stream or container is corrupted.")

        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
        ->
            PlaybackFailure.AudioTrackInitFailed

        else -> PlaybackFailure.Unknown("${error.errorCodeName}: ${error.message ?: "playback error"}")
    }

    private fun giveUp(message: String) {
        _snapshot.value = _snapshot.value.copy(
            isPlaying = false,
            isBuffering = false,
            failure = PlaybackFailure.Unknown(message),
        )
        onFatalError(message)
    }

    // ---- track overrides --------------------------------------------------

    private fun applyOverride(trackType: Int, id: String?) {
        val p = player ?: return
        val builder = p.trackSelectionParameters.buildUpon()
        if (id == null) {
            builder.clearOverridesOfType(trackType)
        } else {
            val (groupIndex, trackIndex) = id.split('/').let { it[0].toInt() to it[1].toInt() }
            val group = p.currentTracks.groups.getOrNull(groupIndex) ?: return
            builder.setOverrideForType(
                TrackSelectionOverride(group.mediaTrackGroup, listOf(trackIndex)),
            )
        }
        p.trackSelectionParameters = builder.build()
    }

    private fun tracksOf(tracks: Tracks, trackType: Int): List<TrackInfo> {
        val out = ArrayList<TrackInfo>()
        tracks.groups.forEachIndexed { g, group ->
            if (group.type != trackType) return@forEachIndexed
            for (t in 0 until group.length) {
                val format = group.getTrackFormat(t)
                out += TrackInfo(
                    id = "$g/$t",
                    label = format.label ?: format.language ?: "Track ${out.size + 1}",
                    language = format.language,
                    isSelected = group.isTrackSelected(t),
                    codec = format.codecs ?: format.sampleMimeType,
                )
            }
        }
        return out
    }

    // ---- snapshot publishing -------------------------------------------------

    private fun publish() {
        val p = player
        val req = request
        val base = _snapshot.value
        if (p == null) {
            _snapshot.value = base
            return
        }
        _snapshot.value = base.copy(
            itemKey = req?.itemKey ?: base.itemKey,
            title = req?.title ?: base.title,
            state = mapState(p.playbackState),
            isPlaying = p.isPlaying,
            isBuffering = p.playbackState == Player.STATE_BUFFERING,
            positionMs = p.currentPosition.coerceAtLeast(0L),
            bufferedMs = p.bufferedPosition.coerceAtLeast(0L),
            durationMs = if (p.duration == C.TIME_UNSET) 0L else p.duration,
            speed = p.playbackParameters.speed,
            resourceIndex = resourceIndex,
        )
    }

    private fun mapState(state: Int): PlaybackState = when (state) {
        Player.STATE_BUFFERING -> PlaybackState.BUFFERING
        Player.STATE_READY -> PlaybackState.READY
        Player.STATE_ENDED -> PlaybackState.ENDED
        else -> PlaybackState.IDLE
    }

    private fun resumeStartFor(req: PlayRequest): Long {
        val entry = resumeStore.get(req.itemKey) ?: return 0L
        return if (entry.isFinished) 0L else entry.positionMs
    }

    private fun persistResume() {
        val p = player ?: return
        val req = request ?: return
        val duration = if (p.duration == C.TIME_UNSET) 0L else p.duration
        if (duration <= 0L) return
        resumeStore.put(req.itemKey, p.currentPosition, duration, System.currentTimeMillis())
    }

    private inner class PlayerListener : Player.Listener {

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) {
                retryAttempt = 0
            }
            if (playbackState == Player.STATE_ENDED) persistResume()
            publish()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) = publish()

        override fun onPlayerError(error: PlaybackException) = handleError(error)

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            _snapshot.value = _snapshot.value.copy(
                videoWidth = videoSize.width,
                videoHeight = videoSize.height,
                pixelAspect = if (videoSize.pixelWidthHeightRatio > 0f) videoSize.pixelWidthHeightRatio else 1f,
            )
        }

        override fun onTracksChanged(tracks: Tracks) {
            tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }?.let { g ->
                if (g.length > 0) lastVideoCodec = g.getTrackFormat(0).let { it.codecs ?: it.sampleMimeType }
            }
            _snapshot.value = _snapshot.value.copy(
                audioTracks = tracksOf(tracks, C.TRACK_TYPE_AUDIO),
                subtitleTracks = tracksOf(tracks, C.TRACK_TYPE_TEXT),
            )
        }

        override fun onCues(cueGroup: CueGroup) {
            _snapshot.value = _snapshot.value.copy(
                activeCues = cueGroup.cues.mapNotNull { it.text?.toString() },
            )
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = publish()
    }

    companion object {
        private const val POSITION_POLL_MS = 500L
    }
}
