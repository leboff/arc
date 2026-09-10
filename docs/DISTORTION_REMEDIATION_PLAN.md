# Distortion remediation plan

Status: implementation specification; no implementation performed. Prepared against the live checkout on 2026-09-09.

## 1. Scope, evidence, and release objective

Replace the inconsistent projection/warp geometry with one physical, eye-centered tangent model; separate fixed viewer geometry from observer IPD; migrate settings without reinterpreting old calibration; and prove the result independently of the existing round-trip tests.

Evidence limitation: the completed distortion audit referenced by the request was not present in the available conversation or discoverable under this checkout's documentation, memory, or available temporary artifacts. This document reconstructs findings from live source; it does not claim to reproduce unavailable audit measurements. No through-lens measurement or authoritative Daydream parameter set has been supplied. Numerical defaults below are explicit provisional choices, not newly measured coefficients.

Confirmed code findings:

- `vrcore/.../distortion/DistortionMesh.kt` applies `undistort()` to radius in eye clip coordinates, assumes a 0.1406 m display width, clamps the horizontal center, and forces the vertical center to zero. Physical screen height, screen-to-lens distance, actual viewport boundaries, and render frustum do not participate in UV construction.
- `vrcore/.../render/StereoLayout.kt` computes physical half-angles without the radial map and ignores divider/cutout cropping in those angles. Both eyes share a mirrored FOV even when pixel geometry differs.
- `app/.../screens/CalibrationScreen.kt:overrideProfile` copies observer IPD into `interLensDistanceM`. Both profile selectors reset IPD when changing viewer.
- `DistortionRenderer.updateMeshes` keys only profile, grid size, and viewports. It cannot invalidate on independently changed physical display geometry or source tangent bounds.
- `VrActivity` derives display metres from resource metrics once, with clamping that can disguise bad DPI and without an explicit physical-panel-to-surface transform.
- `SettingsStore` stores one unversioned active optics tuple, despite documentation promising per-profile overrides. Invalid JSON silently becomes defaults.
- `DistortionMeshTest` proves inverse consistency and some ordering, not physical correctness. `CalibrationScreenTest` explicitly enshrines IPD/lens-separation coupling. The documented instrumented distortion golden test does not exist: neither relevant module currently has an `androidTest` source tree.
- `EyeFramebuffer.resolve()` binds framebuffer targets and performs an MSAA blit while scissor testing remains enabled. The current full-surface scissor can crop supersampled blits on small/narrow targets; resolve must establish its own state. This is a correctness dependency, not an optics coefficient change.

Release objective: a mathematically specified compositor on every supported surface, with calibrated hardware claims restricted to combinations that pass section 8. A passing mathematical model alone cannot certify unknown lenses.

## 2. Coordinate contract and direction

### 2.1 Physical display and surface

Use metres for distances, dimensionless tangent quantities for radial inputs, radians internally for angle conversion, and degrees only for profile/UI half-angle fields. Calculate geometry in `Double`; convert to `Float` at the matrix/VBO boundary.

Define the active illuminated landscape panel as `[0,W] × [0,H]`, origin at its bottom-left, +X right, +Y up. Bezels are outside this rectangle. The renderer's GL surface has `Nx × Ny` pixels, bottom-left origin. Map continuous pixel **edges**, not pixel indices, into this panel with an explicit affine transform. For a full-panel surface:

```
physicalX(px,py) = W * px / Nx
physicalY(px,py) = H * py / Ny
```

For an axis-aligned sub-surface whose physical bounds are `(sx,sy,sw,sh)`, use `sx + sw*px/Nx`, `sy + sh*py/Ny`. Normalize display rotation before constructing this transform; support the two landscape orientations with the appropriate axis reversal and physical lens orientation. Never independently swap pixel dimensions while leaving DPI axes or lens centers unchanged. A pixel sample is `(i+0.5,j+0.5)`; mesh boundaries use integer edges. Texture V and physical Y both increase upward; Canvas/video orientation conversions stay upstream.

Define centered fixed lenses with separation `L` and measured horizontal mounting displacement `ox`:

```
cLeft  = (W/2 + ox - L/2, cy)
cRight = (W/2 + ox + L/2, cy)
```

`cy` is the optical center measured above the **active panel bottom**. Do not equate tray height with this coordinate unless the tray-to-active-panel offset is known. For bottom alignment, `cy = trayToLensM - trayToActiveBottomM`; for center alignment, `cy = H/2 + centerOffsetYM`. Do not silently subtract an assumed bezel size. Convert physical coordinates into canonical landscape orientation before applying these definitions.

