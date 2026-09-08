package com.daydreamvr.player

import android.hardware.input.InputManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.daydreamvr.player.render.DebugCubeScene
import com.daydreamvr.vrcore.input.GamepadDecoder
import com.daydreamvr.vrcore.render.VrRenderer
import kotlinx.coroutines.launch

/**
 * Fullscreen immersive landscape GL surface (ARCHITECTURE.md §18). Intercepts
 * key / motion events at the Activity level — there are no focusable Views — and
 * feeds them to [GamepadDecoder]. Phase 1: no sensors, no network, no video.
 */
class VrActivity : ComponentActivity() {

    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var renderer: VrRenderer
    private lateinit var decoder: GamepadDecoder
    private val overlay = DebugOverlay()

    private lateinit var leftEyeText: TextView
    private lateinit var rightEyeText: TextView

    private val mainHandler = Handler(Looper.getMainLooper())

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            decoder.tick()
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

        decoder = GamepadDecoder(
            bindings = container.inputBindings,
            resolver = container.gamepadProfileResolver,
            clock = System::nanoTime,
            emit = { action -> runOnUiThread { overlay.onAction(action) } },
        )

        renderer = VrRenderer(
            scene = DebugCubeScene(),
            profileProvider = { container.deviceProfile },
            poseProvider = { IDENTITY_POSE },
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

        lifecycleScope.launch {
            overlay.text.collect { text ->
                leftEyeText.text = text
                rightEyeText.text = text
            }
        }
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
        glSurfaceView.onResume()
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onPause() {
        super.onPause()
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        glSurfaceView.onPause()
    }

    override fun onDestroy() {
        getSystemService(InputManager::class.java)
            .unregisterInputDeviceListener(inputDeviceListener)
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

        val IDENTITY_POSE = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )
    }
}
