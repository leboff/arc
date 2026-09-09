# Prompt: Claude Opus Architectural Review & Plan Refinement

You are Claude Opus, the Principal Systems & VR Architect on Arc VR Player.
Your task is to conduct an authoritative, rigorous review of `docs/UI_REDESIGN_PLAN.md` and refine it into an executable, ironclad blueprint for Claude Sonnet (`docs/UI_REDESIGN_REVIEWED_PLAN.md`).

## 1. Context & Codebase
Arc is a Kotlin/OpenGL ES 3.0 VR video player for Android (Pixel 11 Pro, Google Daydream / Cardboard viewer, Bluetooth gamepad).
- Current repository state: `master` @ `ec34a48` (v0.2.5).
- UPnP discovery, browsing, and Media3 ExoPlayer playback are verified working on-device.
- The UI currently renders on offscreen Canvases uploaded to OpenGL textures, with 3D stereoscopic gaze raycasting and controller navigation (D-pad, bumpers, A/Confirm).

## 2. Review Tasks
1. Read `docs/UI_REDESIGN_PLAN.md`, `docs/ARCHITECTURE.md`, `docs/UI_GAZE_PLAN.md`, and relevant source files in `:app` and `:vrcore`.
2. Critique and refine:
   - **Ergonomics & Angular Metrics:** Verify panel width, curvature radius ($R = D = 2.30\text{m}$), field of view (~70° total span), comfortable eye vergence, and typography scales across the 3 columns (Left: Sources/Folders, Center: Media Grid, Right: Inspector) and Bottom Dock.
   - **Local Storage Engine:** Detail `MediaStore.Video.Media` query projection, bucket grouping (`BUCKET_DISPLAY_NAME`), permissions (`READ_MEDIA_VIDEO` for API 33+, `READ_EXTERNAL_STORAGE` for legacy), and hardware thumbnail extraction (`ContentResolver.loadThumbnail`) with thread-safe LRU caching.
   - **Unified Media Architecture:** Define clean Kotlin interfaces (`MediaSource`, `MediaNode.Folder`, `MediaNode.Video`) so `AppStateMachine` and `BrowseScreen` operate seamlessly over both UPnP DIDL items and local files.
   - **State Machine Transitions:** Specify events (`SelectSource`, `SelectFolder`, `SelectSort`, `PageNext`, `PagePrev`, `OverrideProjection`, `PlayVideo`), state reducers, and side-effects.
   - **Widget Hierarchy & Hit Regions:** Layout contracts for `SourceSidebarWidget`, `MediaGridWidget`, `MediaInspectorWidget`, and `SystemDockWidget`, ensuring exact bounding boxes for gaze hit-testing.
   - **Spatial Grounding:** Ground wireframe grid plane at $y = -1.2\text{m}$ in `AppScene` with horizon fade.
3. Write the authoritative, finalized plan to `docs/UI_REDESIGN_REVIEWED_PLAN.md` with numbered milestones, exact mathematical formulas, Kotlin contracts, and strict red-first acceptance tests.
4. When finished, commit `docs/UI_REDESIGN_REVIEWED_PLAN.md` to git with message: `docs: Opus architectural review and refined UI redesign plan`.
