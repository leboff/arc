package com.daydreamvr.vrcore.render

/** Supported front-dome longitude spans and their user-visible labels. */
enum class DomeFov(val degrees: Int) {
    DEG_180(180), DEG_190(190), DEG_200(200), DEG_220(220);
    val label: String get() = "VR$degrees"
}

enum class StereoPacking { MONO, SBS, TOPBOTTOM }
enum class ProjectionMapping { CYLINDER, EQUIRECTANGULAR, EQUIDISTANT_FISHEYE }

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
enum class ProjectionMode(
    val packing: StereoPacking = StereoPacking.MONO,
    val domeFov: DomeFov? = null,
) {
    FLAT,
    SBS_HALF(StereoPacking.SBS),
    SBS_FULL(StereoPacking.SBS),
    TOPBOTTOM_HALF(StereoPacking.TOPBOTTOM),
    TOPBOTTOM_FULL(StereoPacking.TOPBOTTOM),
    EQUIRECT_180(domeFov = DomeFov.DEG_180),
    EQUIRECT_190(domeFov = DomeFov.DEG_190),
    EQUIRECT_200(domeFov = DomeFov.DEG_200),
    EQUIRECT_220(domeFov = DomeFov.DEG_220),
    EQUIRECT_180_SBS(StereoPacking.SBS, DomeFov.DEG_180),
    EQUIRECT_190_SBS(StereoPacking.SBS, DomeFov.DEG_190),
    EQUIRECT_200_SBS(StereoPacking.SBS, DomeFov.DEG_200),
    EQUIRECT_220_SBS(StereoPacking.SBS, DomeFov.DEG_220),
    EQUIRECT_180_TOPBOTTOM(StereoPacking.TOPBOTTOM, DomeFov.DEG_180),
    EQUIRECT_190_TOPBOTTOM(StereoPacking.TOPBOTTOM, DomeFov.DEG_190),
    EQUIRECT_200_TOPBOTTOM(StereoPacking.TOPBOTTOM, DomeFov.DEG_200),
    EQUIRECT_220_TOPBOTTOM(StereoPacking.TOPBOTTOM, DomeFov.DEG_220),
    EQUIRECT_360,
    FISHEYE_180_SBS(StereoPacking.SBS),
    FISHEYE_190_SBS(StereoPacking.SBS),
    FISHEYE_200_SBS(StereoPacking.SBS),
    FISHEYE_220_SBS(StereoPacking.SBS);

    val isStereo: Boolean get() = packing != StereoPacking.MONO
    val isSpherical: Boolean get() = domeFov != null || this == EQUIRECT_360 || mapping == ProjectionMapping.EQUIDISTANT_FISHEYE
    val mapping: ProjectionMapping get() = when {
        name.startsWith("FISHEYE") -> ProjectionMapping.EQUIDISTANT_FISHEYE
        name.startsWith("EQUIRECT") || this == EQUIRECT_360 -> ProjectionMapping.EQUIRECTANGULAR
        else -> ProjectionMapping.CYLINDER
    }
    val fisheyeFovDegrees: Int? get() = if (mapping == ProjectionMapping.EQUIDISTANT_FISHEYE) name.substringAfter('_').substringBefore('_').toInt() else null
    val label: String get() = domeFov?.let {
        it.label + when (packing) {
            StereoPacking.MONO -> ""
            StereoPacking.SBS -> " SBS"
            StereoPacking.TOPBOTTOM -> " OU"
        }
    } ?: when (this) {
        FLAT -> "Flat"
        EQUIRECT_360 -> "VR360"
        in listOf(FISHEYE_180_SBS, FISHEYE_190_SBS, FISHEYE_200_SBS, FISHEYE_220_SBS) -> "Fisheye ${fisheyeFovDegrees}° SBS"
        else -> name.lowercase().replace('_', ' ')
    }

    companion object {

        private val SBS_HALF_TOKENS = listOf("hsbs", "half-sbs", "half sbs", "h-sbs")
        private val SBS_TOKENS = listOf("sbs", "side-by-side", "side by side")
        private val TB_HALF_TOKENS = listOf("hou", "half-ou", "half ou", "half-over-under", "htab")
        private val TB_TOKENS = listOf("over-under", "over under", "tab", "top-bottom", "top bottom")

        private val word180 = Regex("""\b(?:vr)?180\b""")
        private val word3d = Regex("""\b3d\b""")
        private val wordOu = Regex("""\bou\b""")
        private val wordTb = Regex("""\btb\b""")

        /**
         * Best-effort guess from the item title plus the decoded frame size.
         * Explicit filename tokens win; a ~2:1 frame with no token is treated as
         * equirectangular 360 (ARCHITECTURE.md §10.3). Everything else is [FLAT].
         */
        fun detect(title: String, width: Int, height: Int): ProjectionMode {
            val t = title.lowercase().replace('_', ' ')
            if (word180.containsMatchIn(t)) {
                return when {
                    SBS_HALF_TOKENS.any { it in t } || SBS_TOKENS.any { it in t } -> EQUIRECT_180_SBS
                    TB_HALF_TOKENS.any { it in t } || TB_TOKENS.any { it in t } ||
                        wordOu.containsMatchIn(t) || wordTb.containsMatchIn(t) -> EQUIRECT_180_TOPBOTTOM
                    else -> EQUIRECT_180
                }
            }

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
         * in texture image space (the top half starts at `v = 0.5`). The left eye always
         * takes the first half — the left columns for `SBS_*`, the top rows for
         * `TOPBOTTOM_*`.
         */
        fun uvRectFor(mode: ProjectionMode, eye: Eye): FloatArray = when (mode.packing) {
            StereoPacking.SBS ->
                if (eye == Eye.LEFT) floatArrayOf(0f, 0f, 0.5f, 1f) else floatArrayOf(0.5f, 0f, 0.5f, 1f)
            StereoPacking.TOPBOTTOM ->
                if (eye == Eye.LEFT) floatArrayOf(0f, 0.5f, 1f, 0.5f) else floatArrayOf(0f, 0f, 1f, 0.5f)
            StereoPacking.MONO -> floatArrayOf(0f, 0f, 1f, 1f)
        }
    }
}
