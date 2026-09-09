package com.daydreamvr.player.perf

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Detects a wedged GL thread (ARCHITECTURE.md §15). [onFrame] is called from
 * `VrRenderer.onDrawFrame`; if no frame lands for [timeoutMs] the watchdog fires
 * [onWedged] once on the main thread so the Activity can recreate its
 * `GLSurfaceView` and restore from `AppState` (which holds no GL objects).
 *
 * The check runs on the main looper — the failure this guards against is a stuck
 * *GL* thread (surface loss), with the main thread still alive.
 */
class RenderWatchdog(
    private val timeoutMs: Long = 2_000L,
    private val onWedged: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var lastFrameMs = 0L

    @Volatile
    private var running = false
    private var fired = false

    private val check = object : Runnable {
        override fun run() {
            if (!running) return
            val since = SystemClock.uptimeMillis() - lastFrameMs
            if (since > timeoutMs && !fired) {
                fired = true
                onWedged()
            }
            handler.postDelayed(this, timeoutMs / 2)
        }
    }

    fun onFrame() {
        lastFrameMs = SystemClock.uptimeMillis()
        fired = false
    }

    fun start() {
        if (running) return
        running = true
        fired = false
        lastFrameMs = SystemClock.uptimeMillis()
        handler.postDelayed(check, timeoutMs)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(check)
    }
}
