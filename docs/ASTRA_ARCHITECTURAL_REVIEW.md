# Arc VR Player — Architectural Review

**Reviewer:** GPT-6 Astra (Codex, read-only sandbox)
**Date:** September 10, 2026
**Reviewed revision:** `226159b48e510aaf3d9fd32bf72c1fffbd5da0fa`

Scope: production code, tests, module configuration, and release workflow across `app/`, `vrcore/`, `playback/`, and `upnp/`. This is a static review; tests, device playback, and release installation were not executed. Runtime consequences below are distinguished from directly observable implementation defects.

---

## 1. Executive Summary

Arc has a sound foundation: immutable reducer state, injectable playback engines, a JVM-only UPnP layer, and substantial tests for geometry, navigation, and recovery policy. Its greatest weakness is incomplete integration between those components: local browsing is unwired, rendering does not consume several settings correctly, and lifecycle code violates the playback and GL thread contracts. Playback recovery and resume behavior have multiple owners, creating concrete opportunities for stale operations and inconsistent state. The historical timeline-stability and VLC window-size fixes are present, but existing tests largely validate components independently and therefore miss several production wiring failures. Stabilizing those connections should take priority over adding projection modes or undertaking a broad architectural rewrite.

---

## 2. Critical Concerns

Severity: **P1** denotes major feature failure, crash risk, or persistent-state loss; **P2** denotes narrower correctness, interaction, or performance defects.

### C1 — P1: Video-surface lifecycle crosses thread boundaries incorrectly

**Evidence**
- `app/src/main/java/com/daydreamvr/player/render/AppScene.kt:128–145` creates the video surface inside `onGlCreate()` and immediately invokes `onVideoSurfaceReady`.
- `app/src/main/java/com/daydreamvr/player/VrActivity.kt:145` handles that callback with a direct `player.attach(surface)`.
- `playback/src/main/java/com/daydreamvr/playback/VideoPlayer.kt` explicitly declares all methods main-thread.
- `playback/src/main/java/com/daydreamvr/playback/ExoVideoPlayer.kt:73–75` forwards attachment directly to the underlying player.
- `VrActivity.kt:374–380` calls `renderer.onGlDestroy()` from activity teardown on the main thread.
- `AppScene.kt:346–360` consequently releases GL objects and the video texture from that thread.

**Impact**

Initial attachment may appear safe because the underlying ExoPlayer has not yet been constructed. Surface recreation during an existing playback session reaches the player from the GL thread, violating its declared contract and introducing crash risk.

On GL recreation, `AppScene.onGlCreate()` destroys the previous video surface without first coordinating decoder detachment. Conversely, activity destruction issues GL cleanup without guaranteeing a current GL context.

**Recommendation**

Introduce an explicit surface lifecycle coordinator. Attach and detach players on main; release GL resources on the GL thread with a current context. Establish decoder detachment before releasing its surface, and invalidate callbacks from previous surface generations.

### C2 — P1: Local media browsing is not connected to production state

**Evidence**
- `app/src/main/java/com/daydreamvr/player/state/BrowseFocus.kt:47` defaults local permission to `DENIED`.
- `Event.LocalPermissionChanged` exists, but there is no production dispatch of that event.
- `SetupActivity` requests permission and updates its own label; `VrActivity` never transfers the actual grant into application state.
- `app/src/main/java/com/daydreamvr/player/state/EffectRunner.kt:93–97` leaves `localMediaLoader` nullable and silently returns when unset.
- No production assignment to `localMediaLoader` exists.
- `AppContainer` constructs `LocalMediaRepository`, but its scan and observer are not wired into this flow.

**Impact**

Granting permission in the lobby does not enable the reducer's Device source. Even if permission state were corrected independently, library loading would still be a no-op.

**Recommendation**

Wire permission refresh, repository loading, success/failure events, and observer ownership in the activity/effect composition. Make the loader a required dependency rather than an optional callback.

### C3 — P1: Browse responses can populate the wrong server's folder

**Evidence**
- `EffectRunner.kt:100–107` and `174–184` launch browse operations independently.
- Completion events contain `objectId`, but no server/source identity or request generation.
- `AppStateMachine.kt:208–210` selects the destination frame solely by matching `objectId`.
- `AppStateMachine.kt:239–247` similarly handles failures and displays an error even when the originating frame is gone.