Split display pixels deterministically. For total divider `D`, set `dividerLeft=floor((Nx-D)/2)`, `dividerRight=dividerLeft+D`. Left viewport runs `[leftInset,dividerLeft)`, right runs `[dividerRight,Nx-rightInset)`, with explicit bottom/top insets. Thus odd widths/dividers preserve the exact requested divider; a one-pixel eye-size difference is valid. Insets crop visibility; they do not rescale the physical panel or move lenses. Reject empty viewports or lens centers outside the usable eye rectangles. Clear the entire backbuffer black every frame.

### 2.2 Screen tangent to source ray: the actual lookup

Let `d > 0` be the profile's effective screen-to-lens distance. For physical display point `p` and eye center `c`:

```
s = (p - c) / d
r2 = sx*sx + sy*sy
factor = 1 + k1*r2 + k2*r2*r2
t = s * factor
```

`t=(rayX/-rayZ, rayY/-rayZ)` is the intended source-camera ray tangent. The radial polynomial is explicitly **SCREEN_TANGENT_TO_RAY_TANGENT_V1**. Radius is never NDC, UV distance, pixels, metres, or degrees. Define `D(s)=t`; `D` has unit slope at the center. For nonnegative coefficients, a positive off-axis screen radius maps to a larger source ray radius.

For a regular **destination-screen mesh**, vertex positions remain a regular grid in that eye viewport's NDC. Compute `p`, then `s`, then **forward** `D(s)`, then source UV. Do not call the inverse here. Conversely, locating the screen position of a known source ray requires `D^-1(t)`. A regular source-UV mesh could use inverse distortion in its positions, but that is a different mesh construction and is not this migration's chosen implementation.

