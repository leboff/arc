# Codex Fix: Continuous Timeline Seek & Remove Debug Overlays

You are fixing two specific issues in Arc VR Player (`/root/daydream-vr-player`):

## Issue 1: Seek Bar Continuous Gaze Hit Testing
### Root Cause
In `vrcore/.../ui/GazeStabilizer.kt`, the stabilizer compares `rawTarget == candidate` and `rawTarget == current` using data class equality (`equals()`).
Because `GazeTarget.HudTimeline(val fraction: Float)` carries a raw floating-point fraction, natural head-pose micro-jitter causes the fraction to differ slightly on every single frame (`0.50123f` vs `0.50129f`).
As a result, `rawTarget == candidate` was false on every frame, resetting `candidateAge` to 0. The stabilizer never stabilized, permanently returning `null`. This suppressed all timeline gaze hover, preview timestamps, and click-to-seek.

### Solution
1. In `vrcore/src/main/java/com/daydreamvr/vrcore/ui/GazeStabilizer.kt`:
   Allow customizing the control-identity comparator:
   ```kotlin
   class GazeStabilizer<T>(
       private val holdSeconds: Float = 0.06f,
       private val sameControl: (T?, T?) -> Boolean = { a, b -> a == b },
   ) {
       private var current: T? = null
       private var candidate: T? = null
       private var candidateAge = 0f
       private var seenCandidate = false

       fun update(rawTarget: T?, dtSeconds: Float): T? {
           // If already locked on a continuous control (like the timeline), update value immediately
           if (current != null && sameControl(current, rawTarget)) {
               current = rawTarget
               candidate = rawTarget
               candidateAge = 0f
               return current
           }
           if (seenCandidate && sameControl(rawTarget, candidate)) {
               candidate = rawTarget // keep latest continuous coordinate
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
   ```
2. In `app/src/main/java/com/daydreamvr/player/render/AppScene.kt`:
   Construct the stabilizer with a comparator that treats all `GazeTarget.HudTimeline` instances as the same control:
   ```kotlin
   private val stabilizer = GazeStabilizer<GazeTarget>(
       sameControl = { a, b ->
           if (a is GazeTarget.HudTimeline && b is GazeTarget.HudTimeline) true
           else a == b
       }
   )
   ```
3. In `app/src/main/java/com/daydreamvr/player/screens/PlayerHud.kt`:
   Ensure `hitTest(xPx, yPx)` computes timeline bounds robustly:
   ```kotlin
   override fun hitTest(xPx: Float, yPx: Float): GazeTarget? {
       val pad = contentLeft()
       val titleSize = metrics.px(Type.rowTitle.degrees)
       val barTop = pad + titleSize * 1.4f
       val barW = metrics.widthPx - pad * 2
       val barHitH = metrics.px(3.0f)
       val barHitTop = (barTop - metrics.px(0.8f)).coerceAtLeast(0f)
       if (xPx in (pad - metrics.px(0.35f))..(pad + barW + metrics.px(0.35f)) &&
           yPx in barHitTop..(barHitTop + barHitH)) {
           val fraction = ((xPx - pad) / barW).coerceIn(0f, 1f)
           return GazeTarget.HudTimeline(fraction)
       }
       return super.hitTest(xPx, yPx)
   }
   ```
   And publish the HitRegion in `PlayerHud.render` so `hitMap` also has the region registered.

## Issue 2: Remove Left/Right Eye Debug Panels
### Solution
In `app/src/main/java/com/daydreamvr/player/VrActivity.kt`:
- Remove `leftEyeText` and `rightEyeText` `TextView`s from `root.addView(...)`.
- Remove the coroutine collecting `overlay.text` in `wireFlows()`.
- Remove unused helper functions `overlayTextView()` and `eyeLayoutParams(...)`.
- Keep `glSurfaceView` as the sole fullscreen view in `root` so both eyes are completely clean.

## Verification
- Run `./gradlew testDebugUnitTest --no-daemon` to ensure all tests pass (update any test if needed).
- Run `./gradlew assembleRelease --no-daemon` to produce a verified release APK.
