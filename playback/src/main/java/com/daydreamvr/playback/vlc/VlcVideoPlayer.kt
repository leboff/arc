package com.daydreamvr.playback.vlc

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.daydreamvr.playback.InMemoryResumeStore
import com.daydreamvr.playback.PlaybackFailure
import com.daydreamvr.playback.PlaybackSnapshot
import com.daydreamvr.playback.PlaybackState
import com.daydreamvr.playback.PlayRequest
import com.daydreamvr.playback.ResumeStore
import com.daydreamvr.playback.VideoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IVLCVout

/**
 * The LibVLC-backed [VideoPlayer] (docs/FORMAT_SUPPORT_PLAN.md §5, §8.6). Arc's
 * compatibility fallback: VLC's own demuxers plus software audio decode for the
 * containers/codecs Media3 cannot handle. Only ever the *inactive-until-needed*
 * engine behind [com.daydreamvr.playback.PlaybackEngineRouter] — never the
 * primary, because VLC has no vsync-aligned frame release (§5.4).
 *
 * `libvlcjni.so` is loaded lazily on the first [play]; a launch that never hits a
 * hostile file never pays the ~100 ms cold-start cost.
 *
 * Construct, use and [release] on the main thread.
 */
class VlcVideoPlayer(
    context: Context,
    private val resumeStore: ResumeStore = InMemoryResumeStore(),
    private val onFatalError: (String) -> Unit = {},
) : VideoPlayer {

    private val appContext: Context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private val _snapshot = MutableStateFlow(PlaybackSnapshot.EMPTY)
    override val snapshot: StateFlow<PlaybackSnapshot> = _snapshot.asStateFlow()

    private var libVlc: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var openFd: AssetFileDescriptor? = null

    private var surface: Surface? = null
    private var surfaceWidth = DEFAULT_SURFACE_WIDTH
    private var surfaceHeight = DEFAULT_SURFACE_HEIGHT
    private var viewsAttached = false

    private var request: PlayRequest? = null
    private var state = PlaybackState.IDLE
    private var isPlayingNow = false
    private var positionMs = 0L
    private var durationMs = 0L
    private var speed = 1f

    private val ticker = object : Runnable {
        override fun run() {
            pollPosition()
            publish()
            main.postDelayed(this, POSITION_POLL_MS)
        }
    }

    // ---- VideoPlayer ------------------------------------------------------

    override fun attach(surface: Surface) {
        this.surface = surface
        mediaPlayer?.let { attachSurface(it, surface) }
    }

    override fun detach() {
        // Must complete before the GL thread releases the SurfaceTexture (§8.6).
        vout()?.takeIf { viewsAttached }?.detachViews()
        viewsAttached = false
        surface = null
    }

    override fun play(request: PlayRequest) {
        this.request = request
        val startAt = request.startAtMs.coerceAtLeast(resumeStartFor(request))
        val resource = request.rankedResources.firstOrNull()
        if (resource == null) {
            fail("No playable source for \"${request.title}\".")
            return
        }

        val mp = ensurePlayer()
        val media = mediaFor(Uri.parse(resource.uri.toString())).apply {
            setHWDecoderEnabled(true, false)
            if (startAt > 0L) addOption(":start-time=${startAt / 1000}")
        }
        mp.media = media
        media.release()

        surface?.let { attachSurface(mp, it) }
        state = PlaybackState.BUFFERING
        positionMs = startAt
        durationMs = 0L
        mp.play()

        _snapshot.value = _snapshot.value.copy(
            itemKey = request.itemKey,
            title = request.title,
            failure = null,
            resourceIndex = 0,
        )
        main.removeCallbacks(ticker)
        main.postDelayed(ticker, POSITION_POLL_MS)
    }

    override fun playPause() {
        val mp = mediaPlayer ?: return
        if (mp.isPlaying) mp.pause() else mp.play()
        publish()
    }

    override fun pause() {
        mediaPlayer?.pause()
        isPlayingNow = false
        persistResume()
        publish()
    }

    override fun stop() {
        persistResume()
        main.removeCallbacks(ticker)
        mediaPlayer?.stop()
        closeFd()
        request = null
        state = PlaybackState.IDLE
        isPlayingNow = false
        _snapshot.value = PlaybackSnapshot.EMPTY
    }

    override fun seekBy(deltaMs: Long) {
        val mp = mediaPlayer ?: return
        seekTo((mp.time + deltaMs).coerceAtLeast(0L), exact = false)
    }

    override fun seekTo(positionMs: Long, exact: Boolean) {
        val mp = mediaPlayer ?: return
        val clamped = positionMs.coerceIn(0L, if (mp.length > 0L) mp.length else Long.MAX_VALUE)
        mp.setTime(clamped)
        this.positionMs = clamped
        publish()
    }

    override fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.25f, 4f)
        this.speed = clamped
        mediaPlayer?.rate = clamped
        publish()
    }

    override fun selectAudioTrack(id: String?) {
        val mp = mediaPlayer ?: return
        mp.setAudioTrack(id?.substringAfterLast(':')?.toIntOrNull() ?: -1)
    }

    /** LibVLC renders subtitles into its own plane; Arc's fallback engine has no text track (§5.5). */
    override fun selectSubtitleTrack(id: String?) = Unit

    override fun release() {
        persistResume()
        main.removeCallbacksAndMessages(null)
        mediaPlayer?.let { mp ->
            mp.setEventListener(null)
            if (viewsAttached) mp.getVLCVout().detachViews()
            mp.stop()
            mp.release()
        }
        viewsAttached = false
        mediaPlayer = null
        closeFd()
        libVlc?.release()
        libVlc = null
    }

    // ---- internals ------------------------------------------------------

    private fun libVlc(): LibVLC =
        libVlc ?: LibVLC(appContext, VLC_OPTIONS).also { libVlc = it }

    private fun ensurePlayer(): MediaPlayer =
        mediaPlayer ?: MediaPlayer(libVlc()).also { mp ->
            mp.setEventListener(object : MediaPlayer.EventListener {
                override fun onEvent(event: MediaPlayer.Event) = handleEvent(event)
            })
            mediaPlayer = mp
        }

    private fun vout(): IVLCVout? = mediaPlayer?.getVLCVout()

    private fun mediaFor(uri: Uri): Media =
        if (uri.scheme == "content") {
            closeFd()
            val afd = requireNotNull(appContext.contentResolver.openAssetFileDescriptor(uri, "r")) {
                "Could not open $uri"
            }
            openFd = afd
            Media(libVlc(), afd)
        } else {
            Media(libVlc(), uri)
        }

    private fun attachSurface(mp: MediaPlayer, surface: Surface) {
        val vout = mp.getVLCVout()
        if (viewsAttached) vout.detachViews()
        vout.setVideoSurface(surface, null)
        // MANDATORY. Without it: audio plays, screen stays black, no error (§5.3).
        vout.setWindowSize(surfaceWidth, surfaceHeight)
        vout.attachViews(
            newVideoLayoutListener { width, height ->
                surfaceWidth = width
                surfaceHeight = height
                _snapshot.value = _snapshot.value.copy(videoWidth = width, videoHeight = height)
            },
        )
        viewsAttached = true
    }

    private fun handleEvent(event: MediaPlayer.Event) {
        when (event.type) {
            MediaPlayer.Event.Buffering -> {
                state = VlcPlaybackStateHelper.onBufferingEvent(state, isPlayingNow, event.buffering)
            }

            MediaPlayer.Event.Playing -> {
                state = PlaybackState.READY
                isPlayingNow = true
            }

            MediaPlayer.Event.Paused -> {
                state = PlaybackState.READY
                isPlayingNow = false
            }

            MediaPlayer.Event.Stopped -> isPlayingNow = false

            MediaPlayer.Event.EndReached -> {
                state = PlaybackState.ENDED
                isPlayingNow = false
                persistResume()
            }

            MediaPlayer.Event.EncounteredError -> {
                fail("This file could not be played.")
                return
            }

            MediaPlayer.Event.TimeChanged -> positionMs = event.timeChanged.coerceAtLeast(0L)

            MediaPlayer.Event.LengthChanged -> durationMs = event.lengthChanged.coerceAtLeast(0L)
        }
        publish()
    }

    private fun pollPosition() {
        val mp = mediaPlayer ?: return
        positionMs = mp.time.coerceAtLeast(0L)
        if (mp.length > 0L) durationMs = mp.length
        isPlayingNow = mp.isPlaying
        if (isPlayingNow && state == PlaybackState.BUFFERING) {
            state = PlaybackState.READY
        }
    }

    private fun publish() {
        val req = request
        val effState = VlcPlaybackStateHelper.effectiveState(state, isPlayingNow)
        val buffering = VlcPlaybackStateHelper.isBuffering(state, isPlayingNow)
        _snapshot.value = _snapshot.value.copy(
            itemKey = req?.itemKey ?: _snapshot.value.itemKey,
            title = req?.title ?: _snapshot.value.title,
            state = effState,
            isPlaying = isPlayingNow,
            isBuffering = buffering,
            positionMs = positionMs,
            bufferedMs = positionMs,
            durationMs = durationMs,
            speed = speed,
        )
    }

    private fun fail(message: String) {
        main.removeCallbacks(ticker)
        state = PlaybackState.IDLE
        isPlayingNow = false
        _snapshot.value = _snapshot.value.copy(
            isPlaying = false,
            isBuffering = false,
            failure = PlaybackFailure.Unknown(message),
        )
        onFatalError(message)
    }

    private fun resumeStartFor(req: PlayRequest): Long {
        val entry = resumeStore.get(req.itemKey) ?: return 0L
        return if (entry.isFinished) 0L else entry.positionMs
    }

    private fun persistResume() {
        val req = request ?: return
        if (durationMs <= 0L) return
        resumeStore.put(req.itemKey, positionMs, durationMs, System.currentTimeMillis())
    }

    private fun closeFd() {
        openFd?.let { runCatching { it.close() } }
        openFd = null
    }

    companion object {
        private const val POSITION_POLL_MS = 500L
        private const val DEFAULT_SURFACE_WIDTH = 1920
        private const val DEFAULT_SURFACE_HEIGHT = 1080

        private val VLC_OPTIONS = arrayListOf(
            // Try MediaCodec first, then VLC's software decoders (avcodec).
            "--codec=mediacodec_ndk,all",
            "--audio-time-stretch",
            // LAN streaming; mirrors DefaultLoadControl's buffer floor.
            "--network-caching=3000",
            "--file-caching=1500",
            // Subtitles are not supported on this engine (§5.5).
            "--no-sub-autodetect-file",
        )
    }
}

