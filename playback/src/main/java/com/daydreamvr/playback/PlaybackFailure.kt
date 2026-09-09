package com.daydreamvr.playback

/**
 * A playback error, already translated out of `PlaybackException.errorCode`
 * (ARCHITECTURE.md §10.5) so [FallbackPolicy] and the UI never see Media3 types.
 * Nothing throws across this boundary — failures ride on [PlaybackSnapshot.failure].
 */
sealed class PlaybackFailure {

    /** A short, headset-legible description of what went wrong. */
    abstract val userMessage: String

    /** Connection dropped or a read stalled past the timeout. */
    data object NetworkTimeout : PlaybackFailure() {
        override val userMessage = "The connection to the server timed out."
    }

    /** Could not reach the server at all. */
    data object NetworkConnectionFailed : PlaybackFailure() {
        override val userMessage = "Lost connection to the server."
    }

    /** The server answered with an error status (typically 404 / 410 after a re-index). */
    data class BadHttpStatus(val status: Int) : PlaybackFailure() {
        override val userMessage = "The server no longer has this file (HTTP $status)."
    }

    /** The container / codec is not something this phone's decoder can play. */
    data class UnsupportedVideoCodec(val codec: String?) : PlaybackFailure() {
        override val userMessage =
            "This file's video codec isn't supported by this phone" +
                (codec?.let { " ($it)" } ?: "") + "."
    }

    /** The hardware video decoder failed to initialise for this stream. */
    data object DecoderInitFailed : PlaybackFailure() {
        override val userMessage = "This phone's video decoder could not start for this file."
    }

    /** The audio output track failed to initialise (often an unsupported bitstream). */
    data object AudioTrackInitFailed : PlaybackFailure() {
        override val userMessage = "Audio could not be started for this file."
    }

    /** Anything not otherwise classified. */
    data class Unknown(val detail: String) : PlaybackFailure() {
        override val userMessage = detail.ifBlank { "Playback failed." }
    }
}
