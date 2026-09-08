# Implementation Plan — Daydream / Cardboard VR UPnP Player

**Reader:** Claude Sonnet 5 (implementing agent).
**Design source of truth:** [ARCHITECTURE.md](ARCHITECTURE.md). Where this plan and that document disagree, the architecture wins — fix the plan and note it in the commit.

---

## How to use this document

Six phases. **Each phase ends with a working, runnable app** — never a half-wired refactor. Do not start phase *N+1* until every acceptance criterion of phase *N* is checked off and the tests are green.

**Ground rules for every phase**

1. **Tests first where they are cheap.** The `upnp` module, the VR math, the input decoder, and the state machine are pure JVM code — write the test alongside the class, not after the phase.
2. **No TODOs left in merged code.** If something is deferred, it goes in this document's phase backlog, not in a comment.
3. **Package roots:** `com.daydreamvr.player` (`:app`), `com.daydreamvr.vrcore` (`:vrcore`), `com.daydreamvr.upnp` (`:upnp`), `com.daydreamvr.playback` (`:playback`).
4. **`:upnp` must not import `android.*`.** A Gradle check enforces this from Phase 1.
5. **Pin the newest stable library versions at implementation time** in `gradle/libs.versions.toml`. Do not copy version numbers out of the docs.
6. **Commit per phase** (or per coherent sub-step), message form: `Phase N: <what>`. Run `./gradlew check` before each commit.
7. **Signatures below are contracts.** Add members freely; do not rename or re-shape what is written here, because later phases call it.
8. **Device reality check.** Phases 2, 4, and 5 have acceptance criteria that can only be verified on a physical phone in a viewer with a real gamepad. Where hardware is unavailable, mark the criterion `UNVERIFIED-ON-DEVICE` in the commit message rather than declaring it passed.

**Definition of "test spec" below:** the named test class must exist with at least the listed cases. More is welcome.

---

## Phase 1 — Skeleton, Stereo Surface, Gamepad Plumbing

**Goal:** an installable app that opens a fullscreen landscape GL surface, renders two independent eye viewports with a black divider, and prints decoded gamepad actions into a stereo debug overlay. No sensors, no network, no video.

### 1.1 Files

```
settings.gradle.kts
build.gradle.kts
gradle/libs.versions.toml
gradle/module-rules.gradle.kts            # fails build if :upnp references android.*
.editorconfig                             # ktlint-compatible
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/res/xml/network_security_config.xml
app/src/main/res/values/strings.xml
app/src/main/java/com/daydreamvr/player/VrActivity.kt
app/src/main/java/com/daydreamvr/player/SetupActivity.kt          # stub: one "Enter VR" button
app/src/main/java/com/daydreamvr/player/di/AppContainer.kt
app/src/main/java/com/daydreamvr/player/debug/DebugOverlay.kt
vrcore/build.gradle.kts
vrcore/src/main/java/com/daydreamvr/vrcore/gl/GlUtils.kt
vrcore/src/main/java/com/daydreamvr/vrcore/gl/Shader.kt
vrcore/src/main/java/com/daydreamvr/vrcore/gl/Mesh.kt
vrcore/src/main/java/com/daydreamvr/vrcore/render/VrRenderer.kt
vrcore/src/main/java/com/daydreamvr/vrcore/render/Eye.kt
vrcore/src/main/java/com/daydreamvr/vrcore/render/StereoLayout.kt
vrcore/src/main/java/com/daydreamvr/vrcore/render/Scene.kt
vrcore/src/main/java/com/daydreamvr/vrcore/profile/DeviceProfile.kt
vrcore/src/main/java/com/daydreamvr/vrcore/profile/DeviceProfiles.kt
vrcore/src/main/java/com/daydreamvr/vrcore/input/InputAction.kt
vrcore/src/main/java/com/daydreamvr/vrcore/input/InputBindings.kt
vrcore/src/main/java/com/daydreamvr/vrcore/input/AxisMap.kt
vrcore/src/main/java/com/daydreamvr/vrcore/input/GamepadProfileResolver.kt
vrcore/src/main/java/com/daydreamvr/vrcore/input/GamepadDecoder.kt
vrcore/src/test/java/com/daydreamvr/vrcore/input/GamepadDecoderTest.kt
vrcore/src/test/java/com/daydreamvr/vrcore/input/GamepadProfileResolverTest.kt
vrcore/src/test/java/com/daydreamvr/vrcore/render/StereoLayoutTest.kt
app/src/androidTest/java/com/daydreamvr/player/StereoSurfaceTest.kt
```

### 1.2 Signatures

```kotlin
// vrcore/render/Eye.kt
enum class Eye { LEFT, RIGHT }

data class Viewport(val x: Int, val y: Int, val width: Int, val height: Int)

data class EyeParams(
    val eye: Eye,
    val viewport: Viewport,
    val fov: FovAngles,            // outer, inner, up, down — degrees
    val eyeOffsetX: Float,         // metres, signed
)

data class FovAngles(val outer: Float, val inner: Float, val up: Float, val down: Float)

// vrcore/render/StereoLayout.kt  — pure, JVM-testable, no GL
object StereoLayout {
    fun layout(
        surfaceWidthPx: Int, surfaceHeightPx: Int,
        displayWidthM: Float, displayHeightM: Float,
        profile: DeviceProfile, ipdM: Float,
        cutoutInsetPx: Int = 0,
    ): Pair<EyeParams, EyeParams>

    fun projectionMatrix(fov: FovAngles, near: Float, far: Float, out: FloatArray)
    fun viewMatrix(headRotation: FloatArray, eyeOffsetX: Float, neckModel: FloatArray?, out: FloatArray)
}

// vrcore/render/VrRenderer.kt
class VrRenderer(
    private val scene: Scene,
    private val profileProvider: () -> DeviceProfile,
    private val poseProvider: () -> FloatArray,       // Phase 1: always identity
) : GLSurfaceView.Renderer {
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?)
    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int)
    override fun onDrawFrame(gl: GL10?)
    var renderScale: Float
    val frameStats: FrameStats                        // p50/p95/p99 frame time, rolling 600 frames
}

// vrcore/render/Scene.kt
interface Scene {
    fun onGlCreate()
    fun onGlResize(width: Int, height: Int)
    fun update(dtSeconds: Float)
    fun draw(eye: EyeParams, viewM: FloatArray, projM: FloatArray)
    fun onGlDestroy()
}

// vrcore/input/InputAction.kt
sealed interface InputAction {
    enum class Dir { UP, DOWN, LEFT, RIGHT }
    data class Nav(val dir: Dir, val repeat: Boolean) : InputAction
    data class Confirm(val long: Boolean) : InputAction
    data class Cancel(val long: Boolean) : InputAction
    object PlayPause : InputAction
    object Recenter : InputAction
    data class Seek(val deltaSeconds: Int) : InputAction          // bumpers
    data class Scrub(val rate: Float) : InputAction               // triggers, [-1,1], 0 = released
    data class Zoom(val delta: Float) : InputAction
    data class ScreenDistance(val delta: Float) : InputAction
    object Menu : InputAction
    object ToggleHud : InputAction
    object CycleProjection : InputAction
    object PageUp : InputAction
    object PageDown : InputAction
}

// vrcore/input/GamepadDecoder.kt
class GamepadDecoder(
    private val bindings: InputBindings,
    private val resolver: GamepadProfileResolver,
    private val clock: () -> Long,                      // nanoTime, injected for tests
    private val emit: (InputAction) -> Unit,
) {
    fun handleKey(event: KeyEvent): Boolean
    fun handleMotion(event: MotionEvent): Boolean
    fun tick()                                          // drives auto-repeat + analog emission
    fun onDeviceAdded(deviceId: Int); fun onDeviceRemoved(deviceId: Int)
    val connectedGamepads: StateFlow<List<String>>      // descriptors
}
```

