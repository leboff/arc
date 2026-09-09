# Arc VR Player — PLAY'A-Class UI Redesign & Local Media Engine
## Authoritative Reviewed Implementation Blueprint

**Status:** Reviewed & Final, v2.0 — supersedes [UI_REDESIGN_PLAN.md](UI_REDESIGN_PLAN.md) v1.0
**Reviewer:** Claude Opus (Principal Systems & VR Architect)
**Execution agent:** Claude Sonnet, via [SONNET_BUILD_PROMPT.md](SONNET_BUILD_PROMPT.md)
**Baseline:** `master` @ `ec34a48` (v0.2.5 — UPnP discovery/browse + Media3 playback verified on Pixel 11 Pro)
**Binding companions:** [ARCHITECTURE.md](ARCHITECTURE.md), [UI_GAZE_PLAN.md](UI_GAZE_PLAN.md), [TESTING.md](TESTING.md)

> **Precedence.** Where this document and `UI_REDESIGN_PLAN.md` v1.0 disagree, **this document wins**.
> Where this document and `ARCHITECTURE.md` disagree, the amendments in §16 are the authorised deltas;
> everything else in `ARCHITECTURE.md` still binds.

---

## 0. Review verdict

The v1.0 plan gets the *product* right — three curved columns, a detached dock, a local-media source,
an OLED void with a grounded floor — and the milestone ordering is sound. It is **not executable as
written**. It contains four defects that would fail on the first build or the first frame, and eleven
that would produce a shipping regression or an unmaintainable seam.

The most important of these is not a number: **v1.0 never says how a three-column layout plus a
detached dock is supposed to be gaze-tested.** `AppScene.runGazePipeline` raycasts exactly one
`ScreenPanel` per frame (`app/src/main/java/com/daydreamvr/player/render/AppScene.kt:212`). Any design
with more than one interactive surface at more than one depth is architecturally blocked until that
pass becomes multi-surface. That work is not in v1.0's milestone list at all.

### 0.1 Findings register

