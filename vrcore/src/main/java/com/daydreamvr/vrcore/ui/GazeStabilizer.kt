package com.daydreamvr.vrcore.ui

/**
 * Debounces the raw per-frame hover target (UI_GAZE_PLAN.md §2.5).
 *
 * A candidate must hold for [holdSeconds] before it becomes current; until then
 * the previous target stands. `null` is a legitimate target (the reticle left
 * every hit region). Pure — the caller supplies `dt`.
 *
 * 60 ms ≈ 4 frames at 60 Hz: long enough to swallow tracking jitter at a row
 * edge, short enough to feel instant.
 */
class GazeStabilizer<T>(private val holdSeconds: Float = 0.06f) {

    private var current: T? = null
    private var candidate: T? = null
    private var candidateAge = 0f
    private var seenCandidate = false

    /** Returns the stable target after folding in this frame's raw [rawTarget]. */
    fun update(rawTarget: T?, dtSeconds: Float): T? {
        if (rawTarget == current && seenCandidate) {
            candidate = current
            candidateAge = 0f
            return current
        }
        if (seenCandidate && rawTarget == candidate) {
            candidateAge += dtSeconds.coerceAtLeast(0f)
            if (candidateAge >= holdSeconds) {
                current = candidate
                candidateAge = 0f
            }
        } else {
            candidate = rawTarget
            candidateAge = 0f
            seenCandidate = true
        }
        return current
    }

    fun reset() {
        current = null
        candidate = null
        candidateAge = 0f
        seenCandidate = false
    }
}
