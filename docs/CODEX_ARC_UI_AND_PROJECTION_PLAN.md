# Arc UI, Transport, and Projection Implementation Plan

Status: proposed specification, grounded in the inspected codebase.

## Scope and findings

Implement all eight requested changes while preserving the pure reducer/effect architecture, existing projection mappings, absolute browse indices, controller navigation, and decoder texture transforms.

The inspected implementation establishes these corrections to the planning prompt:

- `HudState` lives in `state/AppState.kt`, not a separate file.
- `PlayerHud` is 2.40 m wide but inherits **2.50 m viewing distance** from `ScreenPanel`.
- List navigation already advances by one video; rendering still selects six-item pages.
- Timeline targets represent the midpoints of 40 segments.
- `GazeStabilizer` compares entire targets, including timeline fractions.
- Projection chooser scrolling is recomputed from focus in the renderer.
- `VideoTexture` already sets both wrap axes to `GL_CLAMP_TO_EDGE`.
- `GroundGridPolicy.visibleOn(PLAYER)` already returns false and is used by `AppScene`.
- Cylinder aspect updates do not trigger mesh rebuilds.
- No playback queue is present in `PlaybackSlice`.
- `EffectRunner.playNode` supports both local and UPnP media and already resolves per-item projection.
- `EffectRunner` currently treats `PersistProjectionOverride` as a no-op. Do not describe durable override persistence as an existing guarantee.

The floor artifact’s root cause is **unconfirmed**. UV hardening is necessary defensive work, but cannot alone establish that geometry or framebuffer leakage is fixed.

## Shared implementation contracts

1. Reducers own persistent UI state; renderers measure and draw it.
2. Layout calculations produce pure Kotlin geometry shared by drawing and hit testing.
3. Gaze targets use absolute indices or stable control identities.
4. Hover does not issue playback effects.
5. Playback commands flow through `EffectRunner`.
6. All GL resource changes occur on the GL thread.
7. Existing equirectangular projections retain their geometry and sampling semantics.
8. Newly introduced mapping calculations use `Double` on the CPU and `highp` in shaders.
9. No global typography reduction: compact-list typography is scoped explicitly.
10. Every asynchronous playback transition has an identity so stale results cannot overwrite the active request.

---

## 1. Smaller, tighter file-list text

### Files

- `screens/widgets/MediaListWidget.kt`
- `vrcore/.../ui/Theme.kt`
- `screens/widgets/MediaListLayoutTest.kt`
- `vrcore/.../ui/TypeScaleTest.kt`

### Proposed layout

Introduce compact-list tokens rather than modifying `Type.rowSubtitle`, `Type.meta`, or `Type.numeral` globally.

| Element | Proposed angular size |
|---|---:|
| Title | 0.85° |
| Metadata | 0.75° |
| Duration | 0.85° |
| Compact badge | 0.75° |
| Icon | 1.60° |
| Minimum row hit height | 2.00° |

The title reduction is:

\[
1-\frac{0.85}{1.15}=26.09\%
\]

These tokens intentionally fall below the repository’s existing 1.0° typography policy, implementing the user’s explicit smaller-text request. Document the exception and test it separately; retain the global threshold for other screens.

Use regular title weight initially. The existing layout measures the title with `bold = true`, although `Type.rowSubtitle` draws regular text. Measurement and drawing must use the same token, size, and weight.

### Exact text geometry

For panel width \(P\) pixels and horizontal arc \(\Phi\) degrees:

\[
k=P/\Phi,\qquad p(d)=kd
\]

For measured font ascent \(a<0\), descent \(d>0\):

\[
h_t=d_t-a_t,\qquad h_m=d_m-a_m
\]

With a 0.15° interline gap:

\[
H=h_t+p(0.15)+h_m
\]

Center the complete text block vertically:

\[
y_0=y_c-H/2
\]

\[
b_t=y_0-a_t
\]

\[
b_m=y_0+h_t+p(0.15)-a_m
\]

For rows without metadata, center the title alone.

Extend the pure measurement abstraction to expose descent if necessary. Do not rely on Android `Paint` behavior in ordinary JVM tests.

### Horizontal allocation

Allocate duration and badges from the right edge. Allocate icon and text from the left. The remaining title width is:

\[
W_t=\max(0,x_{\text{badgesLeft}}-x_{\text{textLeft}}-g)
\]

When space is insufficient:

1. Remove optional quality badge.
2. Use a short projection badge label.
3. Ellipsize title and metadata independently.
4. If even the ellipsis cannot fit, draw no text in that field.

Preserve the full projection name in the inspector and chooser.

Clip drawing to the row and list viewport. Whole-row hit targets remain large even when text shrinks.

### Tests

Extend `MediaListLayoutTest` to cover:

