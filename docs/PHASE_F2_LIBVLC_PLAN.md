# Phase F2 LibVLC Compatibility Engine Architecture & Implementation Plan

Target: `daydream-vr-player` (Arc VR Player)
Context: Gerbera DLNA streaming 8K HEVC MP4s with late `moov` atoms (e.g. 18.7 GB `2022.06.03*`) and non-standard/legacy containers (`.wmv`, `.asf`, `.rm`, etc.) fail in Media3 `DefaultExtractorsFactory` with `ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED` / `"Video container not supported by device decoder."`. LibVLC handles these demuxing layouts natively with low overhead and plays smoothly on Android.

---

## 1. Architecture Overview (Option B / Phase F2)

Per `docs/FORMAT_SUPPORT_PLAN.md` §5, §8.3, §8.4, §8.6:
1. **Engine Role Separation:**
   - **Primary:** `Media3` (vsync-aligned `VideoFrameReleaseHelper`, optimal VR frame pacing, low thermal overhead, hardware decode + F1 FFmpeg software audio).
   - **Compatibility Fallback:** `LibVLC` (`org.videolan.android:libvlc-all:3.6.5`). Zero-copy rendering via `IVLCVout` into the identical `android.view.Surface` backed by `GL_TEXTURE_EXTERNAL_OES`.
2. **Dynamic Engine Routing (`PlaybackEngineRouter`):**
   - Implements `VideoPlayer`.
   - Owns both `ExoVideoPlayer` and lazy `VlcVideoPlayer`.
   - Exposes seamless snapshot flow and handles automatic fallover when container/decoder failures occur.
   - Preserves state across engine switch: `currentPositionMs`, `itemKey`, `title`, `projection`, Surface attachment.
3. **Pre-Routing (`EnginePreRoute`):**
   - Routes hostile container extensions (`.wmv`, `.asf`, `.rm`, `.divx`) and MIME types (`video/x-ms-wmv`, etc.) directly to VLC without wasting a Media3 open.
4. **Failure Recovery (`FallbackPolicy`):**
   - On `ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED` or `ERROR_CODE_PARSING_CONTAINER_MALFORMED`:
     - If `VLC` has not been tried yet, emit `FallbackAction.SwitchEngine(PlaybackEngine.VLC)` instead of giving up.
     - Resume at the last attempted position.
5. **Memory & Lifecycle Safety:**
   - Single active engine at a time. The inactive engine is released to avoid holding concurrent `MediaCodec` hardware instances on Qualcomm/Pixel SoC.
   - Strict `detachViews()` before releasing `SurfaceTexture`.

---

## 2. Dependencies & Build Configuration

1. `gradle/libs.versions.toml`:
   - `libvlc = "3.6.5"`
   - `libvlc-all = { group = "org.videolan.android", name = "libvlc-all", version.ref = "libvlc" }`
2. `playback/build.gradle.kts`:
   - `implementation(libs.libvlc.all)`
3. `app/build.gradle.kts`:
   - Ensure `abiFilters` includes `arm64-v8a` (already configured in F1).
4. `app/proguard-rules.pro`:
   - Add keep rules for `org.videolan.libvlc.**` to preserve JNI callbacks and methods across R8 full-mode minification.

---

## 3. Detailed Component Design

### 3.1 Data Types & Interfaces (`playback/src/main/java/com/daydreamvr/playback/`)
- `PlaybackEngine.kt`:
  ```kotlin
  enum class PlaybackEngine { MEDIA3, VLC }
  interface PlaybackEngineStore {
      fun preferred(itemKey: String): PlaybackEngine?
      fun remember(itemKey: String, engine: PlaybackEngine)
  }
  class InMemoryPlaybackEngineStore : PlaybackEngineStore { ... }
  ```
- `EnginePreRoute.kt`:
  ```kotlin
  object EnginePreRoute {
      val MEDIA3_HOSTILE_EXTENSIONS = setOf("wmv", "asf", "rm", "rmvb", "ogm", "divx")
      val MEDIA3_HOSTILE_MIMES = setOf("video/x-ms-wmv", "video/x-ms-asf", "video/x-msvideo-ms", "application/vnd.rn-realmedia", "audio/x-ms-wma")
      fun decide(source: PlaybackSource): PlaybackEngine
  }
  ```
- `FallbackAction.kt` & `FallbackPolicy.kt`:
  - Add `data class SwitchEngine(val to: PlaybackEngine) : FallbackAction`
  - In `decide(...)`: accept `enginesTried: Set<PlaybackEngine> = setOf(PlaybackEngine.MEDIA3)`.
  - On `UnsupportedContainer` / `MalformedContainer` / `Unknown` ("Video container not supported"): switch to VLC if not tried.

### 3.2 `VlcVideoPlayer.kt`
- Implements `VideoPlayer`.
- Wraps `org.videolan.libvlc.LibVLC` and `org.videolan.libvlc.MediaPlayer`.
- Options:
  - `"--codec=mediacodec_ndk,all"` (try HW first, fallback to software)
  - `"--audio-time-stretch"`
  - `"--network-caching=3000"`
  - `"--file-caching=1500"`
  - `"--no-sub-autodetect-file"`
- `attach(surface)`:
  - `val vout = mp.vlcVout`
  - `vout.setVideoSurface(surface, null)`
  - `vout.setWindowSize(width, height)` (CRITICAL: default to 1920x1080 if not yet queried, update on layout event)
  - `vout.attachViews { ... }`
- State mapping:
  - `MediaPlayer.Event.Buffering` -> `PlaybackState.BUFFERING`
  - `MediaPlayer.Event.Playing` -> `PlaybackState.READY`, `isPlaying = true`
  - `MediaPlayer.Event.Paused` -> `PlaybackState.READY`, `isPlaying = false`
  - `MediaPlayer.Event.EndReached` -> `PlaybackState.ENDED`
  - `MediaPlayer.Event.EncounteredError` -> call `onFatalError` or emit failure.

### 3.3 `PlaybackEngineRouter.kt`
- Coordinates `media3: VideoPlayer` and `vlcFactory: () -> VideoPlayer`.
- Manages `currentEngine`, forwards `play()`, `pause()`, `playPause()`, `seekTo()`, `attach()`, `detach()`, `stop()`.
- Catches errors from Media3 and seamlessly transfers playback to VLC via `FallbackPolicy`.

### 3.4 Wire-up in `AppContainer.kt`
- Update `player: VideoPlayer` in `AppContainer` to use `PlaybackEngineRouter(media3 = ExoVideoPlayer(...), vlcFactory = { VlcVideoPlayer(...) })`.

---

## 4. Verification & Testing
1. Unit tests:
   - `EnginePreRouteTest`: verifies extension & MIME routing.
   - `FallbackPolicyTest`: verifies engine switching transitions.
   - `PlaybackEngineRouterTest`: verifies state forwarding and surface lifecycle handoffs.
2. Full build & verification:
   - `./gradlew testDebugUnitTest`
   - `./gradlew assembleRelease`
   - Verify signed release APK with `apksigner`.