`GamepadDecoder` must be constructible in a JVM test: it takes `KeyEvent`/`MotionEvent` (use Robolectric for the event objects, or a thin `RawInput` value class the Activity adapts into — **preferred: define `RawKey`/`RawMotion` value types in `vrcore` and keep `KeyEvent` translation in `:app`**, so the decoder tests are pure JVM).

### 1.3 Tests

- `StereoLayoutTest`
  - `layout_splitsSurfaceIntoTwoEqualViewports_withDivider` — for 2400×1080 and `dividerPx = 8`: left `(0,0,1196,1080)`, right `(1204,0,1196,1080)`.
  - `layout_producesAsymmetricFov_withOuterGreaterThanInner` — Cardboard v2 profile, 0.1406 m wide display: `outer ≈ 44.5°`, `inner ≈ 39.4°`, tolerance 0.2°.
  - `layout_mirrorsFovBetweenEyes`.
  - `layout_shrinksBothViewportsSymmetrically_whenCutoutInsetGiven`.
  - `projectionMatrix_matchesReferenceOffCentreFrustum` — compare against a hand-computed 4×4 for `(l,r,b,t,n,f)`.
  - `viewMatrix_appliesEyeOffsetInHeadSpace` — identity head rotation ⇒ right eye view translates world by `-ipd/2` in X.
- `GamepadProfileResolverTest` — capability fixtures for Xbox Series, DualShock 4, DualSense, 8BitDo (Xbox mode) and 8BitDo (Switch mode):
  - right stick resolves to `(Z,RZ)` for Xbox/DualSense, `(Z,RZ)` for DS4, triggers to `(LTRIGGER,RTRIGGER)` / `(RX,RY)` respectively;
  - deadzone falls back to 0.15 when `MotionRange.flat` is 0;
  - profile is keyed by descriptor and survives a simulated id change.
- `GamepadDecoderTest`
  - `stickCrossingDeadzone_emitsNavImmediately_thenRepeatsAfter400msAt120ms` (clock injected, `tick()` driven).
  - `osKeyRepeat_isIgnored` (`repeatCount > 0` produces nothing).
  - `triggerPressure_mapsToSquaredScrubRate_andEmitsZeroOnRelease`.
  - `holdingB_500ms_emitsCancelLongExactlyOnce`.
  - `hatAxisAndDpadKeycode_produceIdenticalNavActions`.
- `StereoSurfaceTest` (androidTest, `glReadPixels`)
  - the 8 px column centred on `width/2` is pure black for all rows;
  - a marker drawn at world-forward appears in both halves at mirrored-but-not-identical x (disparity > 0);
  - `onDrawFrame` runs ≥ 55 times in 1 s on the test device.

### 1.4 Acceptance criteria

- [ ] `./gradlew check` green; ktlint clean; the `:upnp` android-import check runs (module may be empty).
- [ ] App installs, launches to `SetupActivity`, "Enter VR" opens `VrActivity` fullscreen landscape with system bars hidden and the screen staying awake.
- [ ] Two viewports render distinctly (e.g. a test cube at a fixed world position), separated by a black divider, with correct asymmetric frustums.
- [ ] A connected Bluetooth gamepad's every button and axis produces a labelled line in the stereo debug overlay, in both eyes.
- [ ] Rotating the phone does nothing (orientation locked); no ANR, no leaked GL contexts across pause/resume (3 cycles).

---

## Phase 2 — Head Tracking, Recentre, Prediction

**Goal:** the world is stable and gravity-referenced; the head looks around; **Y** recentres smoothly; latency is visibly low.

### 2.1 Files

```
vrcore/src/main/java/com/daydreamvr/vrcore/math/Matrix4.kt            # (was VrMath.kt)
vrcore/src/main/java/com/daydreamvr/vrcore/math/Quaternion.kt
vrcore/src/main/java/com/daydreamvr/vrcore/tracking/HeadPose.kt       # PoseSample, SensorKind
vrcore/src/main/java/com/daydreamvr/vrcore/tracking/FrameConverter.kt
vrcore/src/main/java/com/daydreamvr/vrcore/tracking/RecenterController.kt
vrcore/src/main/java/com/daydreamvr/vrcore/tracking/PosePredictor.kt
vrcore/src/main/java/com/daydreamvr/vrcore/tracking/HeadTracker.kt    # HeadTracker + SensorHeadTracker
vrcore/src/test/java/com/daydreamvr/vrcore/math/QuaternionTest.kt
vrcore/src/test/java/com/daydreamvr/vrcore/tracking/FrameConverterTest.kt
vrcore/src/test/java/com/daydreamvr/vrcore/tracking/RecenterTest.kt
vrcore/src/test/java/com/daydreamvr/vrcore/tracking/PosePredictorTest.kt
```

