package com.daydreamvr.vrcore.input

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.max

/**
 * A JVM-pure key: mirrors the fields of `android.view.KeyEvent` the decoder
 * cares about. `:app` translates real `KeyEvent`s into these
 * (ARCHITECTURE.md §8.3), keeping [GamepadDecoder] testable off-device.
 *
 * Key codes are numerically identical to `android.view.KeyEvent.KEYCODE_*`.
 */
data class RawKey(
    val keyCode: Int,
    val action: Int,
    val repeatCount: Int = 0,
    val eventTimeNanos: Long = 0L,
    val deviceId: Int = -1,
    val source: Int = 0,
) {
    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1

        const val KEYCODE_BACK = 4
        const val KEYCODE_DPAD_UP = 19
        const val KEYCODE_DPAD_DOWN = 20
        const val KEYCODE_DPAD_LEFT = 21
        const val KEYCODE_DPAD_RIGHT = 22
        const val KEYCODE_DPAD_CENTER = 23
        const val KEYCODE_ENTER = 66
        const val KEYCODE_MEDIA_PLAY_PAUSE = 85
        const val KEYCODE_BUTTON_A = 96
        const val KEYCODE_BUTTON_B = 97
        const val KEYCODE_BUTTON_X = 99
        const val KEYCODE_BUTTON_Y = 100
        const val KEYCODE_BUTTON_L1 = 102
        const val KEYCODE_BUTTON_R1 = 103
        const val KEYCODE_BUTTON_L2 = 104
        const val KEYCODE_BUTTON_R2 = 105
        const val KEYCODE_BUTTON_THUMBL = 106
        const val KEYCODE_BUTTON_THUMBR = 107
        const val KEYCODE_BUTTON_START = 108
        const val KEYCODE_BUTTON_SELECT = 109
    }
}

/**
 * A JVM-pure joystick motion snapshot: the latest value of every axis of
 * interest (history already flattened by `:app`). Axis ids are numerically
 * identical to `android.view.MotionEvent.AXIS_*`.
 */
data class RawMotion(
    val deviceId: Int,
    val source: Int,
    val axisValues: Map<Int, Float>,
    val eventTimeNanos: Long = 0L,
) {
    fun axis(id: Int): Float = axisValues[id] ?: 0f

    companion object {
        const val AXIS_X = 0
        const val AXIS_Y = 1
        const val AXIS_Z = 11
        const val AXIS_RX = 12
        const val AXIS_RY = 13
        const val AXIS_RZ = 14
        const val AXIS_HAT_X = 15
        const val AXIS_HAT_Y = 16
        const val AXIS_LTRIGGER = 17
        const val AXIS_RTRIGGER = 18
        const val AXIS_GAS = 22
        const val AXIS_BRAKE = 23
    }
}

/**
 * Converts raw key / motion events into a stream of [InputAction]s. Owns three
 * pieces of state (ARCHITECTURE.md §8.3): auto-repeat, analog scrub, and
 * long-press discrimination. [tick] must be called roughly once per frame to
 * drive auto-repeat and long-press.
 *
 * Constructible in a plain JVM test — [clock] is an injected `nanoTime` source
 * and [emit] captures the output.
 */
