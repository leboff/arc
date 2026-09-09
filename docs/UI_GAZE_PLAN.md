# UI Sheen, Layout Repair & Gaze Pointer — Architecture & Implementation Plan

**Reader:** Claude Sonnet 5 (implementing agent).
**Design source of truth:** [ARCHITECTURE.md](ARCHITECTURE.md). Where this plan and that document disagree, the architecture wins — fix this plan and note it in the commit.
**Status:** design complete, not yet implemented. Written after a full read of `:vrcore/ui`, `:vrcore/render`, `:vrcore/tracking` and `:app/screens`.

This plan covers three pieces of device feedback from the Pixel 11 Pro / Daydream View test:

1. *"I think it's ugly. Would like some UI sheen on it, incorporate some UX best practices."*
2. *"It looks like the menu isn't working correctly."* — the `ServerListScreen` subtitle collides with the row below it.
3. *"Should also incorporate an eye pointer so things can be looked at and then selected with the controller."*

(2) is not one bug. It is the visible symptom of five interacting layout defects, all of which have to be fixed before (3) can work at all — a gaze pointer is only as good as the hit rectangles it tests against, and today no component knows where its own rows are.

---

## 0. Audit: what is actually wrong today

Every number below is computed from the code as committed at `cc4e5a5`.

### F1 — `Theme.panelWidthDegrees` is a global constant, but no panel subtends 42°

`Theme.panelWidthDegrees = 42f` feeds every call to `AngularMetrics.textSizePx`. But each `ScreenPanel` passes its own `panelWidthM` to a `PanelQuad` with `curveRadiusM = anchor.distanceM = 2.5 m`, so the arc it really subtends is `widthM / 2.5` radians:

| Panel | `widthM` | texture | true arc | assumed | type is oversized by |
|---|---|---|---|---|---|
| `ServerListScreen` | 2.2 | 1024×640 | **50.4°** | 42° | ×1.20 |
| `BrowseScreen` | 2.6 | 1280×768 | **59.6°** | 42° | ×1.42 |
| `SettingsScreen` | 2.4 | 1024×720 | **55.0°** | 42° | ×1.31 |
| `PlayerHud` | 2.8 | 1280×384 | **64.2°** | 42° | ×1.53 |
| `OverlayRenderer` | 2.0 | 1024×768 | **45.8°** | 42° | ×1.09 |

A "1.8° title" on the browse panel is really 2.55° of visual angle. Everything is 9–53% larger than the design intends, which is exactly why the UI reads as blocky and why so few rows fit. This is the root cause behind F2–F5.

### F2 — `ListView.rowHeightPx` cannot contain its own content (**the reported collision**)

```
rowHeightPx  = 3.2° → 3.2/42 × 1024 = 78.0 px      (ServerList)
titleSize    = 1.8° → 43.9 px, baseline at rowTop + 51.9
subtitleSize = 1.3° → 31.7 px, baseline at rowTop + 91.6
```

The subtitle baseline sits **13.6 px past the bottom of its own row band**, i.e. inside the next row's rectangle. On `ServerListScreen` the `"Enter host:port"` subtitle of *Add server manually* is therefore drawn on top of the *Retry discovery* row — precisely the reported fault. The row needs ≥ 4.4° to hold `1.8 + 1.3` of text plus the four hard-coded `+8f`/`+16f` offsets and a descender.

The offsets themselves are the deeper problem: baselines are computed as `textSize + 8f` instead of from `Paint.FontMetrics`, so they are wrong by a font-dependent amount on every device.

### F3 — `SettingsScreen` can focus rows it never draws

`Settings.ROWS` has 14 entries. The panel gives the list `720 − 84 − 58.5 = 577.5 px`, so `visibleRowCount()` returns **7** — and `SettingsScreen.render` passes `scrollTop = 0` unconditionally. Rows 7–13 (`Distortion correction` … `Forget servers`) are reachable with the D-pad but are never rendered. The user navigates blind, and *"Forget servers"* is effectively unreachable.

### F4 — `ServerListScreen` has the same defect

`scrollTop = 0` hard-coded, ~6 rows visible. Any user with ≥ 5 discovered servers cannot see the *Retry discovery* row they are focusing.

### F5 — the reducer's scroll window is a guess

`AppStateMachine.BROWSE_VISIBLE_ROWS = 8` drives `clampScroll` and `PageUp`/`PageDown`, but nothing ties it to the `visibleRowCount()` the renderer actually uses. They agree today by coincidence (`floor(630/78) = 8`); any change to panel size, texture size or type scale silently desynchronises scrolling from drawing.

### F6 — panel yaw is mirrored relative to head yaw

`VrActivity` computes `headYawRad = atan2(-pose[8], pose[10])`. For a head rotated by `θ` about `+Y`, `pose[8] = sin θ` and `pose[10] = cos θ`, so **`headYawRad = −θ`**. `ScreenPanel.drawGl` then does `Matrix.rotateM(model, 0, +degrees(anchor.yawRad), 0, 1, 0)`, placing the panel's centre at world azimuth `−anchor.yawRad = +θ`… mirrored about dead ahead (verified numerically in §1.6).

Consequences: `recenter()` while looking 30° off-centre parks the panel 60° away; lazy-follow past the 35° threshold eases the panel *away* from the head. `AppScene` sets `cylinder.yawRad`/`sphere.yawRad` from the same value, so the cinema screen recentres to the mirrored yaw too. **This must be fixed before gaze**, because the raycast has to know where the panel really is.

### F7 — the panel's curve radius can desynchronise from its distance

`ScreenPanel` captures `curveRadiusM = anchor.distanceM` at construction, but `PanelAnchor.distanceM` is a `var`. The clean closed-form raycast in §1.4 depends on `curveRadiusM == distanceM`; if that ever drifts the geometry silently becomes an off-axis cylinder.

### F8 — panel textures do not preserve physical aspect

`BrowseScreen` is 2.6 m × 1.5 m on a 1280×768 texture: 492.3 px/m horizontally vs 512.0 px/m vertically. Everything on that panel is stretched 4% vertically. `PlayerHud` is 2.8 × 0.9 on 1280×384: 457 vs 427 px/m, 7% squashed.

### F9 — hard-coded right margins

`ServerListScreen` draws `"Searching…"` at `widthPx − padding − 160f`; `BrowseScreen` draws `"Loading…"` at `widthPx − padding − 140f`. Both are guesses at the text width with left-aligned paint. Use `Paint.Align.RIGHT`.

### F10 — `VrKeyboard` mis-centres key labels

`drawText(label, x + cellW/2 − keySize/2, …)` offsets by half the **text size**, not half the **measured width**. Wrong for every glyph; visibly wrong for `⌫`, `↵`, `␣`.

### F11 — icons are font glyphs

`"▸ "`, `"▶ "`, `"✓ "`, `"❚❚"`, `"›"` are drawn as text. Glyph coverage in the default sans typeface is not guaranteed across OEM builds — a missing glyph renders as tofu — and they carry font side-bearings that break alignment.

### F12 — the detail card fakes columns with spaces

`"Resolution  ${w}×${h}"` in a proportional font produces a ragged left edge for the values. Measure and lay out two real columns.

### F13 — `Grid` scroll and focus disagree

`BrowseScreen` passes `scrollRow = frame.scrollTop / 3` while `focusIndex` stays a flat index, so grid mode scrolls to a different place than it focuses.

### F14 — no hover concept exists

There is no gaze, no hit-testing, no notion of a target under a pointer anywhere in the codebase.

---

## 1. Mathematics: gaze ray → panel UV → pixel

