# Prompt: Claude Sonnet — Playback Crash Guard, Navigation Fix & PLAY'A 3-Column UI Overhaul

You are Claude Sonnet, the Principal Implementation Engineer on Arc VR Player.
Your task is to fix the playback crash / format / navigation blockers and finish the PLAY'A VR 3-column UI redesign according to `docs/UI_REDESIGN_REVIEWED_PLAN.md`.

## Context & Urgent Issues
1. **Container / Format error on UPnP stream**:
   - In `playback/src/main/java/com/daydreamvr/playback/ExoVideoPlayer.kt:195`, `MediaItem.fromUri(...)` is built without `mimeType`. Extensionless DLNA URLs from Gerbera fail byte sniffing.
   - Also, `ExoVideoPlayer` uses raw `OkHttpDataSource.Factory(OkHttpClient())` instead of `DefaultDataSource.Factory(context, httpFactory)`, breaking local files.
   - `classify()` does not map `ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED` or `ERROR_CODE_PARSING_CONTAINER_MALFORMED`.
2. **Playback Crash back to "Enter VR" (SetupActivity)**:
   - `SetupActivity` is the root activity. A crash back to it during playback means an uncaught exception occurred on the GL thread or in the player.
   - `video.updateIfDirty()` in `AppScene.update()` must be wrapped in `try/catch` so a surface/decoder exception never terminates the GL thread.
   - Crash logging must persist stack traces to `File(filesDir, "last_crash.txt")` in `PlayerApp.kt`, and `SetupActivity.kt` must display the crash log if present.
3. **No way to exit playback back to the video list**:
   - In `AppStateMachine.kt:426-440` (`reducePlayerInput`), Cancel (B button) currently toggles HUD visibility on/off in an endless loop.
   - Pressing Cancel (B) when the HUD is visible MUST call `leavePlayer(state)` to return to `VrScreen.BROWSE`.
   - Add `"Back to List"` as a control chip in `HudState.CONTROLS` so gaze + A-button can also exit playback.
4. **PLAY'A VR UI redesign was incomplete**:
   - Milestone 4 (State Machine), Milestone 5 (Widgets: `SourceSidebarWidget`, `MediaGridWidget`, `MediaInspectorWidget`, `SystemDockWidget`), and Milestone 8 (Wiring into `BrowseScreen.kt` and `AppScene.kt`) must be implemented to replace the legacy 1-column list view.

---

## Detailed Implementation Tasks

### Part 1: Playback Stability, Mime-Type & Crash Handling
1. **`playback/src/main/java/com/daydreamvr/playback/ExoVideoPlayer.kt`**:
   - Wrap DataSource:
     ```kotlin
     val httpFactory = OkHttpDataSource.Factory(OkHttpClient())
     val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(context, httpFactory)
     ...
     .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
     ```
   - Set MIME type on `MediaItem` in `loadCurrentResource()`:
     ```kotlin
     val item = MediaItem.Builder()
         .setUri(resource.uri.toString())
         .apply {
             val mime = resource.mimeType
             if (!mime.isNullOrBlank() && mime != "*") {
                 setMimeType(mime)
             }
         }
         .build()
     p.setMediaItem(item, positionMs.coerceAtLeast(0L))
     ```
   - In `classify(error: PlaybackException)`:
     Handle `PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED` -> `PlaybackFailure.Unknown("Video container not supported by device decoder.")`.
     Handle `PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED` -> `PlaybackFailure.Unknown("Video stream or container is corrupted.")`.
2. **`app/src/main/java/com/daydreamvr/player/render/AppScene.kt`**:
   - In `update()` around `video.updateIfDirty()`:
     ```kotlin
     if (state.screen == VrScreen.PLAYER) {
         if (snap.videoWidth > 0 && snap.videoHeight > 0) {
             video.setBufferSize(snap.videoWidth, snap.videoHeight)
             val aspect = snap.dimensions.displayAspect
             if (aspect > 0f) cylinder.setAspect(aspect)
         }
         runCatching {
             video.updateIfDirty()
         }.onFailure { t ->
             android.util.Log.e("AppScene", "Error updating video texture frame", t)
         }
     }
     ```