- Exact compact token values and reduction ratio.
- Identical measurement/drawing weights.
- Text baselines contained within rows.
- Rows with and without metadata.
- Long titles, durations, and projection badges.
- Zero available text width.
- Unicode truncation without splitting surrogate pairs.
- No overlapping text/badge allocations.
- Hit rectangles at least 2° high.
- Different panel pixel densities.

Device acceptance: small text must be evaluated through Daydream View lenses; bitmap screenshots alone cannot establish legibility.

---

## 2. Continuous file-list scrolling

### Files

- `state/AppState.kt`
- `state/AppStateMachine.kt`
- `screens/BrowseScreen.kt`
- `screens/widgets/MediaListWidget.kt`
- `render/AppScene.kt`

### State model

Add a dedicated cursor to `BrowseFrame`:

```kotlin
val mediaListScrollTop: Int = 0
```

Do not reuse legacy `scrollTop`: it indexes the mixed folder/item representation and is updated using `frame.rows`.

Add a distinct measured list capacity to `ListWindow`, such as:

```kotlin
val mediaList: Int = 8
```

Replace the list model’s page fields:

```kotlin
data class Model(
    val items: List<MediaGridWidget.Model.Card>,
    val scrollTop: Int,
    val visibleRows: Int,
)
```

Grid mode keeps `GRID_PAGE_SIZE = 6`, `gridPage`, and existing paging behavior.

### Capacity

Let \(B\) be the list-band height in pixels. Use a target pitch of 3.0°:

\[
V=\max\left(1,\left\lfloor B/p(3.0)\right\rfloor\right)
\]

Then:

\[
\text{pitch}=B/V
\]

Use 0.15° top and bottom row gaps, giving:

\[
h_{\text{row}}=\text{pitch}-2p(0.15)
\]

For unusually short panels that cannot provide a 2° target, return an explicit insufficient-layout result rather than creating undersized interactive rows.

Measure capacity from the same layout used for drawing, and report changes after GL creation or layout changes.

### Persistent window equation

For item count \(N\), visible count \(V\ge1\), focus \(F\), and previous top \(S\):

\[
M=\max(0,N-V)
\]

First clamp \(S\) into \([0,M]\). Then:

\[
S'=
\begin{cases}
F,&F<S\\
F-V+1,&F\ge S+V\\
S,&\text{otherwise}
\end{cases}
\]

