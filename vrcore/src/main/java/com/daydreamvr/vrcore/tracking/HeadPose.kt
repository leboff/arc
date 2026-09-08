package com.daydreamvr.vrcore.tracking

/**
 * Which fused-orientation sensor a [HeadTracker] is currently running on
 * (ARCHITECTURE.md §7.1, priority order high → low).
 */
enum class SensorKind {
    /** `TYPE_GAME_ROTATION_VECTOR` — gyro + accel, no magnetometer. Preferred. */
    GAME_ROTATION_VECTOR,

    /** `TYPE_ROTATION_VECTOR` — compass-locked fusion. Fallback. */
    ROTATION_VECTOR,

    /** `TYPE_ACCELEROMETER` + `TYPE_MAGNETIC_FIELD`. Last-ditch, jittery. */
    ACCEL_MAG,

    /** No usable orientation sensor. */
    NONE,
}

/**
 * One head-orientation sample (ARCHITECTURE.md §7.2). Pushed by the sensor
 * thread, read by the GL thread via an `AtomicReference`.
 *
 * @param timestampNs sensor event timestamp, `CLOCK_BOOTTIME`-ish base shared
 *   with `System.nanoTime` on modern devices (ARCHITECTURE.md §5.4).
 * @param rotation the head orientation `R_W_H` as a column-major 4×4 matrix
 *   (maps head-space vectors into GL world space).
 * @param omega body-frame angular velocity in rad/s (`FloatArray(3)`), from
 *   `TYPE_GYROSCOPE` when present, else `null` (predictor falls back to finite
 *   differences).
 */
class PoseSample(
    val timestampNs: Long,
    val rotation: FloatArray,
    val omega: FloatArray?,
) {
    init {
        require(rotation.size == 16) { "rotation must be a column-major 4x4 matrix" }
        require(omega == null || omega.size == 3) { "omega must be a 3-vector" }
    }
}
