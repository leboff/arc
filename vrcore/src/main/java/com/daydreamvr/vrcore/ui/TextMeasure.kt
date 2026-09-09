package com.daydreamvr.vrcore.ui

import android.graphics.Paint
import android.graphics.Typeface

/**
 * Injectable text measurement (UI_GAZE_PLAN.md §4.6).
 *
 * Layout code takes a `TextMeasure` so JVM tests can inject [fixed] and assert
 * exact rectangles — including vertical metrics. Without this,
 * `unitTests.isReturnDefaultValues = true` makes every `Paint` measurement
 * return `0` and layout assertions become vacuous.
 */
interface TextMeasure {

    fun width(text: String, sizePx: Float, bold: Boolean): Float

    /** Distance from baseline to the top of the tallest glyph, **negative** (like `FontMetrics.ascent`). */
    fun ascentPx(sizePx: Float, bold: Boolean): Float

    /** Distance from baseline to the bottom of the lowest descender, **positive**. */
    fun descentPx(sizePx: Float, bold: Boolean): Float

    companion object {

        /** Production: a cached [Paint] per (size, weight). */
        val PAINT: TextMeasure = object : TextMeasure {
            private val medium by lazy { Typeface.create("sans-serif-medium", Typeface.NORMAL) }
            private val regular by lazy { Typeface.create("sans-serif", Typeface.NORMAL) }
            private val paint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply { isSubpixelText = true } }

            private fun prime(sizePx: Float, bold: Boolean) {
                paint.textSize = sizePx
                paint.typeface = if (bold) medium else regular
            }

            override fun width(text: String, sizePx: Float, bold: Boolean): Float {
                prime(sizePx, bold)
                return paint.measureText(text)
            }

            override fun ascentPx(sizePx: Float, bold: Boolean): Float {
                prime(sizePx, bold)
                val a = paint.fontMetrics.ascent
                return if (a < 0f) a else DEFAULT_ASCENT_RATIO * sizePx
            }

            override fun descentPx(sizePx: Float, bold: Boolean): Float {
                prime(sizePx, bold)
                val d = paint.fontMetrics.descent
                return if (d > 0f) d else DEFAULT_DESCENT_RATIO * sizePx
            }
        }

        /** Tests: deterministic. Every glyph is [perCharPx] wide (× 1.08 bold) at 32 px. */
        fun fixed(
            perCharPx: Float,
            ascentRatio: Float = DEFAULT_ASCENT_RATIO,
            descentRatio: Float = DEFAULT_DESCENT_RATIO,
        ): TextMeasure = object : TextMeasure {
            override fun width(text: String, sizePx: Float, bold: Boolean): Float {
                val scale = sizePx / 32f
                return text.length * perCharPx * scale * (if (bold) 1.08f else 1f)
            }

            override fun ascentPx(sizePx: Float, bold: Boolean): Float = ascentRatio * sizePx
            override fun descentPx(sizePx: Float, bold: Boolean): Float = descentRatio * sizePx
        }

        private const val DEFAULT_ASCENT_RATIO = -0.95f
        private const val DEFAULT_DESCENT_RATIO = 0.27f
    }
}