**Deviations from the original file list (Phase 2 implementation):**

- `math/VrMath.kt` → `math/Matrix4.kt`. Pure column-major 4×4 helpers; the
  quaternion math lives in `Quaternion.kt` and there was nothing left for a
  separate `VrMath`.
- `PoseRingBuffer.kt` dropped. `SensorHeadTracker` keeps the two samples the
  predictor needs (`latest`/`previous` `AtomicReference`s); a full ring buffer
  buys nothing until 6DOF or replay debugging, which are v2.
- `SensorHeadTracker` folded into `HeadTracker.kt` alongside the interface
  (one file, per the Phase 2 task brief).
- `RecenterController.kt` added: the yaw-only recentre + slew filter is pulled
  out of `SensorHeadTracker` so it is unit-testable in pure JVM (`RecenterTest`).
- `render/EnvironmentScene.kt` **deferred to Phase 4** (see backlog). The Phase 1
  `DebugCubeScene` (a cube at a fixed world position) already demonstrates 3DOF
  and gravity-referencing; a lit environment sphere is cosmetic and belongs with
  the cinema screen work.

### 2.5 Phase 2 backlog

- **`SensorKind.ACCEL_MAG` fusion fallback.** `SensorHeadTracker` currently
  selects `GAME_ROTATION_VECTOR` → `ROTATION_VECTOR` → `NONE`. The accelerometer
  + magnetometer last-ditch path (ARCHITECTURE.md §7.1) is not wired; the
  manifest already requires a gyroscope, so every target device has at least
  `ROTATION_VECTOR`. Add when a no-gyro device actually needs supporting.
- **`EnvironmentScene`** (dark room + floor grid), moved to Phase 4 with the
  cinema-screen geometry.
- **Instrumented `SensorHeadTrackerTest`** (cross-check `FrameConverter` against
  the real `SensorManager` calls) still to be written — needs a device/emulator.

### 2.2 Signatures

```kotlin
// math/Quaternion.kt — value semantics, FloatArray(4) = [w,x,y,z]
object Quat {
    fun identity(out: FloatArray)
    fun fromAxisAngle(ax: Float, ay: Float, az: Float, radians: Float, out: FloatArray)
    fun multiply(a: FloatArray, b: FloatArray, out: FloatArray)
    fun conjugate(q: FloatArray, out: FloatArray)
    fun normalize(q: FloatArray)
    fun toMatrix(q: FloatArray, out: FloatArray)          // column-major 4x4
    fun fromMatrix(m: FloatArray, out: FloatArray)
    fun slerp(a: FloatArray, b: FloatArray, t: Float, out: FloatArray)
    fun rotateVector(q: FloatArray, v: FloatArray, out: FloatArray)
    fun twistAbout(q: FloatArray, axis: FloatArray, out: FloatArray)   // swing-twist
    fun integrateGyro(q: FloatArray, omega: FloatArray, dt: Float, out: FloatArray)  // exp map
}

// tracking/HeadPose.kt
data class PoseSample(val timestampNs: Long, val rotation: FloatArray, val omega: FloatArray?)

// tracking/FrameConverter.kt  — pure
class FrameConverter {
    fun sensorToGlWorld(rotationVectorValues: FloatArray, displayRotation: Int, out: FloatArray)
    // internally: getRotationMatrixFromVector → remapCoordinateSystem → C_W_A (Rx(-90°))
    // Provide a pure-Kotlin reimplementation of the two SensorManager calls in `VrMath`
    // so this class is unit-testable off-device; SensorManager is used only as a cross-check
    // in an instrumented test.
}

// tracking/HeadTracker.kt
interface HeadTracker {
    fun start(); fun stop()
    fun recenter()
    fun poseFor(targetTimeNs: Long, outRotationMatrix: FloatArray)
    val isCalibrated: StateFlow<Boolean>
    val sensorKind: SensorKind          // GAME_ROTATION_VECTOR | ROTATION_VECTOR | ACCEL_MAG | NONE
    var predictionEnabled: Boolean
    var autoRecenterIdleSeconds: Int    // 0 = off
}

class SensorHeadTracker(
    context: Context,
    private val displayRotationProvider: () -> Int,
    private val clockNs: () -> Long,
) : HeadTracker
```

Implementation notes the implementer must honour:

- Register at 200 Hz on a dedicated `HandlerThread` at `THREAD_PRIORITY_URGENT_DISPLAY`, `maxReportLatencyUs = 0`.
- Handle `values.size == 3` (derive `w`), 4, and 5.
- Recentre cancels **yaw only**, slewed with `TAU = 0.06 s`; use the top-of-skull axis for yaw extraction when `|gaze.y| > 0.99`.
- Prediction clamped to 50 ms; `PosePredictor` takes ω from `TYPE_GYROSCOPE` when present, else finite differences.
- Cache `displayRotation`; refresh on `DisplayListener.onDisplayChanged` only.

### 2.3 Tests

- `QuaternionTest` — matrix round-trip (1000 random quats, ‖error‖ < 1e-5); `slerp` endpoints and shortest-path (negated-`w` case); `integrateGyro` vs analytic `fromAxisAngle` for constant ω (error < 1e-4 rad over 1 s); `twistAbout` recomposition `twist ⊗ swing == q`.
- `FrameConverterTest` — for each of `ROTATION_0/90/180/270`, feeding a rotation vector representing "phone held upright, back facing north" yields a GL head rotation whose gaze is `−Z` within 0.5°; a 30° yaw right yields a gaze rotated 30° about `+Y`; pitch up produces `+Y` gaze component (sign check — this is the test that catches inverted tracking).
- `RecenterTest` — **invariant:** for 200 random starting orientations, after `recenter()` and settling, the gaze vector projected onto the XZ plane is `−Z` within 0.5°, **and** the pitch and roll components are unchanged from before the recentre (within 1e-3 rad). Also: the gimbal case (gaze within 8° of `±Y`) recentres without NaN and without a > 5° jump.
- `PosePredictorTest` — constant 90°/s yaw, 30 ms ahead ⇒ 2.7° ahead within 0.1°; prediction clamps at 50 ms; stale sample (> 200 ms) falls back to no prediction.
- Instrumented `SensorHeadTrackerTest` — cross-check `FrameConverter` against real `SensorManager.getRotationMatrixFromVector` + `remapCoordinateSystem` for 500 random vectors (max element diff < 1e-5).