This section is normative. All of it is pure `Float` arithmetic with **no `android.opengl.Matrix`**, because `:vrcore` unit tests run with `unitTests.isReturnDefaultValues = true` — any Android graphics call in a JVM test silently returns `0` and the test passes while asserting nothing.

### 1.1 Frames and conventions (from ARCHITECTURE.md §5)

GL world `W`: right-handed, `+X` right, `+Y` up, `−Z` forward. Head `H`: `+X` right ear, `+Y` skull top, `−Z` out the nose. Matrices are column-major `FloatArray(16)`; element `(row, col)` is at `m[col*4 + row]`.

`HeadTracker.poseFor()` writes `R_W_H`, mapping head-space vectors into world space. Its columns are the head basis expressed in world coordinates:

```
col0 = (m[0], m[1], m[2])    = head +X (right ear)   in world
col1 = (m[4], m[5], m[6])    = head +Y (skull top)   in world
col2 = (m[8], m[9], m[10])   = head +Z (out the back of the head) in world
```

### 1.2 The gaze ray

Head `−Z` is the gaze direction, so the world-space forward vector is the **negated third column**:

```
D = (−m[8], −m[9], −m[10])
```

`R_W_H` is orthonormal, so `D` is already unit length; do not renormalise on the hot path (assert it in tests instead).

The ray origin is the cyclopean head position. With 3DOF tracking that is the origin, unless the neck model is on: `StereoLayout.viewMatrix` post-translates the view by `−neckModel`, which places the camera at `R_W_H · neckModel`. So:

```
O = R_W_H · n        where n = profile.neckModelM (or 0 when the neck model is off)

O.x = m[0]·n.x + m[4]·n.y + m[8] ·n.z
O.y = m[1]·n.x + m[5]·n.y + m[9] ·n.z
O.z = m[2]·n.x + m[6]·n.y + m[10]·n.z
```

This is not optional pedantry: a 7 cm neck offset against a 2.5 m panel is up to **1.6° of parallax**, and a browse row is only ~3.3° tall — ignoring it would put the reticle half a row off whenever the user tilts their head.

Use the **cyclopean** origin, never a per-eye origin. Both eyes must converge on one world point or the reticle double-images.

Two scalar helpers used throughout:

```
azimuth ψ = atan2(D.x, −D.z)        // 0 = dead ahead, + = to the right
pitch   p = asin(D.y)               // + = up
```

Note `ψ` is exactly the quantity `VrActivity` already computes as `headYawRad`. Keep that name and that formula; §1.6 fixes the *consumer*, not this definition.

### 1.3 The panel surface, parametrised

`PanelQuad.buildVertices` emits, for a curved panel of width `w`, height `h` and curve radius `R`:

```
p_local(u, v) = ( R·sin θ ,  (v − 0.5)·h ,  R·(1 − cos θ) )      θ = (u − 0.5)·arc,  arc = w / R
```

`u ∈ [0,1]` left→right, `v ∈ [0,1]` bottom→top (pinned by `PanelQuadTest`). `ScreenPanel.drawGl` places it with

```
M = R_y(a) · T(0, y₀, −d)          a = anchor.yawRad,  d = anchor.distanceM,  y₀ = verticalOffsetM
```

### 1.4 Simplification: the panel is a cylinder about the world Y axis

Substituting, with `R_y(a)·(x,y,z) = (x·cos a + z·sin a, y, −x·sin a + z·cos a)`:

```
x_w = R·sin θ·cos a + (R − R·cos θ − d)·sin a
z_w = −R·sin θ·sin a + (R − R·cos θ − d)·cos a
```

**When `R = d`** (which `ScreenPanel` guarantees — see F7) the `R − R·cos θ − d` term collapses to `−d·cos θ` and the whole thing telescopes:

```
p_world(u, v) = ( d·sin φ ,  (v − 0.5)·h + y₀ ,  −d·cos φ )        φ = θ − a
```

So `x_w² + z_w² = d²` identically: **the panel lies exactly on a vertical cylinder of radius `d` centred on the world origin**, occupying azimuths `φ ∈ [−arc/2 − a, +arc/2 − a]` and heights `y ∈ [y₀ − h/2, y₀ + h/2]`. Its azimuth (using the `ψ` definition above) is `φ = θ − a`, and its **centre azimuth is `−a`**.

Verified numerically: for `a = 0.3 rad`, `u = 0.75`, `w = 2.4`, `d = 2.5` the world point is `(−0.14991, 0, −2.4955)` — radius `2.500000`, azimuth `−3.4377°`, which equals `θ − a = (0.25 × 0.96) − 0.3 rad = −3.4377°`. ✔

### 1.5 Intersection, UV, and pixels

**Case A — origin at the world origin (`O = 0`, neck model off).** Every forward ray hits the cylinder exactly once:

```
k = √(D.x² + D.z²)                    // = cos(pitch); guard k < 1e-4 (looking straight up/down)
t = d / k                              // always > 0
P = t·D
y = t·D.y                              // = d·tan(pitch)
```

**Case B — general origin (neck model on).** Standard ray/vertical-cylinder quadratic; take the positive root (the viewer is inside the cylinder, so exactly one root is positive):

```
A = D.x² + D.z²
B = 2·(O.x·D.x + O.z·D.z)
C = O.x² + O.z² − d²
disc = B² − 4AC                        // > 0 whenever the viewer is inside; bail if ≤ 0
t = (−B + √disc) / (2A)
P = O + t·D
```

Then, in both cases:

```
ψ_hit = atan2(P.x, −P.z)
θ     = wrapPi(ψ_hit + a)               // invert φ = θ − a; wrapPi handles the ±180° seam
u     = θ / arc + 0.5                   // arc = w / d, radians
v     = (P.y − y₀) / h + 0.5
hit   = u ∈ [0,1] and v ∈ [0,1]
```

**Pixel mapping.** The `Canvas` origin is top-left with `y` growing downward; `v` grows upward:

```
xPx = u · widthPx
yPx = (1 − v) · heightPx
```

The `v = 1 ⇒ yPx = 0` flip is the one that commit `cc4e5a5` had to correct in the shader. Pin it with a test that reads `PanelQuad.buildVertices` directly rather than restating the constant.

**Worked test vector** (browse panel: `w = 2.4 m`, `h = 1.5 m`, `d = 2.5 m`, `1280 × 800 px`, `a = 0`, `y₀ = 0`; gaze `ψ = +10°`, `p = −5°`):

```
D    = (0.172975, −0.087156, −0.981060)
k    = 0.996195,  t = 2.509549,  y = −0.218718 m   (= −2.5·tan 5°)
θ    = 0.174533 rad,  u = 0.681806,  v = 0.354188
xPx  = 872.7,  yPx  = 516.7
```

Cross-check: `1280 px / 55.0° = 23.27 px/°`; 5° below centre is `400 + 116.4 = 516.4 px`. The 0.3 px difference is the honest `tan` vs. linear term. Use these exact numbers in `PanelRaycastTest`.

Further vectors to pin:

| gaze | expectation |
|---|---|
| `ψ = 0, p = 0` | `u = v = 0.5`, `xPx = 640`, `yPx = 400` |
| `ψ = arc/2 = 27.5°` | `u = 1.0` exactly (boundary, inclusive) |
| `ψ = 28°` | `null` (outside the panel) |
| `ψ = 10°`, panel `a = 10°`… | see §1.6: with the fixed convention, `u = 0.5` |
| `D.z > 0` (behind) | `null` — the azimuth falls outside the arc |
| `p = 85°` | `null` (guard on `k`) |

### 1.6 The yaw sign fix (F6), derived