Severity: **B**locker (won't build / won't run), **R**egression (breaks something verified working),
**D**esign (ships but wrong), **P**recision (doc says a number that isn't the number).

| # | Sev | Finding | Disposition |
|---|-----|---------|-------------|
| R1 | **B** | Gaze pass tests one surface only; 3 columns + detached dock needs nearest-hit over N surfaces. | §3.3 — new `activeGazeSurfaces()` + nearest-hit resolution. New M2. |
| R2 | **B** | `1920×1080` @ `2.81 m × 1.45 m` gives horizontal 683.3 px/m vs vertical 744.8 px/m — **8.3 % anisotropic**. `PanelAspectTest` (an existing, passing, enforced test) fails; all text and every icon renders stretched. | §2 — re-derived to `1536 × 800` @ `2.80 m × 1.458333 m`, ratio `1.000000`. |
| R3 | **B** | `content://` URIs cannot be opened. `ExoVideoPlayer` builds `DefaultMediaSourceFactory(OkHttpDataSource.Factory(...))` (`playback/.../ExoVideoPlayer.kt:153,163`) — an HTTP-only upstream. Local playback fails 100 % of the time. | §8.6 — wrap in `DefaultDataSource.Factory(context, httpFactory)`. |
| R4 | **B** | `ScreenPanel.anchor` is `val anchor = PanelAnchor()` — hard-wired to `distanceM = 2.5f` (`ScreenPanel.kt:33`). The dock cannot be placed at any other depth, and v1.0's `R = 2.30 m` is unreachable without a signature change. | §3.2 — add `distanceM` ctor param; keep the F7 `require`. |
| R5 | **R** | `MediaNode.Video.thumbnailBitmap: Bitmap?` inside `AppState`. `AppState` is read by the GL thread every frame and `ScreenPanel.renderIfChanged(key)` compares slices by `equals`. Bitmaps make that comparison identity-based and unstable, pin decoder memory in immutable state, and make every reducer test need a real `Bitmap`. | §7.2 — **no Bitmap ever enters `AppState`.** Renderer queries `ThumbnailCache` by key. |
| R6 | **R** | `MediaNode.Video.uri: android.net.Uri` in `:app` state. `:app` runs unit tests with `unitTests.isReturnDefaultValues = true`, under which `Uri.parse` returns `null`. Every red-first reducer test becomes vacuous or needs Robolectric. | §7.1 — `MediaRef` value class over `String`. |
| R7 | **R** | v1.0 M2 says "refactor `BrowseState` and `AppStateMachine` to operate over `MediaSource`/`MediaNode`" as a single step. `BrowseFrame` carries `MediaServer` + `DidlContainer`/`DidlItem` and `Effect.Play` carries `DidlItem`; `NavigationTest`, `BrowseReducerTest`, `ScrollWindowTest`, `EscapeHatchTest` all assert on those types. Big-bang breaks verified UPnP playback. | §10.7 — strangler sequence with a green gate between each step. |
| R8 | **R** | v1.0 collapses UPnP *fetch* paging (`PageRequest`, `PAGE_FETCH = 200`, `hasMorePages`) into grid *display* paging (`PageNext`/`PagePrev`). They are different concerns; merging them breaks `pageBrowse` and stalls large containers. | §10.5 — `gridPage` is derived, never stored; fetch paging untouched. |
| R9 | **R** | `LruCache<String, Bitmap>(maxSize = 64)` sizes by **count**, not bytes. 64 × 320×240 ARGB_8888 = **19.6 MB** of unbounded-growth risk, and `ARCHITECTURE.md §14` already caps thumbnails at 24 MB. | §9.3 — `SizedLruCache` in KB, RGB_565, 24 MB ceiling. |
| R10 | **R** | Runtime storage permission requested from `VrActivity` renders a mono 2D system dialog while the user is inside a headset — unreadable and untappable. | §8.2 — permission is **lobby-only** (`SetupActivity`), with an in-VR "grant on the phone" dead-end state. |
| R11 | **D** | Three columns as three independent `ScreenPanel`s would each run their own `PanelAnchor` lazy-follow and visibly **shear apart** during head turns. | §3.1 — one texture, three regions. Dock's anchor is *slaved*, not independent. |
| R12 | **D** | Panel half-span `±35°` sits exactly on `PanelAnchor.followThresholdDeg = 35f`. Looking at the far edge of a sidebar starts the panel sliding away from you. | §2.4 — span reduced to `±32.086°`, threshold raised to `45°` for the browse panel. |
| R13 | **D** | `±35°` also violates `AngularMetrics.COMFORT_H_DEGREES = 25f` and `ARCHITECTURE.md §11.3`, with no stated justification. | §2.5 — two-tier comfort model (`eyes-only 25°` / `head-turn 35°`), added as new constants, old ones untouched. |
| R14 | **D** | Dock at `y = −0.65 m, D = 2.05 m` is `−17.6°`; the v1.0 panel's bottom edge is `−17.5°`. They are angularly coincident — overlapping, ambiguous to the gaze ray, and visually colliding. | §2.6 — dock re-sited to a `−20.87° … −26.98°` band with a `2.79°` gutter. |
| R15 | **D** | v1.0 specifies a horizontal 3-tab source switcher in a `19.6°` sidebar → `~5°` per tab, which cannot hold the word "Network" at the `1.5°` type minimum. | §5.1 — vertical 3-row switcher at `2.73°` per row. |
| R16 | **D** | "Wireframe grid" implies `GL_LINES`. Thin world-space lines through a barrel-distortion resample shimmer violently under head motion. | §12.2 — procedural `fwidth()`-antialiased grid in the fragment shader; constant ~1 px line width at any distance. |
| R17 | **D** | Grid at `y = −1.2 m` is not suppressed in `PLAYER`. Inside a `SphereScreen` (`EQUIRECT_180/360`) the floor plane is *inside* the sphere and paints over the film. | §12.4 — grid drawn only in `SERVER_LIST`/`BROWSE`/`SETTINGS`. |
| R18 | **D** | Sorting is specified with no rule for what happens to `focusIndex` (an index into `rows`), and no acknowledgement that UPnP sorting can only ever cover *loaded* pages. | §10.4 — focus is remapped by node id; the sort chip is labelled `Sort: Date (loaded)` when `hasMorePages`. |
| R19 | **D** | Full-panel repaints with six decoded thumbnails happen on the GL thread (`AppScene.update` → `browse.render(state)`), inside a 6 ms CPU frame budget (`ARCHITECTURE.md §14`). | §13.1 — panel painting moves to a dedicated single-thread executor; thumbnail arrivals coalesce at 120 ms. |
| R20 | **P** | Centre-column azimuth stated as `+1.5°`; the stated pixel split (538 / 883 / 499) actually centres it at `+0.71°`. And `538 + 883 + 499 = 1920` leaves **zero** pixels for the two 20 px gutters the same section requires. | §2.3 — layout re-derived, gutters budgeted, centre at exactly `0.000°`. |
| R21 | **P** | `H = 1.45 m ≈ 36.1°` uses the arc-length identity `θ = H/R`, but the panel is **flat** vertically; its true subtense is `2·atan(H/2R) = 34.98°`. | §2.2 — both conventions stated; `PanelMetrics` uses arc-length, and the ≤4 % divergence is documented, not hidden. |
| R22 | **P** | "`27.4 px/deg`, matching Pixel 11 Pro display density" — texture density is not display density. The binding limit is per-eye display px/deg *through the lens*, ≈ 20 px/deg at the centre of field. | §2.7 — density budget derived from the optics, target `23.94 px/deg`. |
| R23 | **P** | `DATE_MODIFIED` is documented in v1.0 alongside `DURATION` with no unit note. `MediaStore` returns `DATE_MODIFIED`/`DATE_ADDED` in **seconds** and `DURATION` in **milliseconds**. Mixing them silently dates every file to 1970. | §8.3 — `×1000` at the mapper boundary, asserted by test. |
| R24 | **P** | `R = D = 2.30 m` is proposed without a stated benefit. Given angle-first sizing, `R` is ergonomically inert: apparent size is angular and unchanged; vergence differs by `0.13°` at 63 mm IPD; accommodation is fixed by the lens. Changing it costs a re-verification of every panel and the F7 invariant for nothing. | §2.1 — **`R = D = 2.50 m` retained.** |

### 0.2 What is adopted from v1.0 without change

Three-column information architecture; detached bottom dock; `MediaSource` / `MediaNode.Folder` /
`MediaNode.Video` as the unifying shape; `BUCKET_DISPLAY_NAME` bucket grouping;
`ContentResolver.loadThumbnail`; projection-override in the inspector; OLED-black void with a grounded
floor plane at `y = −1.2 m`; the milestone *ordering* (storage → domain → widgets → environment).

---

## 1. Design principles (binding)

1. **Angle first, metres second.** Every dimension is authored in degrees of visual angle and converted
   to metres by `x_m = R · θ_rad`. Radius `R` is then a free parameter that changes nothing perceptual.
2. **One texel scale per surface.** A panel's texture must be isotropic: `widthPx/widthM ≡ heightPx/heightM`
   within 1 %. This is already enforced by `PanelAspectTest` and is non-negotiable.
3. **The code that draws a box publishes that box.** Every hit region comes out of the same
   `measureLayout` call that produced the pixels (`UI_GAZE_PLAN.md §2.4`). No parallel geometry.
4. **`AppState` holds no Android objects, no GL objects, and no bitmaps.** It must stay a pure Kotlin
   value type that a JVM test can construct and a `data class` can compare cheaply.
5. **The reducer is pure and total.** No clock, no IO, no `Uri`, no `Context`. Time arrives via `Event.Tick`.
6. **Nothing below `1.5°` of text, nothing below `2.0°` of hit target.**
7. **Never break what is verified working.** UPnP browse and Media3 playback are green on-device;
   every milestone ends on a green `./gradlew test`.

---

## 2. Spatial geometry — derivation

### 2.1 Fixed parameters

| Symbol | Value | Source |
|---|---|---|
| `R` — panel cylinder radius = anchor distance | **2.50 m** | `PanelAnchor.distanceM` default; F7 requires `curveRadiusM == distanceM` |
| `R_dock` | **2.05 m** | new; §2.6 |
| `θ_total` — browse panel horizontal span | **64.1713°** (1.12 rad) | derived below |
| `W` — browse panel arc width | **2.80 m** | `W = R · θ_total,rad = 2.50 × 1.12` |
| `H` — browse panel height | **1.458333 m** | derived from isotropy |
| Texture | **1536 × 800** | derived from §2.7 |
| `ppd` — panel pixel density | **23.9359 px/deg** | `1536 / 64.1713` |

`R = 2.50 m` is **retained from the existing code**, against v1.0's proposed 2.30 m. Justification:
with angle-first sizing the apparent size of everything is `θ`, which is invariant in `R`. Vergence at
63 mm IPD is `2·atan(31.5/2500) = 1.44°` versus `1.57°` at 2.30 m — both far inside the comfort zone,
and accommodation is pinned at the lens focal distance regardless. Changing `R` would force a
re-derivation of every panel in `PanelAspectTest`, a re-check of the F7 invariant, and a re-run of
on-device optics calibration, in exchange for nothing measurable. If a future build wants a different
radius, it is a one-line change to `BrowseLayout.RADIUS_M` and the tests re-derive.

### 2.2 The two angular conventions, stated explicitly (R21)

A curved panel is a cylinder section horizontally and a **flat** strip vertically. Two different
formulas therefore apply, and the codebase uses the first for both:

* **Arc-length (what `PanelMetrics` uses):** `θ = s / R`, linear in `s`. This is what makes
  `PanelMetrics.pxPerDegree` a single constant and what lets `Type` tokens be authored in degrees.
* **True subtense:** horizontal is exactly `θ = s/R` (a genuine arc). Vertical is
  `θ_v = 2·atan(H / 2R) = 2·atan(1.458333 / 5.0) = 33.02°`, against the arc-length value of `33.4225°`
  — a **1.2 % divergence**, well below the 1° perceptual threshold at this scale.

**Rule:** all specification, layout and tests in this document use the arc-length convention, matching
`PanelMetrics`. Where a value must be exact in world space (the ground grid, dock placement, comfort
checks against the true visual field) the `atan` form is used and is labelled *(true)*.

### 2.3 Column split (replaces v1.0 §2.2)

Gutter `g = 24 px = 1.0026°`. Content width `1536 − 2g = 1488 px`. Centre column `C = 700 px`; the two
sidebars take `(1488 − 700)/2 = 394 px` each — **symmetric by construction**, so the centre column's
azimuth is exactly `0.000°` rather than v1.0's accidental `+0.71°`.

| Region | px range | width px | width ° | azimuth range | centre az. |
|---|---|---|---|---|---|
| **Left — Sources & Folders** | `[0, 394)` | 394 | 16.461° | −32.086° … −15.625° | **−23.855°** |
| gutter | `[394, 418)` | 24 | 1.003° | | |
| **Centre — Media Grid** | `[418, 1118)` | 700 | 29.245° | −14.622° … +14.622° | **0.000°** |
| gutter | `[1118, 1142)` | 24 | 1.003° | | |
| **Right — Inspector** | `[1142, 1536)` | 394 | 16.461° | +15.625° … +32.086° | **+23.855°** |

Azimuth of a panel pixel: `az(x) = (x/W_px − 0.5) · θ_total`.

### 2.4 Lazy-follow interaction (R12)

`PanelAnchor.followThresholdDeg` defaults to `35°`. A user reading the outer edge of a sidebar turns
their head to `32.086°` — `2.9°` of margin before the panel starts easing away from them. That is not
enough; small residual head motion will trip it.

**Required:** `BrowseScreen` sets `anchor.followThresholdDeg = 45f`. The panel then stays put across
the entire reachable span and only follows a genuine body/seat reorientation. The `0.5 s` time
constant is unchanged.

### 2.5 Comfort model (R13)

`ARCHITECTURE.md §11.3` and `AngularMetrics.COMFORT_H_DEGREES = 25f` state a single `±25°` horizontal
limit. That figure is the **eyes-only** limit — the arc you can scan without moving your head. A
64° layout is read with small head rotations, which is normal and comfortable; the ergonomic
head-plus-eye limit is `±35°`.

**Required — additive, non-breaking** (existing constants and `isWithinComfortBox` keep their meaning
and their tests):

```kotlin
// vrcore/ui/AngularMetrics.kt
/** Eyes-only comfort: the arc scannable without rotating the head. Primary targets live here. */
const val COMFORT_H_DEGREES = 25f              // unchanged
/** Head-turn comfort: reachable with a small, natural neck rotation. Secondary targets may live here. */
const val COMFORT_H_HEAD_DEGREES = 35f         // new
/** Upward gaze is the costly direction. */
const val COMFORT_V_DEGREES = 20f              // unchanged
/** Downward gaze is the cheap direction — resting gaze already sits ~12° below horizontal. */
const val COMFORT_V_DOWN_DEGREES = 30f         // new
/** Smallest reliable gaze target with head-tracking jitter + the 60 ms stabilizer. */
const val MIN_TARGET_DEGREES = 2.0f            // new

/** Asymmetric comfort test: [yDeg] positive is up. */
fun isWithinComfortBoxAsym(xDeg: Float, yDeg: Float, allowHeadTurn: Boolean = false): Boolean
```

**Placement law**, asserted by test:

* every **column centre** ≤ `COMFORT_H_DEGREES` — satisfied at `±23.855°` and `0.000°`;
* every **panel edge** ≤ `COMFORT_H_HEAD_DEGREES` — satisfied at `±32.086°`;
* everything above the eye line ≤ `COMFORT_V_DEGREES` — satisfied at `+14.399°` *(true)*;
* everything below ≤ `COMFORT_V_DOWN_DEGREES` — satisfied at `−26.981°` *(true, dock bottom)*.

### 2.6 Vertical placement and the dock (R14)

Browse panel vertical centre sits `2.0°` below the eye line, so the grid falls into the natural resting
gaze cone: `verticalOffsetM = −R·tan(2°) = −0.087302 m`.

| Surface | `verticalOffsetM` | top *(true)* | bottom *(true)* | arc height |
|---|---|---|---|---|
| Browse panel (`R = 2.50`) | −0.087302 | **+14.399°** | **−18.086°** | 33.4225° |
| — gutter — | | | | **2.788°** |
| System dock (`R = 2.05`) | −0.912719 | **−20.874°** | **−26.981°** | 7.3200° |

Dock: `W_dock = 1.00 m` → `θ_dock = 1.00/2.05 = 27.9492°`; texture `672 × 176`;
`H_dock = 176 × 1.00/672 = 0.261905 m`; `ppd_dock = 672/27.9492 = 24.0437 px/deg`.
Isotropy: `672/1.00 = 672.0` and `176/0.261905 = 672.0` — **exact**.

The `2.788°` gutter is what makes nearest-hit gaze unambiguous: no ray can plausibly land on both
surfaces, and the dock's smaller radius means that even if one did, `min(PanelHit.distanceM)` resolves
to the dock, which is what the user is looking at.

### 2.7 Texture density budget (R22)

The binding limit is not the texture, it is how many display pixels the eye receives per degree
through the lens:

* Pixel 11 Pro class panel in landscape ≈ `2856 × 1280`; per eye `1428 × 1280`.
* `DeviceProfiles.CARDBOARD_V2.maxFovDegrees = FovAngles(50,50,50,50)` → 100° horizontal per eye.
* Naïve average: `1428 / 100 = 14.3 px/deg`. `EyeFramebuffer.renderScale = 1.15` supersamples the
  render target to `≈ 16.4 px/deg`.
* Barrel distortion concentrates samples at the centre of field by roughly 1.2–1.4×, so the effective
  centre-of-field rate is **≈ 20 px/deg**.

`PanelSurface` binds `GL_TEXTURE_EXTERNAL_OES` with `GL_LINEAR` and **no mipmaps**. Oversampling the
texture far past the display rate therefore buys nothing and starts to *cost* — minification aliasing
on 1 px hairlines and stroke ends. The sweet spot is a modest `1.1–1.3×` over the centre rate.

`ppd = 23.94` is `1.20×` the centre-of-field rate. This is the correct target, and it is why the
texture is `1536 × 800` (1.23 Mpx) and not v1.0's `1920 × 1080` (2.07 Mpx) — a 41 % saving in fill,
upload and repaint cost for no perceptible loss.

Resulting type sizes on the browse panel (unchanged `Type` tokens, resolved through `PanelMetrics`):

| Token | degrees | px on browse panel | px on dock |
|---|---|---|---|
| `screenTitle` | 2.30 | 55.1 | 55.3 |
| `rowTitle` | 1.85 | 44.3 | 44.5 |
| `rowSubtitle` / `meta` | 1.55 | 37.1 | 37.3 |
| `chip` / `sectionLabel` | 1.50 | 35.9 | 36.1 |

The existing browse panel is `1280 px / 55.0° = 23.27 px/deg`. At `23.94` the entire design system
carries across with **no token changes** — a deliberate constraint, and one v1.0's `27.4 px/deg` would
have broken by silently enlarging every glyph by 18 %.

### 2.8 Canonical constants

```kotlin
// app/src/main/java/com/daydreamvr/player/screens/BrowseLayout.kt  — PURE, no Android imports
object BrowseLayout {
    const val RADIUS_M          = 2.50f
    const val WIDTH_M           = 2.80f
    const val WIDTH_PX          = 1536
    const val HEIGHT_PX         = 800
    const val HEIGHT_M          = HEIGHT_PX * WIDTH_M / WIDTH_PX      // 1.458333f
    const val VERTICAL_OFFSET_M = -0.087302f                          // −2.0° (true)
    const val FOLLOW_THRESHOLD_DEG = 45f

    const val GUTTER_PX  = 24
    const val CENTRE_PX  = 700
    const val SIDEBAR_PX = (WIDTH_PX - 2 * GUTTER_PX - CENTRE_PX) / 2  // 394

    val leftX   = 0                       until SIDEBAR_PX                       // [0, 394)
    val centreX = SIDEBAR_PX + GUTTER_PX  until SIDEBAR_PX + GUTTER_PX + CENTRE_PX
    val rightX  = WIDTH_PX - SIDEBAR_PX   until WIDTH_PX
}

object DockLayout {
    const val RADIUS_M          = 2.05f
    const val WIDTH_M           = 1.00f
    const val WIDTH_PX          = 672
    const val HEIGHT_PX         = 176
    const val HEIGHT_M          = HEIGHT_PX * WIDTH_M / WIDTH_PX      // 0.261905f
    const val VERTICAL_OFFSET_M = -0.912719f                          // −24.0° (true)
    const val BUTTONS           = 6
}
```

---

## 3. Surface topology & the gaze pass

### 3.1 Two surfaces, not four (R11)

The three columns are **regions of a single `PanelSurface`**, not three panels. Reasons, in order of
weight:

1. Three `ScreenPanel`s means three `PanelAnchor`s. `PanelAnchor.update` is an exponential ease with
   independent state, so during a head turn the columns would slew at slightly different phases and
   visibly **shear apart**. A single texture cannot shear.
2. `renderIfChanged` is keyed per panel. Three panels means three keys, three `lockHardwareCanvas`
   round-trips and three `SurfaceTexture` allocations to paint one logically atomic view.
3. Gaze cost is linear in surfaces; two is cheap, four is not.

The dock **is** a separate `ScreenPanel` because it is at a different radius (2.05 m vs 2.50 m) —
that is the entire point of "detached", and F7 forbids a panel whose curve radius differs from its
anchor distance.

| Surface | Class | Texture | `widthM × heightM` | `R` | anchor |
|---|---|---|---|---|---|
| Browse | `BrowseScreen` | 1536 × 800 | 2.80 × 1.458333 | 2.50 | own, threshold 45° |
| Dock | `SystemDockScreen` | 672 × 176 | 1.00 × 0.261905 | 2.05 | **slaved** to browse |

**Slaving (required).** `SystemDockScreen` must not run its own lazy-follow, or it will drift relative
to the panel it hangs beneath. Each frame, `AppScene.update` does
`dock.anchor.snapTo(browse.anchor.yawRad)` **after** the browse anchor has been updated.

### 3.2 `ScreenPanel` gains a distance (R4)

```kotlin
abstract class ScreenPanel(
    protected val panel: PanelSurface,
    protected val theme: Theme,
    panelWidthM: Float = 2.2f,
    panelHeightM: Float = 1.35f,
    distanceM: Float = 2.5f,                     // NEW
) {
    val anchor = PanelAnchor(distanceM = distanceM)
    // metrics, quad and the F7 require() are unchanged — they already read anchor.distanceM.
}
```

`verticalOffsetM` is already an `open val` and already flows into both `gazeGeometry()` and
`drawGl`'s `Matrix.translateM`. No change needed there — it is correct as written.

### 3.3 Multi-surface gaze (R1)

```kotlin
// AppScene
/** Interactive surfaces for this state, in paint order. Overlay, when up, suppresses all others. */
private fun activeGazeSurfaces(state: AppState): List<ScreenPanel> {
    if (overlay?.visible == true) return listOfNotNull(overlay)
    return when (state.screen) {
        VrScreen.SERVER_LIST -> listOfNotNull(serverList)
        VrScreen.BROWSE      -> listOfNotNull(browse, dock)      // ← two surfaces
        VrScreen.SETTINGS    -> listOfNotNull(settings)
        VrScreen.PLAYER      -> if (state.hud.visible) listOfNotNull(hud) else emptyList()
    }
}

private fun runGazePipeline(state: AppState, pose: FloatArray, dt: Float) {
    val surfaces = activeGazeSurfaces(state)
    if (surfaces.isEmpty() || !trackerCalibratedProvider()) { /* …unchanged clear path… */ return }

    val ray = GazeRay.fromPose(pose, neckOffsetProvider())

    // Nearest hit wins. Ties are impossible in practice (§2.6 gutter) and resolved by list order.
    var best: PanelHit? = null
    var bestTarget: GazeTarget? = null
    for (s in surfaces) {
        val h = PanelRaycast.intersect(ray, s.gazeGeometry()) ?: continue
        if (best != null && h.distanceM >= best.distanceM) continue
        val t = s.hitMap.hitTest(h.xPx, h.yPx)
        best = h
        bestTarget = t                                  // may be null: on the panel, not on a target
    }

    val stable = stabilizer.update(bestTarget, dt)
    if (stable != lastDispatched) { lastDispatched = stable; onGazeTarget(stable) }

    // Reticle: the winning hit, else an unbounded solve against the PRIMARY surface only.
    val place = best ?: PanelRaycast.intersectUnbounded(ray, surfaces.first().gazeGeometry())
    // …unchanged placement bookkeeping…
}
```

Three properties this must preserve, each an acceptance test:

* **Edge-triggered dispatch** — one `onGazeTarget` per hover *change*, never per frame.
  (`UI_GAZE_PLAN.md §3.1`.)
* **Nearest wins** — a ray that intersects both bounded quads reports the smaller `distanceM`.
* **On-panel-off-target** — a hit with no matching `HitRegion` yields `null`, which is a legitimate
  target that `GazeStabilizer` debounces like any other. It must **not** fall through to a farther
  surface, or looking at the panel's empty background would highlight something behind it.

### 3.4 Draw order

```
clear to #000000 (opaque, OLED void)
  → GroundGrid           (blend, depth-test off, depth-write off)   §12
  → BrowseScreen quad
  → SystemDockScreen quad
  → OverlayRenderer quad (when visible)
  → Reticle              (last, both eyes, from the same cached hit)
```

Painter's order is sufficient and no depth test is required: the panels sit at 2.05–2.50 m and the
ground plane at a downward pitch of `φ` is at `1.2/sin φ` — at the panel's lowest edge (`−18.086°`)
that is `3.87 m`, always behind. Asserted as a static geometric test, not left to runtime luck.

---

## 4. Design system

No new tokens. `Theme`, `Type`, `Space`, `Radius`, `Surfaces` and `Icons` carry over verbatim — that
compatibility is a design constraint (§2.7), not a coincidence. Three additions only:

```kotlin
// vrcore/ui/Icons.kt — extend the enum; paths authored on the same 24×24 grid.
enum class Icon {
    NONE, FOLDER, VIDEO, PLAY, PAUSE, CHECK, SERVER, PLUS, REFRESH,
    GEAR, CHEVRON, WARNING, SEARCH, BACKSPACE, ENTER, SPACE, SPINNER,
    // new:
    PHONE, NETWORK, STAR, SORT, GRID, RECENTER, EXIT, ASPECT, CHEVRON_LEFT, CHEVRON_RIGHT,
}

// vrcore/ui/Theme.kt — badge palette for format tags. Desaturated per the no-saturated-glyph rule.
val badgeVr      = 0xFF9FD8B8.toInt()   // VR180 / VR360
val badge3d      = 0xFFD8B89F.toInt()   // SBS / TB
val badgeQuality = 0xFFB8B8D8.toInt()   // 4K / HEVC
```

Badges are drawn with `Surfaces.chip(accented = false)` plus a coloured 1.5 px stroke — never as
coloured fills behind light text, which blooms through passive optics.

---

## 5. Widget layout contracts

All four widgets take `(theme: Theme, metrics: PanelMetrics, measure: TextMeasure = TextMeasure.PAINT)`
and expose the same shape as `ListView`:

```kotlin
fun measureLayout(model: <Model>, bounds: RectF): <Layout>   // PURE — no Canvas, no Paint
fun draw(canvas: Canvas, model: <Model>, layout: <Layout>, focus: BrowseFocus?, hover: GazeTarget?)
fun hitRegions(layout: <Layout>): List<HitRegion<GazeTarget>>
```

`measureLayout` being pure and `TextMeasure` being injectable is what makes every box below assertable
in a plain JVM test under `unitTests.isReturnDefaultValues = true`. This is the mechanism that closed
defect class F2 in `UI_GAZE_PLAN.md`; it is mandatory for the new widgets, not optional.

### 5.0 Vertical band budget (browse panel, 800 px)

Derived with `ppd = 23.9359`, `Space.L = 0.65° = 15.56 px`, `Space.M = 0.45° = 10.77 px`, and
`ListView`'s font-metric row height `padV + 0.95·size + 0.27·size + padV`.

| Band | height px | height ° | y range |
|---|---|---|---|
| Header / breadcrumb (`drawHeader` contract) | 75.04 | 3.135 | `[0, 75.04)` |
| Toolbar (sort / filter / view chips) | 65.35 | 2.730 | `[75.04, 140.39)` |
| **Grid band** | **578.71** | **24.178** | `[140.39, 719.10)` |
| Footer (pagination) | 65.35 | 2.730 | `[719.10, 784.45)` |
| Bottom pad | 15.56 | 0.650 | `[784.45, 800)` |

Standard row height (`Type.rowTitle`, one line): **75.57 px = 3.157°** — comfortably above
`MIN_TARGET_DEGREES`. Compact row (`Type.chip`): **65.35 px = 2.730°**.

### 5.1 `SourceSidebarWidget` — left column, x ∈ [0, 394) (R15)

```
y=   0.00 ┌──────────────────────────────┐  394 px / 16.461°
          │  SOURCES            (header) │  75.04
   75.04  ├──────────────────────────────┤
          │ ▸ [phone]   Device       12  │  65.35   ← source rows, Type.chip, 2.730° tall
  140.39  │   [net]     Network       3  │  65.35
  205.74  │   [star]    Favourites    8  │  65.35
  271.09  ├──────────────────────────────┤  divider
  284.45  │ [folder] Camera         41   │  75.57   ← folder rows, Type.rowTitle, 3.157° tall
          │ [folder] Movies         12   │  75.57
          │ [folder] Download        7   │  75.57      6 rows visible
          │ [folder] Oculus          3   │  75.57
          │ [folder] Screenshots    22   │  75.57
          │ [folder] VR             15   │  75.57
  784.45  └──────────────────────────────┘
```

* **Vertical** switcher, three full-width rows. A horizontal 3-tab strip gives `394/3 = 131 px = 5.5°`
  per tab; the word "Favourites" at the `1.5°` minimum needs ~180 px. It does not fit, at any type
  size that is legible. (R15.)
* Folder list is a plain `ListView` with `Entry.Item(title, icon = FOLDER, trailing = count)`.
* **Visible-row count is measured, never assumed.** `AppScene.reportListWindowOnce` already exists for
  exactly this (`ListWindow`, fixes F5); extend `ListWindow` with `sidebar: Int` and feed it from
  `sidebar.visibleRows()`. The computed 6 above is informative — the measured value is authoritative.
* Hit regions: `GazeTarget.SourceTab(index)` ×3, `GazeTarget.SidebarRow(absoluteIndex)` ×N.

### 5.2 `MediaGridWidget` — centre column, x ∈ [418, 1118)

**3 columns × 2 rows = 6 cards per page**, derived, not chosen:

```
padH   = px(Space.L) = 15.56
gapH   = px(Space.M) = 10.77
cardW  = (700 − 2·padH − 2·gapH) / 3           = 215.78 px = 9.015°
poster = cardW · 9/16                           = 121.38 px  (16:9)
title  = 2 lines × px(1.55°) × 1.18             =  87.56 px
meta   = 1 line  × px(1.55°) × 1.18             =  43.78 px
cardH  = poster + px(Space.S) + title + meta    = 259.89 px = 10.858°
pitch  = gridBand.height / ROWS = 578.71 / 2    = 289.36 px = 12.089°   (29.5 px slack → cell bottom pad)
```

A 3×3 grid would force `cardW ≈ 143 px = 5.97°` and a poster of 143×80 — below the threshold at which a
4K keyframe conveys anything. **3×2 is the answer; 3×3 is rejected on legibility.** A future dense
mode may add 4×2 (`cardW = 159 px = 6.65°`) behind the existing R3 grid/list toggle, but it is out of
scope here.

Card anatomy, all offsets from the card's top-left, all sizes resolved through `metrics.px()`:

| Element | Geometry | Style |
|---|---|---|
| Poster | `(0, 0, cardW, 121.38)` | `Surfaces.card`; bitmap centre-cropped, or an `Icon.VIDEO` on a `cardFill` gradient when absent |
| Projection badge | top-left, inset `px(Space.S)`, `Type.chip` | `Surfaces.chip`, stroke `badgeVr` / `badge3d` |
| Quality badge | top-right, inset `px(Space.S)`, `Type.chip` | `Surfaces.chip`, stroke `badgeQuality` |
| Duration pill | bottom-right of poster, inset `px(Space.S)` | `Type.numeral` on `theme.scrim` |
| Watched bar | `(0, 121.38−3, cardW·f, 121.38)` | `theme.accent`, `theme.watched` when finished |
| Title | 2 lines from `y = 121.38 + px(Space.S)` | `Type.rowSubtitle`, ellipsised through `TextMeasure` |
| Meta | 1 line under title | `Type.meta` — `"3840×2160 · 4.2 GB"` |
| Focus / hover | on the **whole card box**, not the poster | `Surfaces.focus` / `Surfaces.hover` |

**The whole card is the hit region** — `(0,0,cardW,cardH)` = `9.015° × 10.858°`. Focusing only the
poster would leave a dead strip under every card where the reticle drops off the target.

Hit regions: `GazeTarget.GridCell(absoluteIndex)`. Absolute — `page·6 + slot` — never slot-relative
(`UI_GAZE_PLAN.md §3.2`).

### 5.3 `MediaInspectorWidget` — right column, x ∈ [1142, 1536)

Content width `394 − 2·15.56 = 362.88 px = 15.161°`. Band `[75.04, 784.45)` = 709.4 px.

Layout is **bottom-anchored for actions, top-down for information**, so the primary buttons can never
be pushed off the panel by a verbose file:

```
top-down:
  poster       0.80 · 362.88 = 290.30 px wide, 16:9 → 163.29 px tall, horizontally centred
  title        up to 2 lines, Type.rowTitle,  ellipsised
  meta rows    Type.meta, label left / value right (the existing drawDetailCard pattern),
               drawn in priority order until the next row would collide with the action block:
                 1 Resolution   2 Duration   3 Codec   4 Size
                 5 Audio        6 Frame rate 7 Modified 8 Container
bottom-anchored (always drawn, in this order upward from y = 784.45):
  [ PLAY ]                        75.57 px, Type.rowTitle, Surfaces.focus-styled primary
  [ RESUME 01:24:50 ]             75.57 px, shown only when ResumeStore has a live entry
  [ Projection: Auto (VR180) ]    65.35 px, Type.chip, cycles on activate
```

The information block is clipped by the action block, exactly as `BrowseScreen.drawDetailCard` already
clips with `if (y > top + height - pad) break`. Reuse that pattern; do not invent scrolling here.

Hit regions: `GazeTarget.InspectorAction(Action.PLAY | RESUME | PROJECTION)`.

### 5.4 `SystemDockWidget` — separate surface, 672 × 176 px

Six equal cells: `672/6 = 112 px = 4.658°` wide, `176 px = 7.320°` tall. Every cell is far above
`MIN_TARGET_DEGREES`, which is the point of a dock — it must be hittable with a glance.

| Slot | Icon | `GazeTarget.DockButton` | Action |
|---|---|---|---|
| 0 | `RECENTER` | `RECENTER` | `Effect.Recenter` |
| 1 | `GEAR` | `SETTINGS` | `screen = SETTINGS` |
| 2 | `ASPECT` | `CALIBRATE` | `screen = SETTINGS`, focus "Screen size" |
| 3 | `GRID` | `VIEW_MODE` | toggle grid / list |
| 4 | `REFRESH` | `RESCAN` | `Effect.StartDiscovery(force=true)` or `Effect.RescanLocal` |
| 5 | `EXIT` | `EXIT` | `Overlay.Confirm("Leave the app?", …)` — never quits directly |

Icon `sizePx = metrics.px(2.6f)` centred at `(cell.centerX, 62)`; label `Type.chip` centred at
baseline `y = 130`. Focus/hover paints `Surfaces.focus`/`hover` on the full cell rect.

The dock is drawn only on `VrScreen.BROWSE`. It is **not** a global chrome element — putting it under
`SETTINGS` or `SERVER_LIST` would duplicate affordances those screens already own.

---

## 6. Gaze targets

```kotlin
// app/state/GazeTarget.kt — additions. Existing members are untouched.
sealed interface GazeTarget {
    // …ServerRow, BrowseRow, SettingsRow, HudControl, DialogButton, KeyboardKey — unchanged…

    data class SourceTab(val index: Int) : GazeTarget                 // 0=Device 1=Network 2=Favourites
    data class SidebarRow(val index: Int) : GazeTarget                // absolute folder index
    data class GridCell(val index: Int) : GazeTarget                  // absolute item index
    data class BreadcrumbSegment(val depth: Int) : GazeTarget
    data class ToolbarChip(val chip: Chip) : GazeTarget
    data class PageButton(val forward: Boolean) : GazeTarget
    data class InspectorAction(val action: Action) : GazeTarget
    data class DockButton(val button: Dock) : GazeTarget

    enum class Chip { SORT, FILTER, VIEW }
    enum class Action { PLAY, RESUME, PROJECTION }
    enum class Dock { RECENTER, SETTINGS, CALIBRATE, VIEW_MODE, RESCAN, EXIT }
}
```

`BrowseRow` is retained for the legacy list view and for the strangler window (§10.7); it is not
removed by this work.

**Hit-target invariant (new, enforced):** for every `HitRegion` any widget publishes,
`min(metrics.deg(width), metrics.deg(height)) ≥ AngularMetrics.MIN_TARGET_DEGREES`. This is a single
test over all four widgets driven by `TextMeasure.fixed`.

---

## 7. Unified media domain

New package `com.daydreamvr.player.media` in `:app`. **Pure Kotlin — no `android.*` imports.** That
constraint is what keeps the reducer JVM-testable, and it is the reason for both R5 and R6.

### 7.1 Core types

```kotlin
/** An opaque, JVM-safe reference to playable bytes. Parsed to android.net.Uri only inside EffectRunner. */
@JvmInline value class MediaRef(val value: String) {
    val isContent: Boolean get() = value.startsWith("content://")
}

/** Stable identity across source switches, sorts, pages and process restarts. */
data class MediaKey(val sourceId: String, val nodeId: String) {
    /** The ResumeStore / projection-override key. Format is frozen — it is persisted. */
    fun storageKey(): String = "$sourceId|$nodeId"
}

sealed interface MediaSource {
    val id: String            // "upnp:uuid:abc-123" | "local" | "favourites"
    val title: String
    val icon: Icon

    data class Upnp(val server: MediaServer) : MediaSource {
        override val id get() = "upnp:${server.udn}"
        override val title get() = server.friendlyName
        override val icon get() = Icon.NETWORK
    }
    data object Local : MediaSource {
        override val id = "local"; override val title = "Device"; override val icon = Icon.PHONE
    }
    data object Favourites : MediaSource {
        override val id = "favourites"; override val title = "Favourites"; override val icon = Icon.STAR
    }
}

sealed interface MediaNode {
    val id: String
    val title: String
    val parentId: String?

    data class Folder(
        override val id: String,
        override val title: String,
        override val parentId: String? = null,
        val childCount: Int? = null,
        val icon: Icon = Icon.FOLDER,
    ) : MediaNode

    data class Video(
        override val id: String,
        override val title: String,
        override val parentId: String? = null,
        val playback: PlaybackRef,
        val durationMs: Long? = null,
        val sizeBytes: Long? = null,
        val width: Int = 0,
        val height: Int = 0,
        val mimeType: String? = null,
        /** Epoch MILLISECONDS. MediaStore hands out seconds; the mapper multiplies. (R23) */
        val dateModifiedMs: Long? = null,
        /** Auto-detected. A user override lives in ProjectionOverrideStore, not here. */
        val detectedProjection: ProjectionMode = ProjectionMode.FLAT,
        /** Cache LOOKUP key only. Never a Bitmap. (R5) */
        val thumbnailKey: String? = null,
        /** Remote art to fetch when the local cache misses. Null for local files. */
        val thumbnailRef: MediaRef? = null,
    ) : MediaNode {
        val resolutionLabel: String? get() = if (width > 0 && height > 0) "$width×$height" else null
    }
}

/** How to actually open this video. The one place the two backends differ. */
sealed interface PlaybackRef {
    data class Upnp(val resources: List<Resource>) : PlaybackRef
    data class Local(val ref: MediaRef) : PlaybackRef
}
```

### 7.2 Why no `Bitmap`, and why no `Uri` (R5, R6)

* `AppState` is a `data class` read by the GL thread once per frame, and `ScreenPanel.renderIfChanged`
  compares view-model slices with `equals`. `Bitmap` has identity equality, so a slice containing one
  is *never* equal to itself after a re-decode — the panel would repaint every frame, defeating the R4
  no-repaint invariant. It would also pin megabytes of decoder memory inside immutable state that
  `hardEscape` copies around.
* `android.net.Uri` in `:app` tests resolves through `unitTests.isReturnDefaultValues = true`, where
  `Uri.parse` returns `null`. Every "red-first" reducer assertion touching a URI would pass vacuously.

The renderer therefore asks `ThumbnailCache.peek(node.thumbnailKey)` at paint time — a non-blocking,
non-allocating map read on the paint thread — and draws a placeholder on a miss.

### 7.3 Adapters

```kotlin
object UpnpAdapter {
    fun folder(c: DidlContainer): MediaNode.Folder
    fun video(i: DidlItem): MediaNode.Video       // playback = PlaybackRef.Upnp(i.resources)
                                                 // detectedProjection = ProjectionMode.detect(title, w, h)
                                                 // thumbnailRef = i.albumArtUri?.let { MediaRef(it.toString()) }
                                                 //   ?: i.resources.firstOrNull { it.isThumbnail }?.let { MediaRef(it.uri.toString()) }
}
```

`DidlItem.isPlayableVideo` still gates playability; non-video items map to `Folder`-less nodes that the
grid renders disabled, exactly as today.

---

## 8. Local storage engine

### 8.1 Module placement

`LocalMediaRepository` lives in `:app` (`com.daydreamvr.player.media.local`). It is **not** a new
Gradle module: it has no reusable surface, and `ARCHITECTURE.md §3` reserves modules for
independently-testable subsystems.

Split for testability — this split is the whole reason the acceptance tests can be pure JVM:

```
LocalMediaRepository      ← Android: ContentResolver, permissions, coroutines
  └── MediaCursorMapper   ← PURE: List<VideoRow> → List<MediaNode.Video> + bucket grouping
      VideoRow            ← PURE data class mirroring the projection, one per cursor row
```

### 8.2 Permissions (R10)

```xml
<!-- app/src/main/AndroidManifest.xml -->
<uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
                 android:maxSdkVersion="32" />
<uses-permission android:name="android.permission.READ_MEDIA_VISUAL_USER_SELECTED" />
```

```kotlin
object MediaPermission {
    fun required(): String = when {
        Build.VERSION.SDK_INT >= 33 -> Manifest.permission.READ_MEDIA_VIDEO
        else -> Manifest.permission.READ_EXTERNAL_STORAGE
    }
    enum class Grant { FULL, PARTIAL, DENIED }
    fun status(ctx: Context): Grant
}
```

* `READ_MEDIA_VISUAL_USER_SELECTED` (API 34+) must be handled: a `PARTIAL` grant returns only the
  user-picked subset, so the sidebar shows a `"Limited access — pick more on the phone"` footer row
  rather than pretending the library is empty.
* **The request happens in `SetupActivity` only.** `SetupActivity` is the documented 2D lobby
  (`ARCHITECTURE.md §11.6`) and is the only place a system permission dialog is legible. A dialog
  raised from `VrActivity` appears as a mono 2D sheet on a screen the user is viewing through two
  lenses at 4 cm — it cannot be read and cannot be reliably dismissed.
* In VR with `DENIED`, selecting the Device source shows a terminal, honest state:
  *"Arc needs permission to read videos on this phone. Take the headset off and reopen Arc to grant it."*
  plus a focusable `Exit to lobby` row wired to the existing `Effect.QuitToLobby`. **No in-VR
  permission request is attempted.**

### 8.3 Query (R23)

```kotlin
private val COLLECTION: Uri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
// VOLUME_EXTERNAL, not EXTERNAL_CONTENT_URI — the latter is the primary volume only and silently
// omits SD-card media.

private val PROJECTION = arrayOf(
    MediaStore.Video.Media._ID,                    // Long  → ContentUris.withAppendedId(COLLECTION, id)
    MediaStore.Video.Media.DISPLAY_NAME,           // "Trip.mp4"      — filename, used for detect()
    MediaStore.Video.Media.TITLE,                  // "Trip"          — may be blank
    MediaStore.Video.Media.DURATION,               // Long  MILLISECONDS
    MediaStore.Video.Media.SIZE,                   // Long  bytes
    MediaStore.Video.Media.WIDTH,                  // Int   px (0 when unscanned)
    MediaStore.Video.Media.HEIGHT,                 // Int   px
    MediaStore.Video.Media.MIME_TYPE,              // "video/mp4"
    MediaStore.Video.Media.BUCKET_ID,              // Int   ← the FOLDER KEY
    MediaStore.Video.Media.BUCKET_DISPLAY_NAME,    // "Camera"  ← the folder LABEL only
    MediaStore.Video.Media.RELATIVE_PATH,          // "DCIM/Camera/" — feeds projection detection
    MediaStore.Video.Media.DATE_MODIFIED,          // Long  SECONDS  ← ×1000 (R23)
    MediaStore.Video.Media.DATE_ADDED,             // Long  SECONDS  ← ×1000
    MediaStore.Video.Media.IS_PENDING,             // Int   0/1
)

private const val SELECTION = "${MediaStore.Video.Media.IS_PENDING} = 0"
```

Bucket rules:

* **`BUCKET_ID` is the folder node id; `BUCKET_DISPLAY_NAME` is only its label.** Two directories can
  share a display name (`.../Camera` on internal and SD), and grouping by the name silently merges
  them. v1.0 groups by name.
* Empty/blank `BUCKET_DISPLAY_NAME` falls back to the last segment of `RELATIVE_PATH`, then to
  `"Videos"`.
* Folders sort by `childCount` descending, then title ascending, so `Camera`/`Movies` land at the top.
* API 30+: pass sort and limit through a `Bundle` (`QUERY_ARG_SQL_SORT_ORDER`) plus a
  `CancellationSignal`, not a `sortOrder` string. Cancellation matters — a big library takes seconds
  and the user may switch sources mid-scan.
* Cursor iteration and mapping run on `Dispatchers.IO`. Column indices are resolved **once** before
  the loop, never `getColumnIndexOrThrow` per row.
* Register a `ContentObserver` on `COLLECTION` and debounce 500 ms → `Event.LocalMediaChanged`.

Projection detection for local files augments the existing `ProjectionMode.detect`:

```kotlin
// Filename wins; then the folder path; then the frame aspect (the existing rule).
fun detectLocal(displayName: String, relativePath: String?, w: Int, h: Int): ProjectionMode {
    val direct = ProjectionMode.detect(displayName, w, h)
    if (direct != ProjectionMode.FLAT) return direct
    return relativePath?.let { ProjectionMode.detect(it, 0, 0) } ?: ProjectionMode.FLAT
}
```

This catches the common case of `.../Movies/VR180/clip.mp4`, where the filename carries no token.

### 8.4 `MediaCursorMapper` (pure)

```kotlin
data class VideoRow(
    val id: Long, val displayName: String, val title: String?,
    val durationMs: Long, val sizeBytes: Long, val width: Int, val height: Int,
    val mimeType: String?, val bucketId: Int, val bucketName: String?,
    val relativePath: String?, val dateModifiedSec: Long, val dateAddedSec: Long,
    val isPending: Boolean,
)

object MediaCursorMapper {
    /** Content URI template, injected so the mapper stays JVM-pure. */
    const val URI_TEMPLATE = "content://media/external/video/media/%d"

    fun map(rows: List<VideoRow>): Map<MediaNode.Folder, List<MediaNode.Video>>
}
```

Mapper contract, one acceptance test per bullet:

1. `isPending` rows are dropped.
2. Grouping key is `bucketId`; label is `bucketName` → last `relativePath` segment → `"Videos"`.
3. `dateModifiedMs == dateModifiedSec * 1000L`.
4. `durationMs` passes through unscaled.
5. `MediaRef` = `URI_TEMPLATE.format(id)`.
6. `MediaNode.Video.id` = `id.toString()`; `parentId` = `bucketId.toString()`.
7. `title` = `title?.ifBlank { null } ?: displayName.substringBeforeLast('.')`.
8. `detectedProjection` = `detectLocal(displayName, relativePath, width, height)`.
9. `Folder.childCount` = the group size.
10. Folders come out ordered by `childCount` desc, then title asc.

### 8.5 Resume & override keys

```
UPnP   sourceId = "upnp:${server.udn}"   nodeId = DidlObject.id
Local  sourceId = "local"                nodeId = MediaStore _ID
```

`MediaKey.storageKey()` produces `"upnp:uuid:abc|17$3"` / `"local|4521"`. The UPnP form is **byte-identical
to the existing `"${serverUdn}|${item.id}"`**, so every stored resume position survives the refactor.
This is not incidental — it is a requirement, and it is asserted by a test that pins the format string.

### 8.6 Local playback path (R3)

`ExoVideoPlayer` currently builds:

```kotlin
val httpFactory = OkHttpDataSource.Factory(OkHttpClient())          // :153
…setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))      // :163
```

`OkHttpDataSource` speaks http/https only. `MediaItem.fromUri("content://…")` (`:195`) resolves to that
factory and throws. **Required fix:**

```kotlin
val httpFactory = OkHttpDataSource.Factory(OkHttpClient())
val dataSource  = DefaultDataSource.Factory(context, httpFactory)
…setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
```

`DefaultDataSource` routes `file:`, `content:`, `asset:`, `rtmp:` to local resolvers and delegates
`http(s):` upstream, so UPnP playback is bit-for-bit unchanged and local playback starts working.

`EffectRunner.play` then branches on `PlaybackRef`:

```kotlin
val ranked: List<Resource> = when (val p = node.playback) {
    is PlaybackRef.Upnp  -> ResourceRanker.rank(p.resources, decoderCaps())
    is PlaybackRef.Local -> listOf(
        Resource(
            uri = URI(p.ref.value),
            protocolInfo = "http-get:*:${node.mimeType ?: "video/*"}:*",
            sizeBytes = node.sizeBytes, durationMs = node.durationMs,
            resolution = node.width.takeIf { it > 0 }?.let { Size(it, node.height) },
            bitrate = null,
        ),
    )
}
```

Reusing `Resource`/`PlayRequest` means the whole fallback ladder (`ARCHITECTURE.md §10.5`) works for
local files too, at the cost of a one-element list. Do not add a parallel play path.

> Noted, out of scope: `OkHttpDataSource.Factory(OkHttpClient())` constructs a *fresh, unbound* client
> rather than the network-bound one from `AndroidNetworkBinder`. That is a pre-existing issue; it does
> not block this work, but the thumbnail fetcher (§9.4) must not repeat it.

---

## 9. Thumbnail pipeline

### 9.1 Shape

```kotlin
interface ThumbnailSource { suspend fun load(key: String, ref: MediaRef): Bitmap? }

class ThumbnailCache(
    private val cache: SizedLruCache<String, Bitmap>,
    private val sources: List<ThumbnailSource>,
    private val scope: CoroutineScope,
    private val onBatch: () -> Unit,          // coalesced repaint trigger
) {
    /** Paint-thread safe, non-blocking, non-allocating. Null = draw the placeholder. */
    fun peek(key: String?): Bitmap?
    /** Idempotent; de-duplicates concurrent requests for the same key. */
    fun request(key: String, ref: MediaRef)
    fun trim(level: Int)                       // onTrimMemory
}
```

### 9.2 `SizedLruCache` — pure, JVM-testable (R9)

```kotlin
/** LRU bounded by a caller-supplied SIZE, not a count. Synchronised; no Android types. */
class SizedLruCache<K : Any, V : Any>(
    private val maxSizeKb: Int,
    private val sizeOfKb: (V) -> Int,
) {
    fun get(key: K): V?
    fun put(key: K, value: V): V?
    fun sizeKb(): Int
    fun evictToSize(targetKb: Int)
    fun clear()
}
```

Extracting this from `android.util.LruCache` is what makes eviction assertable in a plain JUnit test.
Production wiring:

```kotlin
val budgetKb = min(
    (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt(),
    24 * 1024,                                    // 24 MB — ARCHITECTURE.md §14
)
SizedLruCache<String, Bitmap>(budgetKb) { it.allocationByteCount / 1024 }
```

Thumbnails are decoded/copied to **`Bitmap.Config.RGB_565`** — they are opaque, so alpha is wasted, and
`384×216` drops from 332 KB to 166 KB. The 24 MB budget therefore holds ~148 thumbnails, comfortably
more than any page.

### 9.3 Local source

```kotlin
class LocalThumbnailSource(private val resolver: ContentResolver) : ThumbnailSource {
    override suspend fun load(key: String, ref: MediaRef): Bitmap? = withContext(Dispatchers.IO) {
        runCatching { resolver.loadThumbnail(Uri.parse(ref.value), Size(384, 216), null) }
            .getOrNull()
            ?.let { if (it.config == Bitmap.Config.RGB_565) it else it.copy(Bitmap.Config.RGB_565, false) }
    }
}
```

* `loadThumbnail` is API 29+; `minSdk = 29` — no version gate needed.
* It **throws `IOException`** when no thumbnail can be produced (corrupt file, unscanned media). It is
  also a blocking call that can take hundreds of milliseconds. Both facts mean it is `Dispatchers.IO`
  and `runCatching`, never main and never GL.
* `Size(384, 216)` is 16:9 to match the card poster, against v1.0's 4:3 `Size(320, 240)` which forces a
  re-scale and a crop on every draw.

### 9.4 UPnP source

Fetches `DidlItem.albumArtUri` (or a `JPEG_TN` `Resource`) over HTTP. It **must** use the
network-bound `OkHttpClient` from `AndroidNetworkBinder` — an unbound client on a phone with mobile
data active will route the request off the LAN and 100 % of album art will fail to load. Cap the
response at 2 MB and decode with `inPreferredConfig = RGB_565` and an `inSampleSize` computed for a
384 px target.

### 9.5 In-flight de-duplication

Six cards mounting at once must not fire six loads for the same key when a page repeats an item.

```kotlin
private val inFlight = ConcurrentHashMap<String, Deferred<Bitmap?>>()

fun request(key: String, ref: MediaRef) {
    if (cache.get(key) != null) return
    inFlight.computeIfAbsent(key) {
        scope.async(Dispatchers.IO) {
            sources.firstNotNullOfOrNull { it.load(key, ref) }
                ?.also { cache.put(key, it) }
                .also { inFlight.remove(key); pending.set(true) }
        }
    }
}
```

### 9.6 Repaint coalescing (R19)

Every arrival must **not** repaint the panel. Six arrivals over 400 ms would mean six full 1536×800
`lockHardwareCanvas` cycles. Instead the cache raises a `pending` flag; `AppScene.update` reads and
clears it at most once every **120 ms**, bumping `AppState.thumbGeneration`, which is part of the
`BrowseScreen` render key. Worst case one extra repaint per 120 ms; typical case one repaint per page.

---

## 10. State machine

### 10.1 New state

```kotlin
data class AppState(
    // …existing fields unchanged…
    val sources: SourcesState = SourcesState(),
    val browse: BrowseState = BrowseState(),
    val thumbGeneration: Int = 0,      // bumped by the coalescer; part of the render key
)

data class SourcesState(
    val available: List<MediaSource> = emptyList(),
    val selectedId: String? = null,
    val localPermission: MediaPermission.Grant = MediaPermission.Grant.DENIED,
    val focusIndex: Int = 0,
)

/** Which region of the browse panel the controller is driving. Replaces a bare Int. */
sealed interface BrowseFocus {
    data class Source(val index: Int) : BrowseFocus
    data class Sidebar(val index: Int) : BrowseFocus
    data class Grid(val index: Int) : BrowseFocus
    data class Inspector(val action: GazeTarget.Action) : BrowseFocus
    data class Dock(val button: GazeTarget.Dock) : BrowseFocus
    data class Toolbar(val chip: GazeTarget.Chip) : BrowseFocus
}

enum class SortOrder(val label: String) {
    TITLE_ASC("Name"), DATE_DESC("Date"), DURATION_DESC("Length"), SIZE_DESC("Size");
    fun next(): SortOrder = entries[(ordinal + 1) % entries.size]
}

data class BrowseFrame(
    val source: MediaSource,
    val objectId: String,
    val title: String,
    val folders: List<MediaNode.Folder> = emptyList(),
    val videos: List<MediaNode.Video> = emptyList(),
    val sort: SortOrder = SortOrder.TITLE_ASC,
    val focus: BrowseFocus = BrowseFocus.Grid(0),
    val sidebarScrollTop: Int = 0,
    val totalMatches: Int = 0,
    val loading: Boolean = true,
    val error: String? = null,
) {
    val loadedCount: Int get() = folders.size + videos.size
    val hasMorePages: Boolean get() = totalMatches > loadedCount

    /** Sorted VIEW of the videos. Recomputed, never stored — storing it would let it drift from `sort`. */
    val sortedVideos: List<MediaNode.Video> get() = when (sort) {
        SortOrder.TITLE_ASC      -> videos.sortedBy { it.title.lowercase() }
        SortOrder.DATE_DESC      -> videos.sortedByDescending { it.dateModifiedMs ?: 0L }
        SortOrder.DURATION_DESC  -> videos.sortedByDescending { it.durationMs ?: 0L }
        SortOrder.SIZE_DESC      -> videos.sortedByDescending { it.sizeBytes ?: 0L }
    }

    val gridFocusIndex: Int get() = (focus as? BrowseFocus.Grid)?.index ?: 0
    /** SINGLE DIVISOR — page and cell can never disagree (the F13 rule). */
    val gridPage: Int  get() = gridFocusIndex / GRID_PAGE_SIZE
    val pageCount: Int get() = ((sortedVideos.size + GRID_PAGE_SIZE - 1) / GRID_PAGE_SIZE).coerceAtLeast(1)
    val focusedVideo: MediaNode.Video? get() = sortedVideos.getOrNull(gridFocusIndex)

    companion object { const val GRID_PAGE_SIZE = 6 }   // 3 × 2, §5.2
}
```

### 10.2 New events

```kotlin
sealed interface Event {
    // …existing members unchanged…

    /** User intent, from EITHER gaze-activate or controller-Confirm. One path, two producers. */
    data class Ui(val intent: UiIntent) : Event

    data class LocalMediaLoaded(val folders: List<MediaNode.Folder>, val byFolder: Map<String, List<MediaNode.Video>>) : Event
    data class LocalMediaFailed(val message: String) : Event
    data class LocalPermissionChanged(val grant: MediaPermission.Grant) : Event
    data object LocalMediaChanged : Event                     // ContentObserver, debounced
    data object ThumbnailsArrived : Event                     // coalesced; bumps thumbGeneration
}

sealed interface UiIntent {
    data class SelectSource(val sourceId: String) : UiIntent
    data class SelectFolder(val folderId: String) : UiIntent
    data class SelectGridCell(val index: Int) : UiIntent
    data object SelectSort : UiIntent                          // cycles
    data object PageNext : UiIntent
    data object PagePrev : UiIntent
    data class NavigateBreadcrumb(val depth: Int) : UiIntent
    data object OverrideProjection : UiIntent                  // cycles the focused video's mode
    data class PlayVideo(val fromStart: Boolean) : UiIntent
    data class DockAction(val button: GazeTarget.Dock) : UiIntent
}
```

> **Why `Event.Ui(UiIntent)` and not seven top-level events.** The codebase's `Event` vocabulary is
> *"things that happened to the app"*; `AppStateMachine.reduce` is total over it and `EscapeHatchTest`
> walks it exhaustively. Nesting user intent under one member keeps that walk tractable and keeps the
> gaze and controller paths converging on one reducer. `InputAction.Confirm` is translated to the
> correct `UiIntent` by `intentForFocus(frame)` — so gaze-activate and A-press are literally the same
> code, and a divergence between them is impossible by construction rather than by discipline.

### 10.3 New effects

```kotlin
sealed interface Effect {
    // …existing members unchanged…
    data object LoadLocalMedia : Effect
    data class BrowseNode(val source: MediaSource, val objectId: String, val page: PageRequest) : Effect
    data class PlayNode(
        val node: MediaNode.Video,
        val key: MediaKey,
        val startAtMs: Long,
        val projectionOverride: ProjectionMode?,
        val skipResumeCheck: Boolean = false,
    ) : Effect
    data class PersistProjectionOverride(val key: MediaKey, val mode: ProjectionMode?) : Effect
    data class PrefetchThumbnails(val keys: List<Pair<String, MediaRef>>) : Effect
}
```

`Effect.Browse` and `Effect.Play` remain until the strangler completes (§10.7), then are deleted.

### 10.4 Reducer rules

**`UiIntent.SelectSource(id)`** → replace the whole browse stack with a fresh root frame for that
source; emit `Effect.LoadLocalMedia` for `Local` (unless already cached and unchanged) or
`Effect.BrowseNode(source, "0", PageRequest.DEFAULT)` for `Upnp`. Focus resets to `Sidebar(0)`.
When `Local` and `localPermission == DENIED`, set the terminal permission state and emit **no** effect.

**`UiIntent.SelectFolder(id)`** → push a frame; emit `BrowseNode` for UPnP, or synthesise the frame
directly from the already-loaded local map (no effect — local media is loaded whole). Focus becomes
`Grid(0)`.

**`UiIntent.SelectSort`** (R18) — the focused item must not move under the user:

```kotlin
val current = frame.focusedVideo?.id                      // capture identity BEFORE re-sorting
val next    = frame.copy(sort = frame.sort.next())
val idx     = next.sortedVideos.indexOfFirst { it.id == current }.coerceAtLeast(0)
next.copy(focus = BrowseFocus.Grid(idx))
```

The chip renders `"Sort: Date"` normally and **`"Sort: Date (loaded)"`** whenever `hasMorePages` is
true, because a UPnP sort can only ever order the pages already fetched. Saying otherwise would be a
lie in the UI.

**`UiIntent.PageNext` / `PagePrev`** (R8) — move the *grid focus* by a page; never touch UPnP fetch
paging:

```kotlin
val target = (frame.gridFocusIndex + GRID_PAGE_SIZE).coerceAtMost(frame.sortedVideos.lastIndex)
```

Fetch paging stays exactly where it is: when `gridPage == pageCount - 1 && hasMorePages`, *also* emit
`Effect.BrowseNode(source, objectId, PageRequest(loadedCount, PAGE_FETCH))` to top up in the
background. The two concerns coexist; they do not merge.

**`UiIntent.OverrideProjection`** → cycle `FLAT → SBS_HALF → TOPBOTTOM_HALF → EQUIRECT_180 →
EQUIRECT_360 → null(Auto)` for the focused video; store in `AppState` and emit
`Effect.PersistProjectionOverride`. `Effect.PlayNode.projectionOverride` already exists on
`Effect.Play` and is already honoured by `EffectRunner.play` — no new playback plumbing.

**`UiIntent.PlayVideo`** → `Effect.PlayNode(node, key, startAtMs, override, skipResumeCheck = !fromStart)`.
The resume-prompt flow (`Event.ResumePrompt` → `Overlay.Confirm` → `PendingResume`) is unchanged.

**`Event.ThumbnailsArrived`** → `state.copy(thumbGeneration = state.thumbGeneration + 1)`, no effects.

**Gaze** — `reduceGaze` extends to write the new targets into `frame.focus`, preserving all four
existing rules from `UI_GAZE_PLAN.md §3.3`: store `gaze` always (including `null`); move focus only
when the target names the active surface; **never** touch a `scrollTop`; ignore screen targets while
an overlay is up. `DockButton` sets `focus = BrowseFocus.Dock(button)` — the dock participates in the
same focus model, so `A` works on it exactly like everywhere else.

### 10.5 Input bindings on `BROWSE` (revised)

The three-column layout changes what `LEFT`/`RIGHT` mean. Today `Dir.LEFT` pops the browse stack
(`AppStateMachine.reduceBrowse`) — that must move to `B`, which already does it via `softEscape`.

| Input | Action |
|---|---|
| `Nav(UP/DOWN)` | Move within the focused region (sidebar row, grid row, inspector action, dock slot) |
| `Nav(LEFT/RIGHT)` | Within the grid: previous/next cell. At a column edge: **move to the adjacent region** — `Sidebar ↔ Grid ↔ Inspector` |
| `Nav(DOWN)` at the bottom grid row | Move to `Dock(RECENTER)` |
| `Nav(UP)` from the dock | Return to the last grid cell |
| `Confirm` (A) | `intentForFocus(frame)` — one translation table, shared with gaze-activate |
| `Cancel` (B) | Pop one level; at root, back to the source list. **Unchanged `softEscape`.** |
| `Cancel` long (B) | `hardEscape`. **Unchanged.** |
| `PageUp` / `PageDown` (L1/R1) | `UiIntent.PagePrev` / `PageNext` |
| `Menu` (Start) | `screen = SETTINGS`. **Unchanged.** |
| `Recenter` (Y) | `Effect.Recenter`. **Unchanged.** |

`EscapeHatchTest` must be extended to walk every `BrowseFocus` variant and assert `B` still produces a
transition (`ARCHITECTURE.md §15`). This is the single most important regression guard in the milestone.

### 10.6 Effect runner additions

```kotlin
Effect.LoadLocalMedia -> scope.launch {
    when (MediaPermission.status(context)) {
        DENIED -> dispatch(Event.LocalPermissionChanged(DENIED))
        else -> localRepo.load().fold(
            onSuccess = { dispatch(Event.LocalMediaLoaded(it.folders, it.byFolder)) },
            onFailure = { dispatch(Event.LocalMediaFailed(humanMessage(it))) },
        )
    }
}
```

### 10.7 Strangler sequence (R7)

v1.0's "refactor `BrowseState` and `AppStateMachine` to operate over `MediaSource`/`MediaNode`" as one
step touches `AppState`, `Event`, `Effect`, `EffectRunner`, `BrowseScreen`, `AppScene` and four test
files simultaneously, with verified-working UPnP playback on the other side. That is a bad trade.
Sequence it, green at every arrow:

```
S1  Add media/ package + UpnpAdapter. Nothing consumes it. Tests: adapter round-trip.        → green
S2  BrowseFrame gains folders/videos ALONGSIDE containers/items; both populated from the same
    Event.BrowseLoaded. Existing tests untouched and still passing.                          → green
S3  BrowseScreen reads folders/videos. Rendering only. Existing reducer tests untouched.     → green
S4  Effect.PlayNode added; EffectRunner handles both PlayNode and Play. NavigationTest gains a
    PlayNode assertion; the old Play assertion still passes.                                 → green
S5  Reducer emits PlayNode instead of Play. Update NavigationTest. Effect.Play deleted.       → green
S6  containers/items removed from BrowseFrame; DidlContainer/DidlItem no longer reach :app
    state. Fixtures rewritten to build MediaNode directly.                                   → green
```

Each of S1–S6 is a separate commit. If S6 goes wrong, S5 is a working build with local media already
shipping.

---

## 11. `BrowseScreen` composition

```kotlin
class BrowseScreen(panel: PanelSurface, theme: Theme) : ScreenPanel(
    panel, theme,
    panelWidthM = BrowseLayout.WIDTH_M,        // 2.80
    panelHeightM = BrowseLayout.HEIGHT_M,      // 1.458333
    distanceM = BrowseLayout.RADIUS_M,         // 2.50
) {
    override val verticalOffsetM = BrowseLayout.VERTICAL_OFFSET_M
    init { anchor.followThresholdDeg = BrowseLayout.FOLLOW_THRESHOLD_DEG }

    private val sidebar   = SourceSidebarWidget(theme, metrics)
    private val grid      = MediaGridWidget(theme, metrics)
    private val inspector = MediaInspectorWidget(theme, metrics)
    private val crumbs    = Breadcrumb(theme, metrics)

    fun render(state: AppState, thumbs: ThumbnailCache) {
        if (state.screen != VrScreen.BROWSE) return
        val f = state.browse.top ?: return
        val key = listOf(
            state.sources.selectedId, state.sources.localPermission,
            state.browse.stack.map { it.objectId },
            f.folders.map { it.id }, f.sortedVideos.map { it.id },
            f.focus, f.sort, f.sidebarScrollTop, f.loading, f.error,
            state.gaze, state.thumbGeneration,           // ← R19: thumbnails enter the key HERE
        )
        renderIfChanged(key) { canvas ->
            canvas.panelBackground(theme, metrics)
            val sl = sidebar.measureLayout(…,   RectF(0f,    0f, 394f,  800f))
            val gl = grid.measureLayout(…,      RectF(418f,  0f, 1118f, 800f))
            val il = inspector.measureLayout(…, RectF(1142f, 0f, 1536f, 800f))
            sidebar.draw(canvas, …, sl); grid.draw(canvas, …, gl, thumbs); inspector.draw(canvas, …, il)
            drawGutters(canvas)
            hitMap = HitMap(sidebar.hitRegions(sl) + grid.hitRegions(gl) + inspector.hitRegions(il))
        }
    }
    fun visibleSidebarRows(): Int = sidebar.visibleRows()
}
```

`state.thumbGeneration` in the render key is the entire mechanism by which a late-arriving thumbnail
becomes visible. Omitting it means posters never appear; including a raw bitmap list means the panel
repaints every frame (R5). One integer is the correct amount of coupling.

Gutters are drawn as `Surfaces.divider` verticals at `x = 406` and `x = 1130` (gutter centres), with
the same fade-to-zero-at-the-ends gradient `Surfaces.panel` uses for its top hairline.

---

## 12. Spatial grounding — the ground grid

### 12.1 Placement

Plane `y = GROUND_Y = −1.2 m` — eye height for a seated user. The mesh is a single quad,
`28 × 28 m`, centred on the origin in XZ. No tessellation: the grid is procedural, so two triangles
suffice and perspective-correct varyings carry world XZ exactly (ES 3.0 core).

Tracking is 3DOF. The floor is correct under rotation and *wrong* under translation — physically
leaning will make it swim. Mitigations, both required: the near hole (§12.2) keeps the highest-parallax
region empty, and the intensity ceiling keeps it subliminal. It is a grounding cue, not scenery.

### 12.2 Shader (R16)

`GL_LINES` at world scale gives a line whose screen width collapses with distance, then aliases
violently once it falls below a pixel — and the distortion resample amplifies exactly that. The fix is
an analytic grid whose line width is defined **in pixels**, via screen-space derivatives:

```glsl
#version 300 es
precision highp float;
in vec3 vWorld;
uniform float uCell;       // 0.5   m between lines
uniform float uNear;       // 1.2   m — inner hole radius
uniform float uFadeStart;  // 3.0   m
uniform float uFadeEnd;    // 12.0  m
uniform vec3  uLine;       // theme.accent, heavily dimmed
uniform vec3  uGlow;       // horizon glow hue
uniform float uIntensity;  // 0.0 .. 1.0, driven by ThermalGovernor
out vec4 fragColor;

/** 1.0 on a grid line, 0.0 off it, with a constant ~1 px width at ANY distance. */
float gridLine(vec2 p, float cell) {
    vec2 d = abs(fract(p / cell - 0.5) - 0.5) * cell;   // metres to the nearest line
    vec2 w = max(fwidth(p), vec2(1e-6));                // metres per pixel, per axis
    return 1.0 - min(min(d.x / w.x, d.y / w.y), 1.0);
}

void main() {
    vec2  p = vWorld.xz;
    float r = length(p);

    float line  = gridLine(p, uCell);
    float minor = gridLine(p, uCell * 4.0) * 0.6;       // brighter every 2 m

    float hole  = smoothstep(uNear, uNear + 0.8, r);              // no crawl under the nose
    float fade  = 1.0 - smoothstep(uFadeStart, uFadeEnd, r);      // dissolve into the void
    float a     = max(line, minor) * hole * fade;

    float band  = exp(-pow((r - uFadeEnd * 0.85) / 1.6, 2.0));    // horizon glow
    vec3  color = uLine * a + uGlow * band * 0.35;
    float alpha = (a + band * 0.35) * uIntensity;

    fragColor = vec4(color * uIntensity, alpha);   // PREMULTIPLIED — matches glBlendFunc(ONE, ONE_MINUS_SRC_ALPHA)
}
```

The premultiplied output is not optional: `PanelQuad.draw` sets
`glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)` and `GroundGrid` renders in the same pass with the same
blend state. A straight-alpha shader here would double-darken the horizon band.

### 12.3 Grounding math

Where the ground is, per gaze pitch `φ` below horizontal: `r(φ) = |GROUND_Y| / sin φ`.

| φ | `r` | note |
|---|---|---|
| 18.086° | 3.87 m | browse-panel bottom edge — grid is always behind it |
| 26.981° | 2.64 m | dock bottom edge — grid is always behind it |
| 45° | 1.70 m | inside `uFadeStart`, fully lit |
| 90° | 1.20 m | straight down — inside the near hole, empty |

This is why §3.4's painter's order needs no depth test, and it is stated as a test rather than a hope.

### 12.4 Screen gating (R17)

```kotlin
private fun groundVisible(state: AppState): Boolean = when (state.screen) {
    VrScreen.SERVER_LIST, VrScreen.BROWSE, VrScreen.SETTINGS -> true
    VrScreen.PLAYER -> false
}
```

`PLAYER` is excluded unconditionally. With `ProjectionMode.isSpherical` the camera sits *inside*
`SphereScreen`, so a floor plane at `y = −1.2` is inside the film and would paint a glowing grid across
the lower third of a 360 video. Even for `FLAT`, a bright floor next to a dark film is a contrast
distraction. The void is the correct backdrop for playback.

`ThermalGovernor` drives `uIntensity`: `NONE/LIGHT → 1.0`, `MODERATE → 0.5` and the glow term is
skipped, `SEVERE/CRITICAL → 0.0` (grid off entirely). This slots into the existing governor table in
`ARCHITECTURE.md §14` alongside `renderScale` and MSAA.

---

## 13. Threading & performance

### 13.1 Move panel painting off the GL thread (R19)

Today `AppScene.update` (GL thread) calls `browse.render(state)`, which calls
`PanelSurface.draw { … }`, which does `lockHardwareCanvas` → paint → `unlockCanvasAndPost` **inline**.
At `1280 × 800` with no bitmaps that is survivable. At `1536 × 800` with six decoded posters, badges
and font-metric text it will not fit in the 6 ms CPU budget (`ARCHITECTURE.md §14`), and every miss is
a dropped stereo frame.

`PanelSurface` is already built for this — its KDoc says *"drawn with a full Canvas off the GL thread
via `draw`"*, it queues into `pendingBlocks` when no `Surface` exists yet, and `hitMap` is already
`@Volatile`. The change is to actually honour it:

* `AppScene` gains a single-threaded `Executor` (`"arc-panel-paint"`, priority `THREAD_PRIORITY_DISPLAY`).
* `update()` computes each panel's render **key** on the GL thread (cheap — it is a list of ids and
  ints) and, on a change, posts the paint block to that executor.
