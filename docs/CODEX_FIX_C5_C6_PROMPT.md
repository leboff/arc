# Codex Fix Task (t_a7eb9ddf): Fix C5 & C6 — Resume Ownership, Persistence Flush, & Recovery Retries

You are fixing findings **C5** and **C6** from Astra's architectural review (`docs/ASTRA_ARCHITECTURAL_REVIEW.md`) in Arc VR Player (`/root/daydream-vr-player`).

---

## 1. Problems & Root Causes

### Issue 1: Competing Resume Ownership Overrides Explicit User Choices (C5)
- In `app/src/main/java/com/daydreamvr/player/state/EffectRunner.kt`, the start position and resume policy are resolved when constructing `PlayRequest`:
  - If the user clicks "Play from start", `skipResumeCheck = true` and `startAtMs = 0L`.
  - If the user confirms a resume prompt, `startAtMs` is set to the saved position.
- However, both engines independently query `resumeStore` and coerce `startAtMs`:
  - `ExoVideoPlayer.kt:97`: `loadCurrentResource(request.startAtMs.coerceAtLeast(resumeStartFor(request)))`
  - `vlc/VlcVideoPlayer.kt:88`: `val startAt = request.startAtMs.coerceAtLeast(resumeStartFor(request))`
- As a result, when the user explicitly clicks "Play from start", both engines override the user's choice and resume from the stored position anyway!
- **Fix:** Engines must strictly honor `request.startAtMs`. `EffectRunner` owns resume policy resolution; engines must not override it.

### Issue 2: Dirty Snapshot Emissions Corrupt Next Item's Resume Position (C5)
- In `ExoVideoPlayer.kt:217` and `VlcVideoPlayer.kt:109`:
  When starting a new item, `_snapshot.value = _snapshot.value.copy(itemKey = req.itemKey, title = req.title, ...)` updates only `itemKey` and `title`, while retaining the OLD item's `positionMs`, `durationMs`, and dimensions.
- `EffectRunner.start()` collects `player.snapshot` and puts into `resumeStore` whenever `durationMs > 0L`.
- Because the old item's positive `durationMs` and `positionMs` leak under the new `itemKey`, the new video's resume state is immediately corrupted with the previous video's progress.
- **Fix:** Atomically reset item-specific snapshot fields (`positionMs = request.startAtMs`, `durationMs = 0L`, `bufferedMs = 0L`, `videoWidth = 0`, `videoHeight = 0`, `audioTracks = emptyList()`, `subtitleTracks = emptyList()`, `activeCues = emptyList()`, `failure = null`) when starting a new video.

### Issue 3: Failover Jump Forward on Backward Seek (C5 & C6)
- In `PlaybackEngineRouter.kt:172`:
  `val resumeAt = maxOf(from.positionMs, request.startAtMs)`
  If a user starts at 10:00, seeks backward to 02:00, and then an engine failover occurs, `maxOf` jumps forward back to 10:00!
- Router failover also ignores the current playback speed, audio/subtitle track selection, and pause intent.
- **Fix:**
  - Use `val resumeAt = if (from.positionMs > 0L) from.positionMs else request.startAtMs`.
  - Preserve playback speed (`from.speed`), audio track, subtitle track, and paused state (`if (!from.isPlaying) active.pause()`).

### Issue 4: Resume Persistence Gaps (C5)
- `resumeStore` lives in memory; disk persistence via `settingsStore.saveResume()` was only called on explicit `stopPlayback()` or `quit()`.
- If the headset is removed, the app is backgrounded, or Android terminates the activity, all progress since the last stop is lost.
- **Fix:**
  - Add `flushResume()` to `EffectRunner` which writes `resumeStore.all()` to `settingsStore`.
  - Periodically flush resume to disk every 10 seconds during active playback.
  - Call `effectRunner.flushResume()` in `VrActivity.onPause()` and `VrActivity.onDestroy()`.

