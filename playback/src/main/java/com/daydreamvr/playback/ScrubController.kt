package com.daydreamvr.playback

import kotlin.math.abs

/**
 * Turns a continuous analog-trigger pressure into a smooth, non-linear seek
 * gesture (ARCHITECTURE.md §8.2, §10.4).
 *
 * Preview-then-commit: while the trigger is held the caller shows
 * [ScrubOutput.previewPositionMs] on the HUD timeline but only issues a real
 * `seekTo(CLOSEST_SYNC)` when [ScrubOutput.commitSeek] is true (throttled to
 * [COMMIT_INTERVAL_MS]). On [onRelease] the caller does one exact `seekTo`.
 *
 * Pure and JVM-testable — time is passed in, never read.
 */
class ScrubController(
    private val maxRateSecPerSec: Float = 120f,
) {

    private var active = false
    private var everScrubbed = false
    private var previewMs = 0L
    private var lastNowMs = 0L
    private var lastCommitMs = 0L

    /**
     * Feed one trigger sample. [rate] is `[-1, 1]` (negative rewinds); `0` ends
     * the gesture. Non-linear: effective speed is `rate² · maxRateSecPerSec`, so
     * a light pull nudges and a full pull sweeps.
     */
    fun onScrub(rate: Float, nowMs: Long, currentPositionMs: Long, durationMs: Long): ScrubOutput {
        val upper = if (durationMs > 0L) durationMs else Long.MAX_VALUE

        if (rate == 0f) {
            active = false
            return ScrubOutput(if (everScrubbed) previewMs else currentPositionMs.coerceIn(0L, upper), false)
        }

        val clamped = rate.coerceIn(-1f, 1f)

        if (!active) {
            active = true
            everScrubbed = true
            previewMs = currentPositionMs.coerceIn(0L, upper)
            lastNowMs = nowMs
            lastCommitMs = nowMs
            return ScrubOutput(previewMs, false)
        }

        val dtMs = (nowMs - lastNowMs).coerceAtLeast(0L)
        lastNowMs = nowMs

        val signedSquare = clamped * abs(clamped)
        val deltaMs = (signedSquare * maxRateSecPerSec * dtMs.toFloat()).toLong()
        previewMs = (previewMs + deltaMs).coerceIn(0L, upper)

        val commit = nowMs - lastCommitMs >= COMMIT_INTERVAL_MS
        if (commit) lastCommitMs = nowMs
        return ScrubOutput(previewMs, commit)
    }

    /** Final exact seek target, or null if the trigger was never pulled since the last release. */
    fun onRelease(): Long? {
        if (!everScrubbed) return null
        val target = previewMs
        active = false
        everScrubbed = false
        return target
    }

    companion object {
        const val COMMIT_INTERVAL_MS = 250L
    }
}

/**
 * @param previewPositionMs where the HUD scrubber should sit right now.
 * @param commitSeek true when the caller should issue a throttled `seekTo`.
 */
data class ScrubOutput(
    val previewPositionMs: Long,
    val commitSeek: Boolean,
)