For a head rotated `θ` about `+Y`, `R_W_H = R_y(θ)` gives `m[8] = sin θ`, `m[10] = cos θ`, so

```
headYawRad = atan2(−m[8], m[10]) = −θ      // verified numerically: θ = 30° → headYawRad = −30°
```

Meanwhile §1.4 shows the panel's centre azimuth is `−a`, where `a = anchor.yawRad`. Setting `a := headYawRad = −θ` puts the panel centre at azimuth `+θ`… but the head's own azimuth under the same `ψ = atan2(D.x, −D.z)` definition is `atan2(−sin θ, cos θ) = −θ`. The panel lands at the **mirror image** of the head yaw.

State the requirement as an invariant, so the test — not a sign buried in a diff — is the contract:

> **Invariant P1.** For any azimuth `ψ`, after `anchor.snapTo(ψ)` the panel's centre direction must satisfy `atan2(centre.x, −centre.z) == ψ`.

Section 1.4 gives `centre azimuth = −a`, where `a` is the angle handed to `Matrix.rotateM` about `+Y`. So P1 holds iff

```
a = −anchor.yawRad
```

**Fix:** keep `headYawRad = atan2(−m[8], m[10]) = ψ` — it is the correct, documented azimuth — and negate at each *consumer* that feeds a `rotateM` about `+Y`. There are three: `ScreenPanel.drawGl`, `CylinderScreen.draw`, `SphereScreen.draw`.

Encapsulate it once so the renderer and the raycast can never disagree:

```kotlin
// ScreenPanel
/** Angle for the panel's model rotation, radians. Negated because Matrix.rotateM about
 *  +Y turns the opposite way from the atan2(x, −z) azimuth convention (UI_GAZE_PLAN.md §1.6). */
protected val modelYawRad: Float get() = -anchor.yawRad

// drawGl
Matrix.rotateM(model, 0, Math.toDegrees(modelYawRad.toDouble()).toFloat(), 0f, 1f, 0f)
```

`PanelGeometry.modelYawRad` carries the same value, and §1.5's `θ = wrapPi(ψ_hit + a)` uses `a = modelYawRad`. Check it end to end: a panel anchored at `ψ = 30°` has `modelYawRad = −30°`; a gaze straight at the panel centre gives `ψ_hit = 30°`, so `θ = 30° − 30° = 0` and `u = 0.5`. ✔

`PanelYawConventionTest` asserts P1 by reconstructing the model matrix in pure Kotlin and comparing the resulting centre direction against the pose forward vector.

### 1.7 Flat panels

`PanelQuad` supports `curved = false` (unused today, likely wanted for the overlay dialog). Plane through `C = R_y(a)·(0, y₀, −d)` with normal `N = R_y(a)·(0,0,1)` and basis `right = R_y(a)·(1,0,0)`, `up = (0,1,0)`:

```
den = N·D;  if (|den| < 1e-6) → null            // ray parallel to the panel
t   = (N·(C − O)) / den;  if (t ≤ 0) → null     // panel behind the viewer
P   = O + t·D
u   = ((P − C)·right) / w + 0.5
v   = ((P − C)·up)    / h + 0.5
```

Same UV→pixel mapping. Ship both paths in `PanelRaycast`, selected by `PanelGeometry.curved`.

---

## 2. Component architecture

### 2.1 New files

```
vrcore/src/main/java/com/daydreamvr/vrcore/ui/PanelMetrics.kt      # angular ↔ pixel, per panel
vrcore/src/main/java/com/daydreamvr/vrcore/ui/GazeRay.kt           # pose → Ray, pure
vrcore/src/main/java/com/daydreamvr/vrcore/ui/PanelRaycast.kt      # Ray × PanelGeometry → PanelHit, pure
vrcore/src/main/java/com/daydreamvr/vrcore/ui/HitRegion.kt         # HitRegion<T>, HitMap<T>, pure
vrcore/src/main/java/com/daydreamvr/vrcore/ui/GazeStabilizer.kt    # hover debounce, pure
vrcore/src/main/java/com/daydreamvr/vrcore/ui/Reticle.kt           # GL billboard + procedural ring
vrcore/src/main/java/com/daydreamvr/vrcore/ui/Surfaces.kt          # panel/card/focus/hover paint recipes
vrcore/src/main/java/com/daydreamvr/vrcore/ui/Icons.kt             # Path-based line icons
vrcore/src/main/java/com/daydreamvr/vrcore/ui/TextMeasure.kt       # injectable text measurement
app/src/main/java/com/daydreamvr/player/state/GazeTarget.kt        # sealed target vocabulary
```

### 2.2 `PanelMetrics` — one honest angular scale per panel (fixes F1, F8)

```kotlin
/**
 * The angular ↔ pixel scale of one panel. Replaces Theme.panelWidthDegrees, which
 * assumed every panel subtended the same arc (UI_GAZE_PLAN.md §0 F1).
 */
data class PanelMetrics(
    val widthPx: Int,
    val heightPx: Int,
    val widthDegrees: Float,
) {
    val pxPerDegree: Float get() = widthPx / widthDegrees
    fun px(degrees: Float): Float = degrees * pxPerDegree
    fun deg(px: Float): Float = px / pxPerDegree
    val heightDegrees: Float get() = deg(heightPx.toFloat())

    companion object {
        /** Curved panel: arc length [widthM] on a cylinder of [radiusM]. */
        fun curved(widthPx: Int, heightPx: Int, widthM: Float, radiusM: Float) =
            PanelMetrics(widthPx, heightPx, Math.toDegrees((widthM / radiusM).toDouble()).toFloat())
    }
}
```

`Theme.panelWidthDegrees` is **deleted**. Widgets take a `PanelMetrics`, not a `panelWidthPx: Int`. `AngularMetrics.textSizePx` stays (it is the primitive `PanelMetrics.px` delegates to) but every call site moves to `metrics.px(deg)`.

**Panel table (new).** Texture sizes chosen so `widthPx / widthM == heightPx / heightM` within 1% (fixes F8):

| Panel | widthM × heightM | texture | arc | px/° | vertical |
|---|---|---|---|---|---|
| `ServerListScreen` | 2.20 × 1.45 | 1024 × 676 | 50.4° | 20.3 | 33.2° |
| `BrowseScreen` | 2.40 × 1.50 | 1280 × 800 | 55.0° | 23.3 | 34.4° |
| `SettingsScreen` | 2.20 × 1.50 | 1024 × 700 | 50.4° | 20.3 | 34.4° |
| `PlayerHud` | 2.40 × 0.62 | 1280 × 332 | 55.0° | 23.3 | 14.2° |
| `OverlayRenderer` | 2.00 × 1.30 | 1024 × 668 | 45.8° | 22.3 | 29.8° |
| `GamepadCalibrationScreen` | 2.20 × 1.10 | 1024 × 512 | 50.4° | 20.3 | 25.2° |
| `CalibrationScreen` | 6.00 × 4.00 | 1536 × 1024 | 137.5° | — | back wall, non-interactive |

`BrowseScreen` at 55° slightly exceeds the ±25° comfort box (ARCHITECTURE.md §11.3); reserve the outer 2.5° per side (≈58 px) as dead margin holding no text and no hit region.

### 2.3 `GazeRay` and `PanelRaycast`

