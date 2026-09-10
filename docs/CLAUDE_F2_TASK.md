# Phase F2 LibVLC Implementation Task

You are an expert Android systems engineer working on the Arc VR Player codebase in `/root/daydream-vr-player`.
Read `/root/daydream-vr-player/docs/PHASE_F2_LIBVLC_PLAN.md` and `/root/daydream-vr-player/docs/FORMAT_SUPPORT_PLAN.md`.

## Goals
Implement Phase F2 (LibVLC compatibility engine):
1. In `gradle/libs.versions.toml`:
   - Add `libvlc = "3.6.5"` under `[versions]`
   - Add `libvlc-all = { group = "org.videolan.android", name = "libvlc-all", version.ref = "libvlc" }` under `[libraries]`
2. In `playback/build.gradle.kts`:
   - Add `implementation(libs.libvlc.all)`
3. In `app/proguard-rules.pro`:
   - Add keep rules for LibVLC:
     ```proguard
     # ---------------------------------------------------------------------------
     # LibVLC (docs/FORMAT_SUPPORT_PLAN.md §5, §8.6)
     # ---------------------------------------------------------------------------
     -keep class org.videolan.libvlc.** { *; }
     -dontwarn org.videolan.libvlc.**
     -keepclasseswithmembernames class org.videolan.libvlc.** {
         native <methods>;
     }
     ```
4. In `playback/src/main/java/com/daydreamvr/playback/`:
   - `PlaybackEngine.kt`:
     - `enum class PlaybackEngine { MEDIA3, VLC }`
     - `interface PlaybackEngineStore { fun preferred(itemKey: String): PlaybackEngine?; fun remember(itemKey: String, engine: PlaybackEngine) }`
     - `class InMemoryPlaybackEngineStore : PlaybackEngineStore`
   - `EnginePreRoute.kt`:
     - `object EnginePreRoute`: defines hostile extensions (`wmv`, `asf`, `rm`, `rmvb`, `ogm`, `divx`) and mimes (`video/x-ms-wmv`, `video/x-ms-asf`, `video/x-msvideo-ms`, `application/vnd.rn-realmedia`, `application/vnd.rn-realmedia-vbr`, `audio/x-ms-wma`), with `fun decide(sourceUri: String, mimeType: String?): PlaybackEngine`
   - Update `PlaybackFailure.kt`:
     - Add `data class UnsupportedContainer(val hint: String? = null) : PlaybackFailure()`
     - Add `data object MalformedContainer : PlaybackFailure()`
   - Update `FallbackAction.kt` and `FallbackPolicy.kt`:
     - Add `data class SwitchEngine(val to: PlaybackEngine) : FallbackAction`
     - In `FallbackPolicy.decide(failure, attempt, remainingResources, enginesTried: Set<PlaybackEngine> = setOf(PlaybackEngine.MEDIA3))`
       - If `failure is PlaybackFailure.UnsupportedContainer || failure is PlaybackFailure.MalformedContainer`:
         - If `PlaybackEngine.VLC !in enginesTried` -> return `FallbackAction.SwitchEngine(PlaybackEngine.VLC)`
         - Else if remainingResources > 0 -> `FallbackAction.NextResource`
         - Else -> `FallbackAction.GiveUp(failure.userMessage)`
       - If `failure is PlaybackFailure.Unknown` and contains "container not supported" (case-insensitive):
         - If `PlaybackEngine.VLC !in enginesTried` -> return `FallbackAction.SwitchEngine(PlaybackEngine.VLC)`
   - In `vlc/VlcVideoPlayer.kt`:
     - Implement `VideoPlayer` wrapping `org.videolan.libvlc.LibVLC` and `org.videolan.libvlc.MediaPlayer`.
     - LibVLC options:
       - `"--codec=mediacodec_ndk,all"`
       - `"--audio-time-stretch"`
       - `"--network-caching=3000"`
       - `"--file-caching=1500"`
       - `"--no-sub-autodetect-file"`
     - Handle `attach(surface: Surface)`:
       - Attach surface to `mediaPlayer.vlcVout.setVideoSurface(surface, null)`
       - Call `mediaPlayer.vlcVout.setWindowSize(1920, 1080)` (or dynamic size)
       - Call `mediaPlayer.vlcVout.attachViews()`
     - Handle `detach()`:
       - Call `mediaPlayer.vlcVout.detachViews()`
     - Handle events from `MediaPlayer.setEventListener`:
       - Track state (Buffering, Playing, Paused, EndReached, EncounteredError)
       - Map to `PlaybackSnapshot` StateFlow
       - Track time & position
   - In `PlaybackEngineRouter.kt`:
     - Implements `VideoPlayer`
     - Constructor: `(private val media3: VideoPlayer, private val vlcFactory: () -> VideoPlayer, private val engineStore: PlaybackEngineStore = InMemoryPlaybackEngineStore(), private val onFatalError: (String) -> Unit = {})`
     - Routes playback according to `EnginePreRoute`, `engineStore`, and runtime failover (`SwitchEngine`).
     - Preserves Surface across engine switch and keeps StateFlow synchronized.
5. In `app/src/main/java/com/daydreamvr/player/di/AppContainer.kt`:
   - Wrap player creation so `container.player` is `PlaybackEngineRouter(media3 = ExoVideoPlayer(...), vlcFactory = { VlcVideoPlayer(...) })`.
6. Add unit tests:
   - `playback/src/test/java/com/daydreamvr/playback/EnginePreRouteTest.kt`
   - Update `FallbackPolicyTest.kt` for `SwitchEngine`.
7. Verify all unit tests pass with `./gradlew testDebugUnitTest --no-daemon`.

Commit changes per milestone or as a clean logical commit. Keep existing tests green!
