package com.daydreamvr.vrcore.input

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

class GamepadDecoderTest {

    private var player = false
    private var nowNanos = 0L
    private val emitted = mutableListOf<InputAction>()
    private lateinit var decoder: GamepadDecoder

    @Before
    fun setUp() {
        nowNanos = 0L
        emitted.clear()
        decoder = GamepadDecoder(
            bindings = InputBindings(),
            resolver = GamepadProfileResolver(),
            clock = { nowNanos },
            playerInputEnabled = { player },
            emit = { emitted += it },
        )
    }

    private fun advanceMs(ms: Long) {
        nowNanos += ms * 1_000_000L
    }

    private fun motion(vararg axes: Pair<Int, Float>) =
        RawMotion(deviceId = 7, source = 0, axisValues = axes.toMap())

    private fun key(code: Int, action: Int, repeatCount: Int = 0) =
        RawKey(keyCode = code, action = action, repeatCount = repeatCount)

    @Test
    fun yawUsesDeviceDeadzoneAndNormalizesRemainingTravel() {
        decoder.registerDevice(GamepadCapabilities("custom", 7, "custom", 0,
            listOf(MotionRangeInfo(RawMotion.AXIS_X, -1f, 1f, 0.4f))))
        player = true
        decoder.tick()
        emitted.clear()
        decoder.handleMotion(motion(RawMotion.AXIS_X to 0.3f))
        assertThat(emitted).isEmpty()
        decoder.handleMotion(motion(RawMotion.AXIS_X to 0.7f))
        assertThat((emitted.single() as InputAction.YawAdjust).rate).isWithin(0.0001f).of(0.5f)
    }

    @Test
    fun playerStickYawStopsOnReleaseExitAndDisconnect() {
        player = true
        decoder.tick()
        emitted.clear()
        decoder.handleMotion(motion(RawMotion.AXIS_X to 1f))
        assertThat(emitted).containsExactly(InputAction.YawAdjust(1f))
        emitted.clear()
        advanceMs(500)
        decoder.tick()
        assertThat(emitted).isEmpty()
        decoder.handleMotion(motion(RawMotion.AXIS_X to 0.1f))
        assertThat(emitted).containsExactly(InputAction.YawAdjust(0f))
        decoder.handleMotion(motion(RawMotion.AXIS_X to -1f))
        decoder.onDeviceRemoved(7)
        assertThat(emitted.last()).isEqualTo(InputAction.YawAdjust(0f))
        decoder.handleMotion(motion(RawMotion.AXIS_X to 1f))
        player = false
        decoder.tick()
        assertThat(emitted.last()).isEqualTo(InputAction.YawAdjust(0f))
        emitted.clear()
        decoder.handleMotion(motion(RawMotion.AXIS_X to 1f))
        assertThat(emitted).containsExactly(InputAction.Nav(InputAction.Dir.RIGHT, false))
    }

    @Test
    fun playerHatStillNavigates() {
        player = true
        decoder.tick()
        emitted.clear()
        decoder.handleMotion(motion(RawMotion.AXIS_HAT_X to -1f))
        assertThat(emitted).containsExactly(InputAction.Nav(InputAction.Dir.LEFT, false))
    }

    @Test
    fun stickCrossingDeadzone_emitsNavImmediately_thenRepeatsAfter400msAt120ms() {
        decoder.handleMotion(motion(RawMotion.AXIS_Y to -1f))
        assertThat(emitted).containsExactly(InputAction.Nav(InputAction.Dir.UP, repeat = false))
        emitted.clear()

        advanceMs(100)
        decoder.tick()
        assertThat(emitted).isEmpty()

        advanceMs(300) // t = 400 ms
        decoder.tick()
        assertThat(emitted).containsExactly(InputAction.Nav(InputAction.Dir.UP, repeat = true))
        emitted.clear()

        advanceMs(120) // t = 520 ms
        decoder.tick()
        assertThat(emitted).containsExactly(InputAction.Nav(InputAction.Dir.UP, repeat = true))
    }

    @Test
    fun osKeyRepeat_isIgnored() {
        decoder.handleKey(key(RawKey.KEYCODE_DPAD_UP, RawKey.ACTION_DOWN, repeatCount = 3))
        assertThat(emitted).isEmpty()
    }

    @Test
    fun triggerPressure_mapsToSquaredScrubRate_andEmitsZeroOnRelease() {
        decoder.handleMotion(motion(RawMotion.AXIS_RTRIGGER to 0.5f))
        assertThat(emitted).containsExactly(InputAction.Scrub(0.25f))
        emitted.clear()

        decoder.handleMotion(motion(RawMotion.AXIS_RTRIGGER to 0f))
        assertThat(emitted).containsExactly(InputAction.Scrub(0f))
    }