**Trigger**

Open server A's root, then switch to server B's root before A finishes. Both roots use `"0"`. A's late response can populate B's current frame.

**Impact**

The browser can show another server's items, derive incorrect media keys, or display an obsolete error over an unrelated screen.

**Recommendation**

Carry source ID, object ID, request generation, and page offset through browse effects and completion events. Reject stale responses and cancel superseded operations. Match page responses to the expected offset before appending.

### C4 — P1: Cylinder geometry remains stale after video dimensions change

**Evidence**
- `vrcore/src/main/java/com/daydreamvr/vrcore/render/CylinderScreen.kt:39–41` changes `aspect` without invalidating the mesh.
- Its mesh is built during `onGlCreate()` at lines 97–104.
- `draw()` uses that mesh without checking geometry changes.
- `AppScene.kt:205–210` calls `setAspect()` after dimensions arrive, but never rebuilds the mesh.
- `Settings.screenDistanceM` and `screenWidthDegrees` are persisted and displayed but never applied to the cylinder.
- The caller does not adjust full SBS/TB dimensions to the per-eye display aspect described by `ProjectionMode`.

**Impact**

On a fresh scene, cylinder geometry remains based on the default 16:9 aspect. Non-16:9 media is distorted, screen-size/distance controls have no rendering effect, and full-packed stereo requires aspect correction that is absent.

**Recommendation**

Give `CylinderScreen` a geometry configuration key comprising radius, angular width, and effective per-eye aspect. Rebuild only when that key changes, and feed it from a consistent render-state snapshot.

### C5 — P1: Resume ownership overrides explicit user choices and leaves persistence gaps

**Evidence**
- `AppStateMachine.kt:788–803` expresses "from start" using `skipResumeCheck`.
- `EffectRunner.kt:134–139` respects that flag.
- Both engines independently impose their stored resume position:
  - `ExoVideoPlayer.kt:97`
  - `vlc/VlcVideoPlayer.kt:88`
- `PlaybackEngineRouter.kt:172` also uses `maxOf(currentPosition, originalStartPosition)` during failover.
- `EffectRunner.kt:47–63` records snapshots into memory.
- Disk writes occur in stop/quit effects at lines 246–261.
- `VrActivity.onPause()` and `onDestroy()` do not flush that store to disk.

**Impact**

An explicit restart can resume anyway. Failover after seeking backward can jump forward to the original start position. Backgrounding followed by process death can lose progress accumulated since the last explicit stop/save.

A further risk is publishing a new item key while copying old snapshot fields: engine startup updates only part of the snapshot, while the effect collector treats any positive duration as eligible for resume storage.

**Recommendation**

Resolve resume policy once before constructing `PlayRequest`; engines should honor the requested position. Reset item-specific snapshot fields atomically. Add bounded periodic persistence and a lifecycle flush owned independently of an activity that is finishing.

### C6 — P1: Recovery has competing owners and stale delayed work

**Evidence**
- `ExoVideoPlayer.kt:232–271` executes resource fallback, retries, and audio recovery.
- `PlaybackEngineRouter.kt:137–159` independently applies the same policy using hardcoded `attempt = 0` and `remainingResources = 0`.
- Exo retry callbacks at lines 245 and 259 are not canceled by `play()` or `stop()`.
- Those callbacks access mutable current-request state.
- URL refresh remains a retry of the same URL at lines 254–259.
- VLC only opens the first ranked resource.
- Router failover carries position but does not explicitly preserve pause intent, playback speed, or track preferences.

**Trigger and impact**

A retry scheduled for item A can run after item B starts and reload B at A's captured position. A pending retry can also resume playback after a pause because loading sets `playWhenReady = true`.

The router cannot correctly interpret exhausted retry budgets from a failure alone. Exo publishes recoverable network failures, which the reducer displays as error overlays. Its comment that the router intercepts container failure "first" does not match the implementation: internal resource recovery may occur without publishing that failure.

**Recommendation**