```kotlin
data class Ray(val ox: Float, val oy: Float, val oz: Float, val dx: Float, val dy: Float, val dz: Float)

object GazeRay {
    /** Forward ray from a column-major R_W_H. [neckOffsetM] is head-space; null ⇒ origin. */
    fun fromPose(pose: FloatArray, neckOffsetM: FloatArray? = null): Ray
    fun azimuthRad(ray: Ray): Float   // atan2(dx, −dz)
    fun pitchRad(ray: Ray): Float     // asin(dy)
}

data class PanelGeometry(
    val modelYawRad: Float,      // ScreenPanel.modelYawRad — already sign-corrected (§1.6)
    val distanceM: Float,
    val widthM: Float,
    val heightM: Float,
    val verticalOffsetM: Float,
    val widthPx: Int,
    val heightPx: Int,
    val curved: Boolean = true,
)

data class PanelHit(
    val u: Float, val v: Float,
    val xPx: Float, val yPx: Float,
    val distanceM: Float,                     // t — the reticle's depth
    val wx: Float, val wy: Float, val wz: Float,
)

object PanelRaycast {
    /** Null when the ray misses the quad's [0,1]² extent. */
    fun intersect(ray: Ray, panel: PanelGeometry): PanelHit?
    /** Same, but returns the surface point even outside the extent (reticle fallback). */
    fun intersectUnbounded(ray: Ray, panel: PanelGeometry): PanelHit?
}
```

Zero allocation on the hot path is *not* required here — this runs once per frame, not once per vertex — but keep it allocation-light: one `PanelHit` per frame is fine, and `null` when nothing is hit.

### 2.4 `HitRegion` / `HitMap` — layout produces pixels **and** hit rectangles

The single idea that makes hit-testing trustworthy: **the code that draws a row is the code that publishes its rectangle.** They cannot drift because they come from the same `measure()` call.

```kotlin
data class HitRegion<out T>(
    val left: Float, val top: Float, val right: Float, val bottom: Float, val id: T,
) {
    fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
}

class HitMap<T>(val regions: List<HitRegion<T>> = emptyList()) {
    /** Last match wins, so later (higher) regions sit on top. */
    fun hitTest(xPx: Float, yPx: Float): T?
    companion object { fun <T> empty() = HitMap<T>() }
}
```

`ScreenPanel` gains:

```kotlin
/** Rebuilt on every repaint; read by the gaze pass on the GL thread. */
@Volatile var hitMap: HitMap<GazeTarget> = HitMap.empty()
    protected set

open fun gazeGeometry(): PanelGeometry
```

Repaints happen on the GL thread today (`AppScene.update` → `render()` → `PanelSurface.draw` paints inline), so there is no race — the `@Volatile` is insurance in case panel painting ever moves to its own thread, as ARCHITECTURE.md §11.2's "PanelDrawThread" anticipates.

Because `renderIfChanged` skips the block when nothing changed, `hitMap` must persist between repaints. It does: it is only reassigned inside the block.

### 2.5 `GazeStabilizer` — no flicker on row boundaries

```kotlin
/**
 * Debounces the raw per-frame hover target. A candidate must hold for
 * [holdSeconds] before it becomes current; until then the previous target stands.
 * Pure — the caller supplies dt.
 */
class GazeStabilizer<T>(private val holdSeconds: Float = 0.06f) {
    fun update(candidate: T?, dtSeconds: Float): T?   // returns the stable target
    fun reset()
}
```

60 ms ≈ 4 frames at 60 Hz — long enough to swallow tracking jitter at a row edge, short enough to feel instant. Combined with edge-triggered dispatch (emit only when the stable target *changes*), a motionless head never fights the D-pad.

### 2.6 `Reticle` — the eye pointer

```kotlin
class Reticle {
    var angularRadiusDeg: Float = 0.55f     // 1.1° ring — unobtrusive, still visible over video
    fun onGlCreate(); fun onGlDestroy()
    fun draw(
        viewM: FloatArray, projM: FloatArray,
        px: Float, py: Float, pz: Float,          // world hit point
        camX: Float, camY: Float, camZ: Float,    // cyclopean origin, for the billboard basis
        hoverT: Float,                            // 0 = idle, 1 = over an interactive target
        alpha: Float,
    )
    companion object {
        fun radiusMForDistance(distanceM: Float, angularRadiusDeg: Float): Float =
            distanceM * tan(toRadians(angularRadiusDeg))
        /** Pure, column-major. Unit quad → billboard at (px,py,pz) facing the camera. */
        fun billboardModel(px: Float, py: Float, pz: Float,
                           camX: Float, camY: Float, camZ: Float,
                           radiusM: Float, out: FloatArray)
    }
}
```

**Why a world-space billboard and not a screen-space overlay:** drawn as a world object with the per-eye `viewM`/`projM`, the reticle's stereo disparity automatically matches the panel it sits on. A screen-space dot at a fixed disparity fights the eyes' vergence and produces the classic double-cursor that makes gaze UIs nauseating.

`billboardModel` builds an orthonormal basis with `−Z` pointing at the camera:

```
f = normalize(cam − p)                       // billboard normal
r = normalize(cross(worldUp, f));  if |cross| < 1e-4 use worldRight (looking straight up/down)
u = cross(f, r)
M = [ r·radius | u·radius | f | p ]          // columns, column-major
```

Constant angular size across depth: `radius = distance · tan(angularRadius)`, so the reticle is the same apparent size on a 2.5 m panel and on the 4 m cinema screen.

**Appearance** (procedural in the fragment shader — no texture, no mip chain):

- dark halo ring at `r ∈ [0.58, 0.86]` in `#B3000000` — keeps the reticle readable over bright video;
- bright core ring at `r ∈ [0.62, 0.82]`, `#F2A9E7F5` idle → accent `#FF6FD8EC` on hover;
- centre dot at `r < 0.16`, alpha 0.9;
- hover adds an outer glow `exp(−18·(r − 0.82)²)` at 35% accent, and scales the whole quad by `1 + 0.18·hoverT`.

`hoverT` is smoothed toward its target with a 90 ms time constant **on the GL thread**, per frame. This is the only animation in the UI, and it is free: the panels stay static, honouring ARCHITECTURE.md R4 (no per-frame Canvas repaints).

Blending matches `PanelQuad`: premultiplied alpha, `glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)`, `glDepthMask(false)`, and **depth test disabled** so the reticle is never occluded by the panel it is sitting on. Draw it last, after every panel and overlay.

**Visibility rules:**

| situation | reticle |
|---|---|
| any UI panel or overlay is up | shown; `hoverT = 1` when over a target |
| hit misses the panel | shown dim (`alpha 0.45`) at the unbounded surface point, so the user can find it |
| `PLAYER` with the HUD hidden | **hidden** — nothing is interactive, and a dot on the film is intolerable |
| `PLAYER` with the HUD visible | shown, raycast against the HUD only |
| head tracker not yet calibrated | hidden |

---

## 3. Hover, hit-testing, and select

### 3.1 The pipeline, per frame (GL thread)

```
VrRenderer.onDrawFrame
  └─ pose = poseProvider()
  └─ scene.update(dt, pose)                       ← Scene interface gains the pose parameter
       ├─ 1. panels render themselves if their slice changed  (publishes hitMap)
       ├─ 2. ray      = GazeRay.fromPose(pose, neckOffset)
       ├─ 3. surface  = activeGazeSurface(state)  ← overlay > screen; null in PLAYER w/o HUD
       ├─ 4. hit      = PanelRaycast.intersect(ray, surface.gazeGeometry())
       ├─ 5. raw      = hit?.let { surface.hitMap.hitTest(it.xPx, it.yPx) }
       ├─ 6. stable   = stabilizer.update(raw, dt)
       ├─ 7. if (stable != last) { last = stable; onGazeTarget(stable) }   ← edge-triggered
       └─ 8. gazeHit  = hit ?: PanelRaycast.intersectUnbounded(...)        ← reticle placement
  └─ scene.draw(eye, viewM, projM)   × 2
       └─ … panels … then reticle.draw(gazeHit)
```

