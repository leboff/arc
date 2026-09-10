# Arc VR Player — UI Polish, Transport Controls & Projection Architecture Plan

You are planning and executing Phase 2 UI polish, transport controls, and projection enhancements for Arc VR Player (`/root/daydream-vr-player`).

## Context & Objectives
The user tested the latest Daydream View build and requested 7 specific fixes and features:
1. **File List Text Size:** The text in `MediaListWidget.kt` needs to be significantly smaller and tighter (currently uses `Type.rowSubtitle` at 1.15° and looks bulky through the Daydream View lenses).
2. **Continuous File List Scrolling:** Scrolling through videos in list mode should be continuous (smooth per-item `scrollTop` windowing) rather than stepped 6-item page swaps (`gridPage = index / 6`).
3. **Continuous Seek Bar Scrubbing:** Direct gaze/click seek on the VR progress bar (`PlayerHud.kt` / `AppStateMachine.kt`) should be continuous based on exact horizontal fraction, rather than dividing the duration into 40 discrete steps.
4. **Player Transport Controls:** The player menu dock (`PlayerHud.kt` / `HudState.kt` / `AppStateMachine.kt`) must have dedicated `Play/Pause` toggle and `Next` / `Prev` track skip buttons.
5. **Projection Menu Scrolling Fix:** In `OverlayRenderer.kt`, selecting/scrolling through the projection menu currently resets or refuses to scroll up because `scrollWindowFor` recomputes `st` from `focus` on every frame rather than maintaining persistent `scrollTop`.
6. **Player Dock Clarity in VR SBS:** The player dock (`PlayerHud.kt`) is placed at `verticalOffsetM = -1.15f` and `distanceM = 2.40f`. In SBS, this puts it in the high-aberration lower periphery of the Daydream lenses. Raise the dock's vertical offset to comfort level (`-0.85f` or similar) and optimize readability.
7. **Fisheye Projection Support:** Implement true equidistant fisheye front dome projection modes (`FISHEYE_180_SBS`, `FISHEYE_190_SBS`, `FISHEYE_200_SBS`, `FISHEYE_220_SBS`) in `ProjectionMode.kt` and `SphereScreen.kt`, mapping circular fisheye lenses with accurate radial geometry ($r = f \cdot \theta$) rather than equirectangular cylindrical stretching.
8. **Under-Screen Video Artifact Fix:** Videos or scanlines sometimes bleed into the floor/bottom below the player cylinder. In `CylinderScreen.kt` / `VideoShaders.kt`, clamp UV bounds to avoid wrapping or edge-pixel repeating into the bottom void, and verify `GroundGridPolicy` behavior during player mode.

## Your Task (Planning Phase)
1. Inspect the relevant files:
   - `app/src/main/java/com/daydreamvr/player/screens/widgets/MediaListWidget.kt`
   - `app/src/main/java/com/daydreamvr/player/screens/PlayerHud.kt`
   - `app/src/main/java/com/daydreamvr/player/screens/OverlayRenderer.kt`
   - `app/src/main/java/com/daydreamvr/player/state/AppState.kt`
   - `app/src/main/java/com/daydreamvr/player/state/AppStateMachine.kt`
   - `app/src/main/java/com/daydreamvr/player/state/GazeTarget.kt`
   - `vrcore/src/main/java/com/daydreamvr/vrcore/render/ProjectionMode.kt`
   - `vrcore/src/main/java/com/daydreamvr/vrcore/render/SphereScreen.kt`
   - `vrcore/src/main/java/com/daydreamvr/vrcore/render/CylinderScreen.kt`
   - `vrcore/src/main/java/com/daydreamvr/vrcore/render/shaders/VideoShaders.kt`
2. Formulate a comprehensive, production-grade technical specification and implementation plan.
3. Write the plan to `/root/daydream-vr-player/docs/CODEX_ARC_UI_AND_PROJECTION_PLAN.md`.
4. Ensure the plan includes exact equations, state transitions, UI layout contracts, test coverage strategy, and backward compatibility invariants.
