# Architectural Review: Arc VR Player — Full Codebase Audit

## Your Role

You are performing a **read-only architectural review** of the Arc VR Player codebase (Daydream VR Android media player, Kotlin/OpenGL ES). This is NOT an implementation task — do not write, edit, or modify any files. Your job is to think deeply about structure, coupling, technical debt, and risk, and produce a written report.

## Context

Arc is a from-scratch Daydream VR video player for Android, built module-by-module over an intense multi-day sprint (v0.1.0 → v0.9.2, ~24 kanban cards) by a rotating cast of Codex/Claude agents (`gpt-5.6-luna`, `gpt-5.6-terra`, `gpt-6-astra`, Claude Sonnet/Opus) working somewhat independently milestone-to-milestone. It plays local media, UPnP/DLNA streams (Gerbera server), and renders stereo VR180/VR360/flat/fisheye projections on a curved HUD panel with gaze-based interaction (head tracking + gamepad).

Modules:
- `app/` — the Android application: activities, screens, state machine, gaze/HUD wiring
- `vrcore/` — VR rendering core: projections, shaders, gaze stabilizer, panel raycasting, theme/UI widgets
- `playback/` — video playback engine abstraction: ExoVideoPlayer (Media3), VlcVideoPlayer (LibVLC fallback), engine router, scrub controller, resume store
- `upnp/` — UPnP/DLNA discovery and browsing adapter (Gerbera integration)

Known history worth knowing:
- A recent regression (`gpt-5.6-luna`) broke continuous seek-bar interaction by hardcoding a `GazeTarget.HudTimeline(0f)` fraction in `HitRegion` construction while `hitTest()` computed a live fraction separately — a dual-construction bug. This was fixed by `gpt-5.6-terra` via a `GazeStabilizer.sameControl` comparator.
- The LibVLC fallback engine has/had a separate projection-distortion bug (`setWindowSize()` called only with placeholder dimensions, never re-applied on `onNewVideoLayout`) — Media3/ExoPlayer path is unaffected since it writes native pixels directly.
- `ProjectionMode.detect()` guesses projection mode purely from filename tokens (e.g. "180", "sbs") with fallback to CylinderScreen misclassification when tokens are absent.
- FFmpeg/software-audio decoder integration was bundled (`media3-decoder-ffmpeg`) for AC-3/DTS/TrueHD support.
- GitHub Actions CI/release pipeline had a silent gap (~38 commits with no `v*` tags pushed) recently found and fixed.

## What I need from you

Perform a genuine architectural review — read the actual code across all 4 modules, not just skim docs. Specifically assess:

1. **Module boundaries & coupling** — Are `app`/`vrcore`/`playback`/`upnp` cleanly separated, or is there leakage (e.g. UI code reaching into playback internals, or vrcore depending on app-level state)? Look for circular or inappropriate dependencies.

2. **State management architecture** — Review `AppStateMachine.kt`, `AppState.kt`, `GazeTarget.kt`, and the reducer/effect pattern. Is state mutation centralized and predictable, or are there scattered mutable vars / side-channel state (like the timeline hit-region bug pattern) that could cause similar dual-source-of-truth bugs elsewhere?

3. **The gaze/interaction pipeline** — `GazeStabilizer.kt`, `PanelRaycast.kt`, `HitRegion.kt`, hit-testing across `PlayerHud.kt`/`BrowseScreen.kt`/screens in general. Given the sameControl fix, are there other places where per-frame float jitter or per-frame reconstructed identity could break debouncing/stability? Is the stabilizer pattern applied consistently everywhere it needs to be?

4. **Playback engine abstraction** — `ExoVideoPlayer.kt`, `VlcVideoPlayer.kt`, the engine router/failover logic, `PlaybackFailure` taxonomy, `FallbackPolicy`. Is the abstraction leaky? How well does error/failure classification actually map to root causes (there was a known case where "container not supported" was misclassified as `Unknown`)? Assess the ExoPlayer→VLC failover path for race conditions or state leakage during the switch.

5. **Projection & rendering correctness** — `ProjectionMode.kt` detection logic, `SphereScreen.kt`/`CylinderScreen.kt`/`FisheyeMapping.kt`, shader code in `VideoShaders.kt`. How fragile is the filename-token-based projection auto-detection? What's the blast radius of a misdetection (cosmetic distortion vs. actual crash)? Any UV-mapping or mesh-generation code that looks copy-pasted/duplicated across projection types that should be unified?

6. **Threading & lifecycle safety** — GL thread vs. main thread vs. playback engine callback threads. Any obvious race conditions, unguarded shared mutable state, or lifecycle leaks (e.g. surfaces/players not released on activity teardown, Choreographer callbacks outliving their owner)?

7. **Testing coverage gaps** — What's actually under test (`GazeStabilizerTest`, `HudReducerTest`, `FisheyeMappingTest`, `MediaListScrollReducerTest`, etc.) vs. what's load-bearing but untested (playback engine failover, projection detection, UPnP browsing, R8/ProGuard rules that have already caused one crash)?

8. **Technical debt from the multi-agent sprint pattern** — Since different agents built different milestones somewhat independently, look for: inconsistent naming/conventions between modules, duplicated logic that should be shared utilities, dead code from superseded approaches (e.g. old debug overlay remnants), and any "two ways to do the same thing" patterns (like the HudTimeline dual-construction bug) that suggest a systemic risk pattern worth flagging broadly, not just patching case-by-case.

9. **Build/release pipeline health** — `.github/workflows/build-and-release.yml`, `proguard-rules.pro`, Gradle module structure. Any other latent CI gaps besides the tag-triggering issue already found?

## Deliverable

Write your findings to `docs/ASTRA_ARCHITECTURAL_REVIEW.md` in the repo (this IS the one file you should create — it's your report, not application code). Structure it as:

1. **Executive Summary** — 3-5 sentence overview, overall health assessment
2. **Critical Concerns** — Things that could cause user-visible bugs, crashes, or data loss. Rank by severity.
3. **Structural/Architectural Concerns** — Coupling, duplication, pattern inconsistency issues that are technical debt but not immediately breaking
4. **Testing Gaps** — Specific list of untested-but-load-bearing code paths
5. **Recommendations** — Concrete, prioritized next steps (not vague "improve architecture" — actual file/module-level suggestions)

Be honest and specific — cite actual file paths and line-level observations where relevant. This is a real codebase that shipped 0.1.0 through 0.9.2 in days under heavy multi-agent velocity; the goal is to find what's genuinely fragile before it bites in production, not to nitpick style.

Do NOT modify any application code. Only write the one report file.