Choose one recovery coordinator. Engines should report typed failure plus request identity; the coordinator should own retry budget, resource index, URL refresh, engine transition, and desired transport state. Cancel scheduled work on replacement and stop.

### C7 — P1: Release artifacts lack stable application versioning and explicit signing continuity

**Evidence**
- `app/build.gradle.kts:15–16` fixes the APK version at `1` / `0.1.0-phase1`.
- Release builds use the debug signing configuration at line 31.
- The workflow renames APKs using Git tags but does not change their embedded version.
- No stable signing-key provisioning is present in the workflow.
- Manual release dispatch accepts a tag independently of the built revision; an empty tag can fall back to `v0.1.0`.

**Impact**

Release filenames can imply versions the installed app does not contain. Update compatibility across build environments is not assured, and manual publication can attach artifacts to a tag without verifying that the tag identifies the built commit.

**Recommendation**

Derive embedded version metadata from a controlled release input, provision a stable signing identity, and verify artifact commit/tag correspondence before publication. Require an explicit tag for manual releases.

### C8 — P2: Thumbnail identity and scheduling can produce wrong posters and sustained load

**Evidence**
- `app/src/main/java/com/daydreamvr/player/media/UpnpAdapter.kt` creates thumbnail keys as `"upnp:${i.id}"`, omitting the server.
- The thumbnail cache is application-wide.
- `BrowseScreen.kt:66–71` requests thumbnails for every loaded video on every rendered frame.
- `AsyncKeyedLoader.kt` deduplicates only cached/in-flight work; failed loads have no cooldown.
- Completed thumbnails evicted from the bounded cache become immediately eligible again.

**Impact**

Servers sharing object IDs can display each other's posters. Large folders can continually reload offscreen thumbnails, while failed artwork can be retried repeatedly.

**Recommendation**

Use source-qualified keys, restrict requests to the visible window plus a small prefetch margin, bound concurrency, and cache failures temporarily.

### C9 — P2: Timeline identity is fixed locally, but interaction state still has competing meanings

**Evidence**
- `AppScene.kt:81–85` correctly treats all timeline fractions as the same control for stabilization.
- `PlayerHud.hitTest()` still calculates timeline geometry separately from `render()`, which still inserts `HudTimeline(0f)`.
- `PlayerHud` includes the raw floating-point `state.gaze` in its repaint key.
- `AppStateMachine.kt:102–104` leaves preview state untouched when gaze becomes null.
- Gaze hover and controller scrubbing both write `PlaybackSlice.previewPositionMs`.
- `AppScene` does not reset stabilized identity when browse content, sorting, or modal context changes.

**Impact**

The prior stabilization bug is repaired, but its duplicated geometry remains. Continuous fraction jitter can force Canvas repaint every frame. Hover preview can remain after leaving the timeline, and index-based targets can retain identity while the underlying item changes.

**Recommendation**

Separate control identity, contextual identity, and continuous value. Share one measured timeline layout, distinguish hover preview from active scrubbing, clear each on its proper transition, and quantize repaint inputs independently of seek precision.

### C10 — P2: Projection detection remains ambiguous; additional rendering defects are separate

**Evidence**
- `ProjectionMode.detect()` uses substring matching for tokens including `"tab"`, `"180"`, and `"360"`.
- Untagged approximately 2:1 media becomes VR360.
- Fisheye and 190°/200°/220° modes lack corresponding detection logic.
- `SphereScreen.kt:176–181` binds `aUv` even for the fisheye shader, which declares no such attribute.
- `FisheyeMapping` is not called by production rendering; the mesh and GLSL contain separate implementations.
- VLC's layout callback reapplies window size, but ignores visible dimensions and sample-aspect ratio.

**Impact**

A title such as "Table tennis" matches `"tab"`; ordinary 2:1 material becomes spherical. Typical misdetection produces distortion or incorrect eye assignment rather than an invalid enum or obvious crash.

The fisheye draw path separately submits an invalid attribute location; `GlUtils.checkGlError()` logs rather than throws, so this is an API error, not evidence of a guaranteed crash.

**Recommendation**

Treat detection as a guess with recorded provenance and an explicit override. Use bounded tokens and structured metadata where available. Correct attribute binding by shader interface, and test displayed geometry with sample-aspect and crop cases.