    @Test
    fun holdingB_500ms_emitsCancelLongExactlyOnce() {
        decoder.handleKey(key(RawKey.KEYCODE_BUTTON_B, RawKey.ACTION_DOWN))

        advanceMs(100)
        decoder.tick()
        assertThat(emitted).isEmpty()

        advanceMs(400) // t = 500 ms
        decoder.tick()
        assertThat(emitted).containsExactly(InputAction.Cancel(long = true))
        emitted.clear()

        advanceMs(300)
        decoder.tick()
        decoder.handleKey(key(RawKey.KEYCODE_BUTTON_B, RawKey.ACTION_UP))
        assertThat(emitted).isEmpty()
    }

    @Test
    fun shortB_emitsCancelShortOnRelease() {
        decoder.handleKey(key(RawKey.KEYCODE_BUTTON_B, RawKey.ACTION_DOWN))
        advanceMs(120)
        decoder.tick()
        decoder.handleKey(key(RawKey.KEYCODE_BUTTON_B, RawKey.ACTION_UP))
        assertThat(emitted).containsExactly(InputAction.Cancel(long = false))
    }

    @Test
    fun hatAxisAndDpadKeycode_produceIdenticalNavActions() {
        decoder.handleMotion(motion(RawMotion.AXIS_HAT_X to 1f))
        val fromHat = emitted.toList()
        emitted.clear()

        // Recentre the hat so the nav state is released.
        decoder.handleMotion(motion(RawMotion.AXIS_HAT_X to 0f))
        emitted.clear()

        decoder.handleKey(key(RawKey.KEYCODE_DPAD_RIGHT, RawKey.ACTION_DOWN))
        val fromKey = emitted.toList()

        assertThat(fromHat).containsExactly(InputAction.Nav(InputAction.Dir.RIGHT, repeat = false))
        assertThat(fromKey).isEqualTo(fromHat)
    }

    @Test
    fun connectedGamepads_reflectRegistrationAndRemoval() {
        val caps = GamepadCapabilities(
            descriptor = "pad-a",
            id = 42,
            name = "Test Pad",
            sources = 0,
            motionRanges = listOf(
                MotionRangeInfo(RawMotion.AXIS_Z, -1f, 1f, 0f),
                MotionRangeInfo(RawMotion.AXIS_RZ, -1f, 1f, 0f),
                MotionRangeInfo(RawMotion.AXIS_LTRIGGER, 0f, 1f, 0f),
                MotionRangeInfo(RawMotion.AXIS_RTRIGGER, 0f, 1f, 0f),
            ),
        )
        decoder.registerDevice(caps)
        assertThat(decoder.connectedGamepads.value).containsExactly("pad-a")

        decoder.onDeviceRemoved(42)
        assertThat(decoder.connectedGamepads.value).isEmpty()
    }

    @Test
    fun rightStickY_scrollsPagesOutsidePlayer_andZoomsInPlayer() {
        decoder.registerDevice(GamepadCapabilities("pad-scroll", 7, "pad-scroll", 0,
            listOf(
                MotionRangeInfo(RawMotion.AXIS_Z, -1f, 1f, 0.2f),
                MotionRangeInfo(RawMotion.AXIS_RZ, -1f, 1f, 0.2f),
            )))

        // 1. Outside player mode: right stick Y down emits PageDown
        player = false
        decoder.tick()
        emitted.clear()
        decoder.handleMotion(motion(RawMotion.AXIS_RZ to 0.8f))
        assertThat(emitted).containsExactly(InputAction.PageDown)

        // After repeat delay, ticks emit auto-repeat PageDown
        advanceMs(401)
        decoder.tick()
        assertThat(emitted).containsExactly(InputAction.PageDown, InputAction.PageDown)

        // Release right stick
        emitted.clear()
        decoder.handleMotion(motion(RawMotion.AXIS_RZ to 0f))
        assertThat(emitted).isEmpty()

        // Right stick Y up emits PageUp
        decoder.handleMotion(motion(RawMotion.AXIS_RZ to -0.8f))
        assertThat(emitted).containsExactly(InputAction.PageUp)

        // Release right stick
        decoder.handleMotion(motion(RawMotion.AXIS_RZ to 0f))
        emitted.clear()

        // 2. In player mode: right stick Y emits Zoom instead of PageUp/PageDown
        player = true
        decoder.tick()
        emitted.clear()
        decoder.handleMotion(motion(RawMotion.AXIS_RZ to 0.8f))
        assertThat(emitted).containsExactly(InputAction.Zoom(-0.8f))
    }
}
