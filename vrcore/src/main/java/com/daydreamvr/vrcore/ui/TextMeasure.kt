package com.daydreamvr.vrcore.ui

import android.graphics.Paint
import android.graphics.Typeface

/**
 * Injectable text measurement (UI_GAZE_PLAN.md §4.6).
 *
 * Layout code takes a `TextMeasure` so JVM tests can inject [fixed] and assert
 * exact rectangles. Without this, `unitTests.isReturnDefaultValues = true` makes
 * `Paint.measureText` return `0` and every layout assertion is vacuous.
 */
fun interface TextMeasure {

    fun width(text: String, sizePx: Float, bold: Boolean): Float

    companion object {

        /** Production: a cached [Paint] per (size, weight). */
        val PAINT: TextMeasure = object : TextMeasure {
            private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isSubpixelText = true }

            override fun width(text: String, sizePx: Float, bold: Boolean): Float {
                paint.textSize = sizePx
                paint.typeface = if (bold) medium else regular
                return paint.measureText(text)
            }
        }

        /** Tests: deterministic — every glyph is [perCharPx] wide (× 1.08 when bold). */
        fun fixed(perCharPx: Float): TextMeasure =
            TextMeasure { text, sizePx, bold ->
                val scale = sizePx / 32f
                text.length * perCharPx * scale * (if (bold) 1.08f else 1f)
            }
    }
}