/**
 * Builds the [IVLCVout.OnNewVideoLayoutListener] that re-applies the *real*
 * decoded video dimensions to LibVLC's output window.
 *
 * VLC parses the stream only after [IVLCVout.attachViews], so the
 * [IVLCVout.setWindowSize] call in [VlcVideoPlayer.attachSurface] necessarily
 * runs against the placeholder guess (`DEFAULT_SURFACE_*`). Without a second
 * `setWindowSize` here VLC keeps scaling every frame into that stale window for
 * the rest of playback, and the shared GL projection mesh — which assumes the
 * Surface is filled edge-to-edge at the true frame aspect — renders warped or
 * seamed output. Media3-native playback is unaffected; this is only reached on
 * the compatibility-engine fallback path.
 */
internal fun newVideoLayoutListener(
    onLayout: (width: Int, height: Int) -> Unit,
): IVLCVout.OnNewVideoLayoutListener = object : IVLCVout.OnNewVideoLayoutListener {
    override fun onNewVideoLayout(
        vlcVout: IVLCVout,
        width: Int,
        height: Int,
        visibleWidth: Int,
        visibleHeight: Int,
        sarNum: Int,
        sarDen: Int,
    ) {
        if (width <= 0 || height <= 0) return
        vlcVout.setWindowSize(width, height)
        onLayout(width, height)
    }
}

/**
 * Pure state-transition helpers for LibVLC buffering and playback states.
 *
 * LibVLC can emit [MediaPlayer.Event.Buffering] events (even at 99%) while audio/video
 * frames are already decoding and presenting. [isBuffering] must strictly be false
 * whenever [isPlayingNow] is true to prevent UI overlays from latching "Buffering…".
 * Additionally, a 100% buffering event must clear [PlaybackState.BUFFERING] back to
 * [PlaybackState.READY].
 */
internal object VlcPlaybackStateHelper {
    fun onBufferingEvent(
        currentState: PlaybackState,
        isPlayingNow: Boolean,
        percent: Float,
    ): PlaybackState = when {
        percent < 100f -> if (!isPlayingNow) PlaybackState.BUFFERING else currentState
        else -> if (currentState == PlaybackState.BUFFERING) PlaybackState.READY else currentState
    }

    fun isBuffering(state: PlaybackState, isPlayingNow: Boolean): Boolean =
        !isPlayingNow && state == PlaybackState.BUFFERING

    fun effectiveState(state: PlaybackState, isPlayingNow: Boolean): PlaybackState =
        if (isPlayingNow && state == PlaybackState.BUFFERING) PlaybackState.READY else state
}
