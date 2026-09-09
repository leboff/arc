package com.daydreamvr.vrcore.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

/** Line-icon set (UI_GAZE_PLAN.md §4.5, fixes F11 — no font-glyph dependency). */
enum class Icon {
    NONE, FOLDER, VIDEO, PLAY, PAUSE, CHECK, SERVER, PLUS, REFRESH,
    GEAR, CHEVRON, WARNING, SEARCH, BACKSPACE, ENTER, SPACE, SPINNER
}

/**
 * Draws [Icon]s as stroked vector paths authored on a 24×24 grid, cached and
 * scaled through the canvas so they stay crisp at any size and carry no font
 * side-bearings.
 */
object Icons {

    private const val GRID = 24f
    private val cache = HashMap<Icon, Path>()

    private val fillIcons = setOf(Icon.PLAY, Icon.WARNING)

    /** Draws [icon] centred at ([cx], [cy]), fitted to a [sizePx] box, in [paint]'s colour. */
    fun draw(canvas: Canvas, icon: Icon, cx: Float, cy: Float, sizePx: Float, paint: Paint) {
        if (icon == Icon.NONE) return
        val path = cache.getOrPut(icon) { build(icon) }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = paint.color
            alpha = paint.alpha
            if (icon in fillIcons) {
                style = Paint.Style.FILL
            } else {
                style = Paint.Style.STROKE
                strokeWidth = 2f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
        }
        val s = sizePx / GRID
        canvas.save()
        canvas.translate(cx - sizePx / 2f, cy - sizePx / 2f)
        canvas.scale(s, s)
        canvas.drawPath(path, stroke)
        canvas.restore()
    }

    private fun build(icon: Icon): Path {
        val p = Path()
        when (icon) {
            Icon.NONE -> Unit
            Icon.FOLDER -> {
                p.moveTo(3f, 6f); p.lineTo(10f, 6f); p.lineTo(12f, 9f); p.lineTo(21f, 9f)
                p.lineTo(21f, 19f); p.lineTo(3f, 19f); p.close()
            }
            Icon.VIDEO -> {
                p.addRoundRect(3f, 6f, 16f, 18f, 2f, 2f, Path.Direction.CW)
                p.moveTo(16f, 10f); p.lineTo(21f, 7f); p.lineTo(21f, 17f); p.lineTo(16f, 14f); p.close()
            }
            Icon.PLAY -> {
                p.moveTo(7f, 5f); p.lineTo(19f, 12f); p.lineTo(7f, 19f); p.close()
            }
            Icon.PAUSE -> {
                p.addRect(7f, 5f, 10f, 19f, Path.Direction.CW)
                p.addRect(14f, 5f, 17f, 19f, Path.Direction.CW)
            }
            Icon.CHECK -> {
                p.moveTo(5f, 13f); p.lineTo(10f, 18f); p.lineTo(19f, 6f)
            }
            Icon.SERVER -> {
                p.addRoundRect(4f, 4f, 20f, 11f, 1.5f, 1.5f, Path.Direction.CW)
                p.addRoundRect(4f, 13f, 20f, 20f, 1.5f, 1.5f, Path.Direction.CW)
                p.moveTo(7f, 7.5f); p.lineTo(7.01f, 7.5f)
                p.moveTo(7f, 16.5f); p.lineTo(7.01f, 16.5f)
            }
            Icon.PLUS -> {
                p.moveTo(12f, 5f); p.lineTo(12f, 19f)
                p.moveTo(5f, 12f); p.lineTo(19f, 12f)
            }
            Icon.REFRESH -> {
                p.addArc(5f, 5f, 19f, 19f, -40f, 280f)
                p.moveTo(19f, 4f); p.lineTo(19f, 10f); p.lineTo(13f, 10f)
            }
            Icon.GEAR -> {
                p.addCircle(12f, 12f, 3.5f, Path.Direction.CW)
                for (i in 0 until 8) {
                    val a = Math.toRadians((i * 45).toDouble())
                    val cxp = 12f + 8f * Math.cos(a).toFloat()
                    val cyp = 12f + 8f * Math.sin(a).toFloat()
                    p.moveTo(12f, 12f); p.lineTo(cxp, cyp)
                }
            }
            Icon.CHEVRON -> {
                p.moveTo(9f, 5f); p.lineTo(16f, 12f); p.lineTo(9f, 19f)
            }
            Icon.WARNING -> {
                p.moveTo(12f, 3f); p.lineTo(22f, 20f); p.lineTo(2f, 20f); p.close()
            }
            Icon.SEARCH -> {
                p.addCircle(11f, 11f, 6f, Path.Direction.CW)
                p.moveTo(15.5f, 15.5f); p.lineTo(20f, 20f)
            }
            Icon.BACKSPACE -> {
                p.moveTo(9f, 5f); p.lineTo(21f, 5f); p.lineTo(21f, 19f); p.lineTo(9f, 19f)
                p.lineTo(3f, 12f); p.close()
                p.moveTo(12f, 9f); p.lineTo(18f, 15f)
                p.moveTo(18f, 9f); p.lineTo(12f, 15f)
            }
            Icon.ENTER -> {
                p.moveTo(21f, 6f); p.lineTo(21f, 14f); p.lineTo(6f, 14f)
                p.moveTo(11f, 9f); p.lineTo(6f, 14f); p.lineTo(11f, 19f)
            }
            Icon.SPACE -> {
                p.moveTo(4f, 10f); p.lineTo(4f, 16f); p.lineTo(20f, 16f); p.lineTo(20f, 10f)
            }
            Icon.SPINNER -> {
                p.addArc(5f, 5f, 19f, 19f, 20f, 300f)
            }
        }
        return p
    }
}
