# Prompt: Claude Opus Format Support Plan for Arc VR Player

You are Claude Opus, the Principal Systems & Video Streaming Architect on Arc VR Player.
Your task is to author a comprehensive, authoritative architectural blueprint: `docs/FORMAT_SUPPORT_PLAN.md`.

## Context & Problem Statement
Arc is a VR video player for Android (Pixel 11 Pro, Daydream/Cardboard viewer, Media3 ExoPlayer + OpenGL ES 3.0).
Users are reporting:
1. "Container not supported error when trying to open a video. VLC plays it fine."
2. Local video playback fails because `ExoVideoPlayer` passes `OkHttpDataSource.Factory` directly into `DefaultMediaSourceFactory` without wrapping in `DefaultDataSource.Factory(context, httpFactory)`.
3. Audio/container codec limitations: standard Media3 ExoPlayer out-of-the-box relies exclusively on Android device `MediaCodec` hardware decoders (H.264, HEVC, VP9, AV1, AAC, MP3) and standard Java extractors. VLC for Android plays virtually any file because VLC bundles LibVLC / FFmpeg (complete software demuxing and decoding for MKV, AVI, WMV, FLV, TS, AC3, EAC3/Dolby Digital Plus, DTS, DTS-HD, TrueHD, Vorbis, Opus, VC-1, DivX, etc.).

## Plan Requirements in `docs/FORMAT_SUPPORT_PLAN.md`
1. **Executive Summary & Diagnostic Root-Cause Analysis**:
   - Local `content://` and `file://` URI failure mechanism in `ExoVideoPlayer` (HTTP-only DataSource).
   - Container extractor limitations vs codec decoding limitations in Media3.
   - Audio codec licensing and hardware gaps on Pixel/Android (AC-3, E-AC-3, DTS, TrueHD).
2. **Architecture Options & Comparative Trade-off Analysis**:
   - **Option A: Media3 + `media3-decoder-ffmpeg` (Official Extension)**:
     - Software audio decoding (AC3, EAC3, DTS, TrueHD, Opus, Vorbis, FLAC, ALAC).
     - Retains hardware-accelerated zero-copy video decoding directly to SurfaceTexture (`GL_TEXTURE_EXTERNAL_OES`) for 4K 60fps/90fps VR playback.
     - Integration complexity, build options (precompiled AAR vs NDK build), licensing (LGPL vs GPL).
   - **Option B: LibVLC Android (`org.videolan.android:libvlc-all`)**:
     - How LibVLC works on Android.
     - Video rendering to OpenGL: LibVLC vmem callbacks vs Surface / SurfaceTexture bridge.
     - Performance, battery, thermal, and 4K VR frame pacing implications compared to MediaCodec.
     - Architecture required to swap or wrap `VideoPlayer` interface with a LibVLC implementation.
   - **Option C: Hybrid Strategy**:
     - Primary engine: Media3 with `media3-decoder-ffmpeg` + `media3-extractor` (fast, native, hardware-accelerated zero-copy).
     - Fallback engine: LibVLC invoked when Media3 extractors or decoders throw unsupported container/codec error.
3. **Recommended Technical Path & Actionable Roadmap**:
   - Precise recommendation with clear rationale for a mobile VR headset player.
   - Detailed Gradle dependencies, repository coordinates, NDK/AAR setup, R8/ProGuard rules.
   - Kotlin code changes required across `:playback`, `:app`, and `:vrcore`.
   - Red-first test strategy and acceptance criteria.

Write out the complete, exhaustive document directly to `docs/FORMAT_SUPPORT_PLAN.md`.
