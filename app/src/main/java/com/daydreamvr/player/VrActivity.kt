package com.daydreamvr.player

import android.hardware.input.InputManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.daydreamvr.player.debug.DebugOverlay
import com.daydreamvr.player.input.isGamepad
import com.daydreamvr.player.input.toGamepadCapabilities
import com.daydreamvr.player.input.toRawKey
import com.daydreamvr.player.input.toRawMotion
import com.daydreamvr.player.perf.ThermalGovernor
import com.daydreamvr.player.perf.ThermalMonitor
import com.daydreamvr.player.render.AppScene
import com.daydreamvr.player.screens.CalibrationScreen
import com.daydreamvr.player.screens.GamepadCalibration
import com.daydreamvr.player.state.AppStateMachine
import com.daydreamvr.player.state.Effect
import com.daydreamvr.player.state.Event
import com.daydreamvr.player.state.Settings
import com.daydreamvr.playback.ExoVideoPlayer
import com.daydreamvr.playback.ScrubController
import com.daydreamvr.playback.VideoPlayer
import com.daydreamvr.vrcore.input.GamepadDecoder
import com.daydreamvr.vrcore.input.InputAction
import com.daydreamvr.vrcore.profile.DeviceProfiles
import com.daydreamvr.vrcore.render.VrRenderer
import com.daydreamvr.vrcore.tracking.SensorHeadTracker
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.atan2

/**
 * Fullscreen immersive landscape GL surface (ARCHITECTURE.md §18). Phase 5 wires
 * the pieces together: decoded gamepad actions and the frame clock feed
 * [AppStateMachine]; [com.daydreamvr.player.state.EffectRunner] runs the effects;
 * [AppScene] renders whatever screen the state selects, plus the video screen and
 * HUD during playback.
 */
class VrActivity : ComponentActivity() {

    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var renderer: VrRenderer
    private lateinit var decoder: GamepadDecoder
    private lateinit var headTracker: SensorHeadTracker
    private lateinit var player: VideoPlayer
    private lateinit var stateMachine: AppStateMachine
    private lateinit var effectRunner: com.daydreamvr.player.state.EffectRunner
    private lateinit var scene: AppScene
    private val overlay = DebugOverlay()
    private val scrub = ScrubController()
    private lateinit var thermalMonitor: ThermalMonitor

    /** Reused every frame by the pose provider — read only on the GL thread. */
    private val poseBuffer = FloatArray(16)

    /** Head yaw (radians) latched by the GL-thread pose provider for panel follow. */
    @Volatile
    private var headYawRad: Float = 0f

    private lateinit var leftEyeText: TextView
    private lateinit var rightEyeText: TextView

    private val mainHandler = Handler(Looper.getMainLooper())

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            decoder.tick()
            stateMachine.dispatch(Event.Tick(SystemClock.uptimeMillis()))
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private val inputDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {
            registerIfGamepad(deviceId)
            decoder.onDeviceAdded(deviceId)
        }

        override fun onInputDeviceRemoved(deviceId: Int) {
            decoder.onDeviceRemoved(deviceId)
        }