class GamepadDecoder(
    /** Live button map. `:app` swaps this for the A/B-swapped variant when the user asks. */
    var bindings: InputBindings,
    private val resolver: GamepadProfileResolver,
    private val clock: () -> Long,
    private val playerInputEnabled: () -> Boolean = { false },
    private val emit: (InputAction) -> Unit,
) {
    private val capsByDeviceId = HashMap<Int, GamepadCapabilities>()
    private val axisMapByDeviceId = HashMap<Int, AxisMap>()

    private val _connectedGamepads = MutableStateFlow<List<String>>(emptyList())
    val connectedGamepads: StateFlow<List<String>> = _connectedGamepads.asStateFlow()

    private class HeldKey(
        val downNanos: Long,
        var longFired: Boolean,
        val shortAction: InputAction,
        val longAction: InputAction,
    )

    private val heldKeys = HashMap<Int, HeldKey>()

    private var keyNavDir: InputAction.Dir? = null
    private var motionNavDir: InputAction.Dir? = null
    private var repeatDir: InputAction.Dir? = null
    private var navStartNanos = 0L
    private var nextRepeatNanos = 0L

    private var lastYawRate = 0f
    private var wasPlayer = false

    fun stopYaw() {
        lastYawRate = 0f
        emit(InputAction.YawAdjust(0f))
    }

    private fun syncContext() {
        val player = playerInputEnabled()
        if (player != wasPlayer) {
            wasPlayer = player
            motionNavDir = null
            updateNav(clock())
            stopYaw()
        }
    }

    private var lastScrubRate = 0f

    // region device lifecycle

    fun registerDevice(caps: GamepadCapabilities) {
        capsByDeviceId[caps.id] = caps
        axisMapByDeviceId[caps.id] = resolver.resolve(caps)
        refreshConnected()
    }

    fun onDeviceAdded(deviceId: Int) {
        refreshConnected()
    }

    fun onDeviceRemoved(deviceId: Int) {
        capsByDeviceId.remove(deviceId)
        axisMapByDeviceId.remove(deviceId)
        if (deviceId == activeMotionDeviceId) {
            stopYaw()
            motionNavDir = null
            updateNav(clock())
        }
        refreshConnected()
    }

    private fun refreshConnected() {
        _connectedGamepads.value = capsByDeviceId.values.map { it.descriptor }.sorted()
    }

    private var activeMotionDeviceId = -1

    private fun axisMapFor(deviceId: Int): AxisMap = axisMapByDeviceId[deviceId] ?: AxisMap.DEFAULT

    // endregion

    // region key handling

    fun handleKey(event: RawKey): Boolean {
        // OS-generated auto-repeat is dropped so key and stick nav behave
        // identically (ARCHITECTURE.md §8.3).
        if (event.action == RawKey.ACTION_DOWN && event.repeatCount > 0) return true

        val now = clock()
        val code = event.keyCode
        val down = event.action == RawKey.ACTION_DOWN

        bindings.navKeys[code]?.let { dir ->
            if (down) {
                keyNavDir = dir
            } else if (keyNavDir == dir) {
                keyNavDir = null
            }
            updateNav(now)
            return true
        }

        if (code in bindings.cancelKeys) {
            handleHold(code, down, now, InputAction.Cancel(long = false), InputAction.Cancel(long = true))
            return true
        }
        if (code in bindings.confirmKeys) {
            handleHold(code, down, now, InputAction.Confirm(long = false), InputAction.Confirm(long = true))
            return true
        }

        if (!down) return false

        val action = when (code) {
            in bindings.recenterKeys -> InputAction.Recenter
            in bindings.playPauseKeys -> InputAction.PlayPause
            in bindings.seekForwardKeys -> InputAction.Seek(bindings.seekSeconds)
            in bindings.seekBackKeys -> InputAction.Seek(-bindings.seekSeconds)
            in bindings.menuKeys -> InputAction.Menu
            in bindings.toggleHudKeys -> InputAction.ToggleHud
            in bindings.cycleProjectionKeys -> InputAction.CycleProjection
            else -> null
        } ?: return false

        emit(action)
        return true
    }

    private fun handleHold(
        code: Int,
        down: Boolean,
        now: Long,
        shortAction: InputAction,
        longAction: InputAction,
    ) {
        if (down) {
            if (!heldKeys.containsKey(code)) {
                heldKeys[code] = HeldKey(now, longFired = false, shortAction, longAction)
            }
        } else {
            val held = heldKeys.remove(code) ?: return
            if (!held.longFired) emit(held.shortAction)
        }
    }

    // endregion

    // region motion handling

    fun handleMotion(event: RawMotion): Boolean {
        val now = clock()
        syncContext()
        activeMotionDeviceId = event.deviceId
        val map = axisMapFor(event.deviceId)

        if (playerInputEnabled()) {
            val x = event.axis(map.leftStickX)
            val dead = map.deadzone(map.leftStickX, bindings.deadzoneFallback).coerceIn(0f, 0.99f)
            val rate = if (!x.isFinite() || abs(x) <= dead) 0f else
                kotlin.math.sign(x) * ((abs(x) - dead) / (1f - dead)).coerceIn(0f, 1f)
            if (rate != lastYawRate) {
                lastYawRate = rate
                emit(InputAction.YawAdjust(rate))
            }
        }
        updateMotionNav(event, map, now)
        updateScrub(event, map)
        emitStickAdjustments(event, map)
        return true
    }

    private fun updateMotionNav(event: RawMotion, map: AxisMap, now: Long) {
        val hatX = event.axis(map.hatX)
        val hatY = event.axis(map.hatY)
        val stickX = if (playerInputEnabled()) 0f else event.axis(map.leftStickX)
        val stickY = event.axis(map.leftStickY)
        val deadX = map.deadzone(map.leftStickX, bindings.deadzoneFallback)
        val deadY = map.deadzone(map.leftStickY, bindings.deadzoneFallback)

        val horizontal = when {
            abs(hatX) >= HAT_THRESHOLD -> if (hatX > 0f) InputAction.Dir.RIGHT else InputAction.Dir.LEFT
            abs(stickX) >= deadX -> if (stickX > 0f) InputAction.Dir.RIGHT else InputAction.Dir.LEFT
            else -> null
        }
        val vertical = when {
            abs(hatY) >= HAT_THRESHOLD -> if (hatY > 0f) InputAction.Dir.DOWN else InputAction.Dir.UP
            abs(stickY) >= deadY -> if (stickY > 0f) InputAction.Dir.DOWN else InputAction.Dir.UP
            else -> null
        }

        motionNavDir = when {
            horizontal != null && vertical == null -> horizontal
            vertical != null && horizontal == null -> vertical
            horizontal != null && vertical != null -> {
                val hMag = max(abs(hatX), abs(stickX))
                val vMag = max(abs(hatY), abs(stickY))
                if (hMag >= vMag) horizontal else vertical
            }
            else -> null
        }
        updateNav(now)
    }

    private fun updateScrub(event: RawMotion, map: AxisMap) {
        val left = triggerValue(event.axis(map.leftTrigger), map.triggersAreZeroToOne)
        val right = triggerValue(event.axis(map.rightTrigger), map.triggersAreZeroToOne)
        val pressure = max(left, right)
        val rate = if (pressure < TRIGGER_THRESHOLD) {
            0f
        } else {
            val sign = if (right >= left) 1f else -1f
            sign * pressure * pressure
        }
        if (rate != lastScrubRate) {
            emit(InputAction.Scrub(rate))
            lastScrubRate = rate
        }
    }

    private fun triggerValue(raw: Float, zeroToOne: Boolean): Float =
        if (zeroToOne) raw.coerceIn(0f, 1f) else ((raw + 1f) / 2f).coerceIn(0f, 1f)

    private fun emitStickAdjustments(event: RawMotion, map: AxisMap) {
        val rsx = event.axis(map.rightStickX)
        val rsy = event.axis(map.rightStickY)
        if (abs(rsy) >= map.deadzone(map.rightStickY, bindings.deadzoneFallback)) {
            emit(InputAction.Zoom(-rsy))
        }
        if (abs(rsx) >= map.deadzone(map.rightStickX, bindings.deadzoneFallback)) {
            emit(InputAction.ScreenDistance(rsx))
        }
    }

    // endregion

    // region auto-repeat + long-press

    private fun updateNav(now: Long) {
        val dir = keyNavDir ?: motionNavDir
        if (dir == repeatDir) return
        repeatDir = dir
        if (dir != null) {
            emit(InputAction.Nav(dir, repeat = false))
            navStartNanos = now
            nextRepeatNanos = now + bindings.repeatDelayMs * NANOS_PER_MS
        }
    }

    fun tick() {
        syncContext()
        val now = clock()

        val longThresholdNanos = bindings.longPressMs * NANOS_PER_MS
        for (held in heldKeys.values) {
            if (!held.longFired && now - held.downNanos >= longThresholdNanos) {
                held.longFired = true
                emit(held.longAction)
            }
        }

        val dir = repeatDir ?: return
        if (now >= nextRepeatNanos) {
            emit(InputAction.Nav(dir, repeat = true))
            val heldFor = now - navStartNanos
            val intervalMs = if (heldFor >= bindings.repeatAccelAfterMs * NANOS_PER_MS) {
                bindings.repeatAccelIntervalMs
            } else {
                bindings.repeatIntervalMs
            }
            nextRepeatNanos = now + intervalMs * NANOS_PER_MS
        }
    }

    // endregion

    companion object {
        private const val NANOS_PER_MS = 1_000_000L
        private const val HAT_THRESHOLD = 0.5f
        private const val TRIGGER_THRESHOLD = 0.02f
    }
}
