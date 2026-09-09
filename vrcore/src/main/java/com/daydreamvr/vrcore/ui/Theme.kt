package com.daydreamvr.vrcore.ui

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/**
 * The one visual style shared by every in-headset panel (ARCHITECTURE.md §11.3).
 *
 * Colours: light text on a dark translucent panel; no saturated blue for text
 * (chromatic aberration is worst at the blue end); no pure white on black
 * (bloom through cheap lenses). Text sizes are requested in degrees of visual
 * angle via [AngularMetrics] by the widgets, not set here.
 */
class Theme(
    /** How many degrees of arc a full-width panel subtends — drives text sizing. */
    val panelWidthDegrees: Float = 42f,
) {
    val textColor: Int = Color.parseColor("#E8E8EA")
    val dimTextColor: Int = Color.parseColor("#9C9CA2")
    val panelColor: Int = Color.parseColor("#DD0A0A0C")
    val panelStrokeColor: Int = Color.parseColor("#2AFFFFFF")
    val focusFillColor: Int = Color.parseColor("#3348A0C8")
    val focusStrokeColor: Int = Color.parseColor("#FF6FB3D2")
    val accentColor: Int = Color.parseColor("#FF6FB3D2")
    val progressTrackColor: Int = Color.parseColor("#33FFFFFF")
    val progressFillColor: Int = Color.parseColor("#FF6FB3D2")
    val watchedColor: Int = Color.parseColor("#FFB8C36F")
    val errorColor: Int = Color.parseColor("#FFE07A6F")

    val cornerRadiusPx: Float = 18f
    val paddingPx: Float = 28f

    fun textPaint(sizePx: Float, color: Int = textColor, bold: Boolean = false): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = sizePx
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            isSubpixelText = true
        }

    fun fillPaint(color: Int): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }

    fun strokePaint(color: Int, widthPx: Float): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = widthPx
        }

    companion object {
        val DEFAULT = Theme()
    }
}