        override fun onInputDeviceChanged(deviceId: Int) {
            registerIfGamepad(deviceId)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = (application as PlayerApp).container

        headTracker = SensorHeadTracker(
            context = this,
            displayRotationProvider = ::currentDisplayRotation,
            clockNs = System::nanoTime,
        )

        decoder = GamepadDecoder(
            bindings = container.inputBindings,
            resolver = container.gamepadProfileResolver,
            clock = System::nanoTime,
            emit = ::onInputAction,
        )

        player = ExoVideoPlayer(
            context = this,
            resumeStore = container.resumeStore,
            onFatalError = { message ->
                runOnUiThread { stateMachine.dispatch(Event.Failure("Playback problem", message, canRetry = true)) }
            },
        )

        stateMachine = AppStateMachine()
        scene = AppScene(
            stateProvider = { stateMachine.state.value },
            snapshotProvider = { player.snapshot.value },
            neckOffsetProvider = {
                if (stateMachine.state.value.settings.neckModelEnabled) container.deviceProfile.neckModelM else null
            },
            trackerCalibratedProvider = { headTracker.isCalibrated.value },
            onGazeTarget = { t -> runOnUiThread { stateMachine.dispatch(Event.GazeMoved(t)) } },
            onVideoSurfaceReady = { surface -> player.attach(surface) },
        ).also { s ->
            s.onListWindowMeasured = { w -> runOnUiThread { stateMachine.dispatch(Event.ListWindowMeasured(w)) } }
        }

        effectRunner = com.daydreamvr.player.state.EffectRunner(
            directory = container.mediaServerDirectory,
            contentDirectory = container.contentDirectoryClient,
            player = player,
            decoderCaps = { container.decoderCapsProvider.caps() },
            resumeStore = container.resumeStore,
            serverStore = container.serverStore,
            settingsStore = container.settingsStore,
            scope = lifecycleScope,
            dispatch = stateMachine::dispatch,
            onRecenter = {
                headTracker.recenter()
                scene.recenter()
            },
            onApplySettings = ::applySettings,
            onQuit = { finish() },
        )

        renderer = VrRenderer(
            scene = scene,
            profileProvider = { container.deviceProfile },
            poseProvider = {
                headTracker.poseFor(System.nanoTime() + PREDICT_AHEAD_NS, poseBuffer)
                headYawRad = atan2(-poseBuffer[8], poseBuffer[10])
                poseBuffer
            },
        ).apply {
            val metrics = resources.displayMetrics
            displayWidthM = (metrics.widthPixels / metrics.xdpi * INCH_TO_M).coerceIn(0.05f, 0.20f)
            displayHeightM = (metrics.heightPixels / metrics.ydpi * INCH_TO_M).coerceIn(0.03f, 0.12f)
            ipdM = 0.063f
        }

        glSurfaceView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        leftEyeText = overlayTextView()
        rightEyeText = overlayTextView()

        val root = FrameLayout(this).apply {
            addView(glSurfaceView)
            addView(leftEyeText, eyeLayoutParams(Gravity.START))
            addView(rightEyeText, eyeLayoutParams(Gravity.END))
        }
        setContentView(root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        configureImmersive()

        registerConnectedGamepads()
        getSystemService(InputManager::class.java)
            .registerInputDeviceListener(inputDeviceListener, mainHandler)

        thermalMonitor = ThermalMonitor(this) { quality -> runOnUiThread { applyQuality(quality) } }

        effectRunner.start()
        wireFlows()
        loadPersistedState(container)
        effectRunner.run(Effect.StartDiscovery(force = false))
    }

    private fun wireFlows() {
        lifecycleScope.launch {
            overlay.text.collect { text ->
                leftEyeText.text = text
                rightEyeText.text = text
            }
        }
        lifecycleScope.launch {
            // Drop the initial empty emission; report real connect/disconnect edges.
            decoder.connectedGamepads.drop(1).collect { pads ->
                stateMachine.dispatch(Event.ControllerConnected(pads.isNotEmpty()))
            }
        }
    }

    private fun loadPersistedState(container: com.daydreamvr.player.di.AppContainer) {
        lifecycleScope.launch {
            val settings = runCatching { container.settingsStore.current() }.getOrDefault(Settings())
            container.resumeStore.restore(runCatching { container.settingsStore.loadResume() }.getOrDefault(emptyList()))
            stateMachine.dispatch(Event.SettingsLoaded(settings))
            applySettings(settings)
        }
    }

    private fun applySettings(settings: Settings) {
        val container = (application as PlayerApp).container
        headTracker.predictionEnabled = settings.predictionEnabled
        headTracker.autoRecenterIdleSeconds = settings.autoRecenterIdleSeconds

        val base = DeviceProfiles.byId(settings.deviceProfileId) ?: DeviceProfiles.DEFAULT
        container.deviceProfile = CalibrationScreen.overrideProfile(base, settings)
        renderer.ipdM = settings.ipdMm / 1000f
        renderer.distortionEnabled = settings.distortionCorrection

        decoder.bindings = GamepadCalibration.bindingsFor(container.inputBindings, settings.gamepadAbSwapped)
    }

    /** Applies a thermal / battery quality step to the renderer (ARCHITECTURE.md §14). */
    private fun applyQuality(quality: ThermalGovernor.Quality) {
        renderer.renderScale = quality.renderScale
        renderer.msaaSamples = quality.msaa
        renderer.chromaticEnabled = quality.chromatic
        overlay.log("Thermal: scale ${quality.renderScale} msaa ${quality.msaa} chroma ${quality.chromatic}")
    }

    private fun configureImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }

