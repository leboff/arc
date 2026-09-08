package com.daydreamvr.vrcore.input

/**
 * The resolved per-device axis assignment produced by [GamepadProfileResolver].
 * Axis ids match `android.view.MotionEvent.AXIS_*` (mirrored on [RawMotion] so
 * the decoder stays JVM-pure).
 *
 * [deadzones] holds a per-axis flat radius, already floored at 0.15
 * (ARCHITECTURE.md §8.2); [deadzone] falls back to [DEFAULT_DEADZONE] for axes
 * the device did not report.
 */
data class AxisMap(
    val rightStickX: Int,
    val rightStickY: Int,
    val leftTrigger: Int,
    val rightTrigger: Int,
    val leftStickX: Int = RawMotion.AXIS_X,
    val leftStickY: Int = RawMotion.AXIS_Y,
    val hatX: Int = RawMotion.AXIS_HAT_X,
    val hatY: Int = RawMotion.AXIS_HAT_Y,
    val triggersAreZeroToOne: Boolean = true,
    val deadzones: Map<Int, Float> = emptyMap(),
) {
    fun deadzone(axis: Int, fallback: Float = DEFAULT_DEADZONE): Float =
        deadzones[axis] ?: fallback

    companion object {
        const val DEFAULT_DEADZONE = 0.15f

        /** Xbox-family layout — the sane default before a device is inspected. */
        val DEFAULT = AxisMap(
            rightStickX = RawMotion.AXIS_Z,
            rightStickY = RawMotion.AXIS_RZ,
            leftTrigger = RawMotion.AXIS_LTRIGGER,
            rightTrigger = RawMotion.AXIS_RTRIGGER,
        )
    }
}