### Issue 5: Zombie Retry Callbacks & Competing Recovery (C6)
- In `ExoVideoPlayer.kt:245` and `259`:
  Error retries use unmanaged `main.postDelayed({ loadCurrentResource(...) }, delayMs)`.
  If playback stops, pauses, or switches to a new video while a retry is pending, the delayed callback runs anyway, reloading the old resource, setting `playWhenReady = true`, and hijacking playback.
- Furthermore, `RetrySameAfter` publishes `failure = failure` to the snapshot during transient retries. This triggers `AppStateMachine` to display an error overlay dialog on the screen during normal background reconnection!
- **Fix:**
  - Track `pendingRetryRunnable: Runnable?`. Cancel it on `play()`, `pause()`, `stop()`, and `release()`.
  - During `RetrySameAfter` and `RefreshUrlFromServer`, set `_snapshot.value = _snapshot.value.copy(isBuffering = true, failure = null)` so transient reconnection does not flash error dialogs. Only publish `failure = failure` on terminal `GiveUp` or when failover is needed.
  - On `UnsupportedContainer` or `MalformedContainer` (or "container not supported" unknown errors), do not silently advance resources in ExoPlayer; publish the failure so `PlaybackEngineRouter` can switch to VLC for that resource.

---

## 2. Implementation Steps

### A. `playback/src/main/java/com/daydreamvr/playback/ExoVideoPlayer.kt`
1. Remove `resumeStartFor(req)` from `play(request: PlayRequest)`:
   Change `loadCurrentResource(request.startAtMs.coerceAtLeast(resumeStartFor(request)))` to:
   `loadCurrentResource(request.startAtMs)`
2. In `play(request: PlayRequest)`:
   - Cancel pending retry: `cancelPendingRetry()`
   - Atomically reset item-specific snapshot fields:
     ```kotlin
     _snapshot.value = PlaybackSnapshot(
         itemKey = request.itemKey,
         title = request.title,
         state = PlaybackState.BUFFERING,
         isBuffering = true,
         positionMs = request.startAtMs,
         durationMs = 0L,
         bufferedMs = 0L,
         resourceIndex = 0,
     )
     ```
3. Add retry callback management:
   ```kotlin
   private var pendingRetry: Runnable? = null

   private fun cancelPendingRetry() {
       pendingRetry?.let { main.removeCallbacks(it) }
       pendingRetry = null
   }
   ```
   Call `cancelPendingRetry()` in:
   - `play()`
   - `pause()`
   - `stop()`
   - `release()`
4. In `handleError(error: PlaybackException)`:
   - If `failure is PlaybackFailure.UnsupportedContainer || failure is PlaybackFailure.MalformedContainer || (failure is PlaybackFailure.Unknown && failure.detail.contains("container not supported", ignoreCase = true))`:
     `cancelPendingRetry()`
     `_snapshot.value = _snapshot.value.copy(failure = failure)`
     return
   - In `FallbackAction.RetrySameAfter`:
     ```kotlin
     cancelPendingRetry()
     retryAttempt++
     val at = player?.currentPosition ?: 0L
     _snapshot.value = _snapshot.value.copy(isBuffering = true, failure = null)
     val runnable = Runnable {
         pendingRetry = null
         loadCurrentResource(at)
     }
     pendingRetry = runnable
     main.postDelayed(runnable, action.delayMs)
     ```
   - In `FallbackAction.RefreshUrlFromServer`:
     ```kotlin
     cancelPendingRetry()
     refreshedOnce = true
     retryAttempt++
     val at = player?.currentPosition ?: 0L
     _snapshot.value = _snapshot.value.copy(isBuffering = true, failure = null)
     val runnable = Runnable {
         pendingRetry = null
         loadCurrentResource(at)
     }
     pendingRetry = runnable
     main.postDelayed(runnable, 500L)
     ```
5. In `stop()`:
   Call `cancelPendingRetry()` and remove `ticker` callbacks: `main.removeCallbacks(ticker)`.

### B. `playback/src/main/java/com/daydreamvr/playback/vlc/VlcVideoPlayer.kt`
1. In `play(request: PlayRequest)`:
   Change `val startAt = request.startAtMs.coerceAtLeast(resumeStartFor(request))` to:
   `val startAt = request.startAtMs`