### 2.4 Acceptance criteria

- [ ] Looking around moves the world correctly in all axes: turn right → world moves left; look up → world moves down; tilt head → horizon stays level with the *real* world.
- [ ] **Y** recentres in ~150 ms with no snap, and does not alter pitch/roll.
- [ ] Tracking survives 10 minutes without visible pitch/roll drift; yaw drift (GAME_ROTATION_VECTOR) is < 5°/10 min and fully corrected by **Y**.
- [ ] Prediction toggle produces a perceptible latency difference; with it on, quick head shakes show no visible swim/overshoot.
- [ ] Sensors are unregistered in `onPause` (verified with `dumpsys sensorservice`).
- [ ] `UNVERIFIED-ON-DEVICE` is not needed: this phase requires a physical device.

---

## Phase 3 — UPnP / DLNA Control Point (pure JVM)

**Goal:** `:upnp` discovers servers and returns parsed folder listings. No UI yet — a JVM `main`-style test harness and instrumented smoke test prove it.

### 3.1 Files

```
upnp/build.gradle.kts                                   # java-library, NOT com.android.library
upnp/src/main/java/com/daydreamvr/upnp/UpnpLog.kt
upnp/src/main/java/com/daydreamvr/upnp/model/MediaServer.kt
upnp/src/main/java/com/daydreamvr/upnp/model/Didl.kt            # DidlObject/Container/Item/Resource
upnp/src/main/java/com/daydreamvr/upnp/model/BrowseResult.kt
upnp/src/main/java/com/daydreamvr/upnp/model/UpnpError.kt
upnp/src/main/java/com/daydreamvr/upnp/net/DatagramChannelProvider.kt
upnp/src/main/java/com/daydreamvr/upnp/net/SafeXml.kt           # hardened parser factory
upnp/src/main/java/com/daydreamvr/upnp/ssdp/SsdpMessage.kt
upnp/src/main/java/com/daydreamvr/upnp/ssdp/SsdpClient.kt
upnp/src/main/java/com/daydreamvr/upnp/device/DeviceDescriptionParser.kt
upnp/src/main/java/com/daydreamvr/upnp/cds/SoapEnvelope.kt
upnp/src/main/java/com/daydreamvr/upnp/cds/DidlParser.kt
upnp/src/main/java/com/daydreamvr/upnp/cds/ContentDirectoryClientImpl.kt
upnp/src/main/java/com/daydreamvr/upnp/cds/ResourceRanker.kt
upnp/src/main/java/com/daydreamvr/upnp/MediaServerDirectoryImpl.kt
upnp/src/test/resources/didl/{gerbera,minidlna,synology,plex,jellyfin}-*.xml
upnp/src/test/java/com/daydreamvr/upnp/...              # see 3.3
app/src/main/java/com/daydreamvr/player/net/AndroidNetworkBinder.kt   # multicast lock + Wi-Fi bind
```

### 3.2 Signatures

```kotlin
// net/DatagramChannelProvider.kt — the seam that keeps :upnp android-free
interface DatagramChannelProvider {
    /** Opens a socket bound to the LAN interface, joined to the SSDP group, with a MulticastLock held. */
    suspend fun <T> withMulticastSocket(block: suspend (MulticastSocket, NetworkInterface) -> T): T
}

// ssdp/SsdpClient.kt
class SsdpClient(private val channels: DatagramChannelProvider, private val log: UpnpLog) {
    fun search(
        searchTargets: List<String> = DEFAULT_TARGETS,   // MediaServer:1, ContentDirectory:1
        timeout: Duration = 5.seconds,
        mxSeconds: Int = 3,
    ): Flow<SsdpMessage>                                 // emits as responses arrive, dedup by USN
    fun listenForNotifications(): Flow<SsdpMessage>      // alive / byebye while browsing
    companion object { const val GROUP = "239.255.255.250"; const val PORT = 1900 }
}

// device/DeviceDescriptionParser.kt — pure
object DeviceDescriptionParser {
    fun parse(xml: String, locationUrl: URI): Result<MediaServer>
    // resolves controlURL against URLBase if present, else against locationUrl
}

// cds/ContentDirectoryClientImpl.kt
class ContentDirectoryClientImpl(
    private val http: HttpTransport,          // thin interface over OkHttp, injectable
    private val log: UpnpLog,
) : ContentDirectoryClient {
    override suspend fun browse(server: MediaServer, objectId: String, page: PageRequest): Result<BrowseResult>
    override suspend fun search(server: MediaServer, containerId: String, query: String): Result<BrowseResult>
    suspend fun browseAll(server: MediaServer, objectId: String, pageSize: Int = 200): Result<BrowseResult>
}

// cds/DidlParser.kt — pure, the highest-value test target in the project
object DidlParser {
    fun parse(didlXml: String): Result<List<DidlObject>>
    fun unescapeResultPayload(soapResultText: String): String   // handles escaped AND CDATA forms
}

// cds/ResourceRanker.kt — pure
object ResourceRanker {
    fun rank(resources: List<Resource>, caps: DecoderCaps): List<Resource>
}
data class DecoderCaps(val maxWidth: Int, val maxHeight: Int, val mimeTypes: Set<String>)

// MediaServerDirectoryImpl.kt
class MediaServerDirectoryImpl(
    private val ssdp: SsdpClient,
    private val http: HttpTransport,
    private val scope: CoroutineScope,
) : MediaServerDirectory {
    override suspend fun addManual(hostPort: String): Result<MediaServer>
    // probes /description.xml, /rootDesc.xml, /dev/description.xml,
    //        /DeviceDescription.xml, /upnp/desc.xml, /  (parallel, 1.5 s each)
}
```

### 3.3 Tests (all JVM, all fast)

