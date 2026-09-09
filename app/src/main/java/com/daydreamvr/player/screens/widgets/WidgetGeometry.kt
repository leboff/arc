package com.daydreamvr.player.screens.widgets

import android.graphics.RectF

/**
 * A pure pixel rectangle for the PLAY'A 3-column widgets
 * (UI_REDESIGN_REVIEWED_PLAN.md §5).
 *
 * `android.graphics.RectF` is a stub under `unitTests.isReturnDefaultValues =
 * true` — its constructor is a no-op and every field reads back `0f` — so a
 * layout contract expressed in `RectF` cannot be asserted in a plain JVM test.
 * `ListView` sidesteps this by passing bare floats; the four M5 widgets share
 * enough geometry that a small value type reads better. It converts to `RectF`
 * only at the `draw` boundary, on-device.
 *
 * Half-open on all sides, matching [com.daydreamvr.vrcore.ui.HitRegion].
 */
data class PixRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun inset(dx: Float, dy: Float): PixRect = PixRect(left + dx, top + dy, right - dx, bottom - dy)

    fun toRectF(): RectF = RectF(left, top, right, bottom)

    /** True when this rectangle and [other] share any interior area. */
    fun overlaps(other: PixRect): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom
}

/**
 * The vertical band budget of the 800 px browse panel (§5.0). Authored in
 * degrees of visual angle and resolved through `PanelMetrics`, so the same
 * tokens hold on any future panel density.
 */
internal object Bands {
    /** Header / breadcrumb band — `[0, 75.04)` at ppd 23.9359. */
    const val HEADER_DEG = 3.135f

    /** Toolbar / footer / source-switcher compact row — `Type.chip`, 2.730° tall. */
    const val COMPACT_ROW_DEG = 2.730f

    /** Standard focusable row — `Type.rowTitle`, 3.157° tall. */
    const val STD_ROW_DEG = 3.157f

    /** Bottom pad below the footer (`Space.L`). */
    const val BOTTOM_PAD_DEG = 0.65f
}
