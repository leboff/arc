# Daydream / Cardboard VR UPnP Player — Technical Architecture

**Status:** Design baseline, v1.0
**Target:** Android 15/16 (API 35/36) phone in a *passive* Cardboard/Daydream View shell, driven exclusively by a Bluetooth HID gamepad, streaming from LAN UPnP/DLNA MediaServers.
**Audience:** the implementing agent (Claude Sonnet 5) and future maintainers. Companion document: [PLAN.md](PLAN.md).

---

## 1. Goals, Non-Goals, Constraints

### 1.1 Product goals

| # | Goal |
|---|------|
| G1 | Once the phone is in the headset, **the user never touches the screen again.** Discovery, browsing, playback, settings, and error recovery are all reachable with a gamepad while wearing the viewer. |
| G2 | Stable, comfortable stereoscopic presentation of **flat** video on a virtual cinema screen (plus optional SBS / over-under / 180 / 360 modes). |
| G3 | Zero-configuration LAN media: SSDP autodiscovery, with a manual `host:port` escape hatch when multicast is blocked. |
| G4 | Robust playback of whatever the DLNA server hands over (MP4/H.264, MKV/H.265, AAC/AC3/DTS where the device allows). |
| G5 | Motion-to-photon latency low enough (< ~60 ms) and framerate stable enough that a 90-minute film does not induce sickness. |

### 1.2 Non-goals (v1)

- No 6DOF, no positional tracking, no room-scale. The phone has no depth sensors in the shell and passive viewers have no controller.
- No Google VR SDK / Daydream SDK dependency. The `com.google.vr:sdk-*` artifacts and the Daydream platform services are dead; the Daydream *controller* is not a target. We implement the small amount of VR we need ourselves (see §6, §19.1).
- No transcoding, no server-side control (no UPnP AVTransport "play to"), no casting. We are a *renderer-less* control point that streams directly.
- No account systems, no telemetry, no cloud. The app never talks to anything outside the local subnet except the Play Store update channel.
- No touch UI beyond a small pre-flight "lobby" screen (§11.6).

### 1.3 Hard constraints that shape the design

1. **No touchscreen while worn.** Every affordance must be reachable from ~12 physical buttons and 2 sticks. This is the single biggest design driver: it forces an explicit navigation state machine (§12) and an in-VR keyboard (§11.5).
2. **Passive optics.** No lens metadata from the OS; distortion parameters come from a hardcoded device profile table (§6.6).
3. **The display is the whole world.** Anything drawn once per eye costs double. The renderer must be able to hit 90 Hz at ~2× 1200×1400 with a video texture, a curved screen, and up to two UI panels.
4. **Thermals.** Phone in a sealed plastic box, screen at full brightness, Wi-Fi + hardware decoder + GPU hot, for 2 hours. Thermal throttling is not an edge case, it is the steady state (§14).
5. **Cleartext HTTP on the LAN.** DLNA is HTTP/1.1 without TLS. Requires an explicit network security config and an honest security note (§16).

---

## 2. Platform Baseline

| Item | Choice | Rationale |
|---|---|---|
| Language | Kotlin 2.x, JVM target 17 | Coroutines/Flow are the backbone of the async design. |
| `minSdk` | 29 (Android 10) | `Surface.lockHardwareCanvas` (23), stable `SurfaceTexture` timestamps, `Display.Mode` refresh-rate switching (23/30), `ConnectivityManager.bindSocket` (23). 29 also gives us scoped-storage-free operation and `setSustainedPerformanceMode`. |
| `compileSdk` / `targetSdk` | 36 | Android 16 behaviours (predictive back, edge-to-edge) must be handled explicitly since we are fullscreen immersive. |
| Graphics API | **OpenGL ES 3.0** via `GLSurfaceView` | ES 3.0 gives us `GL_TEXTURE_EXTERNAL_OES` sampling in GLSL ES 3.00, VAOs, instancing, and `glInvalidateFramebuffer`. Vulkan buys latency we cannot use (no direct-to-display/front-buffer path on a stock phone) at a large complexity cost — rejected, see §19.2. |
| Video | **AndroidX Media3 / ExoPlayer** (pin latest stable at implementation time) | Only viable option for MKV + adaptive buffering + track selection. |
| HTTP | **OkHttp**, shared instance | Used by both the UPnP control point and `media3-datasource-okhttp`, so one connection pool, one timeout policy, one set of interceptors. |
| DI | Manual constructor injection + a single `AppContainer` | The graph is ~20 objects. Hilt's build cost and lifecycle assumptions buy nothing here. |
| Persistence | DataStore (Preferences) + `kotlinx.serialization` JSON blobs | Two tiny tables (known servers, resume positions). Room is over-scoped; see §13. |
| Concurrency | Coroutines + `StateFlow`; GL thread is *not* a coroutine dispatcher | Frame loop must never suspend. |

> **Version pinning rule:** the implementer pins the newest stable release of each library at build time and records the exact versions in `gradle/libs.versions.toml`. Do not copy version numbers from this document.

---

## 3. Module Map

```
daydream-vr-player/
├── app/            Android application. Activities, DI container, state machine,
│                   screens, HUD, wiring. Depends on all others.
├── vrcore/         Android library. GL renderer, stereo math, head tracking,
│                   distortion, canvas-to-texture panels, gamepad decoding.
│                   Knows nothing about UPnP or video.
├── upnp/           PURE KOTLIN/JVM library. SSDP, device description, SOAP,
│                   DIDL-Lite. No android.* imports. Fully unit-testable.
└── playback/       Android library. Media3 wrapper, video-to-GL-texture bridge,
                    track selection, resume logic.
```

**Dependency rules (enforced by a Gradle check in Phase 1):**

```
app  ──▶ vrcore
app  ──▶ upnp
app  ──▶ playback ──▶ (media3)
vrcore ──▶ (android sdk only)
upnp   ──▶ (okhttp, kotlinx-coroutines, javax.xml — NO android.*)
```

`upnp` being an `java-library` (not `com.android.library`) is deliberate and load-bearing: the entire protocol layer — the part most likely to be wrong against a real Gerbera/Synology/Plex box — runs in millisecond-scale JVM tests against MockWebServer, with no emulator in the loop. Any temptation to reach for `android.util.Xml`, `Log`, or `Uri` inside `upnp` is a design violation; the module ships its own tiny `UpnpLog` interface and uses `java.net.URI`.

---

## 4. Threading Model & Data Flow

Four execution contexts. Getting this wrong is the classic way to ship a VR app that judders.

```
┌──────────────────┐   KeyEvent /          ┌─────────────────────┐
│  MAIN (UI) THREAD│   MotionEvent  ──────▶│  GamepadDecoder      │
│  Activity        │                       │  (pure, no state)    │
│  lifecycle       │                       └──────────┬──────────┘
└──────────────────┘                                  │ InputAction
                                                      ▼
┌──────────────────┐  StateFlow<AppState>   ┌─────────────────────┐
│ IO DISPATCHER    │◀──────commands────────▶│  AppStateMachine     │
│ SSDP / SOAP /    │                        │  (single writer,     │
│ HTTP / DataStore │───────results─────────▶│   Main dispatcher)   │
└──────────────────┘                        └──────────┬──────────┘
                                                       │ immutable snapshot
┌──────────────────┐  HeadPose (lock-free)             ▼
│ SENSOR THREAD    │──────────────────────▶ ┌─────────────────────┐
│ HandlerThread    │   double-buffered      │  GL RENDER THREAD    │
│ ~200 Hz          │   AtomicReference      │  VrRenderer.onDraw   │
└──────────────────┘                        │  60/90/120 Hz        │
                                            └─────────────────────┘
                       ExoPlayer decoder thread ──▶ SurfaceTexture ──┘
                                            (onFrameAvailable → requestRender)
```

**Rules:**

