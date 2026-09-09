package com.daydreamvr.vrcore.ui.widgets

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import com.daydreamvr.vrcore.ui.Icon
import com.daydreamvr.vrcore.ui.Icons
import com.daydreamvr.vrcore.ui.PanelMetrics
import com.daydreamvr.vrcore.ui.Radius
import com.daydreamvr.vrcore.ui.Space
import com.daydreamvr.vrcore.ui.Surfaces
import com.daydreamvr.vrcore.ui.TextMeasure
import com.daydreamvr.vrcore.ui.Theme
import com.daydreamvr.vrcore.ui.Type

/**
 * A vertically scrolling list whose layout is computed once, purely, and
 * published as both pixel boxes and (via the caller) hit regions — the code that
 * draws a row is the code that knows where it is (UI_GAZE_PLAN.md §4.7).
 *
 * Row height and every baseline come from [TextMeasure] font metrics, never
 * `textSize + magicConstant`, which permanently closes the F2 collision class.
 */
class ListView(
    private val theme: Theme,
    private val metrics: PanelMetrics,
    private val measure: TextMeasure = TextMeasure.PAINT,
) {

    sealed interface Entry {
        data class Item(
            val title: String,
            val subtitle: String? = null,
            val trailing: String? = null,
            val icon: Icon = Icon.NONE,
            val watchedFraction: Float = 0f,
            val finished: Boolean = false,
            val style: Style = Style.DEFAULT,
        ) : Entry

        /** Non-focusable group label (settings sections). */
        data class Header(val label: String) : Entry
    }

    enum class Style { DEFAULT, ACTION, DANGER }

    data class Box(
        val index: Int,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val focusable: Boolean,
    )

    data class Layout(
        val rowHeightPx: Float,
        val headerHeightPx: Float,
        val visibleCount: Int,
        val boxes: List<Box>,
        val titleBaselineDy: Float,
        val subtitleBaselineDy: Float,
        val twoLine: Boolean,
    )

    private data class RowMetrics(
        val rowHeight: Float,
        val headerHeight: Float,
        val titleBaselineDy: Float,
        val subtitleBaselineDy: Float,
        val twoLine: Boolean,
        val padH: Float,
        val gutter: Float,
    )

    private fun rowMetrics(twoLine: Boolean): RowMetrics {
        val padV = metrics.px(Space.M)
        val padH = metrics.px(Space.L)
        val gap = metrics.px(Space.XS)
        val titleSize = metrics.px(Type.rowTitle.degrees)
        val subSize = metrics.px(Type.rowSubtitle.degrees)
        val tAsc = -measure.ascentPx(titleSize, bold = true)
        val tDesc = measure.descentPx(titleSize, bold = true)
        val sAsc = -measure.ascentPx(subSize, bold = false)
        val sDesc = measure.descentPx(subSize, bold = false)

        val titleBaselineDy = padV + tAsc
        val subtitleBaselineDy = titleBaselineDy + tDesc + gap + sAsc
        val rowHeight = if (twoLine) {
            subtitleBaselineDy + sDesc + padV
        } else {
            titleBaselineDy + tDesc + padV
        }

        val labelSize = metrics.px(Type.sectionLabel.degrees)
        val headerHeight = metrics.px(Space.XL) + (-measure.ascentPx(labelSize, bold = true)) +
            measure.descentPx(labelSize, bold = true)

        return RowMetrics(rowHeight, headerHeight, titleBaselineDy, subtitleBaselineDy, twoLine, padH, metrics.px(2.4f))
    }

    /** PURE. No Canvas, no Paint — text widths come through [measure]. */
    fun measureLayout(
        entries: List<Entry>,
        top: Float,
        height: Float,
        left: Float,
        width: Float,
        scrollTop: Int,
    ): Layout {
        val twoLine = entries.any { it is Entry.Item && it.subtitle != null }
        val rm = rowMetrics(twoLine)

        val boxes = ArrayList<Box>()
        var y = top
        var i = scrollTop.coerceAtLeast(0)
        while (i < entries.size) {
            val e = entries[i]
            val h = if (e is Entry.Header) rm.headerHeight else rm.rowHeight
            if (y + h > top + height + 0.5f) break
            boxes.add(Box(i, left, y, left + width, y + h, focusable = e is Entry.Item))
            y += h
            i++
        }

        return Layout(
            rowHeightPx = rm.rowHeight,
            headerHeightPx = rm.headerHeight,
            visibleCount = boxes.size,
            boxes = boxes,
            titleBaselineDy = rm.titleBaselineDy,
            subtitleBaselineDy = rm.subtitleBaselineDy,
            twoLine = twoLine,
        )
    }

    /** Rows fitting [height]; the value the reducer must use as its scroll window. */
    fun visibleRowCount(height: Float, twoLine: Boolean): Int {
        val rm = rowMetrics(twoLine)
        return (height / rm.rowHeight).toInt().coerceAtLeast(1)
    }

    fun draw(
        canvas: Canvas,
        entries: List<Entry>,
        layout: Layout,
        focusIndex: Int,
        hoverIndex: Int?,
        scrollTop: Int,
    ) {
        val rm = rowMetrics(layout.twoLine)
        val titlePaint = theme.text(Type.rowTitle, metrics)
        val subPaint = theme.text(Type.rowSubtitle, metrics)
        val metaPaint = theme.text(Type.meta, metrics).apply { textAlign = Paint.Align.RIGHT }
        val headerPaint = theme.text(Type.sectionLabel, metrics)
        val actionPaint = theme.text(Type.rowTitle, metrics, colorOverride = theme.accentText)
        val dangerPaint = theme.text(Type.rowTitle, metrics, colorOverride = theme.warning)

        for (box in layout.boxes) {
            val entry = entries.getOrNull(box.index) ?: continue
            when (entry) {
                is Entry.Header -> {
                    canvas.drawText(
                        entry.label.uppercase(),
                        box.left + rm.padH,
                        box.bottom - rm.headerHeight * 0.28f,
                        headerPaint,
                    )
                }

                is Entry.Item -> {
                    val rect = RectF(box.left, box.top + 1f, box.right, box.bottom - 1f)
                    val focused = box.index == focusIndex
                    val hovered = hoverIndex != null && box.index == hoverIndex
                    if (focused) {
                        Surfaces.focus(canvas, rect, metrics, theme, Radius.CARD)
                    } else if (hovered) {
                        Surfaces.hover(canvas, rect, metrics, theme, Radius.CARD)
                    }

                    val textLeft = box.left + rm.padH + rm.gutter
                    if (entry.icon != Icon.NONE) {
                        val iconSize = metrics.px(1.7f)
                        Icons.draw(
                            canvas, entry.icon,
                            box.left + rm.padH + rm.gutter / 2f,
                            box.top + layout.titleBaselineDy - metrics.px(Type.rowTitle.degrees) * 0.35f,
                            iconSize, titlePaint,
                        )
                    }

                    val paint = when (entry.style) {
                        Style.ACTION -> actionPaint
                        Style.DANGER -> dangerPaint
                        Style.DEFAULT -> titlePaint
                    }
                    val trailingW = entry.trailing?.let { measure.width(it, metaPaint.textSize, bold = false) + rm.padH } ?: 0f
                    val titleMax = box.right - rm.padH - textLeft - trailingW
                    canvas.drawText(
                        ellipsize(entry.title, titleMax, titlePaint),
                        textLeft, box.top + layout.titleBaselineDy, paint,
                    )
                    entry.subtitle?.let {
                        val subMax = box.right - rm.padH - textLeft
                        canvas.drawText(
                            ellipsize(it, subMax, subPaint),
                            textLeft, box.top + layout.subtitleBaselineDy, subPaint,
                        )
                    }
                    entry.trailing?.let {
                        canvas.drawText(it, box.right - rm.padH, box.top + layout.titleBaselineDy, metaPaint)
                    }
                    if (entry.watchedFraction > 0f) {
                        val underlineY = box.bottom - metrics.px(Space.XS)
                        val paintBar = theme.fillPaint(if (entry.finished) theme.watched else theme.accent)
                        canvas.drawRect(
                            textLeft, underlineY,
                            textLeft + (box.right - rm.padH - textLeft) * entry.watchedFraction.coerceIn(0f, 1f),
                            underlineY + 3f, paintBar,
                        )
                    }
                }
            }
        }

        drawScrollbar(canvas, entries, layout, scrollTop)
    }

    private fun drawScrollbar(canvas: Canvas, entries: List<Entry>, layout: Layout, scrollTop: Int) {
        if (layout.boxes.isEmpty() || entries.size <= layout.visibleCount) return
        val first = layout.boxes.first()
        val last = layout.boxes.last()
        val trackTop = first.top
        val trackH = last.bottom - first.top
        val x = first.right - metrics.px(Space.S)
        val frac = layout.visibleCount.toFloat() / entries.size
        val thumbH = (trackH * frac).coerceAtLeast(metrics.px(1.2f))
        val thumbY = trackTop + (trackH - thumbH) * (scrollTop.toFloat() / (entries.size - layout.visibleCount)).coerceIn(0f, 1f)
        val r = metrics.px(Radius.CHIP)
        canvas.drawRoundRect(
            RectF(x, thumbY, x + 3f, thumbY + thumbH), r, r,
            theme.fillPaint((theme.accent and 0x00FFFFFF) or (0x99 shl 24)),
        )
    }

    private fun ellipsize(text: String, maxWidth: Float, paint: Paint): String {
        if (maxWidth <= 0f) return ""
        if (measure.width(text, paint.textSize, paint.typeface?.isBold == true) <= maxWidth) return text
        val tp = TextPaint(paint)
        return TextUtils.ellipsize(text, tp, maxWidth, TextUtils.TruncateAt.END).toString()
    }
}
