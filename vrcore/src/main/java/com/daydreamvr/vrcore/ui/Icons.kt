package com.daydreamvr.vrcore.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

/** Line-icon set (UI_GAZE_PLAN.md §4.5, fixes F11 — no font-glyph dependency). */
enum class Icon {
    NONE, FOLDER, VIDEO, PLAY, PAUSE, CHECK, SERVER, PLUS, REFRESH,
    GEAR, CHEVRON, WARNING, SEARCH, BACKSPACE, ENTER, SPACE, SPINNER,
    // UI_REDESIGN_REVIEWED_PLAN.md §4 — same 24×24 grid, desaturated stroke.
    PHONE, NETWORK, STAR, SORT, GRID, RECENTER, EXIT, ASPECT, CHEVRON_LEFT, CHEVRON_RIGHT,
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
            Icon.PHONE -> {
                p.addRoundRect(7f, 3f, 17f, 21f, 2f, 2f, Path.Direction.CW)
                p.moveTo(11f, 18f); p.lineTo(13f, 18f)
            }
            Icon.NETWORK -> {
                p.addArc(4f, 6f, 20f, 22f, 200f, 140f)
                p.addArc(7.5f, 9.5f, 16.5f, 18.5f, 200f, 140f)
                p.moveTo(12f, 15f); p.lineTo(12.01f, 15f)
            }
            Icon.STAR -> {
                p.moveTo(12f, 3f); p.lineTo(14.7f, 9f); p.lineTo(21f, 9.7f); p.lineTo(16.5f, 14f)
                p.lineTo(17.7f, 20.5f); p.lineTo(12f, 17.3f); p.lineTo(6.3f, 20.5f); p.lineTo(7.5f, 14f)
                p.lineTo(3f, 9.7f); p.lineTo(9.3f, 9f); p.close()
            }
            Icon.SORT -> {
                p.moveTo(6f, 6f); p.lineTo(18f, 6f)
                p.moveTo(6f, 12f); p.lineTo(14f, 12f)
                p.moveTo(6f, 18f); p.lineTo(10f, 18f)
            }
            Icon.GRID -> {
                p.addRect(4f, 4f, 10f, 10f, Path.Direction.CW)
                p.addRect(14f, 4f, 20f, 10f, Path.Direction.CW)
                p.addRect(4f, 14f, 10f, 20f, Path.Direction.CW)
                p.addRect(14f, 14f, 20f, 20f, Path.Direction.CW)
            }
            Icon.RECENTER -> {
                p.addCircle(12f, 12f, 4f, Path.Direction.CW)
                p.moveTo(12f, 2f); p.lineTo(12f, 6f)
                p.moveTo(12f, 18f); p.lineTo(12f, 22f)
                p.moveTo(2f, 12f); p.lineTo(6f, 12f)
                p.moveTo(18f, 12f); p.lineTo(22f, 12f)
            }
            Icon.EXIT -> {
                p.moveTo(14f, 4f); p.lineTo(5f, 4f); p.lineTo(5f, 20f); p.lineTo(14f, 20f)
                p.moveTo(12f, 12f); p.lineTo(21f, 12f)
                p.moveTo(17f, 8f); p.lineTo(21f, 12f); p.lineTo(17f, 16f)
            }
            Icon.ASPECT -> {
                p.addRoundRect(3f, 6f, 21f, 18f, 1.5f, 1.5f, Path.Direction.CW)
                p.moveTo(7f, 10f); p.lineTo(7f, 14f); p.lineTo(11f, 14f)
                p.moveTo(17f, 14f); p.lineTo(17f, 10f); p.lineTo(13f, 10f)
            }
            Icon.CHEVRON_LEFT -> {
                p.moveTo(15f, 5f); p.lineTo(8f, 12f); p.lineTo(15f, 19f)
            }
            Icon.CHEVRON_RIGHT -> {
                p.moveTo(9f, 5f); p.lineTo(16f, 12f); p.lineTo(9f, 19f)
            }
        }
        return p
    }
}
