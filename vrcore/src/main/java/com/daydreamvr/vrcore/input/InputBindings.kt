package com.daydreamvr.vrcore.input

/**
 * The button map plus the timing constants the decoder needs. Held as plain
 * primitives so it can be persisted as JSON later (ARCHITECTURE.md §8.4) without
 * dragging serialization into `vrcore` in Phase 1.
 *
 * Key codes match `android.view.KeyEvent.KEYCODE_*` (mirrored on [RawKey]).
 * The default map is the "browser context" column of ARCHITECTURE.md §8.4 with
 * the universally-safe player bindings folded in.
 */
data class InputBindings(
    val navKeys: Map<Int, InputAction.Dir> = DEFAULT_NAV_KEYS,
    val confirmKeys: Set<Int> = setOf(RawKey.KEYCODE_BUTTON_A, RawKey.KEYCODE_DPAD_CENTER, RawKey.KEYCODE_ENTER),
    val cancelKeys: Set<Int> = setOf(RawKey.KEYCODE_BUTTON_B, RawKey.KEYCODE_BACK),
    val playPauseKeys: Set<Int> = setOf(RawKey.KEYCODE_BUTTON_X, RawKey.KEYCODE_MEDIA_PLAY_PAUSE),
    val recenterKeys: Set<Int> = setOf(RawKey.KEYCODE_BUTTON_Y),
    val seekBackKeys: Set<Int> = setOf(RawKey.KEYCODE_BUTTON_L1),
    val seekForwardKeys: Set<Int> = setOf(RawKey.KEYCODE_BUTTON_R1),
    val menuKeys: Set<Int> = setOf(RawKey.KEYCODE_BUTTON_SELECT),
    val toggleHudKeys: Set<Int> = setOf(RawKey.KEYCODE_BUTTON_START),
    val cycleProjectionKeys: Set<Int> = setOf(RawKey.KEYCODE_BUTTON_THUMBR),

    val seekSeconds: Int = 10,

    val longPressMs: Long = 500,
    val repeatDelayMs: Long = 400,
    val repeatIntervalMs: Long = 120,
    val repeatAccelIntervalMs: Long = 60,
    val repeatAccelAfterMs: Long = 1_500,

    val deadzoneFallback: Float = AxisMap.DEFAULT_DEADZONE,
) {
    companion object {
        val DEFAULT_NAV_KEYS: Map<Int, InputAction.Dir> = mapOf(
            RawKey.KEYCODE_DPAD_UP to InputAction.Dir.UP,
            RawKey.KEYCODE_DPAD_DOWN to InputAction.Dir.DOWN,
            RawKey.KEYCODE_DPAD_LEFT to InputAction.Dir.LEFT,
            RawKey.KEYCODE_DPAD_RIGHT to InputAction.Dir.RIGHT,
        )
    }
}
