package com.daydreamvr.vrcore.input

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GamepadProfileResolverTest {

    private val resolver = GamepadProfileResolver()

    private fun range(axis: Int, min: Float = -1f, max: Float = 1f, flat: Float = 0f) =
        MotionRangeInfo(axis, min, max, flat)

    private fun caps(
        descriptor: String,
        name: String,
        axes: List<MotionRangeInfo>,
        id: Int = 1,
    ) = GamepadCapabilities(
        descriptor = descriptor,
        id = id,
        name = name,
        sources = SOURCE_GAMEPAD or SOURCE_JOYSTICK,
        motionRanges = axes,
    )

    private val xboxSeries = caps(
        "xbox-series", "Xbox Wireless Controller",
        listOf(
            range(RawMotion.AXIS_X), range(RawMotion.AXIS_Y),
            range(RawMotion.AXIS_Z), range(RawMotion.AXIS_RZ),
            range(RawMotion.AXIS_LTRIGGER, min = 0f), range(RawMotion.AXIS_RTRIGGER, min = 0f),
            range(RawMotion.AXIS_HAT_X), range(RawMotion.AXIS_HAT_Y),
        ),
    )

    private val dualShock4 = caps(
        "ds4", "Wireless Controller",
        listOf(
            range(RawMotion.AXIS_X), range(RawMotion.AXIS_Y),
            range(RawMotion.AXIS_Z), range(RawMotion.AXIS_RZ),
            range(RawMotion.AXIS_RX, min = 0f), range(RawMotion.AXIS_RY, min = 0f),
            range(RawMotion.AXIS_HAT_X), range(RawMotion.AXIS_HAT_Y),
        ),
    )

    private val dualSense = caps(
        "dualsense", "DualSense Wireless Controller",
        listOf(
            range(RawMotion.AXIS_X), range(RawMotion.AXIS_Y),
            range(RawMotion.AXIS_Z), range(RawMotion.AXIS_RZ),
            range(RawMotion.AXIS_LTRIGGER, min = 0f), range(RawMotion.AXIS_RTRIGGER, min = 0f),
            range(RawMotion.AXIS_HAT_X), range(RawMotion.AXIS_HAT_Y),
        ),
    )

    private val eightBitDoXbox = caps(
        "8bitdo-xinput", "8BitDo Pro 2",
        listOf(
            range(RawMotion.AXIS_X), range(RawMotion.AXIS_Y),
            range(RawMotion.AXIS_Z), range(RawMotion.AXIS_RZ),
            range(RawMotion.AXIS_LTRIGGER, min = 0f), range(RawMotion.AXIS_RTRIGGER, min = 0f),
        ),
    )

    private val eightBitDoSwitch = caps(
        "8bitdo-switch", "8BitDo Pro 2",
        listOf(
            range(RawMotion.AXIS_X), range(RawMotion.AXIS_Y),
            range(RawMotion.AXIS_Z), range(RawMotion.AXIS_RZ),
            range(RawMotion.AXIS_LTRIGGER, min = 0f), range(RawMotion.AXIS_RTRIGGER, min = 0f),
        ),
    )

    @Test
    fun rightStick_resolvesToZAndRz_forXboxDualSenseAndDs4() {
        for (device in listOf(xboxSeries, dualSense, dualShock4, eightBitDoXbox, eightBitDoSwitch)) {
            val map = GamepadProfileResolver().resolve(device)
            assertThat(map.rightStickX).isEqualTo(RawMotion.AXIS_Z)
            assertThat(map.rightStickY).isEqualTo(RawMotion.AXIS_RZ)
        }
    }

    @Test
    fun triggers_resolvePerFamily() {
        assertThat(GamepadProfileResolver().resolve(xboxSeries).let { it.leftTrigger to it.rightTrigger })
            .isEqualTo(RawMotion.AXIS_LTRIGGER to RawMotion.AXIS_RTRIGGER)
        assertThat(GamepadProfileResolver().resolve(dualSense).let { it.leftTrigger to it.rightTrigger })
            .isEqualTo(RawMotion.AXIS_LTRIGGER to RawMotion.AXIS_RTRIGGER)
        assertThat(GamepadProfileResolver().resolve(dualShock4).let { it.leftTrigger to it.rightTrigger })
            .isEqualTo(RawMotion.AXIS_RX to RawMotion.AXIS_RY)
        assertThat(GamepadProfileResolver().resolve(eightBitDoXbox).let { it.leftTrigger to it.rightTrigger })
            .isEqualTo(RawMotion.AXIS_LTRIGGER to RawMotion.AXIS_RTRIGGER)
    }

    @Test
    fun deadzone_fallsBackTo015_whenFlatIsZero() {
        val map = GamepadProfileResolver().resolve(xboxSeries)
        assertThat(map.deadzone(RawMotion.AXIS_Z)).isEqualTo(0.15f)
        // An axis the device never reported also falls back.
        assertThat(map.deadzone(RawMotion.AXIS_RX)).isEqualTo(0.15f)
    }

    @Test
    fun deadzone_usesReportedFlat_whenLargerThanFloor() {
        val device = caps(
            "wide-flat", "Loose Stick",
            listOf(
                range(RawMotion.AXIS_X, flat = 0.22f), range(RawMotion.AXIS_Y, flat = 0.22f),
                range(RawMotion.AXIS_Z), range(RawMotion.AXIS_RZ),
                range(RawMotion.AXIS_LTRIGGER, min = 0f), range(RawMotion.AXIS_RTRIGGER, min = 0f),
            ),
        )
        val map = GamepadProfileResolver().resolve(device)
        assertThat(map.deadzone(RawMotion.AXIS_X)).isEqualTo(0.22f)
    }

    @Test
    fun profile_isKeyedByDescriptor_andSurvivesIdChange() {
        val first = resolver.resolve(xboxSeries.copy(id = 10))
        val afterReconnect = resolver.resolve(xboxSeries.copy(id = 99))

        assertThat(afterReconnect).isSameInstanceAs(first)
        assertThat(resolver.cached(xboxSeries.descriptor)).isEqualTo(first)
    }

    private companion object {
        // Mirror android.view.InputDevice source bits.
        const val SOURCE_GAMEPAD = 0x00000401
        const val SOURCE_JOYSTICK = 0x01000010
    }
}
