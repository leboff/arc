# Codex Fix Task (t_d4156f50): Fix C2 — Local Media Browsing & Lifecycle Wiring

You are fixing finding **C2** from Astra's architectural review (`docs/ASTRA_ARCHITECTURAL_REVIEW.md`) in Arc VR Player (`/root/daydream-vr-player`).

## 1. Problem & Root Cause (Astra Finding C2)
1. **Unwired Local Media Loader:**
   - In `app/src/main/java/com/daydreamvr/player/state/EffectRunner.kt:93–97`, `localMediaLoader` is a nullable property `var localMediaLoader: (suspend () -> Event)? = null` that is **never assigned anywhere in the application**.
   - When `Effect.LoadLocalMedia` is handled, `loadLocalMedia()` does `val loader = localMediaLoader ?: return` and silently does nothing.
   - Even if invoked, `runCatching { loader() }.onSuccess(dispatch)` lacks an `onFailure` branch, meaning any exception or failure leaves the UI stuck in a perpetual loading spinner.
2. **Permission State Disconnected in VR:**
   - `BrowseFocus.kt:47` defaults local storage permission to `DENIED`.
   - `SetupActivity` requests permission and updates its 2D button, but `VrActivity` **never checks or dispatches `Event.LocalPermissionChanged`** to the state machine.
   - Consequently, in VR, Arc believes permission is permanently `DENIED`. Selecting the "Device" source in the sidebar displays an error asking the user to take the headset off and grant permission, even if permission was already granted!
3. **Repository Observer Unwired:**
   - `AppContainer` constructs `LocalMediaRepository(appContext, appScope)`, but its `onChanged` callback is never wired to `Event.LocalMediaChanged`, and `startWatching()` / `stopWatching()` are never called in `VrActivity`'s lifecycle.

---

## 2. Requirements & Implementation Plan

### A. `LocalMediaRepository.kt` (`app/src/main/java/com/daydreamvr/player/media/local/LocalMediaRepository.kt`)
1. Make `onChanged` settable after creation:
   Change `private val onChanged: () -> Unit = {}` to `var onChanged: () -> Unit = {}`.
2. Ensure `startWatching()` and `stopWatching()` are safe against runtime security/observer exceptions:
   ```kotlin
   fun startWatching() {
       if (observer != null) return
       val handler = Handler(Looper.getMainLooper())
       val obs = object : ContentObserver(handler) {
           private var scheduled = false
           override fun onChange(selfChange: Boolean) {
               if (scheduled) return
               scheduled = true
               handler.postDelayed({
                   scheduled = false
                   scope.launch { onChanged() }
               }, DEBOUNCE_MS)
           }
       }
       runCatching {
           resolver.registerContentObserver(collection, true, obs)
           observer = obs
       }.onFailure {
           android.util.Log.w("LocalMediaRepository", "Failed to register content observer", it)
       }
   }

   fun stopWatching() {
       observer?.let { obs ->
           runCatching { resolver.unregisterContentObserver(obs) }
       }
       observer = null
       pendingSignal?.cancel()
   }
   ```

### B. `EffectRunner.kt` (`app/src/main/java/com/daydreamvr/player/state/EffectRunner.kt`)
1. Make `localMediaLoader` a required dependency (or constructor param):
   ```kotlin
   class EffectRunner(
       private val directory: MediaServerDirectory,
       private val contentDirectory: ContentDirectoryClient,
       private val player: VideoPlayer,
       private val decoderCaps: () -> Set<DecoderCap>,
       private val resumeStore: ResumeStore,
       private val serverStore: ServerStore,
       private val settingsStore: SettingsStore,
       private val scope: CoroutineScope,
       private val dispatch: (Event) -> Unit,
       private val onRecenter: () -> Unit,
       private val onApplySettings: (Settings) -> Unit = {},
       private val onQuit: () -> Unit = {},
       var localMediaLoader: suspend () -> Event = { Event.LocalMediaFailed("Local media not configured") },
   )
   ```
2. In `loadLocalMedia()`:
   Ensure both success and failure are cleanly dispatched:
   ```kotlin
   private fun loadLocalMedia() {
       scope.launch {
           runCatching { localMediaLoader() }.fold(
               onSuccess = { event -> dispatch(event) },
               onFailure = { err -> dispatch(Event.LocalMediaFailed(err.message ?: "Failed to read local media")) },
           )
       }
   }
   ```

