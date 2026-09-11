# Codex Fix Task (t_a66faf78): Fix C1 — Video Surface Lifecycle Coordinator & GL Thread Safety

You are fixing finding **C1** from Astra's architectural review (`docs/ASTRA_ARCHITECTURAL_REVIEW.md`) in Arc VR Player (`/root/daydream-vr-player`).

## 1. Problem & Root Cause (Astra Finding C1)
1. **Thread Contract Violation on Player Attachment:**
   - In `app/src/main/java/com/daydreamvr/player/render/AppScene.kt:128–145`, `video.createOnGlThread()` creates the video surface inside `onGlCreate()` on the **GL rendering thread** and immediately invokes `onVideoSurfaceReady(video.surface)`.
   - In `app/src/main/java/com/daydreamvr/player/VrActivity.kt:145`, this callback directly calls `player.attach(surface)` on the GL thread.
   - `VideoPlayer` (`playback/src/main/java/com/daydreamvr/playback/VideoPlayer.kt:11`) explicitly requires: **"All methods are main-thread."** ExoPlayer's `setVideoSurface()` and VLC's `attachSurface()` must run on the main thread. Calling them from the GL thread violates this contract and risks crashes and race conditions.
2. **Surface Destruction Before Decoder Detachment:**
   - On GL recreation (e.g. headset sleep/wake or context loss), `AppScene.onGlCreate()` calls `if (created) onGlDestroy()`.
   - `onGlDestroy()` immediately released the active `Surface` via `video.release()` while the decoder (ExoPlayer / MediaCodec) was still actively attached and decoding into it, resulting in `IllegalStateException` or black screens.
   - The player must be detached (`player.detach()`) on the main thread **before** the old surface is released.
3. **Stale Generation Callback Race Conditions:**
   - Rapid recreation can produce out-of-order surface ready notifications. A generation counter must ensure older, superseded surface events do not overwrite the current active surface.
4. **GL Teardown Invoked From Main Thread:**
   - In `VrActivity.kt:374–380`, `onDestroy()` calls `renderer.onGlDestroy()` directly on the main thread.
   - This executes `GLES30.glDelete*` calls without an active EGL context (or in the Android HWUI context of the main thread). GL resource teardown must be queued to the GL thread via `glSurfaceView.queueEvent`, not run on main.

---

## 2. Requirements & Implementation Plan

### A. Create `VideoSurfaceCoordinator.kt` (`app/src/main/java/com/daydreamvr/player/render/VideoSurfaceCoordinator.kt`)
Create a dedicated, pure JVM-testable coordinator that manages surface attachment, detachment, generation tracking, and thread hopping:
```kotlin
package com.daydreamvr.player.render

import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.daydreamvr.playback.VideoPlayer
import java.util.concurrent.atomic.AtomicLong

/**
 * Coordinates video surface attachment/detachment between the GL thread and the
 * Android main thread (ARCHITECTURE.md §10.2 / ASTRA_ARCHITECTURAL_REVIEW finding C1).
 *
 * Guarantees:
 * 1. Marshals all [player.attach] and [player.detach] calls to the main thread.
 * 2. Ensures decoder detachment ([player.detach]) occurs before retiring surfaces are released.
 * 3. Tracks generation counters so out-of-order or superseded GL events do not attach stale surfaces.
 * 4. Ensures activity destruction does not execute GL resource releases without a current context.
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
    @Volatile private var isReleased = false
    private var activeSurface: Surface? = null

    val generation: Long get() = currentGeneration.get()

    /**
     * Called from the GL thread when a new surface is created.
     * [oldSurface] is the surface being replaced (if any); it will be released
     * on the main thread only AFTER [player.detach] has completed.
     */
    fun onSurfaceCreated(newSurface: Surface, generation: Long, oldSurface: Surface? = null) {
        if (isReleased) {
            newSurface.release()
            oldSurface?.release()
            return
        }
        mainExecutor {
            if (isReleased || generation < currentGeneration.get()) {
                // Stale or released generation; discard
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

    /**
     * Called when the GL surface is being destroyed.
     * Detaches player on main thread and releases [surface].
     */
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

    /**
     * Called from activity [onDestroy] on the main thread.
     * Detaches player, releases active surface, and marks coordinator released
     * so any in-flight GL callbacks are discarded.
     */
    fun release() {
        isReleased = true
        currentGeneration.set(Long.MAX_VALUE)
        player.detach()
        activeSurface?.release()
        activeSurface = null
    }
}
```

### B. Update `VideoTexture.kt` (`vrcore/src/main/java/com/daydreamvr/vrcore/gl/VideoTexture.kt`)
Add helper method to release GL texture ID on the GL thread without prematurely calling `_surface?.release()`:
```kotlin
/** Releases only the GL texture on the GL thread, leaving Surface cleanup to the coordinator. */
fun releaseTextureOnly() {
    if (textureId != 0) {
        GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
        textureId = 0
    }
}
```

### C. Update `AppScene.kt` (`app/src/main/java/com/daydreamvr/player/render/AppScene.kt`)
1. Replace `onVideoSurfaceReady: (Surface) -> Unit` in constructor with:
   ```kotlin
   private val onVideoSurfaceCreated: (Surface, Long, Surface?) -> Unit = { _, _, _ -> },
   private val onVideoSurfaceDestroyed: (Surface?, Long) -> Unit = { _, _ -> },
   ```
   (Provide default no-op lambdas so unit tests like `SceneRecenterYawTest` construct cleanly without mocks).