- `DidlParserTest` — for **each** of the five vendor fixtures: container/item counts, titles with entities (`&amp;`, `&#39;`), `upnp:class` filtering (only `object.item.videoItem*` are playable), `duration` in all observed formats (`1:56:03.000`, `0:45:12`, `01:02:03`), `resolution`, `size`, missing-`res` items, multiple `<res>` per item, and namespace-prefixed vs default-namespace documents.
- `DidlParserEscapingTest` — escaped-in-`<Result>` form and CDATA form both parse to identical objects; double-escaped entities (`&amp;amp;`) survive one unescape only.
- `DeviceDescriptionParserTest` — control URL resolution across the four real cases: absolute `controlURL`; relative with `URLBase`; relative without `URLBase`; `URLBase` present but on a different port (the Synology case) — assert the *documented* precedence (URLBase wins when present and absolute).
- `ContentDirectoryClientTest` (MockWebServer) — happy-path Browse; pagination across 3 pages using `NumberReturned` (with `TotalMatches = 0` in the response, asserting we still terminate); HTTP 500 + `<UPnPError><errorCode>701` → `UpnpError.NoSuchObject`; malformed XML → `Result.failure` and no throw; body > 8 MB → `UpnpError.ResponseTooLarge`; correct `SOAPACTION` header and CRLF envelope bytes asserted against a golden request.
- `SafeXmlTest` — a document with a DOCTYPE is rejected; an XXE payload referencing `file:///etc/passwd` does not resolve; a billion-laughs payload fails fast (< 500 ms).
- `SsdpClientTest` — loopback `DatagramChannelProvider` fake: M-SEARCH bytes match the golden request exactly (including the trailing blank line); three sends at 0/250/750 ms (±50 ms, virtual clock); duplicate USNs dedupe; `ssdp:byebye` removes; timeout ends the flow.
- `ResourceRankerTest` — http-get preferred over rtsp; 4K rejected when `DecoderCaps` maxes at 1080p; among equals, higher bitrate wins; the full ranked list is returned, not just the winner.
- `MediaServerDirectoryTest` — manual add finds the server on the 3rd probed path; a host that answers 200 with non-MediaServer XML is rejected; known servers re-probe before SSDP completes.
- Instrumented `UpnpSmokeTest` — against an in-process fake MediaServer (MockWebServer + a fake SSDP responder), asserting the Android `AndroidNetworkBinder` acquires and releases the multicast lock.

### 3.4 Acceptance criteria

- [ ] `:upnp` compiles as a `java-library`; the android-import check passes; the module has **zero** Android dependencies.
- [ ] All five vendor fixtures parse without error; test suite runs in < 5 s.
- [ ] On a real network: discovery finds a live server (Gerbera at `192.168.50.10:49152` in the reference setup) within 5 s, and `browse("0")` returns its root containers — verified with a temporary debug button on `SetupActivity`.
- [ ] Manual `192.168.50.10:49152` works with Wi-Fi multicast deliberately blocked (test by disabling the multicast lock).
- [ ] Multicast lock and the Wi-Fi network binding are both acquired for discovery and released after; no lock leak across 20 discovery cycles.

---

## Phase 4 — Playback Engine and Video-on-Screen

**Goal:** a hardcoded URL plays on a curved screen in stereo, with correct aspect, seeking, and error fallback. Still no browser UI.

### 4.1 Files

```
playback/build.gradle.kts
playback/src/main/java/com/daydreamvr/playback/VideoPlayer.kt
playback/src/main/java/com/daydreamvr/playback/ExoVideoPlayer.kt
playback/src/main/java/com/daydreamvr/playback/PlaybackSnapshot.kt
playback/src/main/java/com/daydreamvr/playback/PlaybackFailure.kt
playback/src/main/java/com/daydreamvr/playback/FallbackPolicy.kt
playback/src/main/java/com/daydreamvr/playback/ScrubController.kt
playback/src/main/java/com/daydreamvr/playback/DecoderCapsProvider.kt
playback/src/main/java/com/daydreamvr/playback/ResumeStore.kt
vrcore/src/main/java/com/daydreamvr/vrcore/gl/VideoTexture.kt
vrcore/src/main/java/com/daydreamvr/vrcore/render/CylinderScreen.kt
vrcore/src/main/java/com/daydreamvr/vrcore/render/SphereScreen.kt
vrcore/src/main/java/com/daydreamvr/vrcore/render/ProjectionMode.kt
vrcore/src/main/java/com/daydreamvr/vrcore/render/shaders/{video_ext.vert,video_ext.frag}
playback/src/test/java/com/daydreamvr/playback/{ScrubControllerTest,FallbackPolicyTest,ResumeStoreTest}.kt
vrcore/src/test/java/com/daydreamvr/vrcore/render/{CylinderScreenTest,ProjectionModeTest}.kt
app/src/androidTest/java/com/daydreamvr/player/PlaybackSmokeTest.kt
app/src/androidTest/assets/test_5s_h264.mp4
app/src/androidTest/assets/test_5s_h265_ac3.mkv
```

### 4.2 Signatures