### C. `VrActivity.kt` (`app/src/main/java/com/daydreamvr/player/VrActivity.kt`)
1. When instantiating `effectRunner` in `onCreate()`:
   Pass `localMediaLoader`:
   ```kotlin
   localMediaLoader = {
       container.localMediaRepository.load().fold(
           onSuccess = { lib -> Event.LocalMediaLoaded(lib.folders, lib.byFolder) },
           onFailure = { err -> Event.LocalMediaFailed(err.message ?: "Failed to scan device videos") },
       )
   },
   ```
2. In `onCreate()`:
   - Wire repository change callback:
     ```kotlin
     container.localMediaRepository.onChanged = {
         runOnUiThread { stateMachine.dispatch(Event.LocalMediaChanged) }
     }
     ```
   - Dispatch initial permission status:
     ```kotlin
     val initialGrant = com.daydreamvr.player.media.local.MediaPermission.status(this)
     stateMachine.dispatch(Event.LocalPermissionChanged(initialGrant))
     ```
3. In `onResume()`:
   - Query current permission status:
     ```kotlin
     val currentGrant = com.daydreamvr.player.media.local.MediaPermission.status(this)
     stateMachine.dispatch(Event.LocalPermissionChanged(currentGrant))
     if (currentGrant != com.daydreamvr.player.media.local.MediaPermission.Grant.DENIED) {
         container.localMediaRepository.startWatching()
     }
     ```
4. In `onPause()`:
   - Stop watching:
     ```kotlin
     container.localMediaRepository.stopWatching()
     ```
5. In `onDestroy()`:
   - Stop watching:
     ```kotlin
     container.localMediaRepository.stopWatching()
     ```

### D. `AppStateMachine.kt` (`app/src/main/java/com/daydreamvr/player/state/AppStateMachine.kt`)
In `Event.LocalPermissionChanged`:
When permission transitions from `DENIED` to granted (`FULL` or `PARTIAL`):
If the user is currently viewing `MediaSource.Local.id` and has an error displayed, clear the error, switch to `loading = true`, and emit `listOf(Effect.LoadLocalMedia)`:
```kotlin
            is Event.LocalPermissionChanged -> {
                val wasDenied = state.sources.localPermission == MediaPermission.Grant.DENIED
                val isNowGranted = event.grant != MediaPermission.Grant.DENIED
                val nextState = state.copy(sources = state.sources.copy(localPermission = event.grant))
                val top = nextState.browse.top
                if (wasDenied && isNowGranted && nextState.sources.selectedId == MediaSource.Local.id && top != null) {
                    nextState.copy(
                        browse = nextState.browse.replaceTop(top.copy(loading = true, error = null)),
                    ) to listOf(Effect.LoadLocalMedia)
                } else {
                    nextState to noFx()
                }
            }
```

### E. Unit Tests
1. In `app/src/test/java/com/daydreamvr/player/state/LocalBrowseTest.kt`:
   - Add test: `permissionChangeFromDeniedToGrantedTriggersLoadLocalMediaIfViewingLocalSource`
     - Start driver with `DENIED`. Select source "local". Verify frame has error.
     - Send `Event.LocalPermissionChanged(Grant.FULL)`.
     - Verify frame has `loading = true`, `error = null`, and `Effect.LoadLocalMedia` was emitted.
   - Add test: `localMediaChangedTriggersLoadLocalMediaIfViewingLocalSource`
     - Verify `Event.LocalMediaChanged` triggers `Effect.LoadLocalMedia` when `selectedId == "local"`.
2. Add a unit test for `EffectRunner` (e.g. `EffectRunnerLocalMediaTest.kt` in `app/src/test/java/com/daydreamvr/player/state/`):
   - Test that handling `Effect.LoadLocalMedia` calls `localMediaLoader` and dispatches the resulting `Event.LocalMediaLoaded`.
   - Test that if `localMediaLoader` throws or returns failure, `Event.LocalMediaFailed` is dispatched.

---

## 3. Verification & Quality Gates
1. Run `./gradlew testDebugUnitTest --no-daemon` to ensure all unit tests pass cleanly across all modules.
2. Run `./gradlew assembleRelease --no-daemon` to verify release compilation and packaging.
3. Commit the changes cleanly with message:
   `fix(media): wire local media repository, permission lifecycle, and effect runner loader (Astra C2)`