The historical VLC placeholder-window defect **is fixed** by `newVideoLayoutListener()` and has a dedicated test.

---

## 3. Structural/Architectural Concerns

### Module boundaries are acyclic but broader than necessary

The dependency graph is:

```text
app → playback → vrcore
 │       └────→ upnp
 ├────────────→ vrcore
 └────────────→ upnp
```

No reverse dependency from `vrcore` or `upnp` into `app` was found. The JVM-only UPnP boundary is valuable.

However, `playback` publicly exposes both lower modules and Media3 through Gradle `api` dependencies. `PlayRequest` embeds UPnP `Resource` and rendering `ProjectionMode`; local playback consequently fabricates a UPnP-shaped resource. Projection is primarily an app/rendering concern.

A neutral playback-source contract would reduce coupling without requiring another module immediately.

### The media-model migration remains unfinished

`BrowseFrame` retains both DIDL containers/items and unified folders/videos, plus legacy and newer focus/scroll representations. `EffectRunner` retains both `Play`/`PlayNode` and `Browse`/`BrowseNode`.

The two playback paths differ in resume behavior and key construction: the legacy path uses `serverUdn|itemId`, while unified keys include `upnp:`. That creates compatibility and migration risk for stored progress.

Complete this migration explicitly, including persisted-key migration, before extending both paths.

### Reducer purity is useful, but delivery and ownership are weaker than the state model

`AppStateMachine.dispatch()` centralizes state mutation, but its main-thread requirement is conventional rather than enforced. It also ignores `MutableSharedFlow.tryEmit()` results. With no collector, effects are not retained; with a full buffer, effects can be lost.

`VrActivity.handleScrub()` bypasses the effect runner to seek directly. Settings use HUD focus state. Playback snapshots are consumed both directly by rendering and indirectly through reducer events.

The issue is not mutable state itself; it is missing ownership rules for transitions shared by several components.

### Rendering is not based on one coherent frame snapshot

`AppScene.update()` reads state once, but `draw()` reads it again for each eye. A main-thread transition can therefore change screen or projection between panel update and eye rendering.

`VrActivity` mutates ordinary renderer fields from main while GL reads them. An immutable render configuration latched at frame start would provide both thread publication and consistency across eyes.

Head tracking is comparatively disciplined: samples use atomic references, relevant controls are volatile, and recenter operations are synchronized. Choreographer registration/removal is also paired correctly in resume/pause; there is no basis to claim an unconditional callback leak there.

### Several advertised paths remain stubs or unused components

Examples include:
- `PersistProjectionOverride` is a no-op, so overrides disappear on recreation.
- `RefreshUrlFromServer` never refreshes the URL.
- `SubtitleRenderer` has no production construction site, despite Media3 producing cues.
- `RenderWatchdog` and `WifiPerformanceLock` have no production construction sites.
- Passive SSDP notification listening exists but is not connected to directory management.
- `AppScene.groundVisible()` duplicates the active `GroundGridPolicy`.

These are stronger evidence of incomplete milestone integration than naming differences. Comments repeatedly describe future phases as though they were current architecture.

### Failure classification is improved but still underspecified

Unsupported and malformed containers now receive explicit failure types, and the policy includes a narrow compatibility match for "container not supported." The historical case should not be reported as wholly unfixed.

Remaining weaknesses include VLC collapsing errors to generic `Unknown`, HTTP statuses sharing an inaccurate "file no longer exists" message, decoder errors assuming video context, and retryable/final failures sharing one snapshot field. Classification and recovery progress should be distinct.

### UPnP seams are good, but runtime behavior needs stronger ownership

Transport injection, response-size limits, parser tests, and synchronized XML factory use are positive.

However:
- Parsing occurs after HTTP returns to the caller's context; main-scoped callers can perform large DOM work on main.
- Blocking HTTP calls are not explicitly canceled with their owning coroutine.
- Broad `runCatching` wrappers also catch cancellation.
- Discovery launches description fetches into application scope without awaiting them, so discovery completion need not mean descriptions are resolved.
- XML hardening feature failures are silently ignored; JVM tests do not establish Android parser behavior.