```kotlin
// playback/VideoPlayer.kt
interface VideoPlayer {
    val snapshot: StateFlow<PlaybackSnapshot>
    fun attach(surface: Surface)
    fun detach()
    fun play(request: PlayRequest)
    fun playPause(); fun pause(); fun stop()
    fun seekBy(deltaMs: Long); fun seekTo(positionMs: Long, exact: Boolean)
    fun setSpeed(speed: Float)
    fun selectAudioTrack(id: String?); fun selectSubtitleTrack(id: String?)
    fun release()
}

data class PlayRequest(
    val itemKey: String,                  // "${serverUdn}|${objectId}"
    val title: String,
    val rankedResources: List<Resource>,  // from ResourceRanker; index 0 tried first
    val startAtMs: Long,
    val projection: ProjectionMode?,      // null = auto-detect
)

data class PlaybackSnapshot(
    val itemKey: String?, val title: String,
    val isPlaying: Boolean, val isBuffering: Boolean,
    val positionMs: Long, val bufferedMs: Long, val durationMs: Long,
    val videoWidth: Int, val videoHeight: Int, val pixelAspect: Float,
    val audioTracks: List<TrackInfo>, val subtitleTracks: List<TrackInfo>,
    val activeCues: List<String>,
    val failure: PlaybackFailure?,
    val resourceIndex: Int,
)

// playback/ScrubController.kt — pure
class ScrubController(private val maxRateSecPerSec: Float = 120f) {
    fun onScrub(rate: Float, nowMs: Long, currentPositionMs: Long, durationMs: Long): ScrubOutput
    fun onRelease(): Long?          // final exact seek target, or null if never scrubbed
}
data class ScrubOutput(val previewPositionMs: Long, val commitSeek: Boolean)   // commit throttled to 250 ms

// playback/FallbackPolicy.kt — pure
object FallbackPolicy {
    fun decide(failure: PlaybackFailure, attempt: Int, remainingResources: Int): FallbackAction
}
sealed interface FallbackAction {
    data class RetrySameAfter(val delayMs: Long) : FallbackAction
    object NextResource : FallbackAction
    object RefreshUrlFromServer : FallbackAction
    object ForceSoftwareAudio : FallbackAction
    data class GiveUp(val userMessage: String) : FallbackAction
}

// vrcore/gl/VideoTexture.kt
class VideoTexture {
    val textureId: Int
    val surface: Surface
    fun createOnGlThread()
    fun updateIfDirty(): Boolean
    fun transformMatrix(out: FloatArray)
    fun release()
}

// vrcore/render/CylinderScreen.kt
class CylinderScreen(
    var radiusM: Float = 4f,               // 1.5..12
    var widthDegrees: Float = 60f,         // 30..110
) {
    fun setAspect(videoAspect: Float)      // rebuilds vertical extent, keeps width
    fun buildMesh(hSegments: Int = 48, vSegments: Int = 24): Mesh
    fun draw(eye: EyeParams, viewM: FloatArray, projM: FloatArray,
             videoTexture: VideoTexture, projection: ProjectionMode)
}

// vrcore/render/ProjectionMode.kt
enum class ProjectionMode { FLAT, SBS_HALF, SBS_FULL, TOPBOTTOM_HALF, TOPBOTTOM_FULL, EQUIRECT_180, EQUIRECT_360;
    companion object {
        fun detect(title: String, width: Int, height: Int): ProjectionMode
        fun uvRectFor(mode: ProjectionMode, eye: Eye): FloatArray   // [uOff, vOff, uScale, vScale]
    }
}
```

### 4.3 Tests

- `ScrubControllerTest` — full trigger pull for 5 s moves the preview ~600 s; commit seeks fire at most every 250 ms; release returns an exact target; clamping at 0 and `duration`; `rate = 0` ends the gesture.
- `FallbackPolicyTest` — network timeout → 3 backoffs (1 s/3 s/7 s) then `GiveUp`; 404 → `RefreshUrlFromServer` once, then `NextResource`; unsupported video codec → `NextResource`, and `GiveUp` with a codec-naming message when the list is exhausted; audio init failure → `ForceSoftwareAudio` before `GiveUp`.
- `ProjectionModeTest` — `detect` on "Movie.2016.1080p.HSBS.mkv" → `SBS_HALF`; "Beach 360 VR.mp4" with 2:1 aspect → `EQUIRECT_360`; a plain title with 16:9 → `FLAT`; `uvRectFor` gives the left half to `LEFT` in `SBS_*` and the *top* half to `LEFT` in `TOPBOTTOM_*`.
- `CylinderScreenTest` — mesh vertex count and winding; `setAspect(2.35)` keeps the horizontal degrees and shrinks the vertical extent proportionally; all vertices lie within 1e-4 of the cylinder radius.
- `ResumeStoreTest` — write/read round-trip; > 95% marks finished; LRU eviction at 500 entries.
- `PlaybackSmokeTest` (androidTest, MockWebServer serving the asset files) — prepare→play→`positionMs` advances→seek to 3 s→pause→resume from `ResumeStore`; MKV/AC3 asset produces audio format non-null (proves the ffmpeg extension is wired); a first `Resource` returning 404 transparently falls back to the second.

### 4.4 Acceptance criteria

- [ ] A hardcoded LAN URL plays full-motion video on the curved screen, in stereo, with correct aspect (no stretch on 2.35:1 or 4:3 content) and correct orientation (no vertical flip — the SurfaceTexture transform is applied).
- [ ] X pauses/resumes; bumpers seek ±10 s; triggers scrub smoothly with no decoder thrash; right stick resizes/moves the screen within limits.
- [ ] R3 cycles projection modes live; an SBS file shows correct per-eye halves; a 360 file maps to the sphere with the recentre anchor respected.
- [ ] 1080p H.264 and 1080p H.265+AC3 both play with audio; playback holds 90 Hz render with no dropped-frame accumulation over 10 minutes.
- [ ] Pulling the Wi-Fi mid-playback produces a retry sequence and then a legible in-headset error, not a black screen.

---

## Phase 5 — In-Headset UI, State Machine, End-to-End

**Goal:** G1 achieved. Launch → discover → browse → play → back, all from the gamepad, all in the headset.

### 5.1 Files

```
vrcore/src/main/java/com/daydreamvr/vrcore/ui/PanelSurface.kt
vrcore/src/main/java/com/daydreamvr/vrcore/ui/PanelQuad.kt           # curved panel geometry
vrcore/src/main/java/com/daydreamvr/vrcore/ui/PanelAnchor.kt         # world-lock + lazy-follow
vrcore/src/main/java/com/daydreamvr/vrcore/ui/AngularMetrics.kt      # degrees → px/text size
vrcore/src/main/java/com/daydreamvr/vrcore/ui/Theme.kt
vrcore/src/main/java/com/daydreamvr/vrcore/ui/widgets/{ListView,Breadcrumb,ProgressBar,Timeline,Grid}.kt
app/src/main/java/com/daydreamvr/player/state/AppState.kt
app/src/main/java/com/daydreamvr/player/state/Event.kt
app/src/main/java/com/daydreamvr/player/state/Effect.kt
app/src/main/java/com/daydreamvr/player/state/AppStateMachine.kt      # PURE reduce()
app/src/main/java/com/daydreamvr/player/state/EffectRunner.kt
app/src/main/java/com/daydreamvr/player/screens/ServerListScreen.kt
app/src/main/java/com/daydreamvr/player/screens/BrowseScreen.kt
app/src/main/java/com/daydreamvr/player/screens/PlayerHud.kt
app/src/main/java/com/daydreamvr/player/screens/SettingsScreen.kt
app/src/main/java/com/daydreamvr/player/screens/VrKeyboard.kt
app/src/main/java/com/daydreamvr/player/screens/OverlayRenderer.kt    # errors + toasts
app/src/main/java/com/daydreamvr/player/render/AppScene.kt            # implements vrcore Scene
app/src/main/java/com/daydreamvr/player/data/SettingsStore.kt
app/src/main/java/com/daydreamvr/player/data/ServerStore.kt
app/src/test/java/com/daydreamvr/player/state/{NavigationTest,BrowseReducerTest,HudReducerTest,EscapeHatchTest}.kt
app/src/test/java/com/daydreamvr/player/screens/VrKeyboardTest.kt
vrcore/src/test/java/com/daydreamvr/vrcore/ui/{AngularMetricsTest,PanelAnchorTest}.kt
app/src/androidTest/java/com/daydreamvr/player/EndToEndGamepadTest.kt
app/src/androidTest/java/com/daydreamvr/player/PanelSurfaceSelfTest.kt
```

