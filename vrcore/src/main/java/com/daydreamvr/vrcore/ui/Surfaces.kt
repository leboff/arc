package com.daydreamvr.vrcore.ui

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader

/**
 * The elevation recipes (UI_GAZE_PLAN.md §4.4): dark glassmorphic panels, a
 * specular top sheen, hairline highlights, glowing cyan focus, and a quieter
 * gaze-hover treatment.
 *
 * All radii/insets arrive as degrees and resolve through [PanelMetrics]. No
 * `Paint.setShadowLayer` — it is inconsistent on `SurfaceTexture`-backed
 * hardware canvases; bloom is drawn as explicit stacked strokes instead.
 */
object Surfaces {

    /** Full-panel background: gradient fill + specular sheen + top hairline + outer stroke. */
    fun panel(canvas: Canvas, m: PanelMetrics, theme: Theme) {
        val w = m.widthPx.toFloat()
        val h = m.heightPx.toFloat()
        val r = m.px(Radius.PANEL)
        val rect = RectF(1f, 1f, w - 1f, h - 1f)

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 0f, h, theme.panelFillTop, theme.panelFillBottom, Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(rect, r, r, fill)

        val sheen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, h * 0.45f,
                theme.panelSheen, 0x00FFFFFF, Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(rect, r, r, sheen)

        // Top hairline: horizontal gradient fading to zero at both ends so it reads
        // as a light source rather than a border.
        val hairline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
            shader = LinearGradient(
                r, 0f, w - r, 0f,
                intArrayOf(0x00FFFFFF, theme.panelHairline, 0x00FFFFFF),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawLine(r, 2f, w - r, 2f, hairline)

        canvas.drawRoundRect(rect, r, r, theme.strokePaint(theme.panelStroke, 1f))
    }

    /** Elevated card (detail card, dialog, footer button). */
    fun card(canvas: Canvas, rect: RectF, m: PanelMetrics, theme: Theme, radiusDeg: Float = Radius.CARD) {
        val r = m.px(radiusDeg)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, rect.top, 0f, rect.bottom,
                theme.cardFillTop, theme.cardFillBottom, Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(rect, r, r, fill)
        canvas.drawRoundRect(rect, r, r, theme.strokePaint(theme.cardStroke, 1f))
    }

    /** Focus treatment: fill + ring + 3-stop manual bloom + left accent bar. */
    fun focus(canvas: Canvas, rect: RectF, m: PanelMetrics, theme: Theme, radiusDeg: Float = Radius.CARD) {
        val r = m.px(radiusDeg)
        canvas.drawRoundRect(rect, r, r, theme.fillPaint(theme.focusFill))
        val step = m.px(0.10f)
        canvas.drawRoundRect(rect, r, r, theme.strokePaint(withAlpha(theme.accent, 0xE6), 2.5f))
        canvas.drawRoundRect(inset(rect, -step), r + step, r + step, theme.strokePaint(withAlpha(theme.accent, 0x66), 2f))
        canvas.drawRoundRect(inset(rect, -2 * step), r + 2 * step, r + 2 * step, theme.strokePaint(withAlpha(theme.accent, 0x29), 1.5f))
        // Left accent bar — the most legible focus cue at low effective resolution.
        val bar = RectF(rect.left, rect.top + r, rect.left + 3f, rect.bottom - r)
        canvas.drawRect(bar, theme.fillPaint(theme.accent))
    }

    /** Gaze hover: subtle fill + thin accent stroke, no bloom, no bar. */
    fun hover(canvas: Canvas, rect: RectF, m: PanelMetrics, theme: Theme, radiusDeg: Float = Radius.CARD) {
        val r = m.px(radiusDeg)
        canvas.drawRoundRect(rect, r, r, theme.fillPaint(theme.hoverFill))
        canvas.drawRoundRect(rect, r, r, theme.strokePaint(theme.hoverStroke, 1.5f))
    }

    fun divider(canvas: Canvas, x0: Float, x1: Float, y: Float, theme: Theme) {
        canvas.drawLine(x0, y, x1, y, theme.strokePaint(theme.dividerColor, 1f))
    }

    fun chip(canvas: Canvas, rect: RectF, m: PanelMetrics, theme: Theme, accented: Boolean) {
        val r = m.px(Radius.CHIP)
        if (accented) {
            canvas.drawRoundRect(rect, r, r, theme.fillPaint(withAlpha(theme.accent, 0x24)))
            canvas.drawRoundRect(rect, r, r, theme.strokePaint(withAlpha(theme.accent, 0x99), 1.5f))
        } else {
            canvas.drawRoundRect(rect, r, r, theme.fillPaint(theme.cardFillTop))
            canvas.drawRoundRect(rect, r, r, theme.strokePaint(theme.cardStroke, 1f))
        }
    }

    private fun inset(r: RectF, d: Float) = RectF(r.left + d, r.top + d, r.right - d, r.bottom - d)

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)
}
