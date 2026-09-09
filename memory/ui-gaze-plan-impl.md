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

Progress (branch feat/ui-sheen-gaze):
- Step 1 DONE (commit): PanelMetrics, modelYawRad/yaw fix, retexture, aspect/yaw tests.
- Step 2 DONE: Theme §4.2 rewrite (old field names kept as aliases), Space/Radius/Type,
  TextMeasure, Surfaces, Icons.
- Steps 3-4 DONE (one commit): ListView rewrite (pure measureLayout + FontMetrics baselines +
  HitRegion), HitMap, PanelGeometry, GazeTarget, screen redesigns (ServerList pinned footer,
  Settings/Browse scroll), reduceGaze, AppState scroll fields, Event.GazeMoved/ListWindowMeasured.
- Deviation: Settings section headers (VIEWER/OPTICS/...) NOT implemented (kept flat rows) —
  ListView supports Entry.Header but interleaving breaks scrollTop=ROWS-index mapping. Backlog.
- NEXT: Step 5 gaze math (GazeRay, PanelRaycast, GazeStabilizer + tests), then Step 6 Reticle +
  Scene.update(pose) pipeline, Step 7 wiring reduceGaze into AppScene/VrActivity, Step 8 verify + docs.
- Theme back-compat aliases + Theme.panelWidthDegrees deprecated field still present; remove in cleanup.
