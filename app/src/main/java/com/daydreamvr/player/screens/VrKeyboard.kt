package com.daydreamvr.player.screens

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.daydreamvr.vrcore.input.InputAction
import com.daydreamvr.vrcore.ui.Icon
import com.daydreamvr.vrcore.ui.Icons
import com.daydreamvr.vrcore.ui.PanelMetrics
import com.daydreamvr.vrcore.ui.PanelSurface
import com.daydreamvr.vrcore.ui.Radius
import com.daydreamvr.vrcore.ui.Space
import com.daydreamvr.vrcore.ui.Surfaces
import com.daydreamvr.vrcore.ui.Theme
import com.daydreamvr.vrcore.ui.Type

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

    /** Where key (row, col) sits, so the overlay can publish gaze hit regions. */
    data class KeyBox(val row: Int, val col: Int, val left: Float, val top: Float, val right: Float, val bottom: Float)

    /**
     * Draws the keyboard onto [canvas] and returns the per-key rectangles.
     * Labels are centred with [Paint.Align.CENTER] on a `FontMetrics`-derived
     * baseline (fixes F10) and non-alpha keys use icons, not glyphs.
     */
    fun draw(
        canvas: Canvas,
        metrics: PanelMetrics,
        s: KeyboardState,
        subnetPrefix: String?,
        cursorOnly: Boolean = false,
    ): List<KeyBox> {
        val w = metrics.widthPx
        val h = metrics.heightPx
        val pad = metrics.px(Space.XL)
        val titleSize = metrics.px(Type.screenTitle.degrees)
        if (!cursorOnly) {
            val field = RectF(pad, pad, w - pad, pad + titleSize * 1.6f)
            Surfaces.card(canvas, field, metrics, theme, Radius.CARD)
            canvas.drawText(
                s.text.ifEmpty { "|" },
                pad + metrics.px(Space.M),
                field.centerY() + titleSize * 0.35f,
                theme.text(Type.screenTitle, metrics, theme.accentText),
            )
        }

        val grid = layout(s.numericMode)
        val top = pad * 2 + titleSize
        val gap = metrics.px(Space.XS)
        val cols = grid.maxOf { it.size }
        val cellW = (w - pad * 2 - gap * (cols - 1)) / cols
        val cellH = (h - top - pad - gap * (grid.size - 1)) / grid.size
        val keyPaint = theme.text(Type.rowTitle, metrics).apply { textAlign = Paint.Align.CENTER }
        val boxes = ArrayList<KeyBox>()

        grid.forEachIndexed { r, row ->
            row.forEachIndexed { c, key ->
                val x = pad + c * (cellW + gap)
                val y = top + r * (cellH + gap)
                val rect = RectF(x, y, x + cellW, y + cellH)
                boxes += KeyBox(r, c, rect.left, rect.top, rect.right, rect.bottom)
                if (r == s.cursorRow && c == s.cursorCol) {
                    Surfaces.focus(canvas, rect, metrics, theme, Radius.CHIP)
                } else if (!cursorOnly) {
                    Surfaces.chip(canvas, rect, metrics, theme, accented = false)
                }
                if (cursorOnly) return@forEachIndexed
                val icon = iconFor(key)
                if (icon != Icon.NONE) {
                    Icons.draw(canvas, icon, rect.centerX(), rect.centerY(), metrics.px(1.8f), keyPaint)
                } else if (key.isNotEmpty()) {
                    val fm = keyPaint.fontMetrics
                    canvas.drawText(key, rect.centerX(), rect.centerY() - (fm.ascent + fm.descent) / 2f, keyPaint)
                }
            }
        }
        return boxes
    }

    private fun iconFor(key: String): Icon = when (key) {
        "\b" -> Icon.BACKSPACE
        "\n" -> Icon.ENTER
        " " -> Icon.SPACE
        else -> Icon.NONE
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