Finally clamp \(S'\) into \([0,M]\).

```kotlin
fun ensureVisible(
    focus: Int,
    previousTop: Int,
    count: Int,
    visibleRows: Int,
): Int {
    require(visibleRows > 0)
    if (count <= 0) return 0

    val f = focus.coerceIn(0, count - 1)
    val maximum = (count - visibleRows).coerceAtLeast(0)
    val top = previousTop.coerceIn(0, maximum)

    return when {
        f < top -> f
        f >= top + visibleRows -> f - visibleRows + 1
        else -> top
    }.coerceIn(0, maximum)
}
```

Each rendered slot maps to:

\[
\text{absoluteIndex}=S'+\text{slot}
\]

For eight visible rows, moving focus from 7 to 8 changes the top from 0 to 1, retaining seven rows.

### State transitions

| Trigger | Required behavior |
|---|---|
| Controller Up/Down | Move one video; ensure focus visible |
| Gaze hover | Update focus without moving the viewport |
| Enter LIST | Reveal current selected video |
| Enter GRID | Derive grid page from current focus |
| Sort | Preserve selected `MediaKey`, then reveal its new index |
| Append | Preserve selection by key and clamp window |
| Removal/refresh | Preserve key if present; otherwise nearest valid index |
| Enter folder | Initialize child window independently |
| Back | Restore parent window |
| Resize | Recompute capacity and normalize once |

Update all relevant browse reducer paths, not just `applyFocus`. Include `mediaListScrollTop` and measured capacity in `BrowseScreen`’s render key.

For fetch-ahead, use the visible tail rather than `gridPage`:

\[
S+V\ge N
\]

Guard requests with an in-flight request identity so repeated navigation cannot request the same page concurrently.

### Smooth presentation

Implement persistent per-item windowing first, then a short visual transition as part of this feature.

Use a 120 ms ease-out interpolation:

\[
u=\operatorname{clamp}((t-t_0)/120\text{ ms},0,1)
\]

\[
e(u)=1-(1-u)^3
\]

\[
S_{\text{visual}}=S_0+(S_1-S_0)e(u)
\]

Render rows at:

\[
y_i=y_{\text{listTop}}+(i-S_{\text{visual}})\text{pitch}
\]

Render one overscan row on either side and clip to the viewport. Retarget from the current interpolated position on repeated input.

Drawing and hit testing must use the same interpolated rectangles. Clear or revalidate gaze candidates when the window changes so a stationary gaze cannot chase newly arriving rows and reverse controller navigation.

This bounded animation is an explicit exception to the existing no-Canvas-animation guideline. Repaint only during the transition, then return to cached rendering. If device measurements show unacceptable upload cost, move the animated list into a separately translated/clipped surface.

### Tests

Add `MediaListScrollReducerTest` and `MediaListAnimationTest`.

Cover empty lists, partial lists, first/last items, boundary reversals, rapid navigation, large jumps, resize, sort, append, removal, folder restoration, and grid/list toggles.

Property assertions:

\[
0\le S\le\max(0,N-V)
\]

After controller movement, for \(N>0\):

\[
S\le F<\min(N,S+V)
\]

Verify animated hit rectangles match visual rows, have absolute indices, and never escape the viewport.

---

## 3. Continuous timeline seeking

### Files

- `screens/PlayerHud.kt`
- `screens/ScreenPanel.kt`
- `render/AppScene.kt`
- `state/GazeTarget.kt`
- `state/AppStateMachine.kt`
- `vrcore/.../ui/widgets/Timeline.kt`
- `vrcore/.../ui/GazeStabilizer.kt`

### Shared timeline layout

Extract pure `TimelineLayout` containing:

- Visible track rectangle.
- Expanded hit rectangle.
- Time-label bounds.
- Preview-label bounds.

The current `barTop` passed to `Timeline.draw` is not the actual painted track top; `Timeline` adds label height and spacing. Eliminate duplicated geometry.

Provide a virtual panel hit-test method:

```kotlin
open fun hitTest(xPx: Float, yPx: Float): GazeTarget? =
    hitMap.hitTest(xPx, yPx)
```

`AppScene` calls this method. `PlayerHud` overrides it to resolve its single timeline rectangle continuously.

### Exact fraction

For track endpoints \(L<R\):

\[
f=\operatorname{clamp}\left(\frac{x-L}{R-L},0,1\right)
\]

Use expanded endpoint hit margins so the half-open rectangle convention does not make the exact right endpoint unreachable.

For duration \(D>0\):

\[
t=\operatorname{round}(D f)
\]

```kotlin
fun seekPosition(durationMs: Long, fraction: Double): Long? {
    if (durationMs <= 0 || !fraction.isFinite()) return null
    val f = fraction.coerceIn(0.0, 1.0)
    if (f == 0.0) return 0L
    if (f == 1.0) return durationMs

    return (durationMs.toDouble() * f)
        .roundToLong()
        .coerceIn(0L, durationMs)
}
```

Change `HudTimeline.fraction` to `Double`, or explicitly convert before multiplication if retaining its current `Float` API.

For a one-hour video at \(f=0.123456\):

\[
t=444442\text{ ms}
\]

There must be no 40-bin quantization.

### Stable identity, live coordinates

Do not debounce changing fractions.

Separate:

- Stable target identity: timeline.
- Live payload: current fraction and surface generation.

Debounce entry to the timeline using the existing 60 ms rule. Once entered, update fractions continuously while the raw ray still intersects that timeline.

On exit, immediately invalidate timeline activation and clear its preview. A visually retained debounce highlight must not authorize a stale seek.

Use monotonically ordered gaze/input events. Clear timeline state when the HUD hides, an overlay opens, playback changes, or geometry is recreated.

### Interaction transitions

| Event | State change | Effect |
|---|---|---|
| Enter/move on valid timeline | Update gaze preview | None |
| Leave timeline | Clear gaze preview | None |
| Confirm on valid timeline | Clear preview; refresh HUD timer | One exact seek |
| Invalid/unknown duration | No preview or seek | None |
| Hidden HUD Confirm | Reveal HUD | None |
| Item change | Clear prior timeline state | None |

Preserve trigger scrubbing. Introduce preview ownership:

```kotlin
enum class PreviewSource { GAZE, TRIGGER }
```

Trigger preview takes precedence while active. Leaving the timeline must not clear a trigger-owned preview.

Hover produces no decoder seek calls. Existing input exposes confirmation rather than pointer down/up; this feature is continuous positioning plus click-to-seek, not an invented drag gesture.

### Rendering performance

Maintain exact state fractions, but key preview rendering by marker pixel and displayed timestamp rather than the entire floating-point gaze payload.

Do not retain the current 500 ms preview quantization.

### Tests

Add:

- `TimelineLayoutTest`
- `TimelineFractionTest`
- `ContinuousGazeTimelineTest`
- `TimelineSeekReducerTest`

Test endpoints, out-of-range values, nonfinite values, zero-width tracks, invalid duration, multi-hour media, and non-bin fractions.

Simulate a continuously moving gaze for more than 60 ms: acquisition must succeed. Verify one seek per Confirm, no seek on hover, no stale seek after exit, and trigger-preview isolation.

---

## 4. Dedicated transport controls

### Files

- `state/AppState.kt`
- `state/AppStateMachine.kt`
- `state/GazeTarget.kt`
- `state/Effect.kt`
- `state/Event.kt`
- `state/EffectRunner.kt`
- `screens/PlayerHud.kt`

### Typed controls & Two-Tier Layout

Do not stuff all 7 controls onto a single cramped horizontal row. Implement a clean, two-tier visual and hit-target hierarchy:

- **Tier 1 (Primary Transport Cluster):** Center-aligned, generous hit boxes ($h \ge 3.0^\circ$):
  `[ ⏮ Prev ]    [ ⏯ Play / Pause ]    [ ⏭ Next ]`
- **Tier 2 (Utility & Settings Chips):** Positioned underneath:
  `[ Back to Browse ]  •  [ Projection ]  •  [ Speed ]  •  [ Screen size ]`

Replace string dispatch with stable control identifiers:

```kotlin
enum class HudControlId {
    PREVIOUS, PLAY_PAUSE, NEXT,
    BACK, PROJECTION, SPEED, SCREEN_SIZE
}
```

Controller D-pad / left stick navigation moves left/right across buttons within a tier, and up/down between the transport cluster and the utility row. Gaze reticle hits each button's discrete bounding box cleanly.

Use dynamic Play/Pause text and icon. Its action calls the existing:

```kotlin
Effect.SetPlayWhenReady(null)
```

Do not infer the requested toggle solely from `isPlaying`: buffering can make actual playback false while play intent remains true.

### Queue definition

At explicit browse playback, snapshot the loaded sorted videos and source-qualified keys:

```kotlin
data class QueueEntry(
    val key: MediaKey,
    val node: MediaNode.Video,
)

data class PlaybackQueue(
    val entries: List<QueueEntry> = emptyList(),
    val index: Int = -1,
)
```

Store queue state outside transient playback snapshots.

Semantics:

- Prev opens the preceding queue entry.
- Next opens the following queue entry.
- Both start at zero using `skipResumeCheck = true`.
- No wraparound.
- Prev does not restart the current item.
- Disabled at queue boundaries.
- Single-item/legacy playback receives a single-item queue.
- No automatic end-of-track advancement in this phase.
- The queue covers loaded videos; skipping does not silently fetch unknown entries.
- Browse sorting or refresh after playback begins does not reorder the active queue.

Resolve the destination’s current projection override at activation time and reuse `Effect.PlayNode` for local/UPnP resource handling.

### Transactional transitions

Introduce a pending playback request with monotonically increasing request ID.

1. Validate destination.
2. Record pending destination and request ID.
3. Clear scrub/gaze preview.
4. Disable additional track skips during transition.
5. Execute `PlayNode`.
6. Commit queue index when the destination is accepted.
7. On failure, clear pending state and retain the previous committed index.

Correlate projection changes and request failures with the request ID. A plain unkeyed `ProjectionChanged` can otherwise race with rapid track changes.

Ignore delayed snapshots for the previous item while a destination is pending. After completion, reject snapshots whose key does not belong to the committed active item, except explicitly handled idle/stop transitions.

Reuse the established outgoing resume-saving lifecycle; test that switching tracks preserves it.

### Existing Screen size control

`activateHudControl` currently has no `"Screen size"` action. Make it concrete:

- Open a small chooser containing supported widths, for example 40°, 50°, 60°, 70°, 80°, 90°.
- Apply settings through the existing effect path.
- Disable it for spherical projections.
- Preserve the last cylinder setting when entering spherical modes.

Disabled controls remain visible and have an explicit disabled presentation; activation emits no effects.

### Tests

Extend `HudReducerTest` and add `PlaybackQueueReducerTest` and `PlaybackQueueEffectRunnerTest`.

Cover:

- All seven controls and controller traversal.
- Play/Pause while paused, playing, and buffering.
- Both queue boundaries.
- Local and UPnP entries.
- Duplicate titles with distinct keys.
- Sorted queue construction.
- Destination override resolution.
- Start-at-zero semantics.
- Failure rollback.
- Stale projection/snapshot rejection.
- Repeated clicks while pending.
- Resume saving on outgoing media.
- Screen-size enablement and effects.

---

## 5. Persistent projection-menu scrolling

### Files

- `state/AppState.kt`
- `state/AppStateMachine.kt`
- `screens/OverlayRenderer.kt`
- `render/AppScene.kt`

### State and geometry

Add:

```kotlin
// Overlay.ProjectionChooser
val scrollTop: Int = 0

// ListWindow
val projectionChooser: Int = 1
```

The renderer reports the real chooser capacity from the same body rectangle and `ListView.visibleRowCount` used to render it.

Do not use a guessed default as the permanent window size. Normalize again when measured capacity arrives.

### Transitions

- Opening: compute focus from current projection, then reveal it once.
- Controller Up/Down: update focus and persistent top using `ensureVisible`.
- Gaze hover: update focus only.
- Repaint/Tick: leave top unchanged.
- Capacity change: clamp and reveal focus once.
- Close: discard chooser state.
- Reopen: initialize from current projection.

Delete `OverlayRenderer.scrollWindowFor`.

Add `scrollTop` and measured capacity to render invalidation.

Absolute hit indices remain `scrollTop + slot`.

When controller movement scrolls the list, invalidate the previous gaze candidate and require fresh acquisition against the new geometry. This prevents a stationary gaze from immediately undoing navigation.

Preserve existing Auto semantics for this change: browse Auto removes the override; player Auto currently reapplies the current mode. Correcting player Auto detection is a separate behavior change unless explicitly included.

### Tests

Add `ProjectionChooserScrollTest`.

For every supported capacity, traverse all options downward and upward. Assert:

- Every option becomes visible and selectable.
- Hover never changes top.
- Repainting identical state preserves top.
- Boundary navigation does not wrap.
- Initial bottom selections are visible.
- Resize clamps correctly.
- Appending fisheye modes does not break reachability.
- Both chooser origins produce their existing effects.
- Stale gaze does not reverse controller scrolling.

---

## 6. Raise and clarify the player dock

### Files

- `screens/PlayerHud.kt`
- `screens/ScreenPanel.kt`
- `render/AppScene.kt`
- `screens/ComfortTest.kt`

### Placement contract

Make HUD dimensions explicit:

```kotlin
object PlayerHudGeometry {
    const val WIDTH_M = 2.40f
    const val HEIGHT_M = 0.62f
    const val DISTANCE_M = 2.50f
    const val VERTICAL_OFFSET_M = -0.85f
}
```

Retain the actual current viewing distance. The prompt’s 2.40 m describes width, not the inherited distance.

For radius \(R\), vertical offset \(y\), and height \(H\):

\[
\alpha_c=\operatorname{atan2}(y,R)
\]

\[
\alpha_t=\operatorname{atan2}(y+H/2,R)
\]

\[
\alpha_b=\operatorname{atan2}(y-H/2,R)
\]

Proposed placement:

- Center: approximately −18.78°.
- Top: approximately −12.19°.
- Bottom: approximately −24.89°.

Current center is approximately −24.70°. Raising the HUD improves centrality by about 5.92°.

The half horizontal arc is:

\[
\frac{W}{2R}\frac{180}{\pi}\approx27.50^\circ
\]

This is within the existing ±35° head-turn comfort limit.

### Internal layout

Extract a pure `PlayerHudLayout`.

Use the approximately 14.2° vertical panel budget:

- Title band: 2.0°.
- Timeline labels and track: 3.2°.
- Gap: 0.4°.
- Control targets: 3.0°.
- Value labels: 1.2°.
- Remaining space: padding and separation.

With seven controls, derive equal slot widths from actual available width:

\[
w=\frac{W_{\text{content}}-6g}{7}
\]

Assert each target is at least 2° in both dimensions. Use 3° target height and shorter visible labels.

Ellipsize title around speed/buffering indicators. Give the raised HUD a sufficiently opaque panel background so video does not reduce text contrast. Retain existing theme colors.

Rendering and gaze geometry must share the same placement constants.

### Tests and headset acceptance

Extend comfort tests to include HUD center and edges, seven-control geometry, nonoverlap, and panel texture/physical aspect consistency.

Test both eyes with distortion on/off, recentering, neck model enabled/disabled, long titles, pause/buffering indicators, and spherical content.

Accept only when all controls and timeline endpoints can be selected comfortably through Daydream lenses.

---

## 7. True equidistant fisheye SBS projection

### Files

- `vrcore/.../render/ProjectionMode.kt`
- `vrcore/.../render/SphereScreen.kt`
- `vrcore/.../render/shaders/VideoShaders.kt`
- `render/AppScene.kt`
- Projection tests and fixtures

### Explicit mapping family

Introduce:

```kotlin
enum class ProjectionMapping {
    CYLINDER,
    EQUIRECTANGULAR,
    EQUIDISTANT_FISHEYE
}
```

Append these modes without renaming or reordering existing entries:

```kotlin
FISHEYE_180_SBS
FISHEYE_190_SBS
FISHEYE_200_SBS
FISHEYE_220_SBS
```

Each uses SBS packing and its corresponding full lens FOV.

Labels must distinguish fisheye from equirectangular:

`Fisheye 190° SBS`

Do not use `domeFov != null` as a proxy for equirectangular mapping.

Preserve existing detection behavior. New modes are manually selectable initially; do not infer fisheye from aspect ratio or a generic VR180 token.

### Coordinate convention

Use the existing right-handed coordinate system:

- \(+X\): right.
- \(+Y\): up.
- \(-Z\): forward.

For normalized local direction \(\mathbf d=(d_x,d_y,d_z)\):

\[
s=\sqrt{d_x^2+d_y^2}
\]

\[
\theta=\operatorname{atan2}(s,-d_z)
\]

For full lens FOV \(\Phi\):

\[
\theta_{\max}=\frac{\Phi\pi}{360}
\]

Thus half-angles are 90°, 95°, 100°, and 110°.

Equidistant projection is:

\[
r=f_{\text{lens}}\theta
\]

With normalized rim radius 1:

\[
\rho=\theta/\theta_{\max}
\]

For \(s>0\):

\[
\mathbf q=\rho(d_x/s,d_y/s)
\]

At the forward axis, define \(\mathbf q=(0,0)\).

Reject directions satisfying:

\[
\theta>\theta_{\max}
\]

A 220° lens includes directions 20° behind the lateral plane; do not clip it to \(d_z\le0\).

### Lens placement

Define the default encoded eye domain as a lens disk centered in the normalized eye rectangle:

\[
c=(0.5,0.5),\qquad a=(0.5,0.5)
\]

Then:

\[
u_e=0.5+0.5q_x,\qquad v_e=0.5+0.5q_y
\]

This convention allows encoded eye images to have been resized nonuniformly. Physical calibration for off-center lenses, cropped circles, or other fisheye laws is outside these four preset modes.

Represent center/radius as explicit shader parameters so later calibration does not require replacing the mapping.

For SBS:

\[
(u_L,v_L)=(0.5u_e,v_e)
\]

\[
(u_R,v_R)=(0.5+0.5u_e,v_e)
\]

Do not apply an additional Y flip. Preserve the existing decoder transform convention and verify it with a labeled orientation fixture.

### Mesh and fragment mapping

Generate a polar spherical cap:

\[
\theta_i=\frac{i}{N_r}\theta_{\max},\qquad
\phi_j=\frac{2\pi j}{N_\phi}
\]

\[
\mathbf p=R
\begin{pmatrix}
\sin\theta\cos\phi\\
\sin\theta\sin\phi\\
-\cos\theta
\end{pmatrix}
\]

Use a center fan plus annular rings, avoiding a degenerate quad ring at the origin. Duplicate the azimuth seam.

Initial tessellation: 64 radial intervals and 192 azimuth segments. Validate its silhouette and cost on the target device.

Pass local position to the fragment shader, normalize it there, and evaluate the radial mapping per fragment. Vertex-only UV interpolation is insufficient for exact nonlinear radial mapping between mesh vertices.

```glsl
vec3 d = normalize(vLocalPosition);
float s = length(d.xy);
float theta = atan(s, -d.z);

if (theta > uThetaMax) {
    discard;
}

vec2 radial = s > 1e-7
    ? (theta / uThetaMax) * d.xy / s
    : vec2(0.0);

vec2 eyeUv = uLensCenter + uLensRadius * radial;
```

Use a dedicated fisheye shader variant to avoid introducing trigonometric fragment cost into existing cylinder/equirectangular paths.

For every nondegenerate triangle \(a,b,c\), require inward winding:

\[
((b-a)\times(c-a))\cdot(a+b+c)<0
\]

### Sampling transform order

Use:

1. Direction-to-eye UV.
2. Domain validation.
3. Safe eye-domain clamping.
4. SBS packing.
5. Current `SurfaceTexture` matrix.
6. External texture sampling.

The producer matrix must be refreshed after frame updates and applied once; it can encode crop and orientation. [Android SurfaceTexture reference](https://developer.android.com/reference/android/graphics/SurfaceTexture#getTransformMatrix(float%5B%5D))

### Geometry cache

Cache meshes by geometry family, effective FOV, radius, and tessellation. Packing alone need not change geometry.

Invalidate on GL recreation and reset every cache field on destroy.

Keep the existing equirectangular longitude/latitude generator unchanged. Its current tests intentionally exercise `SphereScreen.domeFovDegrees`; narrow those tests to equirectangular modes instead of accidentally applying that contract to fisheye modes.

### Exact test oracles

For direction:

\[
\mathbf d=(\sin\theta\cos\phi,\sin\theta\sin\phi,-\cos\theta)
\]

Expected UV:

\[
(u_e,v_e)=
\left(
0.5+0.5\frac{\theta}{\theta_{\max}}\cos\phi,\;
0.5+0.5\frac{\theta}{\theta_{\max}}\sin\phi
\right)
\]

Examples:

- Forward: \((0.5,0.5)\).
- Half-angle right: \((0.75,0.5)\).
- Right rim: \((1,0.5)\).
- Upper rim: \((0.5,1)\).
- 45° right in 180° mode: \((0.75,0.5)\).
- 45° right in 220° mode: approximately \((0.704545,0.5)\).

Add `FisheyeMappingTest` and `FisheyeMeshTest` covering every FOV, both eyes, center singularity, rim, outside-domain rejection, symmetry, seam closure, winding, finite vertices, radius, and randomized inverse/forward round trips.

Use CPU tolerances around \(10^{-6}\) for normalized UV and separately measured GPU tolerances.

---

## 8. Under-screen artifact prevention

### Files

- `vrcore/.../render/CylinderScreen.kt`
- `vrcore/.../render/shaders/VideoShaders.kt`
- `vrcore/.../gl/VideoTexture.kt`
- `vrcore/.../render/VrRenderer.kt`
- `vrcore/.../render/EyeFramebuffer.kt`
- `render/AppScene.kt`
- `render/GroundGridPolicy.kt`

### Diagnose by rendering stage

Capture a fixture with bright bottom scanlines and different left/right eye colors at:

1. Eye framebuffer before distortion.
2. Final composited output.

Interpretation:

- Artifact in eye framebuffer: investigate scene geometry, video sampling, clear state.
- Artifact only after distortion: investigate compositor bounds and framebuffer sampling.
- Artifact persists after video draw is disabled: investigate stale pixels or other scene layers.

Record mode, dimensions, transform matrix, cylinder geometry key, viewport/scissor, and draw stage.

### Cylinder geometry correctness

Retain:

\[
x=R\sin\theta,\quad z=-R\cos\theta
\]

\[
\theta=(u-0.5)A
\]

\[
H=RA/\text{aspect}
\]

\[
y=(v-0.5)H
\]

Require finite positive radius and aspect, finite supported width, and positive segment counts.

Reject nonfinite aspect updates; `videoAspect > 0` currently admits positive infinity.

Add a geometry key:

```kotlin
data class CylinderGeometryKey(
    val radiusM: Float,
    val widthDegrees: Float,
    val aspect: Float,
)
```

Rebuild once when the key changes. The current aspect setter alone cannot update an already uploaded mesh.

For decoded display aspect \(A_d\), preserve half-packed display proportions and account for full packing:

\[
A_{\text{eye}}=
\begin{cases}
A_d/2 & \text{SBS\_FULL}\\
2A_d & \text{TOPBOTTOM\_FULL}\\
A_d & \text{otherwise for cylinder modes}
\end{cases}
\]

### Reject before clamp

Carry untransformed eye-local UV into the fragment shader.

```glsl
if (any(lessThan(vEyeUv, vec2(0.0))) ||
    any(greaterThan(vEyeUv, vec2(1.0)))) {
    discard;
}

vec2 safeEyeUv = clamp(vEyeUv, uSafeMin, uSafeMax);
vec2 packed = uUvRect.xy + safeEyeUv * uUvRect.zw;
vec2 sampleUv = (uStMatrix * vec4(packed, 0.0, 1.0)).xy;

fragColor = texture(uTexture, sampleUv);
```

Clamping alone repeats edge pixels. Invalid logical domains must be rejected first.

External textures require clamp-to-edge wrapping; retain the existing configuration rather than attempting a border-color wrap mode. [Khronos external-image texture specification](https://registry.khronos.org/OpenGL/extensions/OES/OES_EGL_image_external.txt)

### Per-eye safe bounds

A whole-frame clamp does not stop bilinear filtering across the SBS midpoint.

For an identity producer transform and eye dimensions \(W_e,H_e\):

\[
\delta_u=\frac{1}{2W_e},\qquad
\delta_v=\frac{1}{2H_e}
\]

\[
u_{\min}=\delta_u,\quad u_{\max}=1-\delta_u
\]

and similarly for \(v\).

For crop/rotation, calculate safe margins from the composed eye-to-producer affine transform and actual producer texel dimensions. Do not simply clamp transformed coordinates to an axis-aligned eye rectangle.

Let:

\[
p=Ae+b
\]

For an axis-aligned bilinear footprint with half-widths \(h_x,h_y\), conservative eye-space margins are:

\[
\delta_i=
|(A^{-1})_{i0}|h_x+
|(A^{-1})_{i1}|h_y
\]

Do not assume decoded display dimensions always equal producer allocation dimensions. When the actual texel footprint cannot be established, use validated metadata for supported transforms and retain the producer’s crop protection; treat generalized seam isolation as unverified until tested on the decoder path.

Fisheye validity is evaluated before this rectangular sampling protection. A black encoded lens border must not be stretched over out-of-FOV directions.

### Framebuffer and render-state invariants

At each eye pass:

- Bind the intended framebuffer.
- Set viewport and scissor for that framebuffer.
- Restore color and depth write masks before clearing.
- Clear color to opaque black and clear depth.
- Establish video depth/blend state explicitly.
- Draw video, then HUD/overlay/reticle.
- Ensure compositor sampling is black outside valid eye texture bounds.

Do not claim UV clamping repairs fragments outside the cylinder: ordinary cylinder UVs already lie in \([0,1]\), and sampling cannot create geometry beyond rasterized triangles.

Keep `GroundGridPolicy.visibleOn(PLAYER) == false` for every projection, HUD state, and thermal level. Remove the redundant private `groundVisible` helper in `AppScene` or delegate it to the policy.

### Tests

Extend `CylinderScreenTest` and add `VideoUvBoundsTest`:

- UV range and finite vertices.
- Exact vertical extent and bottom-edge position.
- Inward winding and vertex count.
- Invalid dimensions.
- Mesh invalidation on aspect/size/distance changes.
- No rebuild when unchanged.
- SBS/TB full versus half aspect.
- Identity, flip, crop, and rotation transforms.
- Separate eye colors at internal boundaries.
- Reject-before-clamp behavior.

Extend `GroundGridPolicyTest` across every mode and HUD state.

Add instrumented `VideoBoundaryRenderTest` and `EyeFramebufferClearTest` using the production external-texture shader. Assert black pixels outside the projected screen with HUD/overlay/reticle disabled.

Also test distortion enabled/disabled, context recreation, aspect changes, projection switches, and playback pause/resume.

---

## Implementation sequence

1. **Pure contracts:** layout models, window helper, typed controls, projection mapping metadata.
2. **List UI:** compact typography, measured capacity, persistent window, bounded animation.
3. **Chooser:** persistent scrolling and gaze revalidation.
4. **HUD:** raised placement, shared timeline geometry, continuous gaze payload.
5. **Transport:** queue snapshot, pending transitions, effect integration.
6. **Rendering:** cylinder cache invalidation and UV-domain protection.
7. **Fisheye:** pure math, cap geometry, shader variant, scene dispatch.
8. **Integration:** device fixtures, performance checks, documentation updates.

Keep commits independently reviewable. Do not combine unrelated changes to auto-detection, projection persistence, optics calibration, or autoplay.

## Verification suite

Run focused tests during each implementation step, then:

```bash
./gradlew :app:testDebugUnitTest \
  :vrcore:testDebugUnitTest \
  :playback:testDebugUnitTest \
  :upnp:testDebugUnitTest

./gradlew :app:assembleDebug :app:lintDebug
```

Existing app lint configuration uses `abortOnError = false`; inspect findings rather than treating task success as a clean lint report.

With a suitable connected device:

```bash
./gradlew :app:connectedDebugAndroidTest \
  :vrcore:connectedDebugAndroidTest
```

Ordinary JVM tests use Android return-default stubs. They cannot validate Canvas drawing, shader compilation, or actual GPU sampling.

Required device fixtures:

- Long mixed local/UPnP lists.
- One-hour seek timeline with known timestamps.
- Three-track queue with distinct titles and projection overrides.
- SBS left/right color separation.
- Equirectangular longitude/latitude grid.
- Four equidistant polar-grid fisheye fixtures.
- Bright bottom scanline on otherwise black video.
- Portrait, landscape, full-SBS, and full-TB videos.

Performance acceptance:

- No mesh rebuilds in steady-state playback.
- No decoder seek calls during gaze hover.
- No stationary chooser repaints from scrolling calculations.
- List animation stops repainting after settling.
- Exact timeline state is retained independently of repaint throttling.
- Compare p50/p95 frame times and dropped frames against baseline on the same device, clip, and thermal state; investigate a p95 increase above 1 ms.
- At 60 Hz, preserve the 16.67 ms frame budget.

## Backward compatibility and release gates

Preserve:

- Existing projection enum names and relative ordering.
- Existing equirectangular mapping, stereo packing, and recenter yaw sign.
- `SurfaceTexture` application exactly once.
- Six-item grid paging.
- Source-qualified media identity.
- Folder navigation restoration.
- Existing trigger and relative seeking.
- Existing decoder/resource ranking and resume storage.
- Nonplayer typography and minimum hit-target policy.
- Ground-grid suppression during playback.

Release only after all eight outcomes are demonstrated:

1. Compact list text is visibly smaller and readable.
2. Lists advance continuously without six-item swaps.
3. Non-bin timeline positions seek accurately.
4. Dedicated transport controls work for both media sources.
5. Every projection option is reachable in both directions.
6. HUD controls are comfortably visible and selectable in SBS.
7. All four fisheye fixtures match the equidistant equations.
8. No video-colored pixels appear below the cylinder in pre-distortion or final output.