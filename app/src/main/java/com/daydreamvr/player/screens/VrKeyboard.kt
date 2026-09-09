package com.daydreamvr.player.screens

import android.graphics.Canvas
import android.graphics.RectF
import com.daydreamvr.vrcore.input.InputAction
import com.daydreamvr.vrcore.ui.AngularMetrics
import com.daydreamvr.vrcore.ui.PanelSurface
import com.daydreamvr.vrcore.ui.Theme

/**
 * The in-VR text entry grid (ARCHITECTURE.md §11.5). D-pad / stick moves the
 * cursor (wrapping), A types, B deletes, X types a space, Y toggles
 * numeric / alpha, R1 submits. A numeric-first layout is the default because
 * manual server IP entry is the dominant use case.
 *
 * [reduce] is pure and JVM-tested ([com.daydreamvr.player.screens.VrKeyboardTest]).
 */
class VrKeyboard(private val panel: PanelSurface? = null, private val theme: Theme = Theme.DEFAULT) {

    data class KeyboardState(
        val text: String = "",
        val cursorRow: Int = 0,
        val cursorCol: Int = 0,
        val numericMode: Boolean = true,
    )

    fun handle(action: InputAction, s: KeyboardState): KeyboardState = reduce(action, s, null)

    fun suggestions(subnetPrefix: String?): List<String> = suggestionsFor(subnetPrefix)

    // ---- rendering --------------------------------------------------------

    fun render(surface: PanelSurface, s: KeyboardState, subnetPrefix: String?) {
        surface.draw { canvas -> drawInto(canvas, surface.widthPx, surface.heightPx, s, subnetPrefix) }
    }

    /** Draws straight onto [canvas] — used by [com.daydreamvr.player.screens.OverlayRenderer]. */
    fun draw(canvas: Canvas, widthPx: Int, heightPx: Int, s: KeyboardState, subnetPrefix: String?) =
        drawInto(canvas, widthPx, heightPx, s, subnetPrefix)

    private fun drawInto(canvas: Canvas, w: Int, h: Int, s: KeyboardState, subnetPrefix: String?) {
        canvas.drawColor(theme.panelColor)
        val titleSize = AngularMetrics.textSizePx(2.4f, w, theme.panelWidthDegrees)
        val keySize = AngularMetrics.textSizePx(2.8f, w, theme.panelWidthDegrees)
        canvas.drawText(s.text.ifEmpty { "_" }, theme.paddingPx, theme.paddingPx + titleSize, theme.textPaint(titleSize, theme.accentColor, bold = true))

        val grid = layout(s.numericMode)
        val top = theme.paddingPx * 2 + titleSize
        val cellW = (w - theme.paddingPx * 2) / (grid.maxOf { it.size })
        val cellH = (h - top - theme.paddingPx) / grid.size
        grid.forEachIndexed { r, row ->
            row.forEachIndexed { c, key ->
                val x = theme.paddingPx + c * cellW
                val y = top + r * cellH
                val rect = RectF(x + 4f, y + 4f, x + cellW - 4f, y + cellH - 4f)
                if (r == s.cursorRow && c == s.cursorCol) {
                    canvas.drawRoundRect(rect, 10f, 10f, theme.fillPaint(theme.focusFillColor))
                    canvas.drawRoundRect(rect, 10f, 10f, theme.strokePaint(theme.focusStrokeColor, 3f))
                }
                canvas.drawText(label(key), x + cellW / 2 - keySize / 2, y + cellH / 2 + keySize / 3, theme.textPaint(keySize))
            }
        }
    }

    private fun label(key: String): String = when (key) {
        "\b" -> "⌫"
        "\n" -> "↵"
        " " -> "␣"
        "" -> ""
        else -> key
    }

    companion object {

        private val NUMERIC: List<List<String>> = listOf(
            listOf("1", "2", "3", "."),
            listOf("4", "5", "6", ":"),
            listOf("7", "8", "9", "\b"),
            listOf("0", "/", "-", "\n"),
        )

        private val ALPHA: List<List<String>> = listOf(
            listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
            listOf("a", "s", "d", "f", "g", "h", "j", "k", "l", "."),
            listOf("z", "x", "c", "v", "b", "n", "m", ":", "-", "\b"),
            listOf("1", "2", "3", "4", "5", "6", "7", "8", " ", "\n"),
        )

        fun layout(numeric: Boolean): List<List<String>> = if (numeric) NUMERIC else ALPHA

        fun keyAtCursor(s: KeyboardState): String {
            val grid = layout(s.numericMode)
            return grid.getOrNull(s.cursorRow)?.getOrNull(s.cursorCol) ?: ""
        }

        /** Pure keystroke reducer. [subnetPrefix] backs the one-press suggestion (Menu). */
        fun reduce(action: InputAction, s: KeyboardState, subnetPrefix: String?): KeyboardState {
            val grid = layout(s.numericMode)
            val rowCount = grid.size
            return when (action) {
                is InputAction.Nav -> {
                    var r = s.cursorRow
                    var c = s.cursorCol
                    when (action.dir) {
                        InputAction.Dir.UP -> r = (r - 1 + rowCount) % rowCount
                        InputAction.Dir.DOWN -> r = (r + 1) % rowCount
                        InputAction.Dir.LEFT -> {
                            val n = grid[r].size
                            c = (c - 1 + n) % n
                        }
                        InputAction.Dir.RIGHT -> {
                            val n = grid[r].size
                            c = (c + 1) % n
                        }
                    }
                    c = c.coerceIn(0, grid[r].size - 1)
                    s.copy(cursorRow = r, cursorCol = c)
                }

                is InputAction.Confirm -> when (val key = keyAtCursor(s)) {
                    "", "\n" -> s
                    "\b" -> s.copy(text = s.text.dropLast(1))
                    else -> s.copy(text = s.text + key)
                }

                is InputAction.Cancel -> s.copy(text = s.text.dropLast(1))

                InputAction.PlayPause -> s.copy(text = s.text + " ")

                InputAction.Recenter -> s.copy(numericMode = !s.numericMode, cursorRow = 0, cursorCol = 0)

                InputAction.Menu -> suggestionsFor(subnetPrefix).firstOrNull()?.let { s.copy(text = it) } ?: s

                else -> s
            }
        }

        fun suggestionsFor(prefix: String?): List<String> {
            if (prefix.isNullOrBlank()) return emptyList()
            val p = if (prefix.endsWith(".")) prefix else "$prefix."
            return listOf("${p}10:49152", "${p}1", "${p}10", "${p}2")
        }
    }
}
