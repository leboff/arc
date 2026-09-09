---
name: ui-gaze-plan-impl
description: Status of the UI Sheen / Layout Repair / Gaze Pointer implementation for daydream-vr-player
metadata:
  type: project
---

Implementing `docs/UI_GAZE_PLAN.md` (Opus 5 plan) on branch off `master`. Started 2026-09-08.

Follow §6 checklist steps 1–8. Each commit must stay green (`./gradlew test :app:assembleRelease :app:assembleDebug`).
Deviation from plan: Step 0 "red commit" is folded into the fixing step so every commit is green (invoker requires tests always green).

Core deliverables: PanelMetrics per-panel (kill Theme.panelWidthDegrees), pinned footer for ServerListScreen,
ListView rewrite w/ FontMetrics baselines, Theme/Surfaces/Icons glassmorphic restyle, GazeRay+PanelRaycast+
GazeStabilizer+HitMap+Reticle, reduceGaze, comprehensive tests.

Progress: starting Step 1.
