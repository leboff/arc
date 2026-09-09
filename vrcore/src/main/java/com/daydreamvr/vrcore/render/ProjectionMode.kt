package com.daydreamvr.vrcore.render

/**
 * How a single decoded video frame maps onto the virtual screen and onto the two
 * eyes (ARCHITECTURE.md §10.3).
 *
 * - [FLAT] — both eyes sample the whole texture on the cylinder.
 * - `SBS_*` / `TOPBOTTOM_*` — the frame packs both eye images; each eye samples a
 *   UV sub-rect. `_HALF` content is horizontally (SBS) or vertically (TB)
 *   squashed to fit one frame; `_FULL` is a genuine 2×-wide / 2×-tall frame.
 *   The distinction does not change the UV rect, only the mesh aspect the caller
 *   feeds to [CylinderScreen.setAspect].
 * - `EQUIRECT_*` — swap the cylinder for an inside-out UV sphere; 180 covers a
 *   hemisphere, 360 the full sphere. Screen size / distance controls are disabled;
 *   the recentre yaw anchor still applies.
 */
enum class ProjectionMode {
    FLAT,
    SBS_HALF,
    SBS_FULL,
    TOPBOTTOM_HALF,
    TOPBOTTOM_FULL,
    EQUIRECT_180,
    EQUIRECT_360,
    ;

    /** True when the frame carries a distinct image per eye. */
    val isStereo: Boolean
        get() = this != FLAT && this != EQUIRECT_180 && this != EQUIRECT_360

    /** True when the geometry is a sphere rather than the cinema cylinder. */
    val isSpherical: Boolean
        get() = this == EQUIRECT_180 || this == EQUIRECT_360

    companion object {

        private val SBS_HALF_TOKENS = listOf("hsbs", "half-sbs", "half sbs", "hsbs")
        private val SBS_TOKENS = listOf("sbs", "side-by-side", "side by side")
        private val TB_HALF_TOKENS = listOf("hou", "half-ou", "half ou", "half-over-under", "htab")
        private val TB_TOKENS = listOf("over-under", "over under", "tab", "top-bottom", "top bottom")

        private val word3d = Regex("""\b3d\b""")
        private val wordOu = Regex("""\bou\b""")
        private val wordTb = Regex("""\btb\b""")

        /**
         * Best-effort guess from the item title plus the decoded frame size.
         * Explicit filename tokens win; a ~2:1 frame with no token is treated as
         * equirectangular 360 (ARCHITECTURE.md §10.3). Everything else is [FLAT].
         */
        fun detect(title: String, width: Int, height: Int): ProjectionMode {
            val t = title.lowercase()

            if (SBS_HALF_TOKENS.any { it in t }) return SBS_HALF
            if (TB_HALF_TOKENS.any { it in t }) return TOPBOTTOM_HALF
            if (SBS_TOKENS.any { it in t } || word3d.containsMatchIn(t)) return SBS_HALF
            if (TB_TOKENS.any { it in t } || wordOu.containsMatchIn(t) || wordTb.containsMatchIn(t)) {
                return TOPBOTTOM_FULL
            }
            if ("180" in t) return EQUIRECT_180
            if ("360" in t) return EQUIRECT_360

            if (width > 0 && height > 0) {
                val aspect = width.toFloat() / height.toFloat()
                if (aspect in 1.9f..2.1f) return EQUIRECT_360
            }
            return FLAT
        }

        /**
         * The `[uOff, vOff, uScale, vScale]` sub-rect this [mode] assigns to [eye],
         * in image space (`v = 0` is the top of the frame). The left eye always
         * takes the first half — the left columns for `SBS_*`, the top rows for
         * `TOPBOTTOM_*`.
         */
        fun uvRectFor(mode: ProjectionMode, eye: Eye): FloatArray = when (mode) {
            SBS_HALF, SBS_FULL ->
                if (eye == Eye.LEFT) floatArrayOf(0f, 0f, 0.5f, 1f) else floatArrayOf(0.5f, 0f, 0.5f, 1f)
            TOPBOTTOM_HALF, TOPBOTTOM_FULL ->
                if (eye == Eye.LEFT) floatArrayOf(0f, 0f, 1f, 0.5f) else floatArrayOf(0f, 0.5f, 1f, 0.5f)
            FLAT, EQUIRECT_180, EQUIRECT_360 -> floatArrayOf(0f, 0f, 1f, 1f)
        }
    }
}