    private fun registerConnectedGamepads() {
        for (deviceId in InputDevice.getDeviceIds()) {
            registerIfGamepad(deviceId)
        }
    }

    private fun registerIfGamepad(deviceId: Int) {
        val device = InputDevice.getDevice(deviceId) ?: return
        if (device.isGamepad()) {
            decoder.registerDevice(device.toGamepadCapabilities())
            overlay.log("Gamepad: ${device.name}")
        }
    }

    /** Sink for every decoded [InputAction]; routes to the state machine + player. */
    private fun onInputAction(action: InputAction) {
        headTracker.onUserActivity()
        if (action is InputAction.Recenter) headTracker.recenter()
        if (action is InputAction.Scrub) {
            handleScrub(action.rate)
            return
        }
        runOnUiThread {
            overlay.onAction(action)
            stateMachine.dispatch(Event.Input(action))
        }
    }

    private fun handleScrub(rate: Float) {
        runOnUiThread {
            val snap = player.snapshot.value
            if (rate == 0f) {
                scrub.onRelease()?.let { target ->
                    player.seekTo(target, exact = true)
                    stateMachine.dispatch(Event.ScrubPreview(null))
                }
                return@runOnUiThread
            }
            val out = scrub.onScrub(rate, SystemClock.uptimeMillis(), snap.positionMs, snap.durationMs)
            stateMachine.dispatch(Event.ScrubPreview(out.previewPositionMs))
            if (out.commitSeek) player.seekTo(out.previewPositionMs, exact = false)
        }
    }

    @Suppress("DEPRECATION")
    private fun currentDisplayRotation(): Int =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            display?.rotation ?: android.view.Surface.ROTATION_0
        } else {
            windowManager.defaultDisplay.rotation
        }

    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (decoder.handleKey(event.toRawKey())) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK) && event.action == MotionEvent.ACTION_MOVE) {
            if (decoder.handleMotion(event.toRawMotion())) return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onResume() {
        super.onResume()
        headTracker.start()
        thermalMonitor.start()
        glSurfaceView.onResume()
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onPause() {
        super.onPause()
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        player.pause()
        glSurfaceView.onPause()
        thermalMonitor.stop()
        headTracker.stop()
    }

    override fun onDestroy() {
        getSystemService(InputManager::class.java)
            .unregisterInputDeviceListener(inputDeviceListener)
        player.release()
        renderer.onGlDestroy()
        super.onDestroy()
    }

    private fun overlayTextView(): TextView = TextView(this).apply {
        setText(R.string.debug_overlay_waiting)
        setTextColor(0xFFE8E8EA.toInt())
        setBackgroundColor(0x66000000)
        textSize = 11f
        setPadding(24, 24, 24, 24)
        includeFontPadding = false
    }

    private fun eyeLayoutParams(horizontalGravity: Int) = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT,
        FrameLayout.LayoutParams.WRAP_CONTENT,
    ).apply {
        gravity = horizontalGravity or Gravity.CENTER_VERTICAL
    }

    private companion object {
        const val INCH_TO_M = 0.0254f

        /**
         * Photon-time lookahead for pose prediction (ARCHITECTURE.md §7.4):
         * ~32 ms display latency + ~11 ms for one 90 Hz frame. [PosePredictor]
         * re-clamps to 50 ms.
         */
        const val PREDICT_AHEAD_NS = 43_000_000L
    }
}
