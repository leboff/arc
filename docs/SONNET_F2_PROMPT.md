You are an elite Android systems engineer.
Read `/root/daydream-vr-player/docs/PHASE_F2_LIBVLC_PLAN.md` and `/root/daydream-vr-player/docs/FORMAT_SUPPORT_PLAN.md`.

Your objective is to implement Phase F2: LibVLC compatibility engine in `/root/daydream-vr-player`:
1. Add `org.videolan.android:libvlc-all:3.6.5` to `gradle/libs.versions.toml` and depend on it in `playback/build.gradle.kts`.
2. Add ProGuard keep rules for LibVLC in `app/proguard-rules.pro`.
3. Implement `PlaybackEngine` enum, `PlaybackEngineStore`, and `InMemoryPlaybackEngineStore` in `playback`.
4. Implement `EnginePreRoute` in `playback` with unit tests covering extensions and MIME types.
5. Update `FallbackPolicy` and `FallbackAction` to support `SwitchEngine(val to: PlaybackEngine)`. Update tests in `FallbackPolicyTest`.
6. Implement `VlcVideoPlayer` implementing `VideoPlayer` with full lifecycle, Surface attachment via `IVLCVout`, `setWindowSize`, state flow mirroring, and resource playback.
7. Implement `PlaybackEngineRouter` implementing `VideoPlayer` that routes between `ExoVideoPlayer` and `VlcVideoPlayer`, preserving Surface, position, and automatic failover.
8. Wire `PlaybackEngineRouter` in `AppContainer.kt`.
9. Ensure all unit tests pass with `./gradlew testDebugUnitTest --no-daemon`.

Do not break any existing tests or features. Be clean, idiomatic, and minimal.
