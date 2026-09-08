package com.daydreamvr.player.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.daydreamvr.vrcore.input.GamepadCapabilities
import com.daydreamvr.vrcore.input.MotionRangeInfo
import com.daydreamvr.vrcore.input.RawKey
import com.daydreamvr.vrcore.input.RawMotion

/**
 * The Android <-> `vrcore` input boundary (ARCHITECTURE.md §8.3). Keeping this in
 * `:app` lets `GamepadDecoder` and its tests stay pure JVM.
 */

private val INTERESTING_AXES = intArrayOf(
    MotionEvent.AXIS_X,
    MotionEvent.AXIS_Y,
    MotionEvent.AXIS_Z,
    MotionEvent.AXIS_RX,
    MotionEvent.AXIS_RY,
    MotionEvent.AXIS_RZ,
    MotionEvent.AXIS_HAT_X,
    MotionEvent.AXIS_HAT_Y,
    MotionEvent.AXIS_LTRIGGER,
    MotionEvent.AXIS_RTRIGGER,
    MotionEvent.AXIS_GAS,
    MotionEvent.AXIS_BRAKE,
)

fun KeyEvent.toRawKey(): RawKey = RawKey(
    keyCode = keyCode,
    action = if (action == KeyEvent.ACTION_UP) RawKey.ACTION_UP else RawKey.ACTION_DOWN,
    repeatCount = repeatCount,
    eventTimeNanos = eventTime * 1_000_000L,
    deviceId = deviceId,
    source = source,
)

fun MotionEvent.toRawMotion(): RawMotion {
    val values = HashMap<Int, Float>(INTERESTING_AXES.size)
    for (axis in INTERESTING_AXES) {
        values[axis] = getAxisValue(axis)
    }
    return RawMotion(
        deviceId = deviceId,
        source = source,
        axisValues = values,
        eventTimeNanos = eventTime * 1_000_000L,
    )
}

fun InputDevice.toGamepadCapabilities(): GamepadCapabilities = GamepadCapabilities(
    descriptor = descriptor,
    id = id,
    name = name,
    sources = sources,
    motionRanges = motionRanges.map {
        MotionRangeInfo(axis = it.axis, min = it.min, max = it.max, flat = it.flat, fuzz = it.fuzz)
    },
)

fun InputDevice.isGamepad(): Boolean {
    val s = sources
    return s and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
        s and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
}
