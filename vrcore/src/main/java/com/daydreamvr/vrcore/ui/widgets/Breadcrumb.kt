package com.daydreamvr.vrcore.ui.widgets

import android.graphics.Canvas
import com.daydreamvr.vrcore.ui.Icon
import com.daydreamvr.vrcore.ui.Icons
import com.daydreamvr.vrcore.ui.PanelMetrics
import com.daydreamvr.vrcore.ui.Space
import com.daydreamvr.vrcore.ui.Surfaces
import com.daydreamvr.vrcore.ui.Theme
import com.daydreamvr.vrcore.ui.Type

/**
 * The path strip at the top of the browse screen. Middle-truncates past three
 * segments ("Gerbera › … › Films"); chevrons are drawn as icons, not glyphs.
 */
class Breadcrumb(private val theme: Theme, private val metrics: PanelMetrics) {

    fun draw(canvas: Canvas, segments: List<String>, top: Float = 0f): Float {
        val padL = metrics.px(Space.XL)
        val padT = if (top > 0f) top else metrics.px(Space.L)
        val ancestor = theme.text(Type.rowSubtitle, metrics)
        val current = theme.text(Type.rowTitle, metrics)
        val sizePx = metrics.px(Type.rowTitle.degrees)
        val baseline = padT + sizePx * 0.82f

        val shown = truncate(segments)
        var x = padL
        val chevron = metrics.px(1.2f)
        shown.forEachIndexed { i, seg ->
            if (i > 0) {
                Icons.draw(canvas, Icon.CHEVRON, x + chevron / 2f, baseline - sizePx * 0.3f, chevron, ancestor)
                x += chevron + metrics.px(Space.XS)
            }
            val p = if (i == shown.lastIndex) current else ancestor
            canvas.drawText(seg, x, baseline, p)
            x += p.measureText(seg) + metrics.px(Space.S)
        }

        val contentTop = padT + sizePx * 1.1f + metrics.px(Space.M)
        Surfaces.divider(canvas, padL, metrics.widthPx - padL, contentTop - metrics.px(Space.S), theme)
        return contentTop
    }

    private fun truncate(segments: List<String>): List<String> =
        if (segments.size <= 3) {
            segments
        } else {
            listOf(segments.first(), "…", segments[segments.lastIndex - 1], segments.last())
        }
}
