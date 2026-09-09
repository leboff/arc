# Testing & Manual Verification Matrix — Arc

Companion to [PLAN.md](PLAN.md) §17 and [ARCHITECTURE.md](ARCHITECTURE.md) §17.
This document is the checklist a release is signed off against. It has two
halves: the **automated suite** (run in CI and locally, no hardware) and the
**manual matrix** (a physical phone, a viewer shell, a gamepad, and a live
DLNA server).

---

## 1. Automated suite

```bash
export ANDROID_HOME=/root/android-sdk

./gradlew check                # ktlint + all JVM unit tests + module-import rule
./gradlew connectedCheck       # instrumented tests (needs a device/emulator)
./gradlew :app:assembleRelease # R8 full mode must succeed with the checked-in keep rules
```

### 1.1 JVM unit tests (fast, no emulator)

| Module | Test classes | Covers |
|---|---|---|
| `upnp` | `DidlParserTest`, `DidlParserEscapingTest`, `DeviceDescriptionParserTest`, `ContentDirectoryClientTest`, `SafeXmlTest`, `SsdpClientTest`, `ResourceRankerTest`, `MediaServerDirectoryTest` | DIDL parsing for all five vendor fixtures, `URLBase` resolution, escaped/CDATA `<Result>`, pagination, UPnP fault mapping, XXE/billion-laughs rejection, M-SEARCH byte-exactness and retry timing, resource ranking |
| `vrcore` | `StereoLayoutTest`, `QuaternionTest`, `FrameConverterTest`, `RecenterTest`, `PosePredictorTest`, `GamepadDecoderTest`, `GamepadProfileResolverTest`, `AngularMetricsTest`, `PanelAnchorTest`, **`DistortionMeshTest`** | Asymmetric frustum vs hand-computed reference, quaternion↔matrix round-trip, display-rotation remap, yaw-only recentre invariant, gyro prediction, axis resolution for Xbox/DS4/DualSense/8BitDo, **distortion `undistort(distort(r)) ≈ r` and mesh monotonicity** |
| `playback` | `ScrubControllerTest`, `FallbackPolicyTest`, `ResumeStoreTest`, `ProjectionModeTest`, `CylinderScreenTest` | Preview-then-commit scrub throttling, error → fallback ladder, resume LRU, SBS/OU/360 detection and per-eye UV rects |
| `app` | `NavigationTest`, `BrowseReducerTest`, `HudReducerTest`, `EscapeHatchTest`, `VrKeyboardTest`, **`ThermalGovernorTest`**, **`CalibrationScreenTest`**, **`GamepadCalibrationTest`** | Full navigation graph, focus/scroll restore, HUD auto-hide clock, exhaustive `Cancel`-always-escapes walk, VR keyboard keystrokes, **thermal quality ladder**, **live-calibration → `DeviceProfile` fold**, **A/B-swap detect + remap round-trip** |

Bold rows are the Phase 6 additions.

### 1.2 Instrumented tests (`androidTest`, needs a device)

| Test | Asserts |
|---|---|
| `StereoSurfaceTest` | 8 px divider column is pure black; world-forward marker lands at mirrored, non-identical x in both eyes; `onDrawFrame` ≥ 55 Hz |
| `UpnpSmokeTest` | `AndroidNetworkBinder` acquires and releases the multicast lock around a discovery cycle |
| `PlaybackSmokeTest` | prepare → play → seek → pause → resume from `ResumeStore`; MKV/AC3 asset yields a non-null audio format; first `Resource` 404 falls back to the second |
| `PanelSurfaceSelfTest` | the auto-selected panel path renders a known pattern within `glReadPixels` tolerance; forced `BITMAP_UPLOAD` matches |
| `EndToEndGamepadTest` | cold start → `A` on Enter VR → discovery → browse two levels → play → position advances → `B` restores focus → long `B` → server list, **entirely via injected gamepad events, zero touch** |
| `DistortionGoldenTest` | calibration grid with distortion on vs a checked-in golden per profile, per-pixel tolerance 3/255, ≤ 0.5 % outliers |
| `AllocationTest` | `Debug.startAllocCounting()` over 600 steady-state playback frames: per-frame allocation delta ≤ the documented platform epsilon |
| `RefreshRateTest` | the highest `Display.Mode` at native resolution is requested; if granted, `FrameStats.p95` is within 15 % of that interval |

---

## 2. Manual matrix

Every cell is: **launch → discover/select the server → open the file → watch ≥ 2 min → seek → recentre → exit**. Record pass / fail / note.