### 5.2 Signatures

```kotlin
// vrcore/ui/PanelSurface.kt
class PanelSurface(val widthPx: Int, val heightPx: Int) {
    val textureId: Int
    fun createOnGlThread()
    fun draw(block: (Canvas) -> Unit)          // called off the GL thread; marks dirty
    fun updateIfDirty(): Boolean               // GL thread
    fun release()
    companion object {
        enum class Path { HARDWARE_CANVAS, BITMAP_UPLOAD }
        fun selfTest(): Path                    // draws a pattern, glReadPixels, caches result
    }
}

// vrcore/ui/PanelAnchor.kt — pure
class PanelAnchor(
    var distanceM: Float = 2.5f,
    var followThresholdDeg: Float = 35f,
    var followTauSeconds: Float = 0.5f,
) {
    fun update(headYawRad: Float, dtSeconds: Float): Float   // returns the panel's yaw
    fun snapTo(yawRad: Float)
}

// vrcore/ui/AngularMetrics.kt — pure
object AngularMetrics {
    fun textSizePx(angularDegrees: Float, panelWidthPx: Int, panelWidthDegrees: Float): Float
    fun isWithinComfortBox(xDeg: Float, yDeg: Float): Boolean   // ±25° h, ±20° v
}

// app/state/AppStateMachine.kt
class AppStateMachine(initial: AppState) {
    val state: StateFlow<AppState>
    fun dispatch(event: Event)
    companion object {
        /** PURE. No Android, no IO, no clock reads — time arrives via Event.Tick(nowMs). */
        fun reduce(state: AppState, event: Event): Pair<AppState, List<Effect>>
    }
}

// app/screens/BrowseScreen.kt  (one per screen; all follow this shape)
class BrowseScreen(private val panel: PanelSurface, private val theme: Theme) {
    fun render(state: AppState)          // no-op unless the browse slice changed
    val anchor: PanelAnchor
    fun drawGl(eye: EyeParams, viewM: FloatArray, projM: FloatArray)
}

// app/screens/VrKeyboard.kt
class VrKeyboard(private val panel: PanelSurface) {
    data class KeyboardState(val text: String, val cursorRow: Int, val cursorCol: Int, val numericMode: Boolean)
    fun handle(action: InputAction, s: KeyboardState): KeyboardState     // pure, tested
    fun suggestions(subnetPrefix: String?): List<String>
}
```

### 5.3 Tests

- `NavigationTest` (pure) — server list → browse → nested browse → player, and back out, asserting screen, breadcrumb depth, and effects emitted at each step.
- `BrowseReducerTest` — focus index clamps when a page loads shorter than the previous focus; each stack entry restores its own `focusIndex`/`scrollTop` on pop (the "40 episodes deep" case); page-down at the end of a partially loaded container emits a `Browse` effect for the next page and does not move focus until it arrives.
- `HudReducerTest` — HUD auto-hides 4 s after the last input (injected clock); any input while hidden shows it without consuming the action *except* `Confirm`, which only shows; `Cancel` hides; long `Cancel` exits to browse.
- `EscapeHatchTest` — **exhaustive walk** of every reachable `(screen, overlay)` pair: `Cancel` produces a state change in every one, and a long `Cancel` from any state reaches `ServerList` within 1 transition.
- `VrKeyboardTest` — typing `192.168.50.10` from the numeric layout takes ≤ 15 actions; wrap-around at edges; backspace on empty is a no-op; subnet suggestion from `192.168.50.` is accepted with one action.
- `AngularMetricsTest` — 1.5° at a 60°-wide 1024 px panel gives ≥ 25 px text; the comfort-box predicate rejects a point at 30° horizontal.
- `PanelAnchorTest` — panel does not move while the head stays within 35°; beyond it, eases with a 0.5 s time constant and never overshoots; `snapTo` on recentre is instant.
- `PanelSurfaceSelfTest` (androidTest) — the self-test picks a path and the chosen path renders a known pattern that `glReadPixels` confirms within tolerance; forcing `BITMAP_UPLOAD` produces the same pattern.
- `EndToEndGamepadTest` (androidTest, fake in-process MediaServer, events via `UiAutomation.injectInputEvent`) — cold start → `A` on "Enter VR" → discovery lists the fake server → `A` → root containers → `DPAD_DOWN`×2 → `A` → items → `A` on a video → playback starts and position advances → `B` returns to the same list **with focus restored to the item just played** → long `B` → server list. **No touch events are used anywhere in this test.**

### 5.4 Acceptance criteria

- [ ] From `SetupActivity`, a gamepad alone reaches playback of a real file on a real server without touching the screen.
- [ ] All text is legible through a real viewer at arm's-length equivalent: nothing under 1.5° of visual angle, nothing outside the ±25°/±20° comfort box, nothing crossing the divider.
- [ ] Panels are world-locked with lazy-follow; recentre snaps panels and the screen together.
- [ ] Browse of a 500-item folder scrolls smoothly, pages in without a visible stall, and holds render rate (panel redraw does not run per frame — verified with a redraw counter in the debug overlay).
- [ ] Errors (server gone, file unplayable, controller disconnected) all appear as in-headset panels with a working action.
- [ ] Resume: replaying a partially watched item offers resume/start-over and resumes at the right position.