These warrant targeted runtime tests rather than an unsupported claim that all parsing or discovery is unsafe.

### Build gates do not enforce everything the repository describes

The workflow runs `test` and assembly, while `checkNoAndroidImports` is attached to `check`. That architectural check is not explicitly included in the CI command. Release lint is disabled, and no instrumentation source sets were found.

Broad keep rules for playback, UPnP, Media3, and networking reduce shrinking substantially. The LibVLC JNI keep rule is sensible, but successful assembly cannot prove native/reflection paths work in a minified installed APK.

---

## 4. Testing Gaps

Existing coverage is meaningful: `GazeStabilizerTest`, HUD/navigation/scroll reducers, projection detection and UV rectangles, sphere/cylinder/fisheye math, VLC layout/state helpers, fallback policy, router behavior, resume storage, and UPnP parsing/browsing all have tests.

The missing coverage is chiefly across boundaries:

| Area | Specific missing validation |
|---|---|
| Surface lifecycle | Active playback through pause/resume, GL context recreation, decoder detachment, and destruction ordering |
| Local library | Actual permission grant → state event → repository load → rendered folder |
| Browse concurrency | Two servers with identical object IDs; stale errors; superseded and out-of-order pages |
| Recovery | Delayed retry after new play/stop/pause; real engine and router policy composition |
| Failover | Pause/speed preservation; backward seek before fallback; same-engine stale emissions |
| Resume | Explicit restart with stored progress; item transition snapshots; process-death persistence |
| Rendering | Mesh invalidation after dimensions/settings; full-packed per-eye aspect; state consistency between eyes |
| Fisheye/VLC | Compiled shader attribute interfaces; actual decoded crop/SAR; native surface output |
| Gaze | Full hit-test → stabilizer → reducer path; context change under unchanged index; preview ownership |
| Thumbnails | Cross-server key collisions; bounded request volume; failures and cache churn |
| Android UPnP | Parser hardening behavior, cancellation, main-thread parsing cost, discovery lifecycle |
| Release | Minified installation, LibVLC JNI initialization, FFmpeg decoding, upgrade signing/version continuity |

`PlaybackEngineRouterTest` contains three fake-engine scenarios. It establishes basic switching and terminal-error behavior, but does not execute ExoPlayer's internal retry implementation or asynchronous lifecycle interactions.

The repository has no `app/src/androidTest`, `playback/src/androidTest`, or `vrcore/src/androidTest` directories despite instrumentation dependencies. JVM tests using default Android stub values cannot validate the runtime paths above.

No tests were run during this review.

---

## 5. Recommendations

1. **Fix surface ownership first.** Coordinate main-thread decoder operations with GL-thread resource creation/destruction. Add a device test covering playback through context recreation.

2. **Finish local-library composition.** Dispatch actual permission state, require a repository loader, and connect observer events and failure reporting.

3. **Make browse operations identity-safe.** Add source and request generation to events, cancel replacements, and validate page offsets before mutation.

4. **Repair cylinder configuration.** Apply distance/width settings, compute per-eye aspect, and invalidate meshes using a geometry key. Validate with non-16:9 and full SBS/TB fixtures.

5. **Consolidate resume and recovery.** Make requested position authoritative; centralize retry/resource/engine decisions; preserve desired transport state; reject stale callbacks.

6. **Make persistence intentional.** Implement projection-override storage, migrate legacy media keys, and flush progress through an application-owned persistence component.

7. **Finish the unified media migration.** Remove duplicate DIDL/unified collections and legacy effect paths once their consumers are migrated. Introduce a protocol-neutral playback source.

8. **Separate gaze identity from value and context.** Share measured timeline geometry, distinguish preview modes, reset on content transitions, and avoid raw float values in repaint keys.

9. **Bound thumbnail work.** Qualify cache keys by source, prefetch only nearby visible items, limit concurrency, and back off failures.

10. **Strengthen release gates.** Run the architecture check explicitly, validate embedded versions and signing continuity, bind releases to the built commit, and add an installed minified smoke test covering Media3, VLC, and FFmpeg.

These changes address observed integration failures while preserving the strongest parts of the current architecture.