3. **`app/src/main/java/com/daydreamvr/player/PlayerApp.kt` & `SetupActivity.kt`**:
   - In `PlayerApp.kt`: in `uncaughtExceptionHandler`, write the timestamp and stack trace to `File(filesDir, "last_crash.txt")`.
   - In `SetupActivity.kt`: on `onResume()`, check `File(filesDir, "last_crash.txt")`. If exists and > 0 bytes, show a red error banner with the crash text and a button to "Clear Crash Log".

### Part 2: Player Navigation & Escape Hatch
1. **`app/src/main/java/com/daydreamvr/player/state/AppStateMachine.kt`**:
   - In `reducePlayerInput`:
     ```kotlin
     if (!hud.visible) {
         val shown = state.copy(hud = hud.copy(visible = true, lastInputAtMs = state.nowMs))
         return when (action) {
             is InputAction.Confirm -> shown to noFx()
             is InputAction.Cancel -> shown to noFx()
             else -> applyPlayerAction(shown, action)
         }
     }
     return when (action) {
         is InputAction.Cancel -> leavePlayer(state)
         else -> applyPlayerAction(state.copy(hud = hud.copy(lastInputAtMs = state.nowMs)), action)
     }
     ```
2. **`app/src/main/java/com/daydreamvr/player/state/AppState.kt` & `PlayerHud.kt`**:
   - Update `HudState.CONTROLS`:
     ```kotlin
     val CONTROLS = listOf("Back", "Projection", "Speed", "Screen size")
     ```
   - In `AppStateMachine.kt` (`activateHudControl`):
     ```kotlin
     when (HudState.CONTROLS.getOrNull(state.hud.focusIndex)) {
         "Back" -> leavePlayer(state)
         "Speed" -> ...
         "Projection" -> ...
         else -> state to noFx()
     }
     ```
   - Update any tests in `:app` that check `HudState.CONTROLS`.

### Part 3: PLAY'A 3-Column UI & System Dock
Implement according to `docs/UI_REDESIGN_REVIEWED_PLAN.md`:
1. **Widgets in `app/src/main/java/com/daydreamvr/player/screens/widgets/`**:
   - `SourceSidebarWidget`:
     - Renders sources (Device Storage, UPnP Servers) and folder tree with badges.
     - Bounding boxes for gaze hit-testing (`HitRegion`).
   - `MediaGridWidget`:
     - 2×3 widescreen 16:9 card grid.
     - Asynchronous thumbnail rendering via `ThumbnailCache`.
     - Badges: duration pill, format tags (VR 180, 3D SBS, 4K, MP4), title text.
     - Focused card highlight (`0xFF00E5FF` glassmorphic glow / accent).
     - Page navigation footer (`< Prev`, `Page X of Y`, `Next >`).
   - `MediaInspectorWidget`:
     - High-res poster preview frame.
     - Technical metadata: resolution, duration, format.
     - Projection mode selector (`2D`, `SBS`, `VR 180`, `VR 360`).
     - Big `PLAY VIDEO` and `RESUME` action buttons.
   - `SystemDockWidget`:
     - Floating capsule at $y = -0.65\text{m}$, $R = 2.05\text{m}$.
     - Actions: Recenter, Settings, Exit to 2D Lobby.
2. **`app/src/main/java/com/daydreamvr/player/screens/BrowseScreen.kt`**:
   - Replace the legacy 1-column list with the 3-column layout composing `SourceSidebarWidget`, `MediaGridWidget`, and `MediaInspectorWidget`.
   - Wire input actions (D-pad/stick navigation across columns: Left column <-> Center grid <-> Right inspector, and down to Dock).
   - Wire gaze hit regions so looking at cards, sources, inspector buttons, or dock items highlights them.
3. **`app/src/main/java/com/daydreamvr/player/state/EffectRunner.kt`**:
   - Ensure playing local `MediaNode.Video` builds a `PlayRequest` that `player.play()` can execute.

### Part 4: Verification & Release
1. Run `./gradlew testDebugUnitTest --no-daemon` and fix any broken tests.
2. Build `./gradlew assembleRelease --no-daemon`.
3. Sign the APK with `/root/android-sdk/build-tools/35.0.0/apksigner`:
   ```bash
   /root/android-sdk/build-tools/35.0.0/apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android --key-pass pass:android --out /root/Arc-v0.3.1-release.apk app/build/outputs/apk/release/app-release-unsigned.apk
   ```
4. Commit changes with clear commit messages.
