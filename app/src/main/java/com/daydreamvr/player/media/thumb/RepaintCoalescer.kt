package com.daydreamvr.player.media.thumb

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Collapses a burst of thumbnail arrivals into at most one panel repaint per
 * [windowMs] (UI_REDESIGN_REVIEWED_PLAN.md §9.6, R19).
 *
 * Six decoded posters landing over 400 ms must not mean six full 1536×800
 * `lockHardwareCanvas` cycles on the GL thread. The loader calls [signal] on each
 * arrival; `AppScene.update` calls [poll] once per frame and bumps
 * `AppState.thumbGeneration` only when it returns true.
 *
 * Pure — time arrives as a parameter, never read from a clock.
 */
class RepaintCoalescer(private val windowMs: Long = 120L) {

    private val pending = AtomicBoolean(false)
    private var lastEmitMs = Long.MIN_VALUE

    fun signal() {
        pending.set(true)
    }

    /** True at most once per [windowMs], and only when there is pending work. */
    fun poll(nowMs: Long): Boolean {
        if (!pending.get()) return false
        if (lastEmitMs != Long.MIN_VALUE && nowMs - lastEmitMs < windowMs) return false
        lastEmitMs = nowMs
        pending.set(false)
        return true
    }
}
