# Codex Arc Implementation Instructions

Execute the implementation plan in `docs/CODEX_ARC_UI_AND_PROJECTION_PLAN.md` across `/root/daydream-vr-player`.

## Core Directives
1. Implement all items per the plan:
   - **Item 1: Compact file list typography** (smaller scoped tokens in `MediaListWidget.kt`).
   - **Item 2: Continuous file list scrolling** (single-item `listScrollTop` windowing in `BrowseFrame`).
   - **Item 3: Continuous seek bar scrub** (exact horizontal fraction hit testing in `PlayerHud.kt`).
   - **Item 4: Two-tier transport controls** (`[Prev] [Play/Pause] [Next]` on top tier, `[Back] [Projection] [Speed] [Screen size]` on bottom tier).
   - **Item 5: Projection chooser scrolling fix** (persistent `scrollTop` in `Overlay.ProjectionChooser`).
   - **Item 6: Raise and clarify player dock in VR SBS** (`verticalOffsetM = -0.85f`, contrast enhancements).
   - **Item 7: Equidistant fisheye SBS projections** (`FISHEYE_180_SBS` to `220` with equidistant polar ray mapping).
   - **Item 8: Under-screen floor artifact fix** (UV coordinate clamping in `VideoShaders.kt`).
2. Run `./gradlew testDebugUnitTest --no-daemon` to ensure all unit tests pass (and update or add tests for the new features).
3. Run `./gradlew assembleRelease --no-daemon` to ensure the release APK builds cleanly with R8 minification.
4. Report the final build status and test results.
