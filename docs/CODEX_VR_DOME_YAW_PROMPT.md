# Codex implementation brief — Arc VR Player: stereo dome + joystick yaw

Work directly in `/root/daydream-vr-player` on `master`. Implement BOTH feature cards in a coherent, tested change:

- `t_a97a4a54`: VR 180/190/200/220-degree front-dome projection with proper stereoscopic SBS / over-under UVs.
- `t_34b149ce`: smooth left-stick horizontal yaw alignment for flat theater and spherical VR playback, without recalibrating the headset.

## First: inspect the live code before editing
Relevant existing areas:
- `vrcore/.../render/ProjectionMode.kt`
- `vrcore/.../render/SphereScreen.kt`
- `vrcore/.../render/CylinderScreen.kt`
- `vrcore/.../input/{InputAction,GamepadDecoder}.kt`
- `app/.../VrActivity.kt`
- `app/.../render/AppScene.kt`
- state reducer projection cycle/tests

Do not touch untracked docs/scripts or `.worktrees/`. Do not use a git worktree. Preserve zero-copy `GL_TEXTURE_EXTERNAL_OES` playback.

## A. Stereo 180/190/200/220° dome

### Correct model
- Current `EQUIRECT_180` is monoscopic and maps both eyes to the full frame. This creates double vision for ordinary 3D VR180 media.
- Add explicit projection modes that distinguish stereo packing:
  - `EQUIRECT_180_SBS`
  - `EQUIRECT_180_TOPBOTTOM`
- Per-eye UV must be composed cleanly from **packing** and **projection**. For SBS, left eye gets image-space `(uOff=0, vOff=0, uScale=.5, vScale=1)` and right gets `(0.5,0,.5,1)`. For Top/Bottom preserve the project convention: left is top image `(0,.5,1,.5)` and right is bottom `(0,0,1,.5)`.
- Existing flat SBS and top/bottom modes must retain their behavior. EQUIRECT_360 stays full-sphere and monoscopic unless it was already an explicitly stereo mode.
- Update filename detection: a name with both 180/VR180 token AND SBS/H-SBS/HSBS should select the SBS VR180 mode; 180/VR180 AND OU/TB should select VR180 top-bottom. The 180-only fallback remains monoscopic `EQUIRECT_180`.

### Dome FOV
- Add a first-principles mesh FOV parameter to `SphereScreen`: accepted values exactly 180, 190, 200, and 220 degrees; default 180.
- The mesh must be a front-only dome whose longitude span is the selected angle (`FOV * pi / 180`), centered ahead; never silently turn 220 into 360 or stretch behind the viewer.
- For all equirect-180 variants the mesh uses this FOV; normal 360 uses 360 degrees.
- Model selection must permit users to select FOV. A small deliberate cycle is fine: e.g. the existing projection control can enumerate these distinct user-visible modes (`VR180 SBS`, `VR190 SBS`, …, `VR220 SBS`, and equivalent OU where relevant) OR add a separately surfaced setting that the player HUD and browse inspector can cycle. Avoid hidden numeric literals: put available FOV values and labels in one config/companion source.
- Keep automatic detection at 180° unless explicitly selected.
- Ensure `SphereScreen` rebuilds its mesh when either projection family or dome FOV changes.

### Tests
- Update existing `ProjectionModeTest`: stereo VR180 exact per-eye UV; detection ordering.
- Add `SphereScreenTest` with pure geometry: selected 220 degree dome has correct edge azimuths (±110°) and 360 mesh is full 360; radial coordinates remain radius-correct.
- Update app reducer tests for user projection cycle as necessary.

## B. Left joystick yaw alignment

### User behavior
The *left analog stick horizontal axis* smoothly rotates the virtual scene/screen while held. It is not D-pad navigation and does not call `headTracker.recenter()`. It works when watching both a flat screen and a spherical VR dome.

### Architecture
- Add semantic `InputAction.YawAdjust` carrying a normalized rate/delta from the left stick.
- Avoid conflict with browse navigation: in `GamepadDecoder`, keep left-stick-to-Nav in browse/menu contexts as currently implemented. Route the left X axis to `YawAdjust` only while `VrActivity` is currently in `VrScreen.PLAYER`. It is acceptable to add a `playerInputEnabled: () -> Boolean` predicate to the decoder constructor, supplied by VrActivity state.
- Apply this to `AppScene` in a thread-safe way, through a method such as `adjustWorldYaw(rate, dt)` or input rate setter sampled during `update`. The GL thread owns the accumulated offset.
- Use deadzone from the axis map. Smooth and bounded rate: target about 35–45 degrees/sec at full deflection; apply dt. No per-event position jumps. A user lets go -> no more rotation. Wrap angle at ±pi.
- Apply the accumulated world yaw consistently to **both** `CylinderScreen.yawRad` and `SphereScreen.yawRad`, so it supports theater and dome playback. It must NOT alter head tracker calibration / recenter reference.
- Recenter behavior should retain the user yaw offset (it should only recenter the headset), unless a documented dedicated reset exists. Do not accidentally wipe it on every state transition.
- Cover the pure math in a small JVM-testable helper if useful, and update GamepadDecoder tests proving player-mode left-stick-X emits `YawAdjust` while browse-mode still produces navigation.

## Engineering / delivery
- Do not modify broad unrelated architecture.
- Run `./gradlew testDebugUnitTest --no-daemon` and `./gradlew assembleRelease --no-daemon` before finishing; address failures.
- Commit all feature code/tests together with a clear commit message, then push `origin master`.
- When done, print a concise summary, commit hash, test/build result, and call these exact commands so the board reflects terminal status:
  `hermes kanban complete t_a97a4a54`
  `hermes kanban complete t_34b149ce`
If blocked, call `hermes kanban block <id> --reason "..."` instead.