### 2.1 Coverage grid

Viewer profiles × controllers × servers × content. Not every combination is
required for a release — the **bold** row/column of each axis is mandatory, the
rest is sampled (aim for full coverage of each axis value at least once).

| Axis | Values |
|---|---|
| Viewer profile | Cardboard v1, **Cardboard v2**, Daydream View 2016, Daydream View 2017, Generic 100° shell |
| Controller | **Xbox Series**, DualSense, 8BitDo (Xbox mode), **8BitDo (Switch mode)** |
| Server | **Gerbera**, Synology DS Video / Media Server, Plex DLNA, MiniDLNA, Jellyfin |
| Content | **MP4 H.264 1080p**, **MKV H.265 + AC3 1080p**, SBS/HSBS 3D, over-under 3D, 360 equirect, 4:3 SD, 2.35:1 anamorphic |

### 2.2 Per-run checklist

- [ ] **Discovery** finds the server within 5 s (or manual `host:port` via the VR keyboard works with multicast blocked).
- [ ] **Browse** a 500-item folder: scrolls smoothly, pages in with no visible stall, panel redraw counter (debug overlay) does not climb per frame.
- [ ] **Playback** holds render rate; correct aspect (no stretch on 4:3 or 2.35:1); correct orientation (no vertical flip); audio present on the MKV/AC3 file.
- [ ] **Transport**: `X` play/pause, bumpers ±10 s, triggers scrub smoothly with no decoder thrash, right stick resizes/moves the screen within limits.
- [ ] **Projection**: `R3` cycles modes live; SBS file shows correct per-eye halves; 360 file maps to the sphere and honours the recentre anchor.
- [ ] **Head tracking**: turn right → world moves left; look up → world moves down; horizon stays level. `Y` recentres in ~150 ms with no snap and does not tilt the horizon.
- [ ] **Comfort**: no text under 1.5° of visual angle, nothing outside the ±25°/±20° box, nothing crossing the divider.
- [ ] **Errors surface in-headset**: pull Wi-Fi mid-playback → retry sequence then a legible panel (not a black screen); unplug the controller → pause + high-contrast reconnect panel; reconnect → resumes.

### 2.3 Phase 6 comfort / performance / release checks

- [ ] **Distortion on**: a straight-line calibration grid (Settings → any of IPD / Screen-to-lens / Lens k1 / Lens k2 / Divider width) is straight through a real Cardboard v2 / Daydream View. **Off**: it visibly bows.
- [ ] **Live optics calibration**: adjusting IPD, screen-to-lens, k1, k2 and divider width changes the grid immediately; values persist across an app restart and follow the selected viewer profile.
- [ ] **Gamepad calibration**: with an 8BitDo in Switch mode, Settings → "Gamepad buttons" → toggle to "A/B swapped" makes the lower face button confirm and the right one go back; the setting survives a Bluetooth disconnect/reconnect.
- [ ] **Sustained thermals**: 30 min continuous 1080p playback — no frame-rate collapse, no thermal shutdown, and the debug overlay shows the thermal governor stepping render scale / MSAA / chromatic down as the phone heats.
- [ ] **Allocations**: `StrictMode` clean in the debug build; `AllocationTest` green.
- [ ] **Subtitles**: render legibly on their own panel below the screen and never cross the divider.
- [ ] **Release build**: `:app:assembleRelease` (R8 full mode) produces an APK that installs and runs end-to-end on a clean device with no missing-class / no-such-method crash across this matrix.

### 2.4 Lifecycle / resilience spot checks

- [ ] Rotate the phone while in VR: nothing happens (orientation locked).
- [ ] Background and foreground the app 3× during playback: sensors unregister (`dumpsys sensorservice`), no GL context leak, playback resumes at position.
- [ ] Kill Wi-Fi during discovery: multicast lock and Wi-Fi network binding are both released (no lock leak across 20 cycles).
- [ ] GL thread wedge (surface loss): the watchdog recreates the `GLSurfaceView` and state is restored from `AppState` within ~2 s.

---

## 3. Sign-off

A build ships when: `./gradlew check` and `./gradlew connectedCheck` are green,
`:app:assembleRelease` succeeds, and every mandatory (bold) manual cell plus the
full §2.3 Phase 6 list passes on at least the Cardboard v2 + Xbox + Gerbera +
{MP4 H.264, MKV H.265+AC3} baseline. Cells that can only fail on absent hardware
are marked `UNVERIFIED-ON-DEVICE` with the reason, per PLAN.md ground rule 8.