- **R1.** The GL thread never blocks, never allocates in `onDrawFrame`, and never touches a `Mutex`. It reads exactly two things per frame: `stateFlow.value` (an immutable `AppState` reference) and `headTracker.poseAt(predictedTimeNs)` (an `AtomicReference<PoseSample>` swap).
- **R2.** `AppStateMachine` is the only writer of `AppState`, confined to `Dispatchers.Main.immediate`. All reducers are pure functions `(AppState, Event) -> AppState`; side effects are returned as a list of `Effect` objects and executed by the machine's effect runner on IO. This makes the entire navigation model unit-testable without Android.
- **R3.** Sensor callbacks run on a dedicated `HandlerThread` (priority `THREAD_PRIORITY_URGENT_DISPLAY`) so a slow Main thread cannot starve head tracking. The tracker keeps a small ring buffer of `(timestampNs, quaternion, angularVelocity)` for prediction (§7.4).
- **R4.** UI panel textures are *not* redrawn per frame. `PanelSurface.invalidate()` is called only when the `AppState` slice that panel renders actually changes (structural equality check on the panel's view-model), then the Canvas draw happens on a `PanelDrawThread` and the GL thread only calls `updateTexImage()`. A static browse list costs one `glDrawElements` per eye and zero CPU.
- **R5.** Video frames arrive on the decoder's thread; `onFrameAvailable` sets a flag and calls `requestRender()`. We use `RENDERMODE_CONTINUOUSLY` anyway (head motion requires it), so the flag only gates `updateTexImage()`.

---

## 5. Coordinate Systems & Math Conventions

Ambiguity here is the source of ~80% of "why is my head tracking inverted" bugs, so this section is normative.

### 5.1 Frames

| Frame | Definition |
|---|---|
| **Android world** (`A`) | As returned by `SensorManager.getRotationMatrix*`: **+X East, +Y North (tangential), +Z up (away from gravity)**. |
| **Android device** (`D`) | Phone natural orientation: **+X right along the short edge, +Y up along the long edge, +Z out of the glass toward the viewer's face.** |
| **GL world** (`W`) | Right-handed, **+X right, +Y up, −Z forward.** This is what the projection/view matrices assume. |
| **Head** (`H`) | **+X out the right ear, +Y out the top of the skull, −Z out the nose.** Identical convention to the GL camera. |
| **Eye** (`E`) | Head, translated ±IPD/2 along `X`. |

### 5.2 Frame conversions

The display is locked to `SCREEN_ORIENTATION_LANDSCAPE`. In landscape the device frame must be remapped so that `−Z_D` points out the *back* of the phone, i.e. the direction the user is looking through the lenses:

```kotlin
// R_A_D : rotation taking device-frame vectors into Android-world frame.
SensorManager.getRotationMatrixFromVector(rAD, event.values)

// Remap for the display rotation. For ROTATION_90 (landscape, USB on the right):
SensorManager.remapCoordinateSystem(rAD, AXIS_Y, AXIS_MINUS_X, rADRemapped)
// For ROTATION_270 use (AXIS_MINUS_Y, AXIS_X). Query via context.display.rotation.
```

After the remap, the device frame's axes already coincide with the head convention (`+X` right ear, `+Y` up, `−Z` gaze). Only the *world* frame still differs: Android world is Z-up, GL world is Y-up. The fixed conversion is a −90° rotation about X:

```
        ⎡ 1  0  0 ⎤
C_W_A = ⎢ 0  0  1 ⎥      (maps Android East→+X, Up→+Y, North→−Z)
        ⎣ 0 -1  0 ⎦
```

So the head orientation used by the renderer is:

```
R_W_H = C_W_A · R_A_D_remapped
```

and, because rotation matrices are orthonormal, the view matrix for the head is `R_W_H^T`.

### 5.3 Quaternions

Internally we carry orientation as a unit quaternion `(w, x, y, z)`, Hamilton convention, active rotation, `q ⊗ p` meaning "apply `p` then `q`". Matrices are column-major `FloatArray(16)` to feed `android.opengl.Matrix` and `glUniformMatrix4fv` directly. `VrMath` provides `quatToMatrix`, `matrixToQuat`, `slerp`, `twistAbout(axis)`, `integrateGyro(q, omega, dt)`. All of it lives in `vrcore` as pure functions over `FloatArray`, with no allocation on the hot path (callers pass output buffers).

### 5.4 Units

Metres and seconds throughout the render/optics code. Milliseconds only at the Media3 boundary (it speaks `ms`) and nanoseconds at the sensor boundary (`SensorEvent.timestamp`, `System.nanoTime`, both on the same `CLOCK_BOOTTIME`-ish base on modern devices — we sanity-check the delta once at startup and fall back to wall-clock deltas if the skew exceeds 100 ms).

---

## 6. Stereoscopic Rendering

### 6.1 Frame structure

```
onDrawFrame():
  1. pose      = headTracker.predictedPose(nowNs + latencyNs)      // §7.4
  2. state     = stateFlow.value                                    // immutable snapshot
  3. videoTex.updateIfDirty()                                       // SurfaceTexture.updateTexImage
  4. panels.forEach { it.updateIfDirty() }
  5. scene.update(state, pose, dtSeconds)                           // animation, lazy-follow
  6. for eye in [LEFT, RIGHT]:
         bindEyeTarget(eye)            // FBO (distortion on) or backbuffer viewport (off)
         glViewport(eye.viewport)
         glScissor(eye.viewport); glClear(COLOR|DEPTH)
         computeEyeMatrices(eye, pose) // §6.3
         scene.draw(eye)               // screen mesh → panels → reticle
  7. if (distortionEnabled) { bindBackbuffer(); drawDistortionMesh(LEFT); drawDistortionMesh(RIGHT) }
  8. (EGL swap by GLSurfaceView)
```

Draw order inside `scene.draw`: opaque video screen first (depth write on), then UI panels back-to-front with depth **test** on and depth **write** off, then the head-locked reticle last with depth test disabled. Panels are premultiplied-alpha, `GL_ONE, GL_ONE_MINUS_SRC_ALPHA`.

### 6.2 Viewports and the centre divider

The surface is the full landscape display, e.g. 2400×1080. It is split into two equal halves:

```
 x=0                     x=W/2                    x=W
 ├───────── LEFT EYE ─────┼──────── RIGHT EYE ─────┤
 │                        ║                        │
 │      (viewport L)      ║      (viewport R)      │
 │                        ║                        │
 └────────────────────────╨────────────────────────┘
                     centre divider
```

- `viewportL = (0, 0, W/2 - g, H)`, `viewportR = (W/2 + g, 0, W/2 - g, H)` where `g = dividerPx/2`.
- `dividerPx` defaults to **8 px** (`DeviceProfile.dividerPx`). Its job is not decoration: the nose separator of the viewer is never perfectly aligned, and a black gutter prevents the left eye catching a slice of the right image (a strong binocular-rivalry trigger).
- `glClearColor` is pure black and the divider region is simply never drawn to. `glScissor` is enabled during clear so the divider stays black even when an eye clears to a non-black colour during development.
- Cropping is symmetric: nothing is scaled to "fit" the divider; both eyes lose the same number of columns, so the optical centres computed in §6.4 remain valid.

### 6.3 Per-eye matrices

```kotlin
// headFromEye: pure translation, +X for right eye
val eyeOffsetX = if (eye == RIGHT) +ipdMetres / 2f else -ipdMetres / 2f

// eyeFromWorld = inverse(worldFromHead · headFromEye)
//              = translate(-eyeOffsetX,0,0) · R_W_H^T
Matrix.transposeM(viewM, 0, headRotationM, 0)     // R^T (rotation-only inverse)
Matrix.translateM(viewM, 0, -eyeOffsetX, 0f, 0f)  // pre-multiply in eye space
```

`ipdMetres` defaults to **0.063 m** and is user-adjustable in 0.5 mm steps from the in-VR settings panel (§11.4). Note the deliberate simplification: with 3DOF only, we rotate the eyes about the *head origin*, not about the neck. A neck model (offset of ~0.075 m down, 0.08 m forward) is applied as an additional fixed translation in `headFromEye` because it measurably improves comfort when the user leans/turns — it converts pure rotation into the small translation the brain expects. `DeviceProfile.neckModel = Vector3(0f, -0.075f, 0.080f)`, disable-able in settings.

### 6.4 Asymmetric projection and IPD alignment

The lens optical centre is *not* at the centre of its half-viewport. Lenses are separated by the viewer's `interLensDistance` (ILD), while the half-viewports are separated by `screenWidth/2`. Using a symmetric frustum here is the most common stereo bug: it produces a constant horizontal disparity offset that the viewer perceives as eye strain and "the screen won't fuse".

Given the physical display size in metres (`W_m`, `H_m` — derived from `DisplayMetrics.xdpi/ydpi`, sanity-clamped, overridable per profile) and profile values `interLensDistance`, `screenToLensDistance`, `trayToLensHeight`:

```
lensCentreX(left)  = W_m/2 - ILD/2         // measured from the left edge of the display
viewportCentreX(L) = W_m/4

halfWidthOuter = lensCentreX(left)                          // → left edge of display
halfWidthInner = W_m/2 - lensCentreX(left)                  // → centre divider
halfHeightDown = trayToLensHeight - caseTrayOffset          // → bottom edge
halfHeightUp   = H_m - halfHeightDown

tan(fovOuter) = halfWidthOuter / screenToLensDistance      (each clamped to
tan(fovInner) = halfWidthInner / screenToLensDistance       profile.maxFov,
tan(fovUp)    = halfHeightUp   / screenToLensDistance       typically 50°)
tan(fovDown)  = halfHeightDown / screenToLensDistance
```

Worked example, Cardboard v2 profile on a 6.3" 20:9 phone: `W_m = 0.1406`, `ILD = 0.064`, `screenToLens = 0.039`.
`lensCentreX = 0.0703 - 0.032 = 0.0383`; viewport centre is `0.0352`; the lens sits **3.1 mm inboard** of the geometric viewport centre. Outer half-width 38.3 mm → 44.5°, inner 32.0 mm → 39.4°. A symmetric 42° frustum would misplace every pixel by ~2.5° horizontally, with opposite sign per eye — i.e. ~5° of false vergence.

The right eye's frustum is the mirror image (`left = -fovInner`, `right = +fovOuter` swapped). `VrMath.perspectiveFromFovAngles(l, r, b, t, near, far)` builds the general off-centre frustum; `near = 0.1`, `far = 100.0`.

### 6.5 Screen geometry (the virtual cinema)

The video is drawn on a **cylindrical section** rather than a flat quad: a flat quad at 4 m subtends a wide angle and its corners are noticeably farther from the eye than its centre, which reads as pincushion "corner stretch" through Cardboard optics.

- Radius `r = 4.0 m` (user adjustable 1.5–12 m, right-stick Y).
- Horizontal extent = `screenWidthDegrees` (default 60°, adjustable 30–110°), vertical extent derived from the media's aspect ratio so the picture is never distorted.
- Tessellation 48×24 quads — enough that the cylinder reads as smooth and cheap enough to be irrelevant (2 × 2304 triangles).
- Anchoring: **world-locked in yaw, gravity-locked in pitch/roll.** The screen sits at the recentre yaw and never follows the head. Head-locked video is nauseating; this is the single most important comfort decision in the app.
- Around the screen: a very dark (not black) gradient "room" — a large inverted sphere with a subtle vignette and a floor grid at 1% luminance. Pure black voids destroy the sense of depth and make the screen edges shimmer; a faint environment stabilises the vestibular impression at negligible cost (one 12-triangle skybox draw).

### 6.6 Lens distortion correction

Rendering straight to the backbuffer looks acceptable in cheap viewers and *wrong* in good ones (straight lines bow inward). We support both, gated by `DeviceProfile.distortionEnabled`.

- Per eye, the scene renders into an offscreen FBO at `renderScale × viewportSize` (default 1.15× supersample; RGBA8 colour, DEPTH24 renderbuffer, 4× MSAA when `GL_MAX_SAMPLES ≥ 4` and thermals allow).
- The FBO is resolved onto a **distortion mesh** (40×40 grid per eye) whose vertex positions are display-space and whose texture coordinates are pre-warped by the *inverse* of the lens's radial distortion, so the optics undo the warp:

```
r'  = r · (1 + k1·r² + k2·r⁴)          // Brown–Conrady, even terms only
```

with `r` measured **from the lens optical centre** (§6.4), not the viewport centre. Cardboard v2 reference coefficients: `k1 = 0.34, k2 = 0.55`. Daydream View (2016/2017) profiles ship separate values.

- Optional chromatic aberration correction samples the colour buffer three times with per-channel radial scale factors (`1.0 / 0.9965 / 1.0045` style, from the profile). Off by default; it triples texture bandwidth in the resolve pass for a benefit most users will not notice in dim video content.
- The distortion mesh is computed once on profile change (a few ms of CPU) and uploaded to a static VBO.

### 6.7 Device profiles

```kotlin
data class DeviceProfile(
    val id: String,                     // "cardboard_v2", "daydream_view_2017", "custom"
    val displayName: String,
    val interLensDistanceM: Float,      // 0.064
    val screenToLensDistanceM: Float,   // 0.039
    val trayToLensHeightM: Float,       // 0.035
    val maxFovDegrees: FovAngles,       // outer, inner, up, down clamps
    val distortionK: FloatArray,        // [k1, k2]
    val chromaticScale: FloatArray?,    // null = disabled
    val dividerPx: Int = 8,
    val neckModelM: FloatArray = floatArrayOf(0f, -0.075f, 0.080f),
)
```

Built-ins: Cardboard v1, Cardboard v2, Daydream View 2016, Daydream View 2017, "Generic 100° shell". Users pick from the in-VR settings list and fine-tune ILD/screen-distance/k1/k2 with the sticks while wearing the headset, watching a calibration grid — which is exactly the situation where the correct values are obvious and where a touch UI would be useless. Values persist per profile id. (Scanning the Cardboard QR profile URI is deliberately out of scope: it requires the camera, which is covered by the headset.)

---

## 7. Head Tracking (3DOF)

### 7.1 Sensor selection

Priority order, first available wins:

1. `Sensor.TYPE_GAME_ROTATION_VECTOR` — **preferred.** Gyro + accelerometer fusion with *no magnetometer*. Yaw is arbitrary and drifts slowly, which is irrelevant to us because yaw is recentred on demand (§7.3) — and in exchange we never get the magnetometer's sudden 20° yaw snaps when the user walks past a speaker magnet or the phone's own vibration motor fires. For a seated media player this is strictly better than the compass-locked variant.
2. `Sensor.TYPE_ROTATION_VECTOR` — fallback when GAME is absent.
3. `TYPE_ACCELEROMETER` + `TYPE_MAGNETIC_FIELD` + `getRotationMatrix` — last-ditch fallback; jittery, we apply a stronger low-pass and warn the user once.

Registration: `registerListener(listener, sensor, 5_000 /* µs → 200 Hz */, maxReportLatencyUs = 0, sensorHandler)`. Batching latency is explicitly zero — batching adds exactly the latency we are trying to remove.

### 7.2 From `SensorEvent` to `HeadPose`

```kotlin
override fun onSensorChanged(e: SensorEvent) {
    // values may be length 4 or 5 (some devices omit w or append accuracy)
    val q = if (e.values.size >= 4) quat(e.values[3], e.values[0], e.values[1], e.values[2])
            else deriveW(e.values)               // w = sqrt(max(0, 1 - x² - y² - z²))
    // R_A_D → remap for display rotation → C_W_A → R_W_H
    val rWH = frameConverter.toGlWorld(q, displayRotation)
    ring.push(PoseSample(e.timestamp, rWH, lastGyro))
    latest.set(ring.head)                        // AtomicReference, single writer
}
```

`displayRotation` is cached and refreshed on configuration change / `DisplayListener.onDisplayChanged`, never queried per sensor event (it is a binder call).

### 7.3 Recentre

Pressing **Y** re-aligns the virtual world so the screen is dead ahead.

Only **yaw** is cancelled. Pitch and roll stay in the gravity-referenced frame, because the horizon must remain level with the real world; cancelling roll makes the user tilt their head to level the picture and is a reliable way to induce nausea.

```kotlin
fun recenter() {
    // swing-twist: extract the component of R_W_H about the world up axis (+Y)
    val f = rWH * Vector3(0f, 0f, -1f)             // current gaze direction
    val yaw = atan2(f.x, -f.z)                     // 0 = looking down -Z
    targetRecenter = quatFromAxisAngle(UP, -yaw)   // cancels the current yaw
}

// applied every frame:
recenterQ = slerp(recenterQ, targetRecenter, 1f - exp(-dt / TAU))   // TAU = 0.06 s
poseForRender = recenterQ ⊗ rawPose
```

Two details that matter:

- The recentre is **slewed** over ~150 ms rather than snapped. An instantaneous 90° world rotation is a strong vection trigger; a fast ease-out is not.
- `atan2(f.x, -f.z)` degenerates when the user is looking straight up or down (`f` nearly parallel to `+Y`). If `|f.y| > 0.99`, fall back to the yaw of the head's **+Y** (top-of-skull) axis instead, which is well-conditioned exactly where the gaze axis is not.

An `autoRecenterAfterIdleSeconds` option (default off) recentres slowly when no gamepad input and < 2°/s head motion have been seen for N seconds, to fight GAME_ROTATION_VECTOR yaw drift during long films.

### 7.4 Prediction

Motion-to-photon on a phone is roughly: sensor latency (~5 ms) + our frame (11 ms @90 Hz) + compositor (1–2 frames) + panel scanout. We predict forward to the estimated photon time:

```
predictAheadNs = displayLatencyNs (measured/profiled, default 32 ms) + frameIntervalNs
q_predicted    = integrateGyro(q_latest, omega_latest, dt = predictAheadNs - (nowNs - sampleNs))
```

`integrateGyro` uses the exponential map of the angular-velocity vector (exact for constant ω) rather than Euler integration. Angular velocity comes from `TYPE_GYROSCOPE` when present, otherwise from finite differences of successive fused quaternions. Prediction is clamped to 50 ms; over-prediction produces overshoot that is worse than latency. There is a developer toggle to disable prediction for A/B comparison.

### 7.5 Lifecycle

`onResume` registers sensors and marks pose invalid until the first sample (render holds the last known pose, or identity on cold start). `onPause` unregisters immediately — a background app polling a 200 Hz sensor is a battery bug. The recentre quaternion persists across pause/resume within a session; on cold start it is identity.

---

## 8. Gamepad Input

### 8.1 The problem

Android's HID mapping is *nearly* consistent and the exceptions all matter. Xbox-family pads report `BUTTON_A/B/X/Y` and `AXIS_LTRIGGER/AXIS_RTRIGGER`. DualShock 4 reports its triggers on `AXIS_RX/AXIS_RY` and its right stick on `AXIS_Z/AXIS_RZ`. DualSense on Android 12+ maps Cross→`BUTTON_A`, Circle→`BUTTON_B`. 8BitDo pads in Switch mode swap A/B and X/Y relative to their Xbox mode. Some pads emit `KEYCODE_BACK` for B, some emit `BUTTON_B`, some emit both.

So: no hardcoded axis constants outside the resolver.

### 8.2 Capability discovery

On `InputManager.InputDeviceListener.onInputDeviceAdded` (and for all devices at startup):

```kotlin
fun isGamepad(d: InputDevice) =
    (d.sources and SOURCE_GAMEPAD == SOURCE_GAMEPAD) ||
    (d.sources and SOURCE_JOYSTICK == SOURCE_JOYSTICK)
```

`GamepadProfileResolver` then inspects `device.motionRanges` to build an `AxisMap`:

- Right stick = the first present pair among `(AXIS_Z, AXIS_RZ)`, `(AXIS_RX, AXIS_RY)`.
- Triggers = `(AXIS_LTRIGGER, AXIS_RTRIGGER)` if present; else `(AXIS_BRAKE, AXIS_GAS)`; else whichever of `(AXIS_RX, AXIS_RY)` was *not* claimed by the right stick and has range `[0,1]`.
- D-pad = `KEYCODE_DPAD_*` if `device.hasKeys(...)` says so, plus `AXIS_HAT_X/AXIS_HAT_Y` always (many pads report hats only as axes).
- Deadzone per axis from `MotionRange.flat`, floored at 0.15; saturation from `MotionRange.max`.

The resolved profile is keyed by `device.descriptor` (stable across reconnects, unlike `id`) and persisted, so a user's one-off remap survives a Bluetooth reconnect. A **"Press the button in the top-right position"** in-VR calibration flow (§11.4) writes an override profile for exotic pads; it is the only reliable answer to the Switch-mode swap problem.

### 8.3 Event plumbing

We intercept at the Activity level to bypass the View focus system entirely — there are no focusable Views in the VR activity, and relying on focus is how you get a dead controller after a dialog:

```kotlin
override fun dispatchKeyEvent(e: KeyEvent): Boolean =
    if (gamepad.handleKey(e)) true else super.dispatchKeyEvent(e)

override fun onGenericMotionEvent(e: MotionEvent): Boolean =
    if (e.isFromSource(SOURCE_JOYSTICK) && e.action == ACTION_MOVE) gamepad.handleMotion(e)
    else super.onGenericMotionEvent(e)
```

`GamepadDecoder` converts raw events into a stream of `InputAction`s and owns three pieces of state:

1. **Auto-repeat.** Stick and hat navigation produce discrete `Nav` actions: fire immediately on crossing the deadzone, then after a 400 ms delay repeat at 120 ms, accelerating to 60 ms after 1.5 s of continuous hold. Implemented with a coroutine on Main, cancelled on release. `KeyEvent` auto-repeat from the OS is *ignored* (`e.repeatCount > 0` → drop) so both input paths behave identically.
2. **Analog scrub.** Trigger pressure is not a button: `handleMotion` publishes a continuous `AnalogAxis(id, value)` and the player's scrub controller integrates it (`seekRate = sign · maxRate · pressure²`, `maxRate = 120 s/s`), so a light pull nudges and a full pull sweeps. Squaring gives fine control near zero.
3. **Long-press.** `Confirm`/`Cancel` distinguish short (< 500 ms) and long presses, used for "B = back" vs "B held = exit player".

`MotionEvent` history is consumed (`historySize` loop) so fast stick flicks are not aliased away.

### 8.4 Default mapping

| Physical | Android identifier | Browser context | Player context |
|---|---|---|---|
| D-pad ↑ / ↓ | `KEYCODE_DPAD_UP/DOWN`, `AXIS_HAT_Y` | Move focus in list | HUD focus / volume when HUD closed |
| D-pad ← / → | `KEYCODE_DPAD_LEFT/RIGHT`, `AXIS_HAT_X` | Collapse / expand, breadcrumb | Seek ±30 s |
| Left stick | `AXIS_X` / `AXIS_Y` | Move focus (auto-repeat) | Same as D-pad |
| Right stick Y | resolved pair, Y | Panel distance | **Screen zoom** (angular size 30–110°) |
| Right stick X | resolved pair, X | — | Screen distance 1.5–12 m |
| **A** | `KEYCODE_BUTTON_A` | **Select** — open folder / play file | Show HUD; confirm focused HUD control |
| **B** | `KEYCODE_BUTTON_B`, `KEYCODE_BACK` | **Back** one level (root → server list) | Hide HUD; long-press → exit to browser |
| **X** | `KEYCODE_BUTTON_X` | Play folder as queue | **Play / Pause** |
| **Y** | `KEYCODE_BUTTON_Y` | **Recenter** | **Recenter** |
| L1 / R1 | `KEYCODE_BUTTON_L1/R1` | Page up / page down | **Seek −10 s / +10 s** |
| L2 / R2 | resolved trigger axes | Jump to prev/next letter | **Variable-rate scrub** (analog) |
| L3 | `KEYCODE_BUTTON_THUMBL` | — | Modifier: triggers become zoom in/out |
| R3 | `KEYCODE_BUTTON_THUMBR` | Toggle grid/list | Cycle projection mode (flat/SBS/TB/180/360) |
| Start | `KEYCODE_BUTTON_START` | Settings | Toggle HUD pinned |
| Select/Back | `KEYCODE_BUTTON_SELECT` | Server list | Audio / subtitle track menu |
| — | `KEYCODE_MEDIA_PLAY_PAUSE` etc. | — | Honoured (headset buttons, media remotes) |

Every mapping is a row in `InputBindings`, a data class persisted as JSON, so remapping is a data change and never a code change.

### 8.5 Connection loss

A gamepad that disconnects mid-film leaves the user blind and unable to act. On `onInputDeviceRemoved` with no other gamepad present: pause playback, show a large, high-contrast in-VR panel ("Controller disconnected — reconnect, or remove the headset to continue"), and keep the screen on. Reconnect resumes automatically. Playback is *not* resumed silently while the user is fumbling.

---

## 9. UPnP / DLNA Control Point

Implemented from scratch in the `upnp` module. See §19.3 for why not Cling/jUPnP.

### 9.1 Public surface

```kotlin
interface MediaServerDirectory {
    val servers: StateFlow<List<MediaServer>>
    suspend fun discover(timeout: Duration = 5.seconds)      // SSDP M-SEARCH
    suspend fun addManual(hostPort: String): Result<MediaServer>   // "192.168.50.10:49152"
    suspend fun refresh(server: MediaServer): Result<MediaServer>
}

interface ContentDirectoryClient {
    suspend fun browse(
        server: MediaServer,
        objectId: String = "0",
        page: PageRequest = PageRequest(start = 0, count = 200),
    ): Result<BrowseResult>          // containers + items + totalMatches
    suspend fun search(server: MediaServer, containerId: String, query: String): Result<BrowseResult>
}

data class MediaServer(
    val udn: String, val friendlyName: String, val manufacturer: String?,
    val descriptionUrl: URI, val controlUrl: URI, val searchCapabilities: Set<String>,
    val iconUrl: URI?, val lastSeenEpochMs: Long,
)

sealed interface DidlObject { val id: String; val parentId: String; val title: String }
data class DidlContainer(..., val childCount: Int?) : DidlObject
data class DidlItem(..., val resources: List<Resource>, val durationMs: Long?,
                    val mimeType: String?, val resolution: Size?, val albumArtUri: URI?) : DidlObject
data class Resource(val uri: URI, val protocolInfo: String, val sizeBytes: Long?,
                    val durationMs: Long?, val resolution: Size?, val bitrate: Int?)
```

### 9.2 SSDP discovery

Multicast on Android is a minefield; all four of these are required:

1. **Multicast lock.** `WifiManager.createMulticastLock("vrplayer-ssdp").apply { setReferenceCounted(true); acquire() }` — without it the Wi-Fi chip filters multicast frames and discovery silently returns nothing. Permission: `CHANGE_WIFI_MULTICAST_STATE`. Released as soon as discovery finishes (it costs real battery).
2. **Network binding.** On a phone with mobile data up, an unbound `DatagramSocket` may egress via cellular. We request the Wi-Fi network and bind explicitly:
   `connectivityManager.requestNetwork(NetworkRequest.Builder().addTransportType(TRANSPORT_WIFI).build(), cb)` then `network.bindSocket(socket)`. This is done in `app` and handed to `upnp` as a `SocketFactory`-ish `DatagramChannelProvider`, keeping `upnp` android-free.
3. **Interface selection.** Bind the socket to the Wi-Fi interface's address and set `IP_MULTICAST_IF`; join `239.255.255.250` on that specific `NetworkInterface`.
4. **Repetition.** UDP multicast is lossy. Send the M-SEARCH three times at 0 ms / 250 ms / 750 ms, and keep listening for the full timeout window.

Request (CRLF-terminated, trailing blank line, exactly as below — some servers are strict):

```
M-SEARCH * HTTP/1.1
HOST: 239.255.255.250:1900
MAN: "ssdp:discover"
MX: 3
ST: urn:schemas-upnp-org:device:MediaServer:1
USER-AGENT: Android/16 UPnP/1.0 DaydreamVrPlayer/1.0
```

We issue two search types: the `MediaServer:1` device type above, and `urn:schemas-upnp-org:service:ContentDirectory:1`, because a few servers answer only one. `ssdp:all` is *not* used (it floods the network and the phone with hundreds of responses from every TV, printer, and light bulb).

Responses are parsed for `LOCATION`, `USN` (→ UDN), `ST`, `SERVER`, `CACHE-CONTROL: max-age`. We additionally bind a passive listener to port 1900 and accept `NOTIFY * ssdp:alive` / `ssdp:byebye` while the browser screen is open, so servers that boot after us appear without a manual refresh. Deduplication is by UDN; a server seen on multiple interfaces keeps the first reachable `LOCATION`.

### 9.3 Device description

`GET <LOCATION>` → XML. We extract `friendlyName`, `UDN`, `manufacturer`, icon list, and the `<service>` whose `<serviceType>` is `...:ContentDirectory:1` (or `:2`, `:3`, `:4` — accept any version), then resolve its `<controlURL>` **against `<URLBase>` if present, else against the LOCATION URL** (`URI.resolve`). Getting this resolution wrong is the classic "works with Gerbera, 404s on Synology" bug: relative `controlURL`s are common and `URLBase` is frequently absent, occasionally wrong, and occasionally present but pointing at a different port.

Optionally `POST GetSearchCapabilities` so we know whether to offer in-VR search.

### 9.4 Browse

SOAP 1.1 over HTTP POST:

```
POST <controlUrl> HTTP/1.1
Content-Type: text/xml; charset="utf-8"
SOAPACTION: "urn:schemas-upnp-org:service:ContentDirectory:1#Browse"

<?xml version="1.0"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
            s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
 <s:Body>
  <u:Browse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
   <ObjectID>0</ObjectID>
   <BrowseFlag>BrowseDirectChildren</BrowseFlag>
   <Filter>*</Filter>
   <StartingIndex>0</StartingIndex>
   <RequestedCount>200</RequestedCount>
   <SortCriteria></SortCriteria>
  </u:Browse>
 </s:Body>
</s:Envelope>
```

Notes that are non-obvious and have bitten every DLNA client ever written:

- The response `<Result>` is a **string containing XML-escaped DIDL-Lite**. Parse the envelope, take the text of `<Result>`, unescape, parse *again*. Some servers (older Plex DLNA) return it un-escaped inside a CDATA section; handle both by sniffing for a leading `<` after trimming.
- Paginate with `StartingIndex`/`RequestedCount` against `<TotalMatches>`; never request 0 (which means "all" to some servers and "none" to others — we always request an explicit 200). A `<TotalMatches>0</TotalMatches>` with a non-empty result is legal and observed; trust `NumberReturned` for the loop, `TotalMatches` only for the scrollbar.
- Sort with `SortCriteria = "+dc:title"` **only if** `GetSortCapabilities` advertises `dc:title`; otherwise sort client-side (natural sort, so `Episode 2` precedes `Episode 10`).
- UPnP faults come back as HTTP 500 with a `<UPnPError><errorCode>` body. Map 701 (no such object) / 720 (cannot process) / 402 (invalid args) to typed `UpnpError`s so the UI can say something better than "failed".

### 9.5 DIDL-Lite parsing and resource selection

`<container>` → `DidlContainer`. `<item>` with `upnp:class` starting `object.item.videoItem` → playable. Each `<res>` carries `protocolInfo` (`http-get:*:video/mp4:DLNA.ORG_PN=...`), `size`, `duration` (`H:MM:SS.mmm`), `resolution` (`1920x1080`), `bitrate`.

Resource selection ranks candidates:

1. Reject non-`http-get` protocols (rtsp, internal).
2. Prefer a MIME type in our known-good set (`video/mp4`, `video/x-matroska`, `video/webm`, `video/mpeg`, `video/avi`).
3. Prefer the highest resolution ≤ the decoder's advertised max (`MediaCodecList` query at startup, cached), then the highest bitrate.
4. Keep the full ranked list: if playback fails on the first, we transparently retry the next (`PlaybackFallbackPolicy`, §10.5).

Thumbnails come from `upnp:albumArtURI` or a `res` with `JPEG_TN` in its `protocolInfo`, fetched lazily by the panel renderer with an LRU bitmap cache sized to ~2 screens of list rows.

### 9.6 Manual fallback

Multicast fails on plenty of real networks (guest VLANs, IGMP snooping without a querier, mesh systems). `addManual("192.168.50.10:49152")`:

1. If the string parses as a full URL ending in `.xml`, fetch it directly.
2. Else probe, in parallel with a 1.5 s budget each: `/description.xml`, `/rootDesc.xml`, `/dev/description.xml`, `/DeviceDescription.xml`, `/upnp/desc.xml`, `/` — covering Gerbera, MiniDLNA, Synology, Plex, Universal Media Server, Jellyfin.
3. First response whose body contains `urn:schemas-upnp-org:device:MediaServer` wins.

Entry happens either on the pre-flight lobby screen (touch keyboard, §11.6) or through the in-VR gamepad keyboard (§11.5). Successful manual servers are persisted and re-probed at startup before SSDP even finishes, so a user with a fixed server IP is browsing within ~300 ms of launch.

---

## 10. Playback Engine

### 10.1 Player construction

```kotlin
ExoPlayer.Builder(context)
    .setRenderersFactory(
        DefaultRenderersFactory(context)
            .setExtensionRendererMode(EXTENSION_RENDERER_MODE_PREFER)   // ffmpeg audio ext
            .setEnableDecoderFallback(true))
    .setMediaSourceFactory(DefaultMediaSourceFactory(okHttpDataSourceFactory))
    .setLoadControl(
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 120_000, 2_500, 5_000)         // LAN: buffer hard
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(30_000, true)                                  // cheap short rewinds
            .build())
    .setSeekParameters(SeekParameters.CLOSEST_SYNC)                       // snappy scrub
    .setWakeMode(C.WAKE_MODE_NETWORK)
    .setHandleAudioBecomingNoisy(true)
    .build()
```

The `media3-decoder-ffmpeg` extension is included specifically for AC3/E-AC3/DTS audio in MKV containers, which is the single most common "video plays, no sound" failure on DLNA libraries. Video decoding is always hardware `MediaCodec`; software video fallback is deliberately disabled (a 1080p software decode both fails to hit frame rate and cooks the phone).

### 10.2 Video → GL bridge

```kotlin
class VideoTexture {                       // vrcore
    val textureId: Int                     // GL_TEXTURE_EXTERNAL_OES, created on GL thread
    val surfaceTexture: SurfaceTexture
    val surface: Surface                   // → player.setVideoSurface(surface)
    private val transform = FloatArray(16)
    fun updateIfDirty(): Boolean           // GL thread: updateTexImage + getTransformMatrix
}
```

The shader samples `samplerExternalOES` and applies the SurfaceTexture transform matrix to the UVs — mandatory, since the buffer may be cropped or Y-flipped in ways that differ per device and per codec. `SurfaceTexture.setDefaultBufferSize` is set from `Player.Listener.onVideoSizeChanged`, and the screen mesh's aspect is rebuilt from `videoSize.width * pixelWidthHeightRatio / height` so anamorphic content is correct.

**Colour:** HDR content on a phone panel through Cardboard optics tone-maps unpredictably; we request `Format` preference for SDR and let Media3 tone-map (`setTonemapHdrToSdr`) where the device supports it. HDR passthrough is a v2 concern.

### 10.3 Projection modes

```kotlin
enum class ProjectionMode { FLAT, SBS_HALF, SBS_FULL, TOPBOTTOM_HALF, TOPBOTTOM_FULL, EQUIRECT_180, EQUIRECT_360 }
```

- Detected heuristically from the filename/title (`SBS`, `HSBS`, `3D`, `Half-OU`, `180`, `360`, `VR`) and from `resolution` aspect ratio (2:1 → likely equirect 360), then confirmable by the user with **R3**, which cycles modes live.
- `FLAT`: both eyes sample the whole texture on the cylinder.
- `SBS_*`/`TOPBOTTOM_*`: per-eye UV sub-rect (left eye ← left half, etc.) — a two-float uniform, no extra draw cost.
- `EQUIRECT_*`: swap the cylinder mesh for a UV sphere (inside-out), camera at the centre, screen-size/distance controls disabled. The recentre anchor still applies to yaw.

Mode is remembered per media item so a user's 3D library "just works" the second time.

### 10.4 Transport, seeking, and resume

- Scrub uses a **preview-then-commit** model: while a trigger is held, the HUD timeline moves and shows the target timestamp, but `seekTo` is throttled to 250 ms intervals with `CLOSEST_SYNC`; on release, one final `seekTo` with `EXACT` lands the frame. This keeps the decoder from thrashing during a 10-minute sweep.
- Resume positions are written every 10 s and on pause/stop, keyed by `(serverUdn, objectId)`, holding `positionMs`, `durationMs`, `finishedAt`. Items > 95% watched are marked finished and offered "start over".
- Playback speed (0.75/1.0/1.25/1.5) lives in the HUD menu; audio pitch correction on.

### 10.5 Error handling and fallback

`Player.Listener.onPlayerError` maps `PlaybackException.errorCode` to a `PlaybackFailure`:

| Cause | Action |
|---|---|
| `ERROR_CODE_IO_NETWORK_CONNECTION_FAILED/TIMEOUT` | 3 retries with exponential backoff (1 s, 3 s, 7 s) at the last known position, then an in-VR "Lost connection to *server*" panel with Retry / Back. |
| `ERROR_CODE_IO_BAD_HTTP_STATUS` (404/410) | Re-`Browse` the parent container (the server may have re-indexed and changed the resource URL), retry once with the fresh URL. |
| `ERROR_CODE_DECODING_FORMAT_UNSUPPORTED`, `ERROR_CODE_DECODER_INIT_FAILED` | Try the next-ranked `Resource` (§9.5). If none, show "This file's video codec isn't supported by this phone" with the codec string. |
| `ERROR_CODE_AUDIO_TRACK_INIT_FAILED` | Retry with the ffmpeg extension audio renderer forced, then offer "play without audio". |

Every one of these surfaces **inside the headset**. A silent black screen with a logcat entry is a product failure: the user cannot see logcat and cannot reach the screen.

---

## 11. In-Headset UI

### 11.1 Why not Compose / the View system

Rendering the Android View hierarchy into a texture requires either a virtual `Display` + `Presentation` (fragile, adds a compositor hop, input routing is a nightmare) or `SurfaceControlViewHost` (API 30+, still awkward off-display). Both drag in the Android focus/accessibility system, which we then have to fight because our navigation is our own state machine. Neither buys us anything: our UI is a list, a breadcrumb, and a HUD.

Instead: **an immediate-mode toolkit drawing to a hardware `Canvas`, straight into a GL texture.**

### 11.2 `PanelSurface`

```kotlin
class PanelSurface(widthPx: Int, heightPx: Int) {           // vrcore
    val textureId: Int                                       // GL_TEXTURE_EXTERNAL_OES
    fun draw(block: (Canvas) -> Unit)                        // PanelDrawThread
    fun updateIfDirty(): Boolean                             // GL thread
}
```

`SurfaceTexture(texId)` → `Surface(surfaceTexture)` → `surface.lockHardwareCanvas()` → draw → `unlockCanvasAndPost()` → GL thread `updateTexImage()`. This gives us a GPU-accelerated `Canvas` (full `Paint`, text shaping, `StaticLayout`, shaders, bitmaps) writing into a texture we can sample, with no View system and no per-frame CPU cost when nothing changed.

*Known caveat:* on a handful of devices/drivers `lockHardwareCanvas` on a `SurfaceTexture`-backed `Surface` misbehaves (blank or torn output). `PanelSurface` therefore has a compile-time-selectable fallback path: draw into a `Bitmap`, upload with `glTexSubImage2D` into a `GL_TEXTURE_2D`, dirty-rect limited. A one-time startup self-test (draw a known pattern, `glReadPixels` 4 texels from the panel FBO, compare) picks the path automatically and caches the result.

Panels are 1024×1024 or 1024×512, mip-mapped (text at 3 m through Cardboard lenses aliases badly without mips), sampled with `GL_LINEAR_MIPMAP_LINEAR` and anisotropy 4× where `GL_EXT_texture_filter_anisotropic` exists.

### 11.3 Layout, legibility, and comfort rules

These are constraints, not suggestions — the effective resolution per eye through the lenses is far lower than the panel's pixel count.

- **Text height ≥ 1.5° of visual angle.** At the default 2.5 m panel distance that is ≥ 65 mm of virtual height; the toolkit computes `Paint.textSize` from a requested angular size, never from pixels.
- **Content inside ±25° horizontal, ±20° vertical** of the recentre direction. The periphery of a Cardboard viewer is blurred and heavily distorted; anything important out there is unreadable.
- **Contrast:** light text (#E8E8EA) on a dark translucent panel (#0A0A0Cdd). Avoid saturated blue for text (chromatic aberration is worst at the blue end) and avoid pure white on black (bloom through cheap lenses).
- **Never draw across the centre divider**, and keep panels within the stereo-overlap region so both eyes see all of every panel.
- **Panels are world-locked** at the recentre yaw with **lazy-follow**: if the head yaw exceeds 35° from the panel's anchor, the panel eases toward the new yaw with a 0.5 s time constant. Fully head-locked UI is uncomfortable; fully world-locked UI gets lost when the user shifts in their seat. Lazy-follow is the standard compromise.
- **No fades between screens** longer than 150 ms, no motion of the whole world other than the slewed recentre.

### 11.4 Screens

| Screen | Contents |
|---|---|
| `ServerList` | Discovered servers (name, manufacturer, IP), spinner while SSDP runs, "Add server manually" row, "Retry discovery" row. |
| `Browse` | Breadcrumb strip at top (`Gerbera › Video › Films`), 8-row scrolling list with type icons, thumbnail, title, duration, watched-progress bar, and a right-hand detail card for the focused item (resolution, size, codec, resume position). Page indicator; L2/R2 alpha-jump. |
| `Player` | The cinema screen; HUD hidden by default. HUD = a bottom panel with timeline, elapsed/remaining, play state, volume, and a row of focusable controls (audio track, subtitle track, speed, projection mode, screen size). Auto-hides after 4 s of no input. A small always-visible corner glyph shows buffering state. |
| `Settings` | Device profile picker, live calibration grid (IPD, screen-to-lens, k1/k2, divider width), prediction toggle, neck model toggle, auto-recentre, gamepad remap/calibration, "forget servers". |
| `Keyboard` | §11.5. |
| `Error` / `Toast` | Full panel for blocking errors; a 2 s transient strip for non-blocking ones ("Server unreachable, showing cached listing"). |

### 11.5 In-VR text entry

Needed for manual server IPs and search. A 10×4 character grid, D-pad/stick to move, A to type, B to delete, X for space, Y to toggle numeric/alpha, R1 to submit. Row/column wrap-around, held-direction auto-repeat, and — because IP entry is the dominant use case — a numeric-first layout with `.` and `:` on the home row plus autocompletion from the phone's own subnet (`192.168.50.` prefilled from the Wi-Fi interface address). Typing a full IP takes ~15 button presses.

### 11.6 Pre-flight "lobby" (the one 2D screen)

`SetupActivity` is a normal touch Activity shown on launch *before* the user puts the phone in the headset. It shows: gamepad connection status, discovered servers, a text field for a manual server, headset profile selection, and one big **"Enter VR"** button. It exists because typing an IP with a thumb takes 4 seconds and typing it with a gamepad takes 30. It is skippable — `Enter VR` is auto-focused and any gamepad `A` press activates it, and a "skip this screen next time" preference launches straight into `VrActivity`. Everything on the lobby screen is *also* reachable in VR; nothing is lobby-only.

---

## 12. Application State & Navigation

```kotlin
data class AppState(
    val screen: VrScreen,
    val servers: List<MediaServer>,
    val discovery: DiscoveryState,           // Idle | Running(startedAt) | Failed(reason)
    val browse: BrowseState,                 // stack of (container, items, focusIndex, scrollTop)
    val playback: PlaybackState,             // item, positionMs, durationMs, isPlaying, buffering, tracks
    val hud: HudState,                       // visible, focusIndex, autoHideAt
    val overlay: Overlay?,                   // Error | Toast | Keyboard | ConfirmDialog
    val settings: Settings,
)

sealed interface VrScreen { object ServerList; object Browse; object Player; object Settings }

sealed interface Event {          // from gamepad, network, player, sensors
    data class Input(val action: InputAction) : Event
    data class ServersChanged(val list: List<MediaServer>) : Event
    data class BrowseLoaded(val objectId: String, val result: BrowseResult) : Event
    data class PlayerStateChanged(val snapshot: PlaybackSnapshot) : Event
    data class Failure(val error: AppError) : Event
}

sealed interface Effect {         // executed off the reducer
    data class Browse(val server: MediaServer, val objectId: String, val page: PageRequest) : Effect
    data class Play(val item: DidlItem, val resource: Resource, val startAtMs: Long) : Effect
    data class Seek(val toMs: Long, val exact: Boolean) : Effect
    object StartDiscovery : Effect
    data class Persist(val key: String, val value: String) : Effect
    object Recenter : Effect
}
```

`AppStateMachine.reduce(state, event): Pair<AppState, List<Effect>>` is a **pure function** — no Android, no IO, no clock (time is passed in as an `Event`/parameter). This is the design's main testability lever: the entire navigation model, including "B at the browse root goes to the server list", "B in the player hides the HUD but a long B exits", and "focus index clamps when a page loads short", is covered by fast JVM tests.

The browse stack holds the full path so `B` is always a pop, and each entry keeps its own `focusIndex`/`scrollTop` — returning from a folder puts the cursor exactly where it was, which matters enormously when the user is 40 episodes deep and cannot see a mouse.

---

## 13. Persistence

`DataStore<Preferences>` with `kotlinx.serialization` JSON values:

| Key | Value |
|---|---|
| `servers.known` | `List<PersistedServer>` (udn, friendlyName, descriptionUrl, manual, lastSeen) |
| `resume.positions` | `Map<"udn|objectId", ResumeEntry>` — LRU-capped at 500 entries |
| `settings.device_profile` | profile id + user overrides (ipd, k1, k2, screenToLens, divider) |
| `settings.input_bindings` | `Map<descriptor, InputBindings>` |
| `settings.playback` | preferred audio language, subtitle language, speed, projection overrides per item |

Room is not used: there are no queries, no joins, no migrations worth the ceremony, and everything fits in a few KB read once at startup into memory. If a future "watch history / continue watching" feature needs indexed queries over thousands of rows, that is the moment to introduce Room — not before.

---

## 14. Performance, Thermal, and Power

| Concern | Mitigation |
|---|---|
| Frame rate | `preferredDisplayModeId` selects the highest refresh mode the panel supports at native resolution; `Surface.setFrameRate(videoFps, FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)` on the video surface helps the compositor. Target 90 Hz, hard floor 60. |
| Frame budget | A per-frame `FrameTimer` (GPU timer queries where available) logs p50/p95/p99 to an in-VR developer overlay. Budget: ≤ 6 ms CPU + ≤ 8 ms GPU at 90 Hz. |
| Allocation | Zero allocation in `onDrawFrame`. Enforced in Phase 6 with an allocation-tracking instrumented test (`Debug.startAllocCounting` over 600 frames, assert delta ≈ 0). |
| Overdraw | Video screen is opaque and drawn first; panels are small; the environment sphere is drawn last with depth test (not a full-screen clear-cost skybox). |
| Thermals | `PowerManager.addThermalStatusListener`: at `THERMAL_STATUS_MODERATE` drop `renderScale` 1.15 → 1.0 and disable MSAA; at `SEVERE` drop to 0.85 and disable chromatic correction; at `CRITICAL` show an in-VR warning and pause. `setSustainedPerformanceMode(true)` on the window at start — a lower, *flat* clock is strictly better than a high clock that collapses 8 minutes in. |
| Battery | Screen at full brightness in a headset is the dominant draw. `FLAG_KEEP_SCREEN_ON`, and a "dim UI when video is playing" option (the HUD is the only bright element). Estimated ~2.5 h of playback on a full charge; the lobby screen warns below 30%. |
| Wi-Fi | `WifiManager.WIFI_MODE_FULL_HIGH_PERF` lock during playback prevents the power-save duty cycling that causes periodic rebuffering on 1080p streams. |
| Memory | Thumbnail LRU capped at 24 MB; panel textures ~12 MB total; video decoder buffers dominate and are Media3's to manage. |

---

## 15. Resilience

- **Every network call** goes through a `Result`-returning boundary with typed errors; nothing throws across a module edge.
- **Cached listings.** The last successful `BrowseResult` per container is cached in memory (and the last 20 to disk), so a flaky server still lets the user navigate and retry rather than dumping them at a dead end.
- **The user is blind and one-handed.** Any state that can strand the user must have a gamepad-reachable escape. Rule: *every* screen and overlay handles `B` (and a 2 s long-press of `B` from anywhere returns to the server list). This is asserted by a state-machine test that walks every reachable state and checks `B` produces a transition.
- **Watchdog.** If `onDrawFrame` has not run for 2 s (GL thread wedged, common on surface loss), the Activity recreates the `GLSurfaceView` and restores state from `AppState` — which is why `AppState` holds no GL objects.
- **Surface loss / app switch.** `onPause` pauses playback and unregisters sensors; `onResume` restores position from `PlaybackState`. GL resources are recreated in `onSurfaceCreated`, treated as always-lost.

---

## 16. Security & Privacy

- **Permissions:** `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`. Notably *not* required: location (SSDP multicast does not need it — that restriction covers Wi-Fi *scanning*), camera, microphone, storage.
- **Cleartext HTTP is required** — DLNA has no TLS. `res/xml/network_security_config.xml` sets `cleartextTrafficPermitted="true"` in `base-config`. This cannot be scoped to a subnet: `domain-config` accepts hostnames, not CIDR ranges. The honest statement of risk: on a hostile LAN, media traffic and Browse requests are readable and injectable. We reduce blast radius by (a) never executing anything we download, (b) treating all XML as untrusted (§16.1), (c) never sending credentials, and (d) an optional "block non-private IPs" guard that refuses to stream from anything outside RFC1918/CGNAT ranges, on by default — this also prevents a malicious `LOCATION` from turning us into an SSRF proxy.
- **XML hardening (§16.1):** the parser is configured with `FEATURE_SECURE_PROCESSING`, external general/parameter entities disabled, DOCTYPE declarations rejected outright. This closes XXE and billion-laughs from a hostile or compromised MediaServer, which is the most realistic attack surface in the whole app. Response bodies are size-capped (2 MB for descriptions, 8 MB for Browse results) and parse time is bounded.
- **No analytics, no crash reporting SDK, no ads.** Diagnostics stay in an in-app ring buffer that the user can export as a file.
- **Exported components:** none except the launcher Activity. No content providers, no exported services, no deep links.

---

## 17. Testing Strategy

| Layer | Tooling | What is actually verified |
|---|---|---|
| Protocol (`upnp`) | JUnit + **MockWebServer** + golden XML captures from real servers (Gerbera, MiniDLNA, Synology, Plex, Jellyfin) checked into `upnp/src/test/resources/didl/` | Description parsing, `URLBase` resolution, escaped-DIDL double-parse, pagination, sort fallback, UPnP fault mapping, resource ranking, XXE rejection. |
| SSDP | JUnit with a loopback multicast harness (`DatagramChannelProvider` is injectable) | M-SEARCH formatting, retry timing, dedupe by UDN, `byebye` removal, timeout behaviour. |
| Math (`vrcore`) | Pure JUnit, property-based where cheap | Quaternion↔matrix round-trip, remap correctness for all four display rotations, yaw extraction incl. the gimbal-adjacent case, recentre invariant (*after recentre, the gaze vector projected onto the XZ plane is −Z within 0.5°*), asymmetric frustum against hand-computed reference matrices, gyro integration vs. analytic rotation. |
| State machine | Pure JUnit + Turbine | Every navigation path; "B always escapes" exhaustive walk; focus clamping; HUD auto-hide timing (clock injected). |
| Input | JUnit over synthetic `KeyEvent`/`MotionEvent` builders + a fake `InputDevice` capability set | Axis resolution for Xbox/DS4/DualSense/8BitDo capability fixtures, deadzone, auto-repeat cadence, analog scrub curve, long-press discrimination. |
| Rendering | Instrumented (`androidTest`) + `glReadPixels` golden images with per-pixel tolerance | Two viewports exist and are non-identical; the divider column is black; a known object at world-forward lands at the expected pixel in both eyes with the expected disparity; distortion mesh round-trips a grid within tolerance. |
| Playback | Instrumented against a local `MockWebServer` serving a 5 s test MP4/MKV from assets | Prepare→play→seek→pause→resume; resume-position persistence; fallback to the second `Resource` when the first 404s. |
| End-to-end | Instrumented, gamepad events injected via `Instrumentation.sendKeySync` / `UiAutomation.injectInputEvent` against a fake in-process MediaServer | Cold start → discover → browse two levels → play → seek → back → back, entirely through synthetic gamepad input. This is the test that proves G1. |

Manual test matrix (documented in `docs/TESTING.md`, produced in Phase 6): each supported viewer profile × {Xbox, DualSense, 8BitDo} × {Gerbera, Synology, Plex} × {MP4 H.264, MKV H.265+AC3, SBS 3D, 360 equirect}.

---

## 18. Manifest & Build Notes

```xml
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE"/>
<uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE"/>

<uses-feature android:name="android.hardware.sensor.gyroscope" android:required="true"/>
<uses-feature android:name="android.hardware.gamepad" android:required="false"/>
<uses-feature android:glEsVersion="0x00030000" android:required="true"/>

<application android:networkSecurityConfig="@xml/network_security_config"
             android:hardwareAccelerated="true">
  <activity android:name=".SetupActivity" ... android:screenOrientation="portrait"/>
  <activity android:name=".VrActivity"
            android:screenOrientation="landscape"
            android:configChanges="orientation|screenSize|screenLayout|keyboard|keyboardHidden|navigation|uiMode|density"
            android:resizeableActivity="false"
            android:launchMode="singleTask"/>
</application>
```

`VrActivity` additionally: `WindowCompat.setDecorFitsSystemWindows(window, false)` + `WindowInsetsControllerCompat.hide(systemBars())` with `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`, `FLAG_KEEP_SCREEN_ON`, `window.attributes.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` (a notch cutting into an eye's viewport is worse than losing the whole strip — we letterbox around the cutout by shrinking both viewports symmetrically), and predictive-back opt-out (`android:enableOnBackInvokedCallback="false"` for this activity, since `B` is our back and a system back gesture cannot be performed while the phone is in the shell).

R8 full mode on release, with keep rules for Media3 renderer reflection and `kotlinx.serialization` serializers. Debug builds enable `StrictMode` (disk/network on main) and the in-VR developer overlay.

---

## 19. Alternatives Considered

**19.1 Google Cardboard SDK / GVR SDK.** The GVR/Daydream SDK is deprecated and its runtime services are gone from modern devices; building against it in 2026 is a dead end. The open-source Cardboard SDK is alive but pulls a native layer for lens distortion and QR profiles — features we implement in ~400 lines of Kotlin/GLSL (§6.6) with full control over the frame loop. Given that the frame loop is where all our latency and thermal behaviour lives, owning it is worth more than the SDK saves. If distortion quality proves inadequate we can adopt just the Cardboard SDK's distortion mesh generation without changing anything else.

**19.2 Vulkan.** Would allow finer control of the submission pipeline, but on a stock Android phone we still hand frames to SurfaceFlinger like everyone else — there is no front-buffer/direct-mode path to exploit. ES 3.0 costs a fraction of the code and interoperates trivially with `SurfaceTexture`/`GL_TEXTURE_EXTERNAL_OES`, which is exactly the interop we need for video.

**19.3 Cling / jUPnP.** Cling is unmaintained and LGPL; jUPnP is the maintained fork but drags in a full UPnP *stack* — device advertisement, GENA eventing, a servlet-ish HTTP server, its own threading — of which we need approximately 4%. Its Android integration also assumes a service-based lifecycle we do not want. Writing the client is ~700 lines, keeps the module JVM-pure (§3), and — decisively — lets us test against real-server XML captures in milliseconds instead of debugging someone else's stack against a Synology.

**19.4 A single fullscreen "2D screen in VR" using a mirrored View hierarchy.** Simple, and completely fails G1: text becomes unreadable through the lenses because it was laid out for a 400 dpi flat panel at 30 cm, not for 1.5° of visual angle through a plastic lens.

**19.5 Room + full media library index.** Over-scoped for v1 (§13).

---

## 20. Appendix

### 20.1 Reference device profile values

| Profile | ILD (m) | screen→lens (m) | tray→lens (m) | k1 | k2 | max FOV |
|---|---|---|---|---|---|---|
| Cardboard v1 (2014) | 0.060 | 0.042 | 0.035 | 0.441 | 0.156 | 40/40/40/40 |
| Cardboard v2 (2015) | 0.064 | 0.039 | 0.035 | 0.34 | 0.55 | 50/50/50/50 |
| Daydream View (2016) | 0.064 | 0.039 | 0.035 | 0.34 | 0.55 | 50/50/50/50 |
| Daydream View (2017) | 0.064 | 0.040 | 0.035 | 0.36 | 0.42 | 55/55/50/50 |
| Generic 100° shell | 0.063 | 0.045 | 0.035 | 0.30 | 0.30 | 55/55/55/55 |

Values are starting points, not gospel — the in-VR calibration grid exists precisely because shells vary and the user is the only available measuring instrument.

### 20.2 DIDL-Lite sample (abridged, as returned inside `<Result>`)

```xml
<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
           xmlns:dc="http://purl.org/dc/elements/1.1/"
           xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
  <container id="64" parentID="0" childCount="12" restricted="1">
    <dc:title>Films</dc:title>
    <upnp:class>object.container.storageFolder</upnp:class>
  </container>
  <item id="64$3" parentID="64" restricted="1">
    <dc:title>Arrival (2016)</dc:title>
    <upnp:class>object.item.videoItem.movie</upnp:class>
    <res protocolInfo="http-get:*:video/x-matroska:DLNA.ORG_PN=;DLNA.ORG_OP=01"
         size="8589934592" duration="1:56:03.000" resolution="1920x1080" bitrate="1250000"
    >http://192.168.50.10:49152/content/media/object_id/3/res_id/0/ext/file.mkv</res>
  </item>
</DIDL-Lite>
```

### 20.3 Glossary

**3DOF** rotation-only tracking · **DIDL-Lite** the XML metadata dialect UPnP ContentDirectory returns · **IPD** interpupillary distance · **ILD** inter-lens distance of the viewer · **M-SEARCH** the SSDP discovery request · **motion-to-photon** latency from head movement to the corresponding photons leaving the panel · **UDN** a UPnP device's unique identifier · **vection** the illusion of self-motion from visual flow, the primary nausea trigger.
