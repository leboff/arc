package com.daydreamvr.player.screens.widgets

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import com.daydreamvr.player.media.thumb.ThumbnailCache
import com.daydreamvr.player.state.BrowseFocus
import com.daydreamvr.player.state.GazeTarget
import com.daydreamvr.player.state.GazeTarget.Action
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
 * Right column of the browse panel (UI_REDESIGN_REVIEWED_PLAN.md §5.3): the
 * inspector for the focused card.
 *
 * Layout is **bottom-anchored for actions, top-down for information**, so a
 * verbose file can never push `PLAY` off the panel — the action block is placed
 * first, from `y = 784.45` upward, and the metadata rows are then clipped where
 * they would collide with it (the existing `drawDetailCard` clip pattern).
 *
 *   `[ PLAY ]`                 — always
 *   `[ RESUME hh:mm:ss ]`      — only when a resume position exists; when absent,
 *                                PLAY drops into this slot
 *   `[ Projection: … ]`        — always, cycles on activate
 */
class MediaInspectorWidget(
    private val theme: Theme,
    private val metrics: PanelMetrics,
    private val measure: TextMeasure = TextMeasure.PAINT,
) {

    data class Model(
        val title: String,
        val posterKey: String? = null,
        val metadata: List<Row> = emptyList(),
        /** e.g. "01:24:50" — non-null iff `ResumeStore` has a live entry. */
        val resumeLabel: String? = null,
        val projectionLabel: String = "Auto",
        val hasSelection: Boolean = true,
    ) {
        data class Row(val label: String, val value: String)
    }

    data class MetaRowLayout(val label: String, val value: String, val baseline: Float)

    data class Layout(
        val poster: PixRect,
        val titleBaselines: List<Float>,
        /** Only the rows that fit above the action block; the rest are clipped (§5.3). */
        val metaRows: List<MetaRowLayout>,
        val play: PixRect,
        /** Null when [Model.resumeLabel] is null. */
        val resume: PixRect?,
        val projection: PixRect,
        val bounds: PixRect,
        /** Y at which the metadata band was clipped (top of the action block). */
        val actionBlockTop: Float,
    )

    /** PURE. */
    fun measureLayout(model: Model, bounds: PixRect): Layout {
        val inset = metrics.px(Space.L)
        val contentLeft = bounds.left + inset
        val contentRight = bounds.right - inset
        val contentW = contentRight - contentLeft

        val bandTop = bounds.top + metrics.px(Bands.HEADER_DEG)
        val bottomY = bounds.bottom - inset
        val actionH = metrics.px(Bands.STD_ROW_DEG)
        val projH = metrics.px(Bands.COMPACT_ROW_DEG)

        // Bottom-anchored action block, stacked upward from bottomY.
        val projTop = bottomY - projH
        val projection = PixRect(contentLeft, projTop, contentRight, bottomY)
        val resume: PixRect?
        val play: PixRect
        if (model.resumeLabel != null) {
            val resumeTop = projTop - actionH
            resume = PixRect(contentLeft, resumeTop, contentRight, projTop)
            play = PixRect(contentLeft, resumeTop - actionH, contentRight, resumeTop)
        } else {
            resume = null
            // PLAY takes the bottom action slot RESUME would have occupied.
            play = PixRect(contentLeft, projTop - actionH, contentRight, projTop)
        }
        val actionBlockTop = play.top

        // Top-down information.
        val posterW = 0.80f * contentW
        val posterH = posterW * 9f / 16f
        val posterLeft = contentLeft + (contentW - posterW) / 2f
        val poster = PixRect(posterLeft, bandTop, posterLeft + posterW, bandTop + posterH)

        val titleSize = metrics.px(Type.rowTitle.degrees)
        val titleAsc = -measure.ascentPx(titleSize, bold = true)
        val titleLineH = titleSize * Type.TITLE_LINE_HEIGHT
        val titleTop = poster.bottom + metrics.px(Space.M)
        val titleBaselines = listOf(titleTop + titleAsc, titleTop + titleAsc + titleLineH)

        var y = titleTop + 2 * titleLineH + metrics.px(Space.M)
        val metaSize = metrics.px(Type.meta.degrees)
        val metaAsc = -measure.ascentPx(metaSize, bold = false)
        val lineH = metaSize + metrics.px(Space.M)
        val metaRows = ArrayList<MetaRowLayout>()
        for (row in model.metadata) {
            if (y + lineH > actionBlockTop - metrics.px(Space.M)) break
            metaRows.add(MetaRowLayout(row.label, row.value, y + metaAsc))
            y += lineH
        }

        return Layout(poster, titleBaselines, metaRows, play, resume, projection, bounds, actionBlockTop)
    }

    fun hitRegions(layout: Layout): List<HitRegion<GazeTarget>> {
        val out = ArrayList<HitRegion<GazeTarget>>(3)
        out.add(region(layout.play, GazeTarget.InspectorAction(Action.PLAY)))
        layout.resume?.let { out.add(region(it, GazeTarget.InspectorAction(Action.RESUME))) }
        out.add(region(layout.projection, GazeTarget.InspectorAction(Action.PROJECTION)))
        return out
    }

    private fun region(r: PixRect, id: GazeTarget) = HitRegion(r.left, r.top, r.right, r.bottom, id)

    fun draw(
        canvas: Canvas,
        model: Model,
        layout: Layout,
        focus: BrowseFocus?,
        hover: GazeTarget?,
        thumbs: ThumbnailCache? = null,
    ) {
        if (!model.hasSelection) return
        val focusedAction = (focus as? BrowseFocus.Inspector)?.action
        val hoverAction = (hover as? GazeTarget.InspectorAction)?.action

        drawPoster(canvas, layout.poster, model, thumbs)

        val titlePaint = theme.text(Type.rowTitle, metrics)
        val titleMax = layout.poster.let { layout.bounds.width - 2 * metrics.px(Space.L) }
        val lines = wrap(model.title, titleMax, titlePaint, maxLines = 2)
        lines.forEachIndexed { i, line ->
            canvas.drawText(line, layout.bounds.left + metrics.px(Space.L), layout.titleBaselines[i], titlePaint)
        }

        val labelPaint = theme.text(Type.meta, metrics)
        val valuePaint = theme.text(Type.meta, metrics, theme.textSecondary).apply { textAlign = Paint.Align.RIGHT }
        for (row in layout.metaRows) {
            canvas.drawText(row.label, layout.bounds.left + metrics.px(Space.L), row.baseline, labelPaint)
            canvas.drawText(row.value, layout.bounds.right - metrics.px(Space.L), row.baseline, valuePaint)
        }

        drawAction(canvas, layout.play, "PLAY", Icon.PLAY, Action.PLAY == focusedAction, Action.PLAY == hoverAction, primary = true)
        layout.resume?.let {
            drawAction(canvas, it, "RESUME ${model.resumeLabel}", Icon.PLAY, Action.RESUME == focusedAction, Action.RESUME == hoverAction, primary = false)
        }
        drawAction(
            canvas, layout.projection, "Projection: ${model.projectionLabel}", Icon.ASPECT,
            Action.PROJECTION == focusedAction, Action.PROJECTION == hoverAction, primary = false,
        )
    }

    private fun drawAction(canvas: Canvas, rect: PixRect, text: String, icon: Icon, focused: Boolean, hovered: Boolean, primary: Boolean) {
        val r = rect.inset(0f, metrics.px(Space.XS)).toRectF()
        when {
            focused -> Surfaces.focus(canvas, r, metrics, theme, Radius.CARD)
            hovered -> Surfaces.hover(canvas, r, metrics, theme, Radius.CARD)
            primary -> Surfaces.chip(canvas, r, metrics, theme, accented = true)
            else -> Surfaces.card(canvas, r, metrics, theme, Radius.CARD)
        }
        val paint = theme.text(Type.rowTitle, metrics, if (primary) theme.accentText else theme.textPrimary)
            .apply { textAlign = Paint.Align.CENTER }
        val iconSize = metrics.px(1.8f)
        Icons.draw(canvas, icon, rect.left + metrics.px(Space.L) + iconSize / 2f, rect.centerY, iconSize, paint)
        canvas.drawText(text, rect.centerX, rect.centerY + metrics.px(0.6f), paint)
    }

    private fun drawPoster(canvas: Canvas, poster: PixRect, model: Model, thumbs: ThumbnailCache?) {
        val rect = poster.toRectF()
        val bmp = thumbs?.peek(model.posterKey)
        if (bmp != null) {
            canvas.drawBitmap(bmp, null, rect, null)
        } else {
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, poster.top, 0f, poster.bottom, theme.cardFillTop, theme.cardFillBottom, Shader.TileMode.CLAMP)
            }
            canvas.drawRoundRect(rect, metrics.px(Radius.CARD), metrics.px(Radius.CARD), fill)
            Icons.draw(canvas, Icon.VIDEO, poster.centerX, poster.centerY, metrics.px(3f), theme.text(Type.meta, metrics))
        }
        canvas.drawRoundRect(rect, metrics.px(Radius.CARD), metrics.px(Radius.CARD), theme.strokePaint(theme.cardStroke, 1f))
    }

    private fun wrap(text: String, maxWidth: Float, paint: Paint, maxLines: Int): List<String> {
        if (maxWidth <= 0f) return listOf(text)
        val words = text.split(' ')
        val lines = ArrayList<String>()
        var current = StringBuilder()
        for (w in words) {
            val candidate = if (current.isEmpty()) w else "$current $w"
            if (measure.width(candidate, paint.textSize, bold = true) <= maxWidth || current.isEmpty()) {
                current = StringBuilder(candidate)
            } else {
                lines.add(current.toString())
                current = StringBuilder(w)
                if (lines.size == maxLines - 1) break
            }
        }
        if (lines.size < maxLines && current.isNotEmpty()) lines.add(current.toString())
        return lines.ifEmpty { listOf("") }
    }
}