`onGazeTarget` is a constructor callback on `AppScene`; `VrActivity` supplies
`{ t -> runOnUiThread { stateMachine.dispatch(Event.GazeMoved(t)) } }`. Edge-triggering means one `Runnable` allocation per hover change (a handful per second), not per frame.

### 3.2 `GazeTarget`

```kotlin
// app/state/GazeTarget.kt
sealed interface GazeTarget {
    data class ServerRow(val index: Int) : GazeTarget       // absolute, incl. the footer actions
    data class BrowseRow(val index: Int) : GazeTarget       // absolute row index, not screen-relative
    data class SettingsRow(val index: Int) : GazeTarget
    data class HudControl(val index: Int) : GazeTarget
    data class DialogButton(val index: Int) : GazeTarget
    data class KeyboardKey(val row: Int, val col: Int) : GazeTarget
}
```

Indices published by a `hitMap` are always **absolute** (`scrollTop + i`), so the reducer never has to know about scrolling.

### 3.3 Reducer changes — gaze moves focus, the controller still selects

Add to `AppState`:

```kotlin
val gaze: GazeTarget? = null,          // what the reticle is over; drives the hover style
val settingsScrollTop: Int = 0,        // fixes F3
val serverScrollTop: Int = 0,          // fixes F4
val listWindow: ListWindow = ListWindow(),   // fixes F5
```

```kotlin
/** Visible row counts measured by the renderer, so reduce() scrolls what is actually drawn. */
data class ListWindow(val browse: Int = 8, val settings: Int = 8, val servers: Int = 5)
```

New events:

```kotlin
data class GazeMoved(val target: GazeTarget?) : Event
/** Emitted once by AppScene at GL-create from the real measured layouts (fixes F5). */
data class ListWindowMeasured(val window: ListWindow) : Event
```

`reduceGaze(state, target)`:

1. store `gaze = target` **always** (even `null`) — that is the hover highlight;
2. if the target names the currently active surface, move that surface's focus index to it:
   `ServerRow → serverFocusIndex`, `BrowseRow → browse.top.focusIndex`, `SettingsRow → hud.focusIndex`, `HudControl → hud.focusIndex`, `DialogButton → overlay.focusIndex`, `KeyboardKey → kb.cursorRow/Col`;
3. **never** change any `scrollTop` — gaze can only reach visible rows by construction;
4. for `HudControl`, also refresh `hud.lastInputAtMs = state.nowMs`, so looking at the HUD keeps it alive;
5. if an overlay is up, ignore targets belonging to the screen behind it.

