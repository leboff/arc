package com.daydreamvr.player.debug

import com.daydreamvr.vrcore.input.InputAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * A tiny rolling log of decoded gamepad actions, surfaced identically in both
 * eyes (Phase 1 acceptance: "every button and axis produces a labelled line in
 * the stereo debug overlay, in both eyes").
 *
 * Phase 1 renders this into two `TextView`s over the GL surface (one per eye
 * half); a proper canvas-to-texture panel arrives in Phase 5.
 */
class DebugOverlay(private val maxLines: Int = 14) {

    private val lines = ArrayDeque<String>()
    private val _text = MutableStateFlow("Gamepad debug overlay\nWaiting for input…")
    val text: StateFlow<String> = _text.asStateFlow()

    @Synchronized
    fun log(message: String) {
        lines.addLast(message)
        while (lines.size > maxLines) lines.removeFirst()
        _text.value = lines.joinToString("\n")
    }

    fun onAction(action: InputAction) = log(describe(action))

    private fun describe(action: InputAction): String = when (action) {
        is InputAction.Nav -> "NAV ${action.dir}" + if (action.repeat) " (repeat)" else ""
        is InputAction.Confirm -> "CONFIRM" + if (action.long) " (long)" else ""
        is InputAction.Cancel -> "CANCEL" + if (action.long) " (long)" else ""
        InputAction.PlayPause -> "PLAY/PAUSE"
        InputAction.Recenter -> "RECENTER"
        is InputAction.Seek -> "SEEK ${action.deltaSeconds}s"
        is InputAction.Scrub -> String.format(Locale.US, "SCRUB %.2f", action.rate)
        is InputAction.Zoom -> String.format(Locale.US, "ZOOM %.2f", action.delta)
        is InputAction.ScreenDistance -> String.format(Locale.US, "SCREEN DIST %.2f", action.delta)
        InputAction.Menu -> "MENU"
        InputAction.ToggleHud -> "TOGGLE HUD"
        InputAction.CycleProjection -> "CYCLE PROJECTION"
        InputAction.PageUp -> "PAGE UP"
        InputAction.PageDown -> "PAGE DOWN"
    }
}
