package com.daydreamvr.playback

import android.view.Surface
import com.daydreamvr.upnp.model.Resource
import com.daydreamvr.vrcore.render.ProjectionMode
import kotlinx.coroutines.flow.StateFlow

/**
 * The playback surface the rest of the app talks to (ARCHITECTURE.md §10). The
 * only implementation is [ExoVideoPlayer]; everything downstream reads
 * [snapshot] and issues transport commands. All methods are main-thread.
 */
interface VideoPlayer {

    val snapshot: StateFlow<PlaybackSnapshot>

    /** Binds the decoder output to a GL video surface ([com.daydreamvr.vrcore.gl.VideoTexture]). */
    fun attach(surface: Surface)

    fun detach()

    fun play(request: PlayRequest)

    fun playPause()

    fun pause()

    fun stop()

    fun seekBy(deltaMs: Long)

    fun seekTo(positionMs: Long, exact: Boolean)

    fun setSpeed(speed: Float)

    fun selectAudioTrack(id: String?)

    fun selectSubtitleTrack(id: String?)

    fun release()
}

/** Coarse decoder lifecycle state, mirrored from `Player.STATE_*`. */
enum class PlaybackState { IDLE, BUFFERING, READY, ENDED }

/**
 * Decoded frame geometry. [pixelAspect] is `Format.pixelWidthHeightRatio`;
 * [displayAspect] is what [com.daydreamvr.vrcore.render.CylinderScreen.setAspect]
 * wants so anamorphic content is not stretched.
 */
data class VideoDimensions(
    val width: Int,
    val height: Int,
    val pixelAspect: Float,
) {
    val displayAspect: Float
        get() = if (height <= 0) 0f else width * pixelAspect / height

    companion object {
        val UNKNOWN = VideoDimensions(0, 0, 1f)
    }
}

/**
 * A request to play one library item. [rankedResources] comes from
 * `ResourceRanker`; index 0 is tried first and playback falls back down the list
 * on unrecoverable format errors ([FallbackPolicy]).
 */
data class PlayRequest(
    val itemKey: String,
    val title: String,
    val rankedResources: List<Resource>,
    val startAtMs: Long = 0L,
    val projection: ProjectionMode? = null,
)
