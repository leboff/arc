package com.daydreamvr.playback

/**
 * The pure decision function behind transparent recovery from a playback error
 * (ARCHITECTURE.md §10.5). It never performs IO — the caller executes the
 * returned [FallbackAction].
 *
 * @param attempt how many times the *current* resource has already been retried
 *                for this failure kind (0 on the first failure).
 * @param remainingResources how many lower-ranked resources are still untried.
 */
object FallbackPolicy {

    /** Network retry backoffs, in order (ARCHITECTURE.md §10.5). */
    val BACKOFFS_MS: LongArray = longArrayOf(1_000L, 3_000L, 7_000L)

    fun decide(failure: PlaybackFailure, attempt: Int, remainingResources: Int): FallbackAction =
        when (failure) {
            PlaybackFailure.NetworkTimeout,
            PlaybackFailure.NetworkConnectionFailed,
            -> when {
                attempt < BACKOFFS_MS.size -> FallbackAction.RetrySameAfter(BACKOFFS_MS[attempt])
                remainingResources > 0 -> FallbackAction.NextResource
                else -> FallbackAction.GiveUp(failure.userMessage)
            }

            is PlaybackFailure.BadHttpStatus -> when {
                attempt == 0 -> FallbackAction.RefreshUrlFromServer
                remainingResources > 0 -> FallbackAction.NextResource
                else -> FallbackAction.GiveUp(failure.userMessage)
            }

            is PlaybackFailure.UnsupportedVideoCodec -> when {
                remainingResources > 0 -> FallbackAction.NextResource
                else -> FallbackAction.GiveUp(failure.userMessage)
            }

            PlaybackFailure.DecoderInitFailed -> when {
                remainingResources > 0 -> FallbackAction.NextResource
                else -> FallbackAction.GiveUp(failure.userMessage)
            }

            PlaybackFailure.AudioTrackInitFailed -> when {
                attempt == 0 -> FallbackAction.ForceSoftwareAudio
                else -> FallbackAction.GiveUp("This file plays, but its audio can't be decoded on this phone.")
            }

            is PlaybackFailure.Unknown -> when {
                remainingResources > 0 -> FallbackAction.NextResource
                else -> FallbackAction.GiveUp(failure.userMessage)
            }
        }
}

/** What the player should do next after a [PlaybackFailure]. */
sealed interface FallbackAction {

    /** Retry the same resource at the last known position after [delayMs]. */
    data class RetrySameAfter(val delayMs: Long) : FallbackAction

    /** Advance to the next-ranked [com.daydreamvr.upnp.model.Resource]. */
    data object NextResource : FallbackAction

    /** Re-`Browse` the parent container for a fresh URL, then retry once. */
    data object RefreshUrlFromServer : FallbackAction

    /** Rebuild the player forcing the software / extension audio renderer. */
    data object ForceSoftwareAudio : FallbackAction

    /** Nothing else to try; show [userMessage] inside the headset. */
    data class GiveUp(val userMessage: String) : FallbackAction
}