This direction agrees with Google's screen-UV-to-render-texture conversion. Google's own mesh uses inverse distortion because it instead fixes the source UV grid and moves destination vertices. See [Cardboard lens conversion](https://github.com/googlevr/cardboard/blob/master/sdk/lens_distortion.cc) and [Cardboard mesh construction](https://github.com/googlevr/cardboard/blob/master/sdk/distortion_mesh.cc), inspected 2026-09-09. These references establish conventions, not Daydream coefficient provenance. Pin the reference revision when adding reproducible external fixtures; never make tests download `master`.

### 2.3 Source tangent bounds and projection

Each eye owns signed `TangentBounds(left,right,bottom,top)` with `left<0<right`, `bottom<0<top`. Avoid temporal/nasal names in runtime matrix code. For an axis-aligned physical eye viewport `[x0,x1] × [y0,y1]` containing its lens center, compute positive screen-axis distances:

```
aLeft=(cx-x0)/d; aRight=(x1-cx)/d
aBottom=(cy-y0)/d; aTop=(y1-cy)/d
F(a)=a*(1+k1*a*a+k2*a*a*a*a)
left   = -min(F(aLeft),   tan(maxLeftRadians))
right  =  min(F(aRight),  tan(maxRightRadians))
bottom = -min(F(aBottom), tan(maxBottomRadians))
top    =  min(F(aTop),    tan(maxTopRadians))
```

For the left eye, profile outer/inner limits become maxLeft/maxRight; for the right eye they become maxRight/maxLeft. Vertical bounds need no eye mirroring. These axis-based limits deliberately define a conservative rectangular source frustum, not coverage of every physical corner. Radial coupling means some corners map outside it and must be black; never stretch or clamp UVs to make corners fit. FOV caps shrink the visible footprint rather than changing the polynomial. Future overscan must be separately specified, not added as an arbitrary multiplier.

Projection uses `l=near*left`, `r=near*right`, `b=near*bottom`, `u=near*top` and the existing right-handed, -Z-forward perspective matrix formula. In column-major storage:

```
m0=2*near/(r-l); m5=2*near/(u-b)
m8=(r+l)/(r-l); m9=(u+b)/(u-b)
m10=-(far+near)/(far-near); m11=-1
m14=-2*far*near/(far-near); all other elements=0
```

Sampling the very same frustum:

```
uv.x = (t.x-left)/(right-left)
uv.y = (t.y-bottom)/(top-bottom)
```

At the optical center, UV is `(-left/(right-left),-bottom/(top-bottom))`, generally not `(0.5,0.5)`. This contract removes all duplicated lens-center fractions and the renderer's second right-eye FOV swap.

For the diagnostic distortion-off path, use the same physical viewports and centers but identity `D(s)=s` when constructing both its direct projection and geometry. A zero-coefficient distorted pass must match this direct pass. Do not use a distortion-expanded projection for direct rendering; the on/off difference should be lens compensation, not an unrelated projection zoom.

### 2.4 Numerics, invalid inputs, and chromatic correction

For this release accept only finite `k1,k2` in `[0,1]`, effective distance in `[0.030,0.060]` m, positive finite dimensions, and half-angle limits strictly in `(0,89)` degrees. These are software validation limits, not measured lens capabilities. With this polynomial, radial derivative `1+3*k1*r²+5*k2*r⁴ >= 1` and tangential eigenvalue `factor>=1`, so the continuous map has no folds. Reject overflow/nonfinite results and degenerate bounds. Future negative or higher-order coefficient support requires a separate domain/monotonicity specification.

Forward mapping needs no iteration. Provide an inverse only for independent placement/calibration helpers: for target radius `R>=0`, bracket `[0,R]`, bisect 64 times in Double (identity special case permitted), return midpoint with target residual at most `1e-9*max(1,R)` over the validated display domain. Return the zero vector exactly at zero; invalid inputs return a typed error, never an invented radius.

Keep all RGB UVs equal and chromatic correction disabled for provisional profiles. Existing `chromaticScale` has no calibrated convention and must not acquire meaning by accident. Retain the eight-float vertex layout during migration if useful; quality settings may enable chromatic only when the optical profile explicitly has a validated channel model. Do not fit RGB coefficients by eye or invent channel scales. Use highp UV interpolation and fragment arithmetic. Preserve outside-[0,1] black masking and clamp-to-edge texture filtering; define mask against unclamped UV. No extra arbitrary vignette coefficient is required in this release.

## 3. Shared data and ownership

Add pure Kotlin types under `vrcore/src/main/java/com/daydreamvr/vrcore/optics/`:

```
RadialCoefficients(k1: Double, k2: Double)
RadialConvention { SCREEN_TANGENT_TO_RAY_TANGENT_V1 }
ParameterConfidence { VERIFIED, PROVISIONAL, USER_CALIBRATED }
DisplayGeometry(panelWidthM, panelHeightM, surfaceWidthPx, surfaceHeightPx,
                surfaceToPanel: Affine2D, usableInsets: PixelInsets,
                measurementSource, measurementRevision)
ViewerOptics(profileId, profileRevision, lensSeparationM, horizontalOffsetM,
             verticalAlignment, verticalOffsetM, screenToLensM,
             coefficients, convention, maxFov, confidence)
ObserverGeometry(ipdM)
TangentBounds(left, right, bottom, top)
EyeOptics(eye, viewport, lensCenterPanelM, screenToLensM,
          coefficients, sourceBounds)
StereoOptics(left, right, geometryKey)
RenderConfiguration(display, viewer, observer, dividerPx,
                    distortionEnabled, neckModel, revision)
```

Use immutable scalar data classes and named vector/affine/inset types. Do not put mutable arrays or GL objects in equality/cache keys. `verticalAlignment` has CENTER and BOTTOM; BOTTOM requires a measured tray-to-active-bottom offset. Profile legacy tray numbers can remain metadata, not silently active measurements. Viewer limits remain in temporal/nasal profile format only at the conversion boundary.

`OpticsGeometry.compute(configuration)` produces both eye optical geometries. `RadialDistortion.screenToRay` and `rayToScreen` are pure. `StereoLayout` owns pixel splitting/view/projection or delegates splitting to this builder; it must not recompute independent optics. `EyeParams` should contain `optics: EyeOptics` plus `eyeOffsetX`, with temporary derived `eye`/`viewport`/`fov` accessors to preserve callers. Projection ultimately accepts `TangentBounds` directly.

Pass one immutable configuration snapshot to the GL thread using `GLSurfaceView.queueEvent`, or a single volatile reference read once at frame start. Do not independently publish IPD, profile, dimensions, and toggles. Pose and quality remain independent, but quality cannot alter optical coordinates. Cache by the complete optical inputs (physical transform, pixel viewport, centers, distance, coefficients/convention/revision, bounds, tessellation); exclude observer IPD, pose, neck model, and render scale. Quality changes may require FBO allocation or tessellation reassessment, not a new physical model.

Generate replacement CPU meshes and upload both successfully before replacing/releasing the active pair. Keep the old coherent pair on allocation/build failure, with a diagnostic; if no valid pair exists, render black rather than inconsistent eyes. Context recreation invalidates all GL names and cache state. Cache only successfully installed geometry.

## 4. Fixed lenses, observer IPD, and provisional defaults

IPD controls virtual eye translations `left=-IPD/2`, `right=+IPD/2`; it must not move physical lens centers, alter distortion, or alter the optical frustum. Default observer IPD is **64 mm**, independently of selected viewer; keep the current user adjustment interval 52–74 mm and 0.5 mm step. Preserve it across profile changes. No automatic toe-in or off-axis eye-position lens correction is included. A large real pupil/lens mismatch is outside this central-eye radial model; software cannot move fixed lenses.

Expose fixed lens separation as viewer metadata. Permit a separately labeled advanced measured geometry override, never the IPD control. Existing IPD values cannot establish lens separation. Calibration UI should distinguish observer stereo scale from physical alignment; a doubled finite-distance target alone cannot diagnose lens separation.

Use the following explicit **provisional development defaults**, pending measured or authoritative profiles:

| Viewer | Fixed L | Effective d | Vertical center | k1 | k2 | Half-angle caps (outer,inner,up,down) |
|---|---:|---:|---|---:|---:|---|
| Daydream View 2016 | 0.064 m | 0.039 m | active-panel center | 0.34 | 0.55 | 40°,40°,40°,40° |
| Daydream View 2017 | 0.064 m | 0.040 m | active-panel center | 0.36 | 0.42 | 40°,40°,40°,40° |

These preserve the repository's polynomial and separation/distance candidates while reducing its 50–55° caps and avoiding an unsupported tray/bezel assumption. The 40° cap is a conservative engineering rollout choice, not a verified Daydream specification or guarantee of comfort. Set mounting offsets to zero, chromatic off, divider to 8 pixels, and observer IPD to 64 mm. Do not replace the 2017 polynomial with the 2016/Cardboard polynomial on speculation. Mark all currently unproven built-ins PROVISIONAL; other built-ins retain their existing scalar coefficients/distances and explicit provisional center alignment, with a 40° maximum per side until validated. Do not rename these as official profiles.

Display geometry is phone-specific, not viewer-specific. Prefer measured active-panel dimensions and a verified surface mapping. A finite, plausible Android DPI estimate may be offered as an explicitly unverified estimate. Validate landscape W in `[0.08,0.20]` m and H in `[0.03,0.12]` m, usable centers inside viewports, and both DPI axes finite/positive; do not clamp invalid metadata into apparent validity. If estimation is invalid, require display calibration before enabling immersive output (show a normal 2D setup explanation). Do not silently restore the mesh's 0.1406 m constant or renderer's 0.140 × 0.065 m defaults. A measured panel override follows a display identity/mode and orientation mapping, never a viewer selection.

Unknowns blocking a calibrated Daydream release: actual per-generation lens coefficients and convention, effective distance, mounting offsets/alignment, phone bezel/tray seating, panel dimensions and DPI reliability, lens tolerances/eye relief, pupil-position dependence, per-channel aberration, and usable FOV. Record provenance and uncertainty when resolving each. Do not derive physical lens data from a marketed total FOV or a screenshot alone.

## 5. Persisted settings migration and versioning

Implement a pure JSON migration before applying settings, then persist its result atomically using the existing DataStore. Keep `settings.blob` as the active key; add a one-time `settings.blob.legacy.v1` backup key containing the exact original string. Do not touch `resume.positions`. Use separate `schemaVersion=2`, `opticsModelVersion=1`, and per-profile `profileRevision=1`; these represent storage, equation convention, and parameter revisions respectively.

V2 contains global observer IPD and non-optics settings; selected profile ID; `viewerOverrides: Map<String, ViewerOverride>`; `displayCalibrations: Map<String, DisplayCalibration>`; migration status; and a legacy-calibration record. Each override records profile revision, convention, explicit optional measured fields, and confidence. Store full effective baseline values or an explicit baseline revision alongside overrides so future table edits cannot silently change existing calibrated users. Profile switching loads that profile's override or baseline and never resets global IPD. Factory reset of one viewer clears only its override after the normal explicit reset action.

Migration rules:

1. Parse the JSON envelope before choosing a decoder. Absent `schemaVersion` means legacy V1. A recognized V2 is validated without remigration. Versions greater than 2 are preserved unchanged and opened in a recovery/read-only-settings state; do not decode-and-save them through `ignoreUnknownKeys`.
2. Backup legacy bytes and write V2 in one `store.edit` transaction before publishing migrated state. Install a migration barrier before ordinary saves so setup/VR cannot overwrite migration with startup defaults. Preserve absent-vs-explicit fields by examining the original JSON object, not just default-filled legacy DTOs.
3. Preserve finite IPD within 52–74 mm as observer IPD; otherwise use 64 mm and record the reason. Preserve valid selected profile ID, divider within 0–40 pixels, distortion toggle, and independently valid non-optics fields. Unknown profile ID selects the provisional default but archives the original ID and tuple. Preserve existing non-optics validation ranges; never overwrite the resume key.
4. Archive legacy `lensK1`, `lensK2`, `screenToLensMm`, `ipdMm`, and profile ID together as `LEGACY_CLIP_INVERSE`. Do not load old coefficients or screen distance into the new optical override. There is no exact scalar conversion from the old aspect-dependent NDC inverse into the new physical forward polynomial. Legacy IPD cannot recover fixed lens separation. Initialize viewer optics from the explicit provisional baseline and mark `needsOpticsRecalibration=true`, even when legacy numbers equal table values.
5. Preserve the user's distortion on/off preference, but honor display validation before enabling immersive rendering. Display a one-time notice that the optical model changed and old calibration has been archived. Do not force-enable compensation previously disabled by the user.
6. On malformed JSON, retain raw bytes in the backup/recovery record, use validated defaults in memory, and report recovery state. Persist a replacement only through the explicit recovery/save path; do not repeatedly overwrite the backup. Missing settings are a fresh V2 install, not a corruption case.
7. V2 invalid override fields are rejected individually with a reason and baseline fallback; reject the full optical tuple if the combined geometry is invalid. Unknown future model conventions/revisions remain archived and inactive until explicitly supported. Ensure saves preserve inactive per-profile data.

Put serialization/migration in `SettingsMigration.kt` and `SettingsStore.kt`; keep storage DTOs out of vrcore. Update both setup and in-VR profile selection to use one resolver. Avoid maintaining two reset/migration implementations. Treat migration failure as a settings recovery condition rather than swallowing it with `getOrDefault(Settings())` in `VrActivity`.

## 6. Exact likely file changes

Paths below are future implementation work, not authorization to change them during this planning task. `vrcore/...` means `vrcore/src/main/java/com/daydreamvr/vrcore/`; `app/...` means `app/src/main/java/com/daydreamvr/player/`.

| File | Required change |
|---|---|
| NEW `vrcore/.../optics/OpticsTypes.kt` | Immutable physical/tangent types, convention, confidence, errors, validation. |
| NEW `vrcore/.../optics/RadialDistortion.kt` | Forward polynomial, bounded inverse for helpers; no Android/GL imports. |
| NEW `vrcore/.../optics/OpticsGeometry.kt` | Single pixel-to-physical-to-tangent builder, per-eye bounds, typed cache key. |
| `vrcore/.../profile/DeviceProfile.kt` | Explicit revision/convention/alignment/confidence; scalar immutable coefficients; compatibility adapter while migrating. |
| `vrcore/.../profile/DeviceProfiles.kt` | Named provisional baselines from section 4 and provenance metadata; no unsupported chromatic model. |
| `vrcore/.../render/Eye.kt` | Carry shared EyeOptics; temporary derived legacy accessors. |
| `vrcore/.../render/StereoLayout.kt` | Exact viewport partition and tangent projection; retain view matrix behavior in this scope. |
| `vrcore/.../render/VrRenderer.kt` | Atomic snapshot, shared direct/distorted geometry, remove `projectionFov` swap, coherent cache lifecycle and invalid-geometry behavior. |
| `vrcore/.../distortion/DistortionMesh.kt` | Regular destination grid with forward physical-to-source UV; remove reference width, center clamp, clip-radius inverse. Keep CPU vertices separate from GL build. |
| `vrcore/.../distortion/DistortionRenderer.kt` | Typed geometry key, successful pair replacement, explicit viewport/scissor per eye, context invalidation. |
| `vrcore/.../distortion/shaders/DistortionShaders.kt` | highp UV varyings/math, explicit mask behavior, provisional RGB identity. |
| `vrcore/.../render/EyeFramebuffer.kt` | Establish/restore resolve scissor state, validate framebuffer completeness and allocation errors; no optical rescaling. |
| NEW `app/.../data/SettingsMigration.kt` | Pure V1/V2 parser, validator, migration result and archived tuple. |
| NEW `app/.../data/OpticsSettingsResolver.kt` | One settings-to-optics/config resolver used by both activities; no dependency on calibration UI. |
| NEW `app/.../render/DisplayGeometryProvider.kt` | Android metrics, rotation, surface bounds/insets and measured override mapping; pure injectable conversion helper. |
| `app/.../data/SettingsStore.kt` | V2 DTOs, atomic backup/migration, serialization of maps, recovery and save barrier. |
| `app/.../state/AppState.kt` | Observer/optics separation, profile override maps, calibration status and rows. |
| `app/.../state/AppStateMachine.kt` | Profile switch preserves IPD and overrides; distinguish measurement overrides from observer controls. |
| `app/.../SetupActivity.kt`, `app/.../VrActivity.kt` | Shared resolver, migration-aware startup, physical surface updates and GL snapshot publication. |
| `app/.../di/AppContainer.kt` | Replace separately mutable active optics wiring with resolved immutable configuration ownership. |
| `app/.../screens/CalibrationScreen.kt`, `SettingsScreen.kt` | Remove profile derivation from screen; label confidence, separate IPD/alignment, update grid/readout/cache key. |
| `app/.../render/AppScene.kt` | Add diagnostic calibration modes using the same eye geometry, if existing panel routing cannot display full per-eye angular references. |
| `docs/ARCHITECTURE.md`, `docs/TESTING.md`, `docs/PLAN.md` | Later correct inverse-mesh wording, settings guarantees, and distinguish implemented tests from planned ones. |

Existing `Scene.draw` consumers can keep `EyeParams` with derived accessors; locate all constructors/field accesses before removing adapters. `ThermalGovernor` can retain its chromatic request, but renderer gates it on validated optical support. No new library or Gradle change is needed for pure tests or basic instrumentation: both modules already declare AndroidJUnitRunner and test dependencies. Any later build-system change needs its own justification.

## 7. Buildable migration phases

Each phase ends with a compiling runnable app and the applicable gates below. Do not switch only mesh direction while retaining old projection geometry.

1. **Pure model and independent fixtures.** Add optics types/math/builder and tests alongside unchanged production paths. Encode all fixture values explicitly. Establish physical panel mapping and validation. Existing renderer remains active.
2. **Configuration and storage groundwork.** Add V2 codec/resolver and tests, immutable configuration plumbing, and compatibility accessors. Keep V2 migration activation off until phase 3 so legacy rendering never consumes newly interpreted settings. No dual-writing of new tangent coefficients to old fields.
3. **Atomic production cutover.** In one buildable change, activate V2 migration, remove IPD-to-lens coupling in both activities/selectors, install shared projection and mesh mapping, and publish configuration snapshots. New renderer and new persisted semantics activate together. Remove or isolate the old renderer from normal settings; no user-facing mix-and-match toggle.
4. **Compositor and surface robustness.** Complete framebuffer/scissor validation, cache invalidation, context loss, odd dimensions/cutouts/rotation, and error handling. Gate even the provisional build on actual zero-distortion and analytic GL tests. Implement deterministic tessellation selection below.
5. **Calibration experience and hardware qualification.** Add per-eye optical-axis and angular-grid diagnostic modes, measured display/mount overrides, provenance/readout, recovery messaging, and per-profile persistence. Execute the hardware matrix and record results; unknown devices remain provisional.
6. **Cleanup and documentation.** Remove old inverse-in-clip-space APIs/constants and temporary FOV/profile adapters once all callers use shared types. Update old tests instead of merely loosening tolerances. Update architecture/testing docs and retain regression fixtures/migration backups.

For rollback during development, revert an entire coherent implementation phase in a separate authorized task. Do not plan an automatic downgrade that writes V1 over V2. Archived legacy bytes enable explicit recovery/export; they are not a hidden route to silently restoring invalid optical semantics.

## 8. Acceptance gates

These are required future checks, not test results from this document-writing task. Do not run builds during this task because their outputs would violate the one-file write scope.

### 8.1 Pure unit tests

Add `vrcore/src/test/java/com/daydreamvr/vrcore/optics/{RadialDistortionTest,OpticsGeometryTest,OpticsValidationTest}.kt`; revise existing `render/StereoLayoutTest.kt` and `distortion/DistortionMeshTest.kt`.

Mandatory analytic fixtures (hand-specified expected values, not outputs re-generated with production functions):

- **Direction:** `k=(0.34,0.55)`, `s=(0.5,0)` gives `factor=1.119375`, `t=(0.5596875,0)`. At `s=(0.3,0.4)` the same radius gives `t=(0.3358125,0.44775)`. Double error ≤1e-12; Float output error ≤1e-6. This test must fail if the mesh uses inverse distortion.
- **Asymmetric full physical fixture:** panel `0.140 × 0.070 m`, surface `2000 × 1000`, divider 0, `L=0.064 m`, `d=0.040 m`, `cy=0.035 m`, identity polynomial, caps 80°. Centers are `(0.038,0.035)` and `(0.102,0.035)`. Left bounds `(-0.95,0.8,-0.875,0.875)`, right bounds `(-0.8,0.95,-0.875,0.875)`. Center UVs `(19/35,0.5)` and `(16/35,0.5)`; center surface X coordinates `542.857142857` and `1457.142857143`. Geometry tolerance 1e-9 Double or 1e-6 Float.
- **Independent nonzero lookup:** same physical fixture with `k=(0.34,0.55)`, caps 45°, hence source bounds `[-1,1]²`. Left physical point `(0.058,0.035)` gives screen tangent `(0.5,0)`, source UV `(0.77984375,0.5)`. Do not derive this expectation through the production mesh.
- **Center offset:** set `cy=0.030 m` in the identity fixture. Vertical bounds become `(-0.75,1.0)` and center V is `3/7`; no vertical recentering to 0.5.
- **Crop:** in the identity fixture with divider 8, viewports are `[0,996)` and `[1004,2000)`. Inner physical tangent magnitude is `0.793`, outer stays `0.95`. An extra left outer inset of 10 px changes left outer magnitude to `0.9325` and leaves physical lens centers unchanged. For `Nx=2001,D=9`, divider is exactly `[996,1005)`.
- **Metric isotropy:** equal metre offsets from a lens along X and Y produce equal tangent radius despite a non-square eye viewport; changing resolution without changing physical panel leaves corresponding normalized physical samples invariant.
- **IPD isolation:** switching 52→64→74 mm changes only eye view offsets; optical bounds/UVs/cache key remain equal. Switching viewer does not change observer IPD.
- **Matrix correspondence:** points `ray=(tx,ty,-1)` at each tangent-bound edge project to NDC ±1 within 1e-6; optical-axis ray projects to the same UV as the center fixture. Both eyes tested without a second mirror.
- **Inverse:** sample radii 0 through maximum physical-corner radius for each fixture/profile, 1001 uniformly spaced values, and the same number of resulting target radii. Require residual bound in section 2.4. Include zero and identity. Inverse round-trip is supplemental, not the direction oracle.
- **Invalid input:** zero/negative/NaN/infinite distance/dimensions; coefficients outside the supported domain; angles 0 or ≥89°; invalid transforms; excessive divider/insets; lens outside viewport; nonfinite UV; all produce the specified typed error, no NaN VBO or silent center clamp.

Mesh acceptance: compare actual triangle-interpolated source UV against an independent Double analytic oracle over a deterministic 9×9 barycentric lattice in **every triangle**, including edges. Convert UV error into actual FBO texel distance; maximum Euclidean error ≤0.25 texel in the valid sampling region and ≤0.5 display pixel displacement of the validity boundary. Start at 40×40 quads; double to 80,160,320 until the gate passes. Test and select on geometry/FBO-size change, not every frame. If 320 fails, report unsupported precision and retain the previous valid configuration; never loosen the gate or alter coefficients. Triangle winding must stay positive and all vertices finite. Reject degenerate triangles. When comparing masked edges, evaluate the analytic boundary as well as interior UV error. FBO size belongs in tessellation selection, although it does not alter optical coordinates.

### 8.2 Settings and integration tests

Add `app/src/test/java/com/daydreamvr/player/data/{SettingsMigrationTest,OpticsSettingsResolverTest}.kt`, `state/OpticsSettingsTest.kt`, and display-mapping pure tests; revise `screens/CalibrationScreenTest.kt`.

Required fixtures: absent blob; exact current legacy defaults; customized legacy tuple; missing individual fields; unknown profile; malformed JSON; invalid individual numeric values; existing V2 with overrides for two viewers; future schema/model version; repeated migration; simulated migration/write failure. Assert exact legacy-byte backup, idempotence, preserved observer IPD/non-optics data/resume storage, inactive legacy coefficients, correct recalibration status, and no cross-profile override loss. Test fresh startup and profile cycling through both setup and VR resolver paths. Test atomic configuration publication: each rendered frame observes entirely old or entirely new optics, never a hybrid. IPD-only updates do not rebuild meshes; coefficients, distance, physical panel dimensions, mounting alignment, inset/rotation, or FOV revision do.

Run after each relevant phase:

```
./gradlew :vrcore:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug
```

Final repository gate:

```
./gradlew check :app:assembleDebug :app:assembleRelease
```

All tasks must succeed; inspect lint results explicitly because the current app sets `abortOnError=false` and disables release lint checks. No new relevant lint errors may be hidden by those settings. Record pre-existing unrelated failures separately; they do not establish a passing release.

### 8.3 GL instrumentation

Create `vrcore/src/androidTest/java/com/daydreamvr/vrcore/distortion/DistortionRenderTest.kt` with a minimal EGL ES3 harness, and `app/src/androidTest/java/com/daydreamvr/player/OpticsSettingsPersistenceTest.kt` for real DataStore/relaunch behavior. Existing dependencies suffice. Use deterministic procedural UV/color textures and fiducials; turn off pose changes, dithering, blending, and variable video decode for golden captures.

- Identity direct rendering versus identity FBO+mesh: RGB difference ≤3/255 in every channel at ≥99.5% of compared pixels. Explicitly exclude only a documented one-pixel raster boundary band, not arbitrary failure regions.
- Nonzero fixture above: compare `glReadPixels` UV-gradient lookup to an independent CPU pixel-center oracle, same ≤3/255 and ≤0.5% outlier gate away from the documented mask/filter boundary. Fiducial position error ≤0.5 display pixel. This catches correct-looking but reversed warps and right-eye mirror errors.
- Divider and inset pixels exactly black RGB; no left/right color contamination. Outside-source mask pixels black, with the one-pixel filtering boundary documented separately. Odd widths/dividers and asymmetric insets required.
- MSAA off and supported MSAA 2/4, renderScale 0.75/1.0/1.15/1.5; include a small surface where supersampled target exceeds surface height. Resolve must copy the entire eye target, rebind the intended framebuffer, and leave correct scissor/viewport for the next draw. Require complete FBOs and zero GL errors.
- Repeated resize, optical edits, activity pause/resume and EGL context recreation: correct first rendered frame, no stale mesh, no mixed geometry, no leaked live FBO/texture/mesh count after 100 cycles. Test allocation failure with an injected allocator.
- A real restart preserves two independently edited viewer overrides and IPD, triggers V1 migration once, and leaves resume entries byte-equivalent.

Run on a connected ES3 target:

```
./gradlew :vrcore:connectedDebugAndroidTest :app:connectedDebugAndroidTest
```

A checked-in golden must identify fixture, dimensions, renderer precision, and source revision. Generate expected values independently; do not bless the current output merely to make a screenshot test pass.

### 8.4 Device/through-lens qualification

Required matrix: an actual Daydream View 2016 and 2017, each with at least one measured phone; at least two phone panel aspect/size combinations overall, both landscape mount orientations if advertised, and at least one cutout/asymmetric-surface case. Untested combinations remain provisional. Record viewer generation, phone/display mode, measured illuminated dimensions, seating, lens center measurements, coefficients/convention/revision, test app revision, render scale, and thermal state.

Use a fixed camera at each nominal eye position with its own camera lens distortion calibrated out. Capture straight angular reference lines and axis fiducials; include central 60° total field and the accepted boundary. A flat finite-distance world grid alone is insufficient because perspective/stereo disparity can be mistaken for lens error. Provide separate monocular axis/ray grids and finite-distance binocular targets.

Acceptance for a combination to be marked VERIFIED:

- Angular line residual from best straight line ≤0.25° RMS and ≤0.5° maximum throughout the central ±30° field; ≤1° maximum to the claimed visible boundary. If the fixture/camera cannot measure these tolerances, record the result as inconclusive, not pass.
- Optical-axis fiducial within 1 mm of measured panel lens center; binocular infinity references have vertical angular mismatch ≤0.25°. Finite-distance disparity agrees with the selected IPD geometric reference within 0.5° at 2 m and 4 m. No software IPD adjustment shifts measured warp centers.
- At nominal pupil position, no repeated edge texture, folds, reversed orientation, eye bleed, or unexplained asymmetric clipping. A mounted user can fuse targets and read UI during a short session; stop on discomfort and record failure rather than tuning coefficients without a measurement protocol.
- Calibration survives process death and viewer switching. Distortion-off diagnostic, mono/stereo video and UI all use the intended geometry. No changes to existing SBS/top-bottom video eye assignment.
- Ten-minute playback and head-motion run at fixed documented quality after warmup: p95 app render time below one refresh period, no more than 1% missed frame deadlines, and no more than 10% p95 regression against the same scene/quality baseline. Record thermal throttling and memory, not just average FPS. If precision requires an expensive mesh, optimize generation/upload/cache before reducing correctness gates.

Do not require the uncompensated view to visibly bow as the sole success criterion; a low-distortion lens or limited field can make that comparison inconclusive. Do not certify comfort or physical accuracy from emulator screenshots.

## 9. Completion checklist and executive summary

Implementation is complete only when one geometry snapshot drives viewports, physical lens centers, source frusta and UVs; no clip-space coefficient interpretation or fixed display-width constant remains; observer IPD is independent; migration is atomic and reversible through preserved data; all specified software gates pass; and measured hardware combinations have explicit qualification status. Provisional defaults remain labeled even when software tests pass.

**Executive summary:** Correct the entire screen-to-ray pipeline together: convert physical screen offsets to tangent units, apply the forward polynomial for destination-mesh UVs, and normalize against the exact frustum used to render each eye. Keep fixed lens separation separate from user IPD. Archive legacy calibration rather than converting incompatible coefficients. Ship explicit provisional Daydream baselines with reduced FOV, no speculative chromatic correction, and no fabricated phone dimensions; require independent numerical, GL, persistence, and measured through-lens gates before claiming calibrated support.