**Selection needs no new code.** `InputAction.Confirm` already acts on the focus index, so "look at a row, press A" works the moment gaze writes focus. That is exactly the requested blend of gaze and D-pad: both drive the same cursor, last one wins, and the visual language distinguishes *hover* (where you're looking) from *focus* (what A will activate) only in the rare frames where they differ.

### 3.4 Suppressing gaze/D-pad fights

Edge-triggered dispatch plus the 60 ms stabilizer hold is sufficient: a stationary head emits nothing, so D-pad navigation is never overridden. The one residual behaviour — D-pad to row 5 while looking at row 2, then a small head movement snaps focus back to row 2 — is *correct*: the user looked away and then moved their head, so the pointer wins. Document it; do not add a timer.

---

## 4. Design system overhaul

### 4.1 Principles specific to this display path

These are constraints from the optics, not taste:

- **Nothing below 1.5° of visual angle** (ARCHITECTURE.md §11.3). Assert it in tests for every type token on every panel.
- **No saturated blue text.** Chromatic aberration is worst at the blue end; cyan is fine for *strokes and fills*, but text uses the desaturated `accentText`.
- **No pure white on black.** Bloom through Cardboard lenses.
- **No animation on the Canvas.** Panels repaint only when their slice changes (R4). All motion lives in the GL reticle, which is per-frame anyway.
- **Every baseline comes from `Paint.FontMetrics`**, never `textSize + magicConstant`. This is the discipline that permanently closes the F2 class of bug.

### 4.2 Tokens — `Theme.kt` rewrite

```kotlin
class Theme {
    // ── Surface ────────────────────────────────────────────────
    val panelFillTop      = 0xF21B1E26.toInt()   // dark slate, faintly blue
    val panelFillBottom   = 0xF20B0C10.toInt()
    val panelStroke       = 0x24FFFFFF
    val panelHairline     = 0x40FFFFFF           // top-edge highlight, fades out at both ends
    val cardFillTop       = 0x14FFFFFF
    val cardFillBottom    = 0x08FFFFFF
    val cardStroke        = 0x1AFFFFFF
    val divider           = 0x14FFFFFF
    val scrim             = 0x99000000

    // ── Text ───────────────────────────────────────────────────
    val textPrimary       = 0xFFF0F2F6.toInt()   // not pure white
    val textSecondary     = 0xFFA8AFBC.toInt()
    val textTertiary      = 0xFF737B8A.toInt()
    val textOnAccent      = 0xFF04161C.toInt()

    // ── Accent (cyan) ──────────────────────────────────────────
    val accent            = 0xFF6FD8EC.toInt()   // strokes / fills only
    val accentText        = 0xFFA9E7F5.toInt()   // desaturated — safe for glyphs
    val focusFill         = 0x2E6FD8EC
    val focusStroke       = 0xE66FD8EC.toInt()
    val focusGlowInner    = 0x666FD8EC
    val focusGlowOuter    = 0x226FD8EC
    val hoverFill         = 0x14FFFFFF
    val hoverStroke       = 0x806FD8EC

    // ── Status ─────────────────────────────────────────────────
    val watched           = 0xFF8FD69F.toInt()
    val warning           = 0xFFE8C56F.toInt()
    val error             = 0xFFF0907F.toInt()
    val progressTrack     = 0x26FFFFFF
}
```

`Theme` keeps `textPaint`/`fillPaint`/`strokePaint` but loses `panelWidthDegrees`, `cornerRadiusPx` and `paddingPx` — those become angular tokens resolved through `PanelMetrics`:

```kotlin
object Space {           // degrees of visual angle
    const val XS = 0.15f; const val S = 0.30f; const val M = 0.45f
    const val L = 0.65f;  const val XL = 0.95f; const val XXL = 1.30f
}
object Radius { const val CHIP = 0.35f; const val CARD = 0.55f; const val PANEL = 1.10f }
```

### 4.3 Type scale

| token | size | weight | colour | use |
|---|---|---|---|---|
| `screenTitle` | 2.30° | medium | `textPrimary` | panel header |
| `sectionLabel` | 1.50° | bold, uppercase, +0.10 letter-spacing | `textTertiary` | settings group headers |
| `rowTitle` | 1.85° | medium | `textPrimary` | list rows |
| `rowSubtitle` | 1.55° | regular | `textSecondary` | second line |
| `meta` | 1.55° | regular | `textTertiary` | trailing durations, detail card values |
| `chip` | 1.50° | medium | `accentText` / `textSecondary` | status pills, HUD control labels |
| `numeral` | 1.60° | medium, `fontFeatureSettings = "tnum"` | `textPrimary` | timeline clocks |

Tabular figures (`"tnum"`) stop the timeline digits from jittering as the position advances — a small thing that reads as polish.

Line heights: title `×1.22`, subtitle `×1.18`. Typefaces: `Typeface.create("sans-serif-medium", NORMAL)` for medium, `sans-serif` for regular. Text that can overflow uses `TextUtils.ellipsize(..., TruncateAt.END)`, replacing `Grid`'s hand-rolled loop.

### 4.4 `Surfaces.kt` — the elevation recipes

```kotlin
object Surfaces {
    /** Full-panel background: gradient + top hairline + outer stroke. */
    fun panel(canvas: Canvas, m: PanelMetrics, theme: Theme)
    /** Elevated card (detail card, dialog, footer). */
    fun card(canvas: Canvas, rect: RectF, m: PanelMetrics, theme: Theme, radiusDeg: Float = Radius.CARD)
    /** Focus treatment: fill + ring + 3-stop manual bloom + left accent bar. */
    fun focus(canvas: Canvas, rect: RectF, m: PanelMetrics, theme: Theme, radiusDeg: Float = Radius.CARD)
    /** Gaze hover: subtle fill + thin accent stroke, no bloom, no bar. */
    fun hover(canvas: Canvas, rect: RectF, m: PanelMetrics, theme: Theme, radiusDeg: Float = Radius.CARD)
    fun divider(canvas: Canvas, x0: Float, x1: Float, y: Float, theme: Theme)
    fun chip(canvas: Canvas, rect: RectF, m: PanelMetrics, theme: Theme, accented: Boolean)
}
```

**Panel** (in order): vertical `LinearGradient(panelFillTop → panelFillBottom)` round-rect fill at `Radius.PANEL`; a second round-rect fill with `LinearGradient(0 → 45% height, #12FFFFFF → #00FFFFFF)` — this is the *sheen*, a specular fall-off that makes the panel read as glass rather than paint; a 1.5 px top hairline inset by the corner radius, painted with a **horizontal** `LinearGradient` that fades to zero at both ends (`0x00FFFFFF → panelHairline → 0x00FFFFFF`) so the highlight looks like a light source rather than a border; finally a 1 px `panelStroke` outline.

**Focus** uses a manual three-stop bloom — round-rect strokes at `r`, `r + 0.10°`, `r + 0.20°` with alphas `0.90 / 0.40 / 0.16` of `accent` — rather than `Paint.setShadowLayer`, which is inconsistent across hardware-canvas drivers and is exactly the kind of thing that renders differently on a `SurfaceTexture`-backed canvas. Plus a 3 px `accent` bar down the left edge of the row: the single most legible focus cue at low effective resolution.

**Hover** is deliberately quieter than focus. When hover and focus coincide (the common case), draw focus only.

### 4.5 `Icons.kt` — real vector icons (fixes F11)

```kotlin
enum class Icon { NONE, FOLDER, VIDEO, PLAY, PAUSE, CHECK, SERVER, PLUS, REFRESH,
                  GEAR, CHEVRON, WARNING, SEARCH, BACKSPACE, ENTER, SPACE, SPINNER }

object Icons {
    /** Draws [icon] centred at (cx, cy), fitted to a [sizePx] box, using [paint]'s colour. */
    fun draw(canvas: Canvas, icon: Icon, cx: Float, cy: Float, sizePx: Float, paint: Paint)
}
```

Paths authored on a 24×24 grid, stroked at 2 units (`STROKE`, `ROUND` cap/join), cached as `Path` objects and drawn through a scale `Matrix` — crisp at any size, no font dependency, and a coherent modern line-icon look. Row icons sit in a leading gutter of `2.4°` so titles align on a common left edge whether or not a row has an icon.

### 4.6 `TextMeasure.kt` — makes layout unit-testable

```kotlin
fun interface TextMeasure {
    fun width(text: String, sizePx: Float, bold: Boolean): Float
    companion object {
        val PAINT: TextMeasure = …            // production: a cached Paint
        fun fixed(perCharPx: Float): TextMeasure = …   // tests: deterministic
    }
}
```

Layout code takes a `TextMeasure`; JVM tests inject `fixed(...)` and assert exact rectangles. Without this, `unitTests.isReturnDefaultValues = true` makes `Paint.measureText` return `0` and every layout assertion becomes vacuous.

### 4.7 `ListView` rewrite (fixes F2, F5; enables §3)

```kotlin
class ListView(private val theme: Theme, private val metrics: PanelMetrics,
               private val measure: TextMeasure = TextMeasure.PAINT) {

    sealed interface Entry {
        data class Item(
            val title: String,
            val subtitle: String? = null,
            val trailing: String? = null,
            val icon: Icon = Icon.NONE,
            val watchedFraction: Float = 0f,
            val finished: Boolean = false,
            val style: Style = Style.DEFAULT,
        ) : Entry
        /** Non-focusable group label (settings sections). */
        data class Header(val label: String) : Entry
    }
    enum class Style { DEFAULT, ACTION, DANGER }

    data class Box(val index: Int, val left: Float, val top: Float, val right: Float, val bottom: Float)
    data class Layout(
        val rowHeightPx: Float,
        val headerHeightPx: Float,
        val visibleCount: Int,
        val boxes: List<Box>,          // absolute indices, in draw order
        val titleBaselineDy: Float,    // from FontMetrics, not guessed
        val subtitleBaselineDy: Float,
    )

    /** PURE. No Canvas, no Paint (text widths come through [measure]). */
    fun measureLayout(entries: List<Entry>, top: Float, height: Float,
                      left: Float, width: Float, scrollTop: Int): Layout

    fun draw(canvas: Canvas, entries: List<Entry>, layout: Layout,
             focusIndex: Int, hoverIndex: Int?, scrollTop: Int)

    /** Rows fitting [height]; the value the reducer must use as its scroll window. */
    fun visibleRowCount(height: Float, twoLine: Boolean): Int
}
```

**Row height, derived not guessed.** With `Space`/type tokens in degrees:

```
one-line  = M(0.45) + 1.85×1.22 + M(0.45)                       = 3.16°
two-line  = M(0.45) + 1.85×1.22 + XS(0.15) + 1.55×1.18 + M(0.45) = 4.98°
```

vs. today's 3.2° for a two-line row. On the browse panel (23.3 px/°) a one-line row is 73.6 px; with a 640 px body that is **8 visible rows**, matching `BROWSE_VISIBLE_ROWS = 8` — but now derived, and reported to the reducer via `Event.ListWindowMeasured` instead of assumed.

**Baselines.** `titleBaselineDy = padTop − fm.ascent`, `subtitleBaselineDy = titleBaselineDy + fm.descent + gap − fmSub.ascent`. `measureLayout` asserts `subtitleBaselineDy + fmSub.descent ≤ rowHeight − padBottom`; `ListViewLayoutTest` asserts it too, for every panel in the table.

**Browse rows become single-line.** The per-row `"1920×1080"` subtitle is redundant with the detail card, costs 1.8° per row, and is the reason only five rows would fit. Browse rows: icon + title + right-aligned duration + a 3 px watched-progress underline. The detail card carries resolution/size/type for the focused row. This is both prettier and the reason the 8-row window survives.

**Scrollbar** moves inside the body (`right − 0.30°`), 3 px wide, `Radius.CHIP` rounded, `accent` at 60%, with a minimum thumb length of 1.2°.

---

## 5. Screen-by-screen specification

### 5.1 `ScreenPanel` (shared)

- add `metrics: PanelMetrics`, `hitMap`, `modelYawRad`, `gazeGeometry()`;
- `panelBackground(...)` extension is replaced by `Surfaces.panel(...)`;
- add a `header(canvas, title, status: Chip?)` helper: title at `Space.XL` from the left/top, optional right-aligned status chip, a `Surfaces.divider` under it, returning the content top. Every screen uses it, so headers stop drifting;
- `drawGl` uses `modelYawRad` (§1.6).

### 5.2 `ServerListScreen` — kills the reported collision structurally

Layout: header `"Media servers"` + a status chip (`"Searching…"` accent-filled while `DiscoveryState.RUNNING`, `"3 found"` otherwise, right-aligned via `Paint.Align.RIGHT` — fixes F9). Then a **scrolling list of servers only** (two-line: name / `manufacturer · host`, `Icon.SERVER`), then a **pinned footer** holding the two actions side by side as `Surfaces.card` buttons: `＋ Add manually` and `⟳ Retry discovery`.

Moving the actions into a footer means the `"Enter host:port"` subtitle no longer exists and *cannot* collide with anything — the fix is structural, not a padding tweak. The focus model is untouched: footer buttons keep absolute indices `servers.size` and `servers.size + 1`, `serverRowCount` is unchanged, and `Down` from the last server enters the footer.

Add an empty state when `servers.isEmpty() && discovery != RUNNING`: a centred `Icon.SEARCH`, `"No servers found"`, and `"Check the phone is on the same Wi-Fi as your server."` in `meta`. Scrolling honours `state.serverScrollTop` (fixes F4).

Hit regions: one per server row (`ServerRow(i)`), one per footer button.

### 5.3 `BrowseScreen`

- breadcrumb with middle truncation past three segments (`Gerbera › … › Films`), `Icon.CHEVRON` separators, current segment in `textPrimary` medium, ancestors in `textSecondary`; divider below;
- left column (62%): single-line rows per §4.7;
- right column: a real `Surfaces.card` detail panel — title, then **two measured columns** for label/value (fixes F12), never space-padding;
- `"Loading…"` as a header chip, right-aligned (fixes F9);
- errors as a bottom bar: `Icon.WARNING` + message in `theme.error` on a `0x1AF0907F` strip;
- grid mode: `scrollRow` derived as `scrollTop / columns` **and** `focusIndex` mapped through the same divisor, so they agree (fixes F13).

Hit regions: `BrowseRow(scrollTop + i)` per drawn row.

### 5.4 `SettingsScreen`

- rows become **single-line two-column**: label left, value right-aligned in `meta` — the standard settings idiom, half the height of a stacked row, and 8 rows fit;
- grouped with `Entry.Header` section labels: `VIEWER`, `OPTICS`, `TRACKING`, `DATA`. Headers are not focusable; the reducer keeps working on focusable indices only;
- **scrolling** driven by `state.settingsScrollTop`, updated in `reduceSettings` via `clampScroll(focusIndex, scrollTop, ROWS.size, window = listWindow.settings)` (fixes F3);
- adjustable rows show `‹ value ›` chevrons in `accentText` so it is obvious that left/right changes them; toggles render as a pill (`On` accent-filled / `Off` outline);
- `Forget servers` uses `Style.DANGER` (`theme.warning` label).

Hit regions: `SettingsRow(absoluteIndex)`.

### 5.5 `PlayerHud`

- panel narrows to 2.4 m × 0.62 m (55° × 14.2°) so it sits inside the comfort box;
- line 1: `Icon.PLAY`/`PAUSE`/`SPINNER` + title (ellipsized) + right-aligned speed chip when `speed != 1.0`;
- line 2: `Timeline` — buffered track, played fill in `accent`, a 0.35° knob at the play head, `numeral` clocks left (elapsed) and right (`−remaining`), scrub preview marker in `accentText` with the preview time above it;
- line 3: five control chips (`Audio`, `Subtitles`, `Speed`, `Projection`, `Screen size`), each a `Surfaces.chip` with the label in `chip` and the value below in `meta`; focus uses `Surfaces.focus`, gaze hover `Surfaces.hover`;
- buffering shows an `Icon.SPINNER` arc in the header (static — no animation on Canvas; the state itself changes often enough to repaint).

Hit regions: `HudControl(i)` per chip. (Gaze scrubbing on the timeline is backlog, §8.)

### 5.6 `OverlayRenderer`

- full-panel `scrim` behind the dialog so the screen underneath recedes;
- dialog is a `Surfaces.card` with an `Icon.WARNING` for errors, title in `screenTitle`, body in `rowSubtitle`, buttons as pills ≥ 2.6° tall with focus/hover treatments;
- toasts: a pill (`Radius.CHIP`) with `Surfaces.card` styling at 82% height, ellipsized to 80% panel width.

Hit regions: `DialogButton(i)`; for the keyboard, `KeyboardKey(r, c)`.

### 5.7 `VrKeyboard`

- keys are `Surfaces.chip` tiles with `Space.XS` gaps; cursor uses `Surfaces.focus`, gaze uses `Surfaces.hover`;
- labels centred with `Paint.Align.CENTER` and a `FontMetrics`-derived baseline (fixes F10);
- `⌫ ↵ ␣` replaced by `Icon.BACKSPACE`, `Icon.ENTER`, `Icon.SPACE`;
- the entry field gets a card, a caret, and the suggestion strip under it.

---

## 6. Task checklist for the implementing agent

Work top to bottom. **Every step ends with `./gradlew check` green** and a runnable app; do not batch steps into one commit. Steps 1–4 are the "menu audit"; 5–7 are the gaze pointer.

### Step 0 — pin the bugs with failing tests

- [ ] `vrcore …/ui/ListViewLayoutTest.kt`: assert a two-line row's subtitle bottom ≤ row bottom, using the **current** 3.2°/1.8°/1.3° numbers. **Must fail.**
- [ ] `vrcore …/ui/PanelYawConventionTest.kt`: assert invariant P1 (§1.6) by rebuilding the model matrix in pure Kotlin. **Must fail.**
- [ ] `app …/state/ScrollWindowTest.kt`: assert `settingsScrollTop` keeps focus row 13 of 14 inside an 8-row window. **Must fail** (no such field yet — write it against the API you are about to add).
- [ ] Commit as `test: pin UI layout, yaw convention and scroll defects (red)`.

### Step 1 — geometry and metrics

- [ ] `PanelMetrics.kt` + `PanelMetricsTest` (curved arc, `px`/`deg` round-trip, degenerate inputs).
- [ ] Delete `Theme.panelWidthDegrees`; add `PanelMetrics` to `ScreenPanel`, built from `panelWidthM` and `anchor.distanceM`.
- [ ] Retexture every panel per the §2.2 table; add `PanelAspectTest` asserting `widthPx/widthM ≈ heightPx/heightM` within 1% for all seven (fixes F8).
- [ ] `require(curveRadiusM == distanceM)` in `ScreenPanel` (fixes F7); make `PanelAnchor.distanceM` a `val` or have `ScreenPanel` rebuild the quad when it changes.
- [ ] Add `ScreenPanel.modelYawRad` and use it in `drawGl`; apply the same negation in `CylinderScreen` and `SphereScreen` (fixes F6). Step 0's `PanelYawConventionTest` goes green.
- [ ] Update every `AngularMetrics.textSizePx(...)` call site to `metrics.px(...)`.

### Step 2 — design system

- [ ] `Theme.kt` rewrite with §4.2 tokens; `Space`, `Radius`, and a `Type` token object.
- [ ] `TextMeasure.kt` (+ `PAINT` and `fixed` implementations).
- [ ] `Surfaces.kt`: `panel`, `card`, `focus`, `hover`, `divider`, `chip`.
- [ ] `Icons.kt` with the 17 icons, path-cached.
- [ ] `TypeScaleTest`: every type token on every panel in the §2.2 table is ≥ `AngularMetrics.MIN_TEXT_DEGREES`.

### Step 3 — widgets

- [ ] `ListView` rewrite per §4.7: `Entry`, `Style`, `measureLayout` (pure), `draw`, `visibleRowCount`. `ListViewLayoutTest` goes green and gains: no two boxes overlap; boxes stay inside the body; `visibleCount × rowHeight ≤ height`; header rows are excluded from focusable indices.
- [ ] `HitRegion.kt` + `HitRegionTest` (centre hits, gap misses, half-open boundaries, last-wins ordering).
- [ ] `ProgressBar`, `Timeline` (tabular numerals, knob, preview marker), `Breadcrumb` (middle truncation), `Grid` (`TextUtils.ellipsize`, consistent scroll/focus divisor — fixes F13).

### Step 4 — screens, scrolling, hit maps

- [ ] `ScreenPanel`: `hitMap`, `gazeGeometry()`, shared `header(...)`, `Surfaces.panel`.
- [ ] `ServerListScreen` per §5.2 (scrolling list + pinned action footer + empty state), publishing `ServerRow` regions.
- [ ] `BrowseScreen` per §5.3, publishing `BrowseRow` regions.
- [ ] `SettingsScreen` per §5.4 (two-column rows, sections, scrolling), publishing `SettingsRow` regions.
- [ ] `PlayerHud` per §5.5, publishing `HudControl` regions.
- [ ] `OverlayRenderer` + `VrKeyboard` per §5.6–5.7, publishing `DialogButton` / `KeyboardKey` regions.
- [ ] `AppState`: add `settingsScrollTop`, `serverScrollTop`, `listWindow`; `Event.ListWindowMeasured`; `AppScene` dispatches it once at GL-create from the real `measureLayout` results (fixes F5). `reduceSettings`/`reduceServerList` maintain their scroll tops through `clampScroll` (fixes F3, F4). Step 0's `ScrollWindowTest` goes green.
- [ ] Right-align every status string with `Paint.Align.RIGHT` (fixes F9).

### Step 5 — gaze math (pure, no GL)

- [ ] `GazeRay.kt` + `GazeRayTest`: identity pose → `(0,0,−1)`; `R_y(θ)` pose → `(−sin θ, 0, −cos θ)`; `|D| = 1`; neck offset rotates with the head; `azimuthRad` matches `VrActivity`'s existing formula.
- [ ] `PanelRaycast.kt` (curved + flat) + `PanelRaycastTest` using the §1.5 table **including the exact worked vector** `(872.7, 516.7)`; plus `u`/`v` monotonicity in `ψ`/`p`, the `a`-shift property, `wrapPi` at the ±180° seam, misses behind the viewer, and the `k < 1e-4` guard.
- [ ] `GazeStabilizer.kt` + `GazeStabilizerTest`: a 1-frame blip does not switch; a target held past `holdSeconds` does; `null` is a legitimate target; `reset()` clears.

### Step 6 — the reticle

- [ ] `Reticle.kt`: unit quad mesh, procedural ring shader (§2.6), premultiplied blend, depth test off, drawn last.
- [ ] `ReticleTest` on the pure parts: `radiusMForDistance` (constant angular size across depth); `billboardModel` produces orthonormal columns, `−Z` toward the camera, scale `= radius`, translation `= p`; degenerate straight-up case does not produce NaN.
- [ ] `Scene.update(dtSeconds)` → `update(dtSeconds, pose: FloatArray)`; `VrRenderer` passes the pose it already fetched (keep the current call order — `update` runs before the `width == 0` early return).
- [ ] `AppScene`: `poseProvider` no longer needed separately; add `neckOffsetProvider: () -> FloatArray?`; implement the §3.1 pipeline; draw the reticle after all panels in both eyes from the *same* cached hit.

### Step 7 — hover and select

- [ ] `GazeTarget.kt`; `Event.GazeMoved`; `AppState.gaze`.
- [ ] `reduceGaze` per §3.3 + `GazeReducerTest`: hover writes the right focus index per screen; hover never changes `scrollTop`; targets for the screen behind an overlay are ignored; `HudControl` hover refreshes `lastInputAtMs`; `Confirm` after a hover activates the hovered row; D-pad after a hover still moves focus.
- [ ] `AppScene` constructor gains `onGazeTarget: (GazeTarget?) -> Unit`; `VrActivity` wires it through `runOnUiThread`.
- [ ] Every screen passes `hoverIndex` from `state.gaze` into its widgets and adds `state.gaze` to its `renderIfChanged` key.
- [ ] Reticle visibility rules per §2.6.

### Step 8 — verification

- [ ] `./gradlew check` green; ktlint clean per `.editorconfig`.
- [ ] Update ARCHITECTURE.md §11.3 (per-panel angular metrics replacing the global constant) and §11.4 (gaze pointer), and add a §11.7 "Gaze interaction" pointing at this document.
- [ ] Device pass, §7.

---

## 7. On-device verification checklist (Pixel 11 Pro + Daydream View + BT pad)

Mark anything not verifiable as `UNVERIFIED-ON-DEVICE` in the commit message rather than declaring it passed.

1. **Collision gone.** Server list with 0, 1, 3 and 7 servers: no text touches any other text; the action footer is always visible.
2. **Settings reachable.** D-pad from `Viewer profile` to `Forget servers`: focus stays on screen the whole way, the list scrolls, the scrollbar tracks.
3. **Yaw.** Recentre while facing 30° right — the panel lands dead ahead, not 60° away. Turn 40°: the panel eases *toward* you.
4. **Legibility.** Every string is readable through the lenses without leaning in.
5. **Reticle.** Present on every UI screen, absent over video with the HUD hidden; no double image; the same apparent size on the browse panel and the cinema screen.
6. **Gaze select.** Look at a row → it highlights within ~60 ms → press A → it opens. Look at a HUD chip → the HUD stays alive while you look at it.
7. **No fight.** D-pad to a row, hold still: focus stays put. Look elsewhere: focus follows the eyes.
8. **Frame budget.** Debug overlay: redraw counters do not climb while the head is still; frame time unchanged from `cc4e5a5` within noise.
9. **Thermals.** 10-minute playback session with the HUD toggling: no new throttling steps versus the previous build.

---

## 8. Backlog (explicitly out of scope)

- Gaze scrubbing on the HUD timeline (hover the track, A to seek to that point).
- Gaze auto-scroll when the reticle rests near the top/bottom edge of a long list.
- Dwell-select (no controller): the reticle shader already has the ring geometry for a progress arc.
- Laser-pointer mode driven by a 3DOF controller pose, if a Daydream controller is ever supported — `PanelRaycast` takes an arbitrary `Ray`, so only the ray source changes.
- Mip-mapping and anisotropic filtering for panel textures (ARCHITECTURE.md §11.2 asks for it; `PanelSurface` does not do it yet, and it is a visible sharpness win at 2.5 m).
- Per-panel `Theme` variants (the HUD could stand to be darker than the browse panel).

---

## 9. Risks

| risk | mitigation |
|---|---|
| The yaw negation (§1.6) is a two-line change with wide blast radius — panels, cinema screen, recentre. | `PanelYawConventionTest` encodes the invariant, not the sign. Verify on device (checklist 3) before building gaze on top. |
| Layout tests are vacuous under `isReturnDefaultValues = true`. | All layout math is pure and takes an injected `TextMeasure`; no `Paint` call in any assertion path. |
| Hover repaints could break R4 (no per-frame Canvas work). | Edge-triggered dispatch + 60 ms stabilizer bounds repaints to a few per second. Watch the debug overlay's redraw counters (checklist 8). |
| Single-line browse rows lose per-row resolution info. | It is already in the detail card for the focused row, and the trailing column keeps duration. Revisit if the device pass says otherwise. |
| Retexturing panels raises VRAM and canvas cost. | Net texel count is roughly flat (Browse 1280×768 → 1280×800, HUD 1280×384 → 1280×332). |