2. Track `private var videoGeneration: Long = 0L`.
3. In `onGlCreate()`:
   ```kotlin
   val oldSurface = if (created) runCatching { video.surface }.getOrNull() else null
   val gen = ++videoGeneration
   if (created) {
       listOfNotNull(serverList, browse, dock, settings, calibration, gamepadCal, hud, overlay).forEach { it.onGlDestroy() }
       cylinder.onGlDestroy()
       sphere.onGlDestroy()
       reticle.onGlDestroy()
       groundGrid.onGlDestroy()
       video.releaseTextureOnly()
   }

   serverList = ServerListScreen(PanelSurface(1024, 676), theme).also { it.onGlCreate() }
   browse = BrowseScreen(PanelSurface(BrowseLayout.WIDTH_PX, BrowseLayout.HEIGHT_PX), theme).also { it.onGlCreate() }
   dock = SystemDockScreen(PanelSurface(DockLayout.WIDTH_PX, DockLayout.HEIGHT_PX), theme).also { it.onGlCreate() }
   settings = SettingsScreen(PanelSurface(1024, 700), theme).also { it.onGlCreate() }
   calibration = CalibrationScreen(PanelSurface(1536, 1024), theme).also { it.onGlCreate() }
   gamepadCal = GamepadCalibrationScreen(PanelSurface(1024, 512), theme).also { it.onGlCreate() }
   hud = PlayerHud(PanelSurface(1280, 332), theme).also { it.onGlCreate() }
   overlay = OverlayRenderer(PanelSurface(1024, 668), theme).also { it.onGlCreate() }

   cylinder.onGlCreate()
   sphere.onGlCreate()
   video = VideoTexture()
   video.createOnGlThread()
   reticle.onGlCreate()
   groundGrid.onGlCreate()

   onVideoSurfaceCreated(video.surface, gen, oldSurface)
   ```
4. In `onGlDestroy()`:
   ```kotlin
   val gen = videoGeneration
   val surf = runCatching { video.surface }.getOrNull()
   onVideoSurfaceDestroyed(surf, gen)
   listOfNotNull(serverList, browse, dock, settings, calibration, gamepadCal, hud, overlay).forEach { it.onGlDestroy() }
   serverList = null
   browse = null
   dock = null
   settings = null
   calibration = null
   gamepadCal = null
   hud = null
   overlay = null
   cylinder.onGlDestroy()
   sphere.onGlDestroy()
   video.release()
   reticle.onGlDestroy()
   groundGrid.onGlDestroy()
   created = false
   ```

### D. Update `VrActivity.kt` (`app/src/main/java/com/daydreamvr/player/VrActivity.kt`)
1. Add property `private lateinit var surfaceCoordinator: VideoSurfaceCoordinator`.
2. In `initVr()`:
   Initialize `surfaceCoordinator`:
   ```kotlin
   surfaceCoordinator = VideoSurfaceCoordinator(player)
   ```
   Wire `AppScene`:
   ```kotlin
   scene = AppScene(
       stateProvider = { stateMachine.state.value },
       snapshotProvider = { player.snapshot.value },
       neckOffsetProvider = {
           if (stateMachine.state.value.settings.neckModelEnabled) container.deviceProfile.neckModelM else null
       },
       trackerCalibratedProvider = { headTracker.isCalibrated.value },
       onGazeTarget = { t -> runOnUiThread { stateMachine.dispatch(Event.GazeMoved(t)) } },
       thermalStatusProvider = {
           runCatching {
               (getSystemService(POWER_SERVICE) as android.os.PowerManager).currentThermalStatus
           }.getOrDefault(0)
       },
       thumbnailCacheProvider = { container.thumbnailCache },
       onVideoSurfaceCreated = surfaceCoordinator::onSurfaceCreated,
       onVideoSurfaceDestroyed = surfaceCoordinator::onSurfaceDestroyed,
   ).also { s ->
       s.onListWindowMeasured = { w -> runOnUiThread { stateMachine.dispatch(Event.ListWindowMeasured(w)) } }
   }
   ```
3. In `onDestroy()`:
   ```kotlin
   override fun onDestroy() {
       (application as PlayerApp).container.localMediaRepository.stopWatching()
       brightnessController.restoreBrightness()
       getSystemService(InputManager::class.java)
           .unregisterInputDeviceListener(inputDeviceListener)
       surfaceCoordinator.release()
       player.release()
       // Queue GL destruction to the GL thread; do NOT execute raw OpenGL calls on the main thread
       glSurfaceView.queueEvent {
           renderer.onGlDestroy()
       }
       super.onDestroy()
   }
   ```

### E. Unit Tests
Create `app/src/test/java/com/daydreamvr/player/render/VideoSurfaceCoordinatorTest.kt`:
1. `onSurfaceCreated marshals attach to main executor with surface and generation`
2. `onSurfaceCreated with old surface detaches player before releasing old surface`
3. `stale generation callbacks are discarded`
4. `onSurfaceDestroyed detaches player and releases surface`
5. `release marks coordinator released and detaches player`
Verify that `SceneRecenterYawTest` and all existing unit tests continue to pass.

---

## 3. Verification & Quality Gates
1. Run `./gradlew testDebugUnitTest --no-daemon` to ensure all unit tests pass cleanly across all modules.
2. Run `./gradlew assembleRelease --no-daemon` to verify release compilation and packaging.
3. Commit the changes cleanly with message:
   `fix(render): video surface lifecycle coordinator and GL thread safety (Astra C1)`
