# Codex Fix Task (t_2b083b2e): Fix C4 — Cylinder Geometry Mesh Invalidation, Settings Wiring, & Per-Eye Display Aspect

You are fixing finding **C4** from Astra's architectural review (`docs/ASTRA_ARCHITECTURAL_REVIEW.md`) in Arc VR Player (`/root/daydream-vr-player`).

## 1. Problem & Root Cause (Astra Finding C4)
1. **Mesh Stale / No Invalidation:**
   - In `vrcore/.../render/CylinderScreen.kt`, `setAspect(videoAspect)` updates `var aspect: Float` but **never rebuilds or invalidates the GL mesh**.
   - `mesh` is built once during `onGlCreate()` and `draw()` draws whatever mesh was allocated initially (which defaults to 16:9).
   - In `AppScene.kt`, `setAspect()` is called when video dimensions arrive, but the cylinder remains permanently shaped as the default 16:9 mesh.
2. **Settings Ignored by Renderer:**
   - `Settings.screenDistanceM` (default 4.0m, range 1.5–12m) and `screenWidthDegrees` (default 60°, range 30–110°) are stored in `AppState.settings`, adjusted via the HUD / Settings UI, and persisted in `SettingsStore`.
   - However, they are **never applied to `CylinderScreen`**! The cylinder always renders with hardcoded `radiusM = 4f` and `widthDegrees = 60f`.
3. **Missing Per-Eye Aspect Adjustment for Full SBS / Full TB:**
   - For `SBS_FULL` (e.g. 3840×1080 composite frame), two unsqueezed 16:9 eyes are packed side-by-side. The raw frame aspect is 32:9 (~3.55). If applied directly to the cylinder, each eye is stretched 2× wider than it should be! Effective per-eye aspect must be `rawAspect / 2f`.
   - For `TOPBOTTOM_FULL` (e.g. 1920×2160 composite frame), two unsqueezed 16:9 eyes are packed top-and-bottom. The raw frame aspect is 8:9 (~0.88). If applied directly, each eye is squished horizontally by 2×. Effective per-eye aspect must be `rawAspect * 2f`.
   - For `FLAT`, `SBS_HALF`, and `TOPBOTTOM_HALF`, the effective per-eye aspect is `rawAspect` (in half-format 3D, the content was anamorphically squashed into a 16:9 frame, so rendering across the full 16:9 cylinder restores the original un-squashed aspect).

---

## 2. Requirements & Implementation Plan

### A. `CylinderScreen.kt` (`vrcore/src/main/java/com/daydreamvr/vrcore/render/CylinderScreen.kt`)
1. Add a geometry configuration key:
   ```kotlin
   data class GeometryKey(
       val radiusM: Float,
       val widthDegrees: Float,
       val aspect: Float,
   )
   ```
2. Track `private var activeKey: GeometryKey? = null`.
3. Add `fun updateGeometry(radiusM: Float, widthDegrees: Float, aspect: Float)`:
   - Validate that each parameter is finite and > 0f before applying.
   - Update `this.radiusM`, `this.widthDegrees`, and `this.aspect`.
4. Keep `fun setAspect(videoAspect: Float)` for backward compatibility:
   - If finite and > 0f, updates `this.aspect = videoAspect`.
5. Ensure mesh rebuild runs safely on the GL thread:
   - In `draw(eye, viewM, projM, videoTexture, projection)`:
     ```kotlin
     val key = GeometryKey(radiusM, widthDegrees, aspect)
     if (mesh == null || activeKey != key) {
         rebuildMesh()
     }
     ```
   - In `rebuildMesh()`:
     ```kotlin
     mesh?.release()
     mesh = buildMesh()
     activeKey = GeometryKey(radiusM, widthDegrees, aspect)
     ```
   - In `onGlCreate()`:
     Initialize shader and call `rebuildMesh()`.
   - In `onGlDestroy()`:
     Release `mesh` and `shader`, set `activeKey = null`, `mesh = null`, `shader = null`.
6. Provide a way to inspect `activeKey` (e.g., `internal val currentGeometryKey: GeometryKey? get() = activeKey`) or check geometry updates in tests.

### B. `ProjectionMode.kt` (`vrcore/src/main/java/com/daydreamvr/vrcore/render/ProjectionMode.kt`)
Add helper method:
```kotlin
/**
 * Computes the effective per-eye display aspect (width / height) on the virtual cinema screen.
 *
 * - [FLAT], [SBS_HALF], [TOPBOTTOM_HALF]: display aspect equals the frame's [videoAspect].
 *   (For half-SBS/TB, content was anamorphically squeezed in the frame, so displaying it at the
 *   video's aspect ratio stretches each eye's half-frame back to proper proportions).
 * - [SBS_FULL]: the video frame packs two unsqueezed eyes side-by-side (2× wide).
 *   Each eye's display aspect is `videoAspect / 2f`.
 * - [TOPBOTTOM_FULL]: the video frame packs two unsqueezed eyes top-and-bottom (2× tall).
 *   Each eye's display aspect is `videoAspect * 2f`.
 * - Other/spherical: returns [videoAspect].
 */
fun effectiveDisplayAspect(videoAspect: Float): Float {
    if (!videoAspect.isFinite() || videoAspect <= 0f) return 16f / 9f
    return when (this) {
        SBS_FULL -> videoAspect / 2f
        TOPBOTTOM_FULL -> videoAspect * 2f
        else -> videoAspect
    }
}
```

### C. `AppScene.kt` (`app/src/main/java/com/daydreamvr/player/render/AppScene.kt`)
1. In `update(dtSeconds: Float)`:
   When `state.screen == VrScreen.PLAYER`:
   - Obtain `rawAspect = snap.dimensions.displayAspect`.
   - Compute `effectiveAspect = state.playback.projection.effectiveDisplayAspect(if (rawAspect > 0f) rawAspect else 16f / 9f)`.
   - Update cylinder with settings:
     ```kotlin
     cylinder.updateGeometry(
         radiusM = state.settings.screenDistanceM,
         widthDegrees = state.settings.screenWidthDegrees,
         aspect = effectiveAspect,
     )
     ```
2. In `draw(...)`:
   When `state.screen == VrScreen.PLAYER`:
   - Before `cylinder.draw(eye, viewM, projM, video, mode)`, ensure `cylinder` has the latest settings from `state.settings` and `mode.effectiveDisplayAspect(...)` to guard against mid-frame settings changes.

### D. Unit Tests
1. In `vrcore/src/test/java/com/daydreamvr/vrcore/render/CylinderScreenTest.kt`:
   - Test `updateGeometry` updates `radiusM`, `widthDegrees`, and `aspect` and ignores non-finite/non-positive values.
   - Test that changing `radiusM` and `widthDegrees` changes vertex positions appropriately in `buildVertices()` (e.g. verify radius scaling on x/z, arc width scaling).
   - Test `GeometryKey` equality and discrimination.
2. In `vrcore/src/test/java/com/daydreamvr/vrcore/render/ProjectionModeTest.kt`:
   - Test `effectiveDisplayAspect` for `FLAT`, `SBS_HALF`, `SBS_FULL`, `TOPBOTTOM_HALF`, `TOPBOTTOM_FULL`, and invalid/zero aspect fallback.

---

## 3. Verification & Quality Gates
1. Run `./gradlew testDebugUnitTest --no-daemon` to ensure all unit tests pass cleanly across all modules.
2. Run `./gradlew assembleRelease --no-daemon` to verify release compilation and packaging.
3. Commit the changes cleanly with message:
   `fix(render): cylinder mesh invalidation, settings wiring, and 3D per-eye aspect (Astra C4)`