2. Atomically reset snapshot on `play()`:
   ```kotlin
   state = PlaybackState.BUFFERING
   positionMs = startAt
   durationMs = 0L
   isPlayingNow = false
   _snapshot.value = PlaybackSnapshot(
       itemKey = request.itemKey,
       title = request.title,
       state = PlaybackState.BUFFERING,
       isBuffering = true,
       positionMs = startAt,
       durationMs = 0L,
       bufferedMs = 0L,
       resourceIndex = 0,
   )
   ```
3. In `stop()` and `release()`:
   Remove ticker callbacks: `main.removeCallbacks(ticker)`.

### C. `playback/src/main/java/com/daydreamvr/playback/PlaybackEngineRouter.kt`
In `switchTo(to: PlaybackEngine, from: PlaybackSnapshot)`:
```kotlin
    private fun switchTo(to: PlaybackEngine, from: PlaybackSnapshot) {
        val request = currentRequest ?: return
        switching = true
        mirrorJob?.cancel()
        val resumeAt = if (from.positionMs > 0L) from.positionMs else request.startAtMs
        startOn(to, request, resumeAt)
        if (from.speed != 1f) {
            active.setSpeed(from.speed)
        }
        from.audioTracks.firstOrNull { it.isSelected }?.id?.let {
            active.selectAudioTrack(it)
        }
        from.subtitleTracks.firstOrNull { it.isSelected }?.id?.let {
            active.selectSubtitleTrack(it)
        }
        if (!from.isPlaying && from.state != PlaybackState.IDLE) {
            active.pause()
        }
        switching = false
    }
```

### D. `app/src/main/java/com/daydreamvr/player/state/EffectRunner.kt`
1. Add `flushResume()`:
   ```kotlin
   fun flushResume() {
       val entries = resumeStore.all()
       scope.launch(Dispatchers.IO) {
           runCatching { settingsStore.saveResume(entries) }
       }
   }
   ```
2. In `start()` when collecting `player.snapshot`:
   ```kotlin
   private var lastResumeSaveMs = 0L
   ```
   In snapshot collector:
   ```kotlin
   if (snap.durationMs > 0L) {
       val key = snap.itemKey ?: return@collect
       resumeStore.put(key, snap.positionMs, snap.durationMs, System.currentTimeMillis())
       val now = System.currentTimeMillis()
       if (now - lastResumeSaveMs >= RESUME_SAVE_INTERVAL_MS) {
           lastResumeSaveMs = now
           flushResume()
       }
   }
   ```
   With `private const val RESUME_SAVE_INTERVAL_MS = 10_000L` in companion object.

### E. `app/src/main/java/com/daydreamvr/player/VrActivity.kt`
In `onPause()`:
Call `effectRunner.flushResume()`.
In `onDestroy()`:
Call `effectRunner.flushResume()`.

### F. Tests
1. `playback/src/test/java/com/daydreamvr/playback/PlaybackEngineRouterTest.kt`:
   - Add test `failoverPreservesSeekBackwardPositionAndState`:
     Start with request at `startAtMs = 30_000L`.
     Emit snapshot where user seeked backward to `positionMs = 10_000L`, `speed = 1.5f`, `isPlaying = false`, and error is `UnsupportedContainer`.
     Verify VLC starts at `10_000L` (NOT `30_000L`), and is paused with speed `1.5f`.
2. Add tests in `app/src/test/java/com/daydreamvr/player/state/EffectRunnerLocalMediaTest.kt` or dedicated test verifying `flushResume()`.

---

## 3. Verification & Quality Gates
1. Run `./gradlew testDebugUnitTest --no-daemon` to ensure all unit tests pass cleanly across all modules.
2. Run `./gradlew assembleRelease --no-daemon` to verify release compilation and packaging.
3. Commit the changes cleanly with message:
   `fix(playback): resume ownership, persistence flush, and zombie retry cancellation (Astra C5, C6)`
