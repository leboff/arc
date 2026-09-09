package com.daydreamvr.playback

/** One selectable audio or subtitle track. */
data class TrackInfo(
    val id: String,
    val label: String,
    val language: String?,
    val isSelected: Boolean,
    val codec: String? = null,
)

/**
 * An immutable view of the player, published on [VideoPlayer.snapshot] whenever
 * anything observable changes. The UI never reaches into ExoPlayer directly.
 */
data class PlaybackSnapshot(
    val itemKey: String? = null,
    val title: String = "",
    val state: PlaybackState = PlaybackState.IDLE,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1f,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val pixelAspect: Float = 1f,
    val audioTracks: List<TrackInfo> = emptyList(),
    val subtitleTracks: List<TrackInfo> = emptyList(),
    val activeCues: List<String> = emptyList(),
    val failure: PlaybackFailure? = null,
    val resourceIndex: Int = 0,
) {
    val dimensions: VideoDimensions
        get() = VideoDimensions(videoWidth, videoHeight, pixelAspect)

    companion object {
        val EMPTY = PlaybackSnapshot()
    }
}
