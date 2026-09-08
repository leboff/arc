package com.daydreamvr.vrcore.input

/**
 * The semantic vocabulary the rest of the app reacts to. [GamepadDecoder] is the
 * only producer; screens and the state machine are the consumers. Raw Android
 * key/axis constants never escape the decoder (ARCHITECTURE.md §8.1).
 */
sealed interface InputAction {

    enum class Dir { UP, DOWN, LEFT, RIGHT }

    /** Discrete list / menu navigation. [repeat] is true for auto-repeat fires. */
    data class Nav(val dir: Dir, val repeat: Boolean) : InputAction

    data class Confirm(val long: Boolean) : InputAction

    data class Cancel(val long: Boolean) : InputAction

    data object PlayPause : InputAction

    data object Recenter : InputAction

    /** Bumper seek, whole seconds, signed. */
    data class Seek(val deltaSeconds: Int) : InputAction

    /** Analog trigger scrub, normalised to `[-1, 1]`; `0` means released. */
    data class Scrub(val rate: Float) : InputAction

    data class Zoom(val delta: Float) : InputAction

    data class ScreenDistance(val delta: Float) : InputAction

    data object Menu : InputAction

    data object ToggleHud : InputAction

    data object CycleProjection : InputAction

    data object PageUp : InputAction

    data object PageDown : InputAction
}
