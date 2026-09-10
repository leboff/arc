package com.daydreamvr.vrcore.ui

import android.graphics.Paint
import android.graphics.Typeface

/**
 * The one visual style shared by every in-headset panel (ARCHITECTURE.md §11.3,
 * UI_GAZE_PLAN.md §4).
 *
 * Constraints baked in from the optics, not taste:
 *  - nothing below [AngularMetrics.MIN_TEXT_DEGREES] of visual angle;
 *  - no saturated blue text (chromatic aberration is worst at the blue end) —
 *    glyphs use [accentText], strokes/fills may use [accent];
 *  - no pure white on black (bloom through passive lenses);
 *  - no animation on the Canvas — motion lives in the GL reticle only.
 *
 * Sizes are requested in degrees of visual angle and resolved through a
 * [PanelMetrics], never a shared constant.
 */
class Theme {

    // ── Surface ────────────────────────────────────────────────
    val panelFillTop = 0xF21B1E26.toInt() // dark slate, faintly blue
    val panelFillBottom = 0xF20B0C10.toInt()
    val panelStroke = 0x24FFFFFF
    val panelHairline = 0x40FFFFFF // top-edge highlight, fades out at both ends
    val panelSheen = 0x12FFFFFF
    val cardFillTop = 0x14FFFFFF
    val cardFillBottom = 0x08FFFFFF
    val cardStroke = 0x1AFFFFFF
    val dividerColor = 0x14FFFFFF
    val scrim = 0x99000000.toInt()

    // ── Text ───────────────────────────────────────────────────
    val textPrimary = 0xFFF0F2F6.toInt() // not pure white
    val textSecondary = 0xFFA8AFBC.toInt()
    val textTertiary = 0xFF737B8A.toInt()
    val textOnAccent = 0xFF04161C.toInt()

    // ── Accent (cyan) ──────────────────────────────────────────
    val accent = 0xFF6FD8EC.toInt() // strokes / fills only
    val accentText = 0xFFA9E7F5.toInt() // desaturated — safe for glyphs
    val focusFill = 0x2E6FD8EC
    val focusStroke = 0xE66FD8EC.toInt()
    val focusGlowInner = 0x666FD8EC
    val focusGlowOuter = 0x226FD8EC
    val hoverFill = 0x14FFFFFF
    val hoverStroke = 0x806FD8EC.toInt()

    // ── Format badges (UI_REDESIGN_REVIEWED_PLAN.md §4) ────────
    // Desaturated per the no-saturated-glyph rule — drawn as a 1.5 px stroke on a
    // neutral chip, never as a coloured fill behind light text.
    val badgeVr = 0xFF9FD8B8.toInt() // VR180 / VR360
    val badge3d = 0xFFD8B89F.toInt() // SBS / TB
    val badgeQuality = 0xFFB8B8D8.toInt() // 4K / HEVC

    // ── Status ─────────────────────────────────────────────────
    val watched = 0xFF8FD69F.toInt()
    val warning = 0xFFE8C56F.toInt()
    val error = 0xFFF0907F.toInt()
    val progressTrack = 0x26FFFFFF

    // ── Back-compat aliases (pre-§4.2 field names) ─────────────
    val textColor: Int get() = textPrimary
    val dimTextColor: Int get() = textSecondary
    val panelColor: Int get() = panelFillTop
    val panelStrokeColor: Int get() = panelStroke
    val focusFillColor: Int get() = focusFill
    val focusStrokeColor: Int get() = focusStroke
    val accentColor: Int get() = accent
    val progressTrackColor: Int get() = progressTrack
    val progressFillColor: Int get() = accent
    val watchedColor: Int get() = watched
    val errorColor: Int get() = error

    /** Legacy fixed geometry — new code resolves radii/spacing through [PanelMetrics]. */
    val cornerRadiusPx: Float = 18f
    val paddingPx: Float = 28f

    /** @deprecated per-panel arc lives in [PanelMetrics.widthDegrees] now (F1). */
    @Deprecated("Use PanelMetrics.widthDegrees", ReplaceWith("metrics.widthDegrees"))
    val panelWidthDegrees: Float = 42f

    private val medium: Typeface by lazy { Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val regular: Typeface by lazy { Typeface.create("sans-serif", Typeface.NORMAL) }

    fun textPaint(sizePx: Float, color: Int = textPrimary, bold: Boolean = false): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = sizePx
            typeface = if (bold) medium else regular
            isSubpixelText = true
        }

    /** A text paint for a [Type] token, sized through [metrics]. */
    fun text(token: Type.Token, metrics: PanelMetrics, colorOverride: Int? = null): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorOverride ?: token.color(this@Theme)
            textSize = metrics.px(token.degrees)
            typeface = if (token.medium) medium else regular
            isSubpixelText = true
            if (token.letterSpacing != 0f) letterSpacing = token.letterSpacing
            if (token.tabularFigures) fontFeatureSettings = "tnum"
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
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

    companion object {
        val DEFAULT = Theme()
    }
}

/** Spacing tokens, in degrees of visual angle — resolve through [PanelMetrics.px]. */
object Space {
    const val XS = 0.15f
    const val S = 0.30f
    const val M = 0.45f
    const val L = 0.65f
    const val XL = 0.95f
    const val XXL = 1.30f
}

/** Corner-radius tokens, in degrees of visual angle. */
object Radius {
    const val CHIP = 0.35f
    const val CARD = 0.55f
    const val PANEL = 1.10f
}

/** The type scale (UI_GAZE_PLAN.md §4.3). Every token is ≥ [AngularMetrics.MIN_TEXT_DEGREES]. */
object Type {

    data class Token(
        val degrees: Float,
        val medium: Boolean,
        val letterSpacing: Float = 0f,
        val tabularFigures: Boolean = false,
        val colorRole: Role = Role.PRIMARY,
    ) {
        fun color(theme: Theme): Int = when (colorRole) {
            Role.PRIMARY -> theme.textPrimary
            Role.SECONDARY -> theme.textSecondary
            Role.TERTIARY -> theme.textTertiary
            Role.ACCENT -> theme.accentText
        }
    }

    enum class Role { PRIMARY, SECONDARY, TERTIARY, ACCENT }

    val screenTitle = Token(1.70f, medium = true)
    val sectionLabel = Token(1.10f, medium = true, letterSpacing = 0.08f, colorRole = Role.TERTIARY)
    val rowTitle = Token(1.35f, medium = true)
    val rowSubtitle = Token(1.15f, medium = false, colorRole = Role.SECONDARY)
    val meta = Token(1.10f, medium = false, colorRole = Role.TERTIARY)
    val chip = Token(1.10f, medium = true, colorRole = Role.ACCENT)
    val numeral = Token(1.20f, medium = true, tabularFigures = true)

    val ALL = listOf(screenTitle, sectionLabel, rowTitle, rowSubtitle, meta, chip, numeral)

    const val TITLE_LINE_HEIGHT = 1.22f
    const val SUBTITLE_LINE_HEIGHT = 1.18f
}