* `updateIfDirty()` stays on the GL thread; it already only pumps `SurfaceTexture.updateTexImage`.
* `hitMap` is published by the paint thread and read by the gaze pass on the GL thread — already
  `@Volatile`, and now genuinely cross-thread, which is exactly the case that annotation was insurance
  against.
* One paint may be in flight when the next key change arrives: coalesce by keeping only the latest
  pending block per panel.

### 13.2 Budget

| Item | Before | After |
|---|---|---|
| Browse panel texture | 1280×800 = 1.02 Mpx | 1536×800 = 1.23 Mpx |
| Dock texture | — | 672×176 = 0.12 Mpx |
| Panel texture memory total | ~12 MB (`ARCHITECTURE.md §14`) | ~13.4 MB |
| Thumbnail cache | — | ≤ 24 MB, RGB_565 |
| Ground grid fragment cost | — | ~15 ALU over ≈35 % of both eyes |
| GL-thread paint cost | full repaint inline | key comparison only |

The v1.0 `1920×1080` panel would have been 2.07 Mpx — 69 % more fill and upload than the design here,
for a density the optics cannot resolve (§2.7).

---

## 14. Milestones

Every milestone is **red-first**: write the listed tests, watch them fail for the stated reason, then
implement. Every milestone ends on `./gradlew test` fully green and is one commit.