---

## Phase 6 — Distortion, Comfort, Performance, Release

**Goal:** the app is *pleasant* and shippable.

### 6.1 Files

```
vrcore/src/main/java/com/daydreamvr/vrcore/distortion/DistortionMesh.kt
vrcore/src/main/java/com/daydreamvr/vrcore/distortion/DistortionRenderer.kt
vrcore/src/main/java/com/daydreamvr/vrcore/distortion/shaders/{distortion.vert,distortion.frag}
vrcore/src/main/java/com/daydreamvr/vrcore/render/EyeFramebuffer.kt
vrcore/src/main/java/com/daydreamvr/vrcore/render/FrameStats.kt
app/src/main/java/com/daydreamvr/player/perf/ThermalGovernor.kt
app/src/main/java/com/daydreamvr/player/perf/WifiPerformanceLock.kt
app/src/main/java/com/daydreamvr/player/perf/RenderWatchdog.kt
app/src/main/java/com/daydreamvr/player/screens/CalibrationScreen.kt
app/src/main/java/com/daydreamvr/player/screens/GamepadCalibrationScreen.kt
app/src/main/java/com/daydreamvr/player/debug/DiagnosticsRingBuffer.kt
app/src/main/java/com/daydreamvr/player/subtitles/SubtitleRenderer.kt
app/proguard-rules.pro
docs/TESTING.md
vrcore/src/test/java/com/daydreamvr/vrcore/distortion/DistortionMeshTest.kt
app/src/test/java/com/daydreamvr/player/perf/ThermalGovernorTest.kt
app/src/androidTest/java/com/daydreamvr/player/{DistortionGoldenTest,AllocationTest,RefreshRateTest}.kt
```

### 6.2 Signatures

```kotlin
// distortion/DistortionMesh.kt — pure
object DistortionMesh {
    fun build(eye: EyeParams, profile: DeviceProfile, gridSize: Int = 40): Mesh
    fun distort(r: Float, k: FloatArray): Float          // r * (1 + k1 r² + k2 r⁴)
    fun undistort(rDistorted: Float, k: FloatArray): Float   // Newton iteration, ≤ 6 steps
}

// render/EyeFramebuffer.kt
class EyeFramebuffer(var renderScale: Float, var msaaSamples: Int) {
    fun ensure(width: Int, height: Int)
    fun bind(); fun resolve(): Int      // returns the resolved colour texture id
}

// perf/ThermalGovernor.kt — pure decision function + Android listener wrapper
object ThermalGovernor {
    data class Quality(val renderScale: Float, val msaa: Int, val chromatic: Boolean)
    fun qualityFor(thermalStatus: Int, batteryPercent: Int): Quality
}

// perf/RenderWatchdog.kt
class RenderWatchdog(private val timeoutMs: Long = 2000, private val onWedged: () -> Unit) {
    fun onFrame(); fun start(); fun stop()
}

// subtitles/SubtitleRenderer.kt
class SubtitleRenderer(private val panel: PanelSurface) {
    fun render(cues: List<String>, angularSizeDeg: Float)   // drawn on its own panel below the screen
}
```

### 6.3 Tests

- `DistortionMeshTest` — `undistort(distort(r)) ≈ r` for r ∈ [0, 1.2] within 1e-4 across all built-in profiles; the mesh's UVs stay in `[-0.1, 1.1]`; grid corners map monotonically (no folding).
- `ThermalGovernorTest` — the documented quality ladder for `NONE/LIGHT/MODERATE/SEVERE/CRITICAL`; low battery (< 15%) drops render scale independently of thermal status.
- `DistortionGoldenTest` (androidTest) — render a calibration grid with distortion on, `glReadPixels`, compare against a checked-in golden per profile with a per-pixel tolerance of 3/255 and a ≤ 0.5% outlier budget.
- `AllocationTest` (androidTest) — `Debug.startAllocCounting()` around 600 frames of steady-state playback: allocation count delta ≤ 0 objects per frame (allow a small fixed epsilon for the platform, documented in the test).
- `RefreshRateTest` (androidTest) — the highest available `Display.Mode` at native resolution is requested and, if granted, `FrameStats.p95` is within 15% of that mode's frame interval.
- Regression pass: the whole suite from Phases 1–5 stays green.

### 6.4 Acceptance criteria

- [ ] Distortion correction on: a straight-line calibration grid appears straight through a real Cardboard v2 / Daydream View. Off: it visibly bows (proving the correction does something).
- [ ] The in-VR calibration screen adjusts IPD, screen-to-lens, k1/k2 and divider width live, and the values persist across restart per profile.
- [ ] Gamepad calibration handles an 8BitDo in Switch mode (swapped A/B) and the mapping survives a Bluetooth reconnect.
- [ ] Sustained 30-minute 1080p playback: no frame-rate collapse, thermal governor demonstrably steps quality down (visible in the debug overlay), no thermal shutdown.
- [ ] Zero allocations per frame in steady state; `StrictMode` clean in debug.
- [ ] Subtitles render legibly on their own panel and do not cross the divider.
- [ ] Release build: R8 full mode, no crashes across the manual matrix in `docs/TESTING.md`, APK installs and runs on a clean device.

---

## Cross-Phase Definition of Done

A phase is done when **all** of these hold:

1. Every acceptance checkbox is ticked (or explicitly marked `UNVERIFIED-ON-DEVICE` with a reason).
2. `./gradlew check` and `./gradlew connectedCheck` pass.
3. No new lint suppressions, no `@Suppress` without a one-line justification.
4. The architecture doc still describes reality — if the implementation diverged for a good reason, ARCHITECTURE.md is updated **in the same commit**.
5. The app builds, installs, and runs end-to-end at the level the phase promises. No phase may leave `main` in a state where launching the app is broken.

## Deferred to v2 (do not build these now)

HDR passthrough · UPnP AVTransport "play to" a TV · Jellyfin/Plex native APIs · local file browsing · playlists and queue management · Cardboard QR profile scanning · voice control · multi-user profiles · subtitle style customisation beyond size · 6DOF of any kind.
