package com.daydreamvr.player.screens.widgets

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import com.daydreamvr.player.media.thumb.ThumbnailCache
import com.daydreamvr.player.state.BrowseFocus
import com.daydreamvr.player.state.GazeTarget
import com.daydreamvr.vrcore.render.ProjectionMode
import com.daydreamvr.vrcore.ui.HitRegion
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
 * Centre column of the browse panel (UI_REDESIGN_REVIEWED_PLAN.md §5.2): a
 * **3 × 2** grid of media cards, six per page. The dimensions are derived, not
 * chosen — a 3 × 3 grid would drop `cardW` below the legibility floor for a 4K
 * keyframe.
 *
 * The whole card box is the hit region — not just the poster — so the reticle
 * never drops into a dead strip under a card. Published target:
 * `GazeTarget.GridCell(page * 6 + slot)`, always absolute.
 */
class MediaGridWidget(
    private val theme: Theme,
    private val metrics: PanelMetrics,
    private val measure: TextMeasure = TextMeasure.PAINT,
) {

    data class Model(
        /** The full (sorted) item list for the current folder — not a page slice. */
        val items: List<Card>,
        val page: Int = 0,
        val pageCount: Int = 1,
    ) {
        data class Card(
            val title: String,
            val meta: String? = null,
            val durationLabel: String? = null,
            val projection: ProjectionMode = ProjectionMode.FLAT,
            val qualityLabel: String? = null,
            val watchedFraction: Float = 0f,
            val finished: Boolean = false,
            val thumbnailKey: String? = null,
            val enabled: Boolean = true,
        )
    }

    data class CardLayout(
        val slot: Int,
        /** `page * PAGE_SIZE + slot`. */
        val absoluteIndex: Int,
        val box: PixRect,
        val poster: PixRect,
        val titleBaselines: List<Float>,
        val metaBaseline: Float,
        /** True when [Model.items] has an entry at [absoluteIndex]. */
        val populated: Boolean,
    )

    data class Layout(
        val gridBand: PixRect,
        val cardW: Float,
        val cardH: Float,
        val posterH: Float,
        val cards: List<CardLayout>,
        val bounds: PixRect,
    )

    /** PURE. */
    fun measureLayout(model: Model, bounds: PixRect): Layout {
        val padH = metrics.px(Space.L)
        val gapH = metrics.px(Space.M)
        val gapS = metrics.px(Space.S)

        val cardW = (bounds.width - 2 * padH - 2 * gapH) / COLS
        val posterH = cardW * 9f / 16f
        val lineH = metrics.px(Type.rowSubtitle.degrees) * Type.SUBTITLE_LINE_HEIGHT
        val titleH = 2 * lineH
        val metaH = lineH
        val cardH = posterH + gapS + titleH + metaH

        val gridTop = bounds.top + metrics.px(Bands.HEADER_DEG) + metrics.px(Bands.COMPACT_ROW_DEG)
        val gridBottom = bounds.bottom - metrics.px(Bands.COMPACT_ROW_DEG) - metrics.px(Bands.BOTTOM_PAD_DEG)
        val gridBand = PixRect(bounds.left, gridTop, bounds.right, gridBottom)
        val pitch = (gridBottom - gridTop) / ROWS

        val titleAsc = -measure.ascentPx(metrics.px(Type.rowSubtitle.degrees), bold = false)

        val cards = ArrayList<CardLayout>(PAGE_SIZE)
        for (slot in 0 until PAGE_SIZE) {
            val c = slot % COLS
            val r = slot / COLS
            val left = bounds.left + padH + c * (cardW + gapH)
            val top = gridTop + r * pitch
            val box = PixRect(left, top, left + cardW, top + cardH)
            val poster = PixRect(left, top, left + cardW, top + posterH)
            val b0 = poster.bottom + gapS + titleAsc
            val b1 = b0 + lineH
            val absoluteIndex = model.page * PAGE_SIZE + slot
            cards.add(
                CardLayout(
                    slot = slot,
                    absoluteIndex = absoluteIndex,
                    box = box,
                    poster = poster,
                    titleBaselines = listOf(b0, b1),
                    metaBaseline = b1 + lineH,
                    populated = absoluteIndex < model.items.size,
                ),
            )
        }
        return Layout(gridBand, cardW, cardH, posterH, cards, bounds)
    }

    fun hitRegions(layout: Layout): List<HitRegion<GazeTarget>> =
        layout.cards
            .filter { it.populated }
            .map { c ->
                HitRegion(c.box.left, c.box.top, c.box.right, c.box.bottom, GazeTarget.GridCell(c.absoluteIndex))
            }

    fun draw(
        canvas: Canvas,
        model: Model,
        layout: Layout,
        focus: BrowseFocus?,
        hover: GazeTarget?,
        thumbs: ThumbnailCache? = null,
    ) {
        val focusedIndex = (focus as? BrowseFocus.Grid)?.index
        val hoverIndex = (hover as? GazeTarget.GridCell)?.index
        val titlePaint = theme.text(Type.rowSubtitle, metrics)
        val metaPaint = theme.text(Type.meta, metrics)
        val pillPaint = theme.text(Type.numeral, metrics, theme.textPrimary).apply { textAlign = Paint.Align.RIGHT }

        for (card in layout.cards) {
            if (!card.populated) continue
            val item = model.items.getOrNull(card.absoluteIndex) ?: continue

            val boxRect = card.box.toRectF()
            when (card.absoluteIndex) {
                focusedIndex -> Surfaces.focus(canvas, boxRect, metrics, theme, Radius.CARD)
                hoverIndex -> Surfaces.hover(canvas, boxRect, metrics, theme, Radius.CARD)
            }

            drawPoster(canvas, card.poster, item, thumbs)

            // Badges.
            val inset = metrics.px(Space.S)
            projectionBadge(item.projection)?.let {
                drawBadge(canvas, it, card.poster.left + inset, card.poster.top + inset, theme.badgeVr, alignRight = false)
            }
            item.qualityLabel?.let {
                drawBadge(canvas, it, card.poster.right - inset, card.poster.top + inset, theme.badgeQuality, alignRight = true)
            }
            item.durationLabel?.let {
                canvas.drawText(it, card.poster.right - inset, card.poster.bottom - inset, pillPaint)
            }
            if (item.watchedFraction > 0f) {
                val barTop = card.poster.bottom - metrics.px(0.12f)
                canvas.drawRect(
                    card.poster.left, barTop,
                    card.poster.left + card.poster.width * item.watchedFraction.coerceIn(0f, 1f), card.poster.bottom,
                    theme.fillPaint(if (item.finished) theme.watched else theme.accent),
                )
            }

            val textMax = card.box.width
            canvas.drawText(ellipsize(item.title, textMax, titlePaint), card.box.left, card.titleBaselines[0], titlePaint)
            item.meta?.let { canvas.drawText(ellipsize(it, textMax, metaPaint), card.box.left, card.metaBaseline, metaPaint) }
        }
    }

    private fun drawPoster(canvas: Canvas, poster: PixRect, item: Model.Card, thumbs: ThumbnailCache?) {
        val rect = poster.toRectF()
        val bmp = thumbs?.peek(item.thumbnailKey)
        if (bmp != null) {
            canvas.drawBitmap(bmp, centreCrop(bmp.width, bmp.height, poster.width, poster.height), rect, null)
        } else {
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, poster.top, 0f, poster.bottom,
                    theme.cardFillTop, theme.cardFillBottom, Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRoundRect(rect, metrics.px(Radius.CARD), metrics.px(Radius.CARD), fill)
            val iconSize = metrics.px(3f)
            Icons.draw(canvas, Icon.VIDEO, poster.centerX, poster.centerY, iconSize, theme.text(Type.meta, metrics))
        }
        canvas.drawRoundRect(rect, metrics.px(Radius.CARD), metrics.px(Radius.CARD), theme.strokePaint(theme.cardStroke, 1f))
    }

    private fun drawBadge(canvas: Canvas, text: String, x: Float, y: Float, stroke: Int, alignRight: Boolean) {
        val paint = theme.text(Type.chip, metrics).apply { textAlign = if (alignRight) Paint.Align.RIGHT else Paint.Align.LEFT }
        val w = measure.width(text, paint.textSize, bold = true) + metrics.px(Space.S) * 2
        val h = metrics.px(Type.chip.degrees) + metrics.px(Space.XS) * 2
        val rect = if (alignRight) RectF(x - w, y, x, y + h) else RectF(x, y, x + w, y + h)
        Surfaces.chip(canvas, rect, metrics, theme, accented = false)
        canvas.drawRoundRect(rect, metrics.px(Radius.CHIP), metrics.px(Radius.CHIP), theme.strokePaint(stroke, 1.5f))
        canvas.drawText(text, if (alignRight) x - metrics.px(Space.S) else x + metrics.px(Space.S), y + h - metrics.px(Space.XS) * 2, paint)
    }

    private fun projectionBadge(mode: ProjectionMode): String? = if (mode == ProjectionMode.FLAT) null else mode.label

    private fun centreCrop(bw: Int, bh: Int, tw: Float, th: Float): Rect {
        if (bw <= 0 || bh <= 0 || tw <= 0f || th <= 0f) return Rect(0, 0, bw, bh)
        val targetAspect = tw / th
        val srcAspect = bw.toFloat() / bh
        return if (srcAspect > targetAspect) {
            val cropW = (bh * targetAspect).toInt()
            val x = (bw - cropW) / 2
            Rect(x, 0, x + cropW, bh)
        } else {
            val cropH = (bw / targetAspect).toInt()
            val y = (bh - cropH) / 2
            Rect(0, y, bw, y + cropH)
        }
    }

    private fun ellipsize(text: String, maxWidth: Float, paint: Paint): String {
        if (maxWidth <= 0f) return ""
        if (measure.width(text, paint.textSize, paint.typeface?.isBold == true) <= maxWidth) return text
        var s = text
        while (s.isNotEmpty() && measure.width("$s…", paint.textSize, paint.typeface?.isBold == true) > maxWidth) {
            s = s.dropLast(1)
        }
        return "$s…"
    }

    companion object {
        const val COLS = 3
        const val ROWS = 2
        const val PAGE_SIZE = COLS * ROWS
    }
}
