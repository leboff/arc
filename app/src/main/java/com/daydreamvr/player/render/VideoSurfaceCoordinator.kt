package com.daydreamvr.player.render

import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.daydreamvr.playback.VideoPlayer
import java.util.concurrent.atomic.AtomicLong

/**
 * Coordinates video-surface ownership between the GL thread and Android's main
 * thread. Decoder operations are always dispatched to the main thread, while
 * GL resources remain owned by the GL thread.
 */
class VideoSurfaceCoordinator(
    private val player: VideoPlayer,
    private val mainExecutor: ((Runnable) -> Unit) = { runnable ->
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run()
        } else {
            Handler(Looper.getMainLooper()).post(runnable)
        }
    },
) {
    private val currentGeneration = AtomicLong(0L)

    @Volatile
    private var isReleased = false
    private var activeSurface: Surface? = null

    val generation: Long get() = currentGeneration.get()

    /**
     * Called on the GL thread after creating a replacement surface. Any retired
     * surface is released only after the decoder has been detached on main.
     */
    fun onSurfaceCreated(newSurface: Surface, generation: Long, oldSurface: Surface? = null) {
        if (isReleased) {
            newSurface.release()
            oldSurface?.release()
            return
        }
        mainExecutor {
            if (isReleased || generation < currentGeneration.get()) {
                newSurface.release()
                oldSurface?.release()
                return@mainExecutor
            }

            currentGeneration.set(generation)
            if (activeSurface != null) {
                player.detach()
                activeSurface?.release()
                activeSurface = null
            } else if (oldSurface != null) {
                player.detach()
                oldSurface.release()
            }
            activeSurface = newSurface
            player.attach(newSurface)
        }
    }

    /** Called on the GL thread before its current video surface is retired. */
    fun onSurfaceDestroyed(surface: Surface?, generation: Long) {
        mainExecutor {
            if (generation >= currentGeneration.get()) {
                player.detach()
                if (activeSurface == surface || surface == null) {
                    activeSurface?.release()
                    activeSurface = null
                } else {
                    surface.release()
                }
            }
        }
    }

    /** Releases the main-thread decoder/surface state and rejects late GL events. */
    fun release() {
        isReleased = true
        currentGeneration.set(Long.MAX_VALUE)
        player.detach()
        activeSurface?.release()
        activeSurface = null
    }
}
