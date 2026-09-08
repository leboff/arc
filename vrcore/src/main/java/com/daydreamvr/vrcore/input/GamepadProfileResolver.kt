package com.daydreamvr.vrcore.input

import kotlin.math.max

/** One `InputDevice.MotionRange`, copied into a JVM-pure value type. */
data class MotionRangeInfo(
    val axis: Int,
    val min: Float,
    val max: Float,
    val flat: Float,
    val fuzz: Float = 0f,
)

/**
 * The capability snapshot of a connected gamepad, adapted from `InputDevice` by
 * `:app` so the resolver is unit-testable (ARCHITECTURE.md §8.2).
 */
data class GamepadCapabilities(
    val descriptor: String,
    val id: Int,
    val name: String,
    val sources: Int,
    val motionRanges: List<MotionRangeInfo>,
    val keys: Set<Int> = emptySet(),
)

/**
 * Turns a [GamepadCapabilities] into an [AxisMap] by probing which axes the
 * device actually reports (ARCHITECTURE.md §8.2):
 *
 *  - Right stick = first present pair among `(Z, RZ)`, `(RX, RY)`.
 *  - Triggers = `(LTRIGGER, RTRIGGER)`; else `(BRAKE, GAS)`; else whichever of
 *    `(RX, RY)` was not claimed by the right stick.
 *  - Per-axis deadzone from `MotionRange.flat`, floored at 0.15.
 *
 * Results are cached by `descriptor` (stable across Bluetooth reconnects, unlike
 * `id`), so a one-off remap survives a reconnect.
 */
class GamepadProfileResolver {

    private val cacheByDescriptor = HashMap<String, AxisMap>()

    fun resolve(caps: GamepadCapabilities): AxisMap {
        cacheByDescriptor[caps.descriptor]?.let { return it }

        val axes = caps.motionRanges.associateBy { it.axis }
        fun has(axis: Int) = axes.containsKey(axis)

        val (rightStickX, rightStickY) = when {
            has(RawMotion.AXIS_Z) && has(RawMotion.AXIS_RZ) -> RawMotion.AXIS_Z to RawMotion.AXIS_RZ
            has(RawMotion.AXIS_RX) && has(RawMotion.AXIS_RY) -> RawMotion.AXIS_RX to RawMotion.AXIS_RY
            else -> RawMotion.AXIS_Z to RawMotion.AXIS_RZ
        }

        val (leftTrigger, rightTrigger) = when {
            has(RawMotion.AXIS_LTRIGGER) && has(RawMotion.AXIS_RTRIGGER) ->
                RawMotion.AXIS_LTRIGGER to RawMotion.AXIS_RTRIGGER
            has(RawMotion.AXIS_BRAKE) && has(RawMotion.AXIS_GAS) ->
                RawMotion.AXIS_BRAKE to RawMotion.AXIS_GAS
            rightStickX != RawMotion.AXIS_RX && has(RawMotion.AXIS_RX) && has(RawMotion.AXIS_RY) ->
                RawMotion.AXIS_RX to RawMotion.AXIS_RY
            else -> RawMotion.AXIS_LTRIGGER to RawMotion.AXIS_RTRIGGER
        }

        val triggersAreZeroToOne = axes[leftTrigger]?.let { it.min >= 0f } ?: true

        val deadzones = caps.motionRanges.associate { range ->
            range.axis to max(range.flat, AxisMap.DEFAULT_DEADZONE)
        }

        val map = AxisMap(
            rightStickX = rightStickX,
            rightStickY = rightStickY,
            leftTrigger = leftTrigger,
            rightTrigger = rightTrigger,
            triggersAreZeroToOne = triggersAreZeroToOne,
            deadzones = deadzones,
        )
        cacheByDescriptor[caps.descriptor] = map
        return map
    }

    fun cached(descriptor: String): AxisMap? = cacheByDescriptor[descriptor]

    fun forget(descriptor: String) {
        cacheByDescriptor.remove(descriptor)
    }
}