### M0 — Guard rails (no behaviour change)

* Add `BrowseLayout` / `DockLayout` (pure constants).
* Extend `PanelAspectTest`'s table with `BrowseScreen(1536, 800, 2.80f, 1.458333f)` and
  `SystemDock(672, 176, 1.00f, 0.261905f)`.
* Add `AngularMetrics.COMFORT_H_HEAD_DEGREES`, `COMFORT_V_DOWN_DEGREES`, `MIN_TARGET_DEGREES`,
  `isWithinComfortBoxAsym`.

**Acceptance**
1. `PanelAspectTest` — both new rows within 1 %. *(Fails first with v1.0's 1920×1080/2.81×1.45 at ratio 0.917.)*
2. `BrowseLayoutTest` — `leftX + gutter + centreX + gutter + rightX` tiles `[0,1536)` with no gap and no overlap.
3. `BrowseLayoutTest` — column centre azimuths are `−23.855°`, `0.000°`, `+23.855°` (±0.01°); edges `±32.086°`.
4. `ComfortTest` — every centre ≤ 25°; every edge ≤ 35°; panel top ≤ +20° *(true)*; dock bottom ≥ −30° *(true)*.
5. `ComfortTest` — `BrowseLayout.FOLLOW_THRESHOLD_DEG > maxEdgeAzimuth + 10°`.

### M1 — Local storage engine

* `VideoRow`, `MediaCursorMapper` (pure), `LocalMediaRepository` (Android), `MediaPermission`.
* Manifest permissions; permission request in `SetupActivity` with a "Videos on this phone" row
  showing `Granted` / `Limited` / `Not granted`.

**Acceptance** — `MediaCursorMapperTest`, all pure JVM:
1. Two rows sharing `bucketId` produce one `Folder` with `childCount == 2`.
2. Two rows with **the same `bucketName` but different `bucketId`** produce **two** folders. *(Fails against v1.0's group-by-name.)*
3. `dateModifiedMs == dateModifiedSec * 1000L`. *(Fails against a passthrough — R23.)*
4. `durationMs` passes through unscaled.
5. `isPending = true` rows are excluded.
6. Blank `bucketName` falls back to the last `relativePath` segment, then `"Videos"`.
7. `MediaRef` == `"content://media/external/video/media/$id"`.
8. `"clip_180_sbs.mp4"` → `SBS_HALF`; `"DCIM/VR180/clip.mp4"` with a plain name → `EQUIRECT_180`; plain name + plain path + 1920×1080 → `FLAT`.
9. Folders ordered by `childCount` desc then title asc.
10. `MediaPermissionTest` — `required()` is `READ_MEDIA_VIDEO` at SDK 33/34/36 and `READ_EXTERNAL_STORAGE` at 29–32.

### M2 — Multi-surface gaze + `ScreenPanel` distance

* `ScreenPanel(distanceM = …)`; `AppScene.activeGazeSurfaces`; nearest-hit resolution; dock anchor slaving.

**Acceptance**
1. `MultiSurfaceGazeTest` (pure, via `PanelRaycast` + two `PanelGeometry`) — a ray hitting both quads resolves to the smaller `distanceM`.
2. A ray hitting only the far quad resolves to it.
3. A ray inside the far quad's bounds but off every `HitRegion` yields `null`, **not** a target on the near quad.
4. `AnchorSlavingTest` — after N `update` calls with an arbitrary head-yaw sequence, `dock.anchor.yawRad == browse.anchor.yawRad` exactly.
5. `PanelYawConventionTest` still green (the F6 sign fix is untouched).

### M3 — Domain model + strangler S1–S3

* `media/` package, `MediaRef`, `MediaKey`, `MediaSource`, `MediaNode`, `PlaybackRef`, `UpnpAdapter`.
* `BrowseFrame` carries `folders`/`videos` alongside the legacy `containers`/`items`.

**Acceptance**
1. `UpnpAdapterTest` — `DidlContainer → Folder` and `DidlItem → Video` round-trip id, title, duration, size, resolution, mime.
2. `UpnpAdapterTest` — `albumArtUri` maps to `thumbnailRef`; absent art falls back to the `JPEG_TN` `Resource`; neither present → `null`.
3. `MediaKeyTest` — `MediaKey("upnp:uuid:abc","17$3").storageKey() == "upnp:uuid:abc|17$3"`, **byte-identical to the pre-refactor `ResumeStore` key**.
4. `MediaModelPurityTest` — reflect over `MediaNode`/`MediaSource`/`PlaybackRef`/`AppState`; assert **no** property type is in `android.*`. *(Fails against v1.0's `Uri` and `Bitmap` — R5, R6.)*
5. All existing `:app` tests still green.

### M4 — State machine + strangler S4–S6

* `BrowseFocus`, `SourcesState`, `SortOrder`, `UiIntent`, new events/effects, `intentForFocus`.

**Acceptance**
1. `SortReducerTest` — cycling sort keeps the same node focused (focus index changes, focused **id** does not). *(Fails against a naive index-preserving reducer — R18.)*
2. `GridPageTest` — `gridPage == gridFocusIndex / 6` for indices 0…40; `pageCount` is correct for sizes 0, 1, 6, 7, 12.
3. `GridPageTest` — `PageNext` at the last page is a no-op on focus **and** emits `BrowseNode(PageRequest(loadedCount, 200))` when `hasMorePages`. *(Fails if display and fetch paging are merged — R8.)*
4. `ColumnNavTest` — `RIGHT` from `Sidebar(n)` → `Grid`; `RIGHT` from the grid's right column → `Inspector`; `LEFT` from `Inspector` → `Grid`; `DOWN` from the bottom grid row → `Dock`; `UP` from the dock → the previous grid cell.
5. `EscapeHatchTest` — extended to every `BrowseFocus` variant plus every `Dock` button: `B` always transitions.
6. `LocalBrowseTest` — `SelectSource("local")` with `DENIED` emits **no** effect and sets the permission state; with `FULL` emits `LoadLocalMedia`; `LocalMediaLoaded` populates the sidebar and `SelectFolder` fills the grid with **no** further effect.
7. `NavigationTest` — the full UPnP happy path still passes, now asserting `Effect.PlayNode`.
8. `ProjectionOverrideTest` — cycling reaches `Auto` after five steps and emits `PersistProjectionOverride`.

### M5 — Widgets

* `SourceSidebarWidget`, `MediaGridWidget`, `MediaInspectorWidget`, `SystemDockWidget`, all with pure
  `measureLayout` and injected `TextMeasure`.

**Acceptance** — all driven by `TextMeasure.fixed(perCharPx = 16f)`:
1. `MediaGridLayoutTest` — 6 cells; `cardW` within 0.5 px of `215.78`; poster is 16:9 within 0.5 %.
2. `MediaGridLayoutTest` — the 6 card boxes are pairwise non-overlapping and all inside the grid band `[140.39, 719.10)`.
3. `MediaGridLayoutTest` — hit regions are the **full card**, not the poster: `box.bottom − box.top == cardH` ±0.5 px.
4. `MediaGridLayoutTest` — indices are absolute: on page 1, slot 0 publishes `GridCell(6)`.
5. `HitTargetSizeTest` — over all four widgets with a fully-populated model, every `HitRegion` satisfies `min(deg(w), deg(h)) ≥ 2.0°`. *(Fails against a horizontal 3-tab sidebar at 5.5° × … or any sub-2° chip — R15.)*
6. `SourceSidebarLayoutTest` — 3 source rows then a divider then folder rows; `visibleRows()` matches the number of boxes actually emitted (the F5 rule).
7. `MediaInspectorLayoutTest` — with 40 metadata rows, the PLAY and PROJECTION boxes are **still present** and still inside the panel; meta rows are truncated instead.
8. `MediaInspectorLayoutTest` — with a resume entry, RESUME is present; without, it is absent and PLAY moves down.
9. `SystemDockLayoutTest` — 6 cells tile `[0, 672)` exactly; each is `4.658° × 7.320°` ±0.01°.
10. `PanelAspectTest`, `TypeScaleTest`, `ListViewLayoutTest` still green.

### M6 — Thumbnail pipeline

* `SizedLruCache` (pure), `ThumbnailCache`, `LocalThumbnailSource`, `UpnpThumbnailSource`, 120 ms coalescer.

**Acceptance**
1. `SizedLruCacheTest` — sizing is by `sizeOfKb`, not count: 3 × 1024 KB entries in a 2048 KB cache evict the least-recently-*used* one. *(Fails against `LruCache(64)` — R9.)*
2. `SizedLruCacheTest` — `get` promotes; `evictToSize(0)` empties; `sizeKb()` tracks put/evict exactly.
3. `ThumbnailCacheTest` (fake `ThumbnailSource`, `TestScope`) — 6 concurrent `request` calls for one key invoke `load` **once**. *(Fails without the in-flight map — §9.5.)*
4. `ThumbnailCacheTest` — a failing source leaves no cache entry and does not wedge the in-flight map (a later request retries).
5. `CoalescerTest` — 6 arrivals inside 120 ms bump `thumbGeneration` once; a 7th at 130 ms bumps it again. *(Fails against per-arrival invalidation — R19.)*

### M7 — Ground grid

* `GroundGrid` mesh + shader in `:vrcore`, wired into `AppScene` before the panels, gated by screen and
  thermal state.

**Acceptance**
1. `GroundGridTest` — `buildVertices()` returns 6 vertices, all at `y == −1.2f`, spanning ±14 m in XZ.
2. `GroundGridTest` — the pure Kotlin mirror of the fade function is 0 at `r = uNear`, ~1 in `[uFadeStart/2, uFadeStart]`, and 0 at `r ≥ uFadeEnd`; monotonic in the fade band.
3. `GroundOcclusionTest` — for every pitch in `[0°, 30°]`, `1.2/sin φ` exceeds the browse panel's 2.50 m and the dock's 2.05 m, proving painter's order is safe with no depth test. *(§12.3.)*
4. `GroundGatingTest` — `groundVisible` is false for `PLAYER` and true for the other three. *(Fails if the grid is unconditional — R17.)*
5. `GroundGridTest` — `uIntensity` is 1.0 / 0.5 / 0.0 for `LIGHT` / `MODERATE` / `SEVERE`.

### M8 — Integration & release

* `ExoVideoPlayer` `DefaultDataSource.Factory` fix (R3) — **this is what makes local playback work at all**.
* Wire `SystemDockScreen` into `AppScene`; delete the legacy list path from `BrowseScreen`.
* `proguard-rules.pro`: no new keeps expected (`MediaStore` is framework), but verify
  `assembleRelease` and confirm local browse + play on-device under R8 full mode.

**Acceptance**
1. `./gradlew test` — 100 % green across `:app`, `:vrcore`, `:upnp`, `:playback`.
2. `./gradlew assembleRelease` succeeds; the signed APK installs.
3. `ExoPlayerFactoryTest` — the media-source factory resolves a `content://` URI without throwing. *(Fails against the current HTTP-only factory — R3.)*
4. On-device checklist (§15).

---

## 15. On-device verification checklist

Pixel 11 Pro + Daydream View + Bluetooth gamepad. Every line is pass/fail, no interpretation.

1. Enter VR from the lobby with video permission granted → the Device source lists real folders with real counts.
2. Enter VR with permission **denied** → the Device source shows the "grant on the phone" state and `Exit to lobby` works. **No system dialog appears in the headset.**
3. Network source still lists the Gerbera server; browsing it still works; a UPnP video still plays.
4. Local video plays. **This is the R3 regression gate — it fails on the current data-source factory.**
5. Six posters appear within ~2 s of opening a folder; the frame rate does not visibly hitch as they land.
6. Gaze the far-left sidebar edge and hold for 3 s → the panel does **not** start following (R12).
7. Gaze the dock → the dock highlights and the browse panel does not (R1/R14 nearest-hit).
8. Gaze the browse panel's empty background → **nothing** highlights, including the dock behind it.
9. Head-turn 40° and back → the columns and the dock move as one rigid assembly, with no shear (R11).
10. Ground grid is visible and stable while browsing; lines do **not** crawl or shimmer under head motion (R16).
11. Enter the player → the grid disappears; play a 360 video → no grid inside the sphere (R17).
12. Cycle sort → the focused card stays the same card (R18).
13. `B` from every focus region — sidebar, grid, inspector, dock — always goes back one level.
14. Long-`B` from anywhere returns to the source list.
15. Resume: play a local file 60 s in, exit, reopen → the RESUME button shows `01:00` and works.
16. 20 min of continuous browsing → thermal status stays ≤ MODERATE; the grid dims rather than the frame rate dropping.

---

## 16. Authorised amendments to `ARCHITECTURE.md`

These are the only places this work contradicts the standing architecture. Apply them to
`ARCHITECTURE.md` in M8 so the two documents do not drift.

1. **§11.3** — the single `±25°` horizontal comfort limit becomes the two-tier model in §2.5 here
   (eyes-only 25° for primary targets, head-turn 35° for secondary), and the vertical limit becomes
   asymmetric (`+20°` up, `−30°` down).
2. **§11.4** — the `Browse` screen row is replaced by the three-column + dock description in §5.
3. **§16** — "Notably *not* required: … storage" is no longer true. `READ_MEDIA_VIDEO` (33+) /
   `READ_EXTERNAL_STORAGE` (≤32) / `READ_MEDIA_VISUAL_USER_SELECTED` (34+) are now required, requested
   in the 2D lobby only, and used for nothing but enumerating and reading local video. No write access
   is requested and none is needed.
4. **§14** — the thermal table gains the ground-grid intensity row; the thumbnail 24 MB cap is
   restated as the `SizedLruCache` budget and is now enforced by a test rather than by convention.
5. **§12** — `BrowseFrame`'s shape changes from `MediaServer` + DIDL types to `MediaSource` +
   `MediaNode`, per §10.1 here.

---

## 17. Risks and non-goals

### Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| Moving panel paint off the GL thread surfaces a latent race in `PanelSurface` | Medium | It is already designed for this and `hitMap` is already `@Volatile`. Land M2 (gaze) before M5 (widgets) so the surface count is stable before the thread model changes. Keep one pending block per panel. |
| The strangler stalls half-done and `:app` carries both models for weeks | Medium | S1–S6 are six commits in one milestone, not six milestones. Do not start M5 with S6 outstanding. |
| `loadThumbnail` is slow or throws on a large fraction of a real library | Medium | Placeholder-first rendering means a miss costs nothing visually; the in-flight map means a failure is not retried in a hot loop. |
| Ground-grid fill cost is worse than modelled on a thermally-loaded device | Low | `uIntensity` is already on the governor; worst case it is disabled and nothing else regresses. |
| `MediaStore _ID` changes across a full rescan, orphaning resume entries | Low | Accepted. `ResumeStore` already evicts; a lost local resume position is a minor, recoverable annoyance. Documented, not engineered around. |
| Column-edge `LEFT`/`RIGHT` navigation feels wrong in the headset | Medium | It is a reducer-only change behind `ColumnNavTest`; tune the edge rules without touching the renderer. |

### Explicit non-goals for this work

Search; playlists and queueing; favourites persistence (the source appears but is empty and is
disabled); disk thumbnail cache; UPnP `SortCriteria` server-side sorting; a 4×2 dense grid; Compose or
`View`-to-texture rendering (`ARCHITECTURE.md §11.1` rejects it and nothing here changes that
analysis); animated panel transitions; audio-only or image media.

---

## 18. Appendix — derived constants

```
R              = 2.50 m                     R_dock         = 2.05 m
W              = 2.80 m                     W_dock         = 1.00 m
θ_total        = W/R  = 1.12 rad            θ_dock         = 0.487805 rad
               = 64.1713°                                  = 27.9492°
W_px           = 1536                       W_px,dock      = 672
H_px           =  800                       H_px,dock      = 176
H              = H_px·W/W_px = 1.458333 m   H_dock         = 0.261905 m
ppd            = W_px/θ_total = 23.9359     ppd_dock       = 24.0437
isotropy       = (1536/2.80)/(800/1.458333) = 1.000000  ✓
isotropy_dock  = (672/1.00)/(176/0.261905)  = 1.000000  ✓
θ_v (arc)      = H/R = 33.4225°             θ_v,dock (arc) = 7.3200°
θ_v (true)     = 2·atan(H/2R) = 33.02°
y_offset       = −R·tan(2°)  = −0.087302 m  y_offset,dock  = −R_d·tan(24°) = −0.912719 m
panel top      = +14.399° (true)            dock top       = −20.874° (true)
panel bottom   = −18.086° (true)            dock bottom    = −26.981° (true)
inter-surface gutter = 2.788°
columns (px)   = [0,394) | 24 | [418,1118) | 24 | [1142,1536)
columns (°)    = −32.086…−15.625 | −14.622…+14.622 | +15.625…+32.086
column centres = −23.855° | 0.000° | +23.855°
grid           = 3 cols × 2 rows = 6 / page
cardW          = 215.78 px = 9.015°         cardH  = 259.89 px = 10.858°
cell pitch     = 289.36 px = 12.089°        poster = 121.38 px (16:9)
row height     = 75.57 px = 3.157° (rowTitle) | 65.35 px = 2.730° (chip)
dock cell      = 112 px = 4.658° × 176 px = 7.320°
GROUND_Y       = −1.2 m, cell 0.5 m, hole 1.2 m, fade 3.0 → 12.0 m
```

*End of authoritative plan.*
