package com.daydreamvr.vrcore.optics

import com.daydreamvr.vrcore.render.Eye
import com.daydreamvr.vrcore.render.Viewport

/** The only radial convention accepted by the compositor (ARCHITECTURE.md / DISTORTION_REMEDIATION_PLAN §2.2). */
enum class RadialConvention { SCREEN_TANGENT_TO_RAY_TANGENT_V1 }

/** Provenance and qualification confidence of optics parameters (§2.4, §4). */
enum class ParameterConfidence { VERIFIED, PROVISIONAL, USER_CALIBRATED }

/** Vertical lens alignment model (§2.1, §3). */
enum class VerticalAlignment { CENTER, BOTTOM }

/** Typed exception thrown on invalid optical or geometrical configuration inputs (§2.4). */
class OpticsValidationException(message: String) : IllegalArgumentException(message)

/** Dimensionless Brown-Conrady radial polynomial coefficients (even terms only, k1 and k2 in [0, 1]). */
data class RadialCoefficients(val k1: Double, val k2: Double) {
    init {
        if (!k1.isFinite() || !k2.isFinite() || k1 !in 0.0..1.0 || k2 !in 0.0..1.0) {
            throw OpticsValidationException("Radial coefficients must be finite and within [0, 1], got k1=$k1, k2=$k2")
        }
    }
}

/** Signed tangent bounds of an eye frustum (left < 0 < right, bottom < 0 < top). */
data class TangentBounds(
    val left: Double,
    val right: Double,
    val bottom: Double,
    val top: Double,
) {
    init {
        if (!left.isFinite() || !right.isFinite() || !bottom.isFinite() || !top.isFinite()) {
            throw OpticsValidationException("Tangent bounds must be finite")
        }
        if (left >= 0.0 || right <= 0.0 || bottom >= 0.0 || top <= 0.0) {
            throw OpticsValidationException(
                "Tangent bounds must enclose the optical axis: left<0<right, bottom<0<top (got [$left, $right, $bottom, $top])"
            )
        }
    }
}

/** Insets in surface pixels. */
data class PixelInsets(
    val left: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
    val top: Int = 0,
) {
    init {
        if (left < 0 || right < 0 || bottom < 0 || top < 0) {
            throw OpticsValidationException("Insets must be non-negative")
        }
    }
}

/** 2D Affine transform mapping surface coordinates to illuminated panel coordinates. */
data class Affine2D(
    val sx: Double = 1.0,
    val sy: Double = 1.0,
    val tx: Double = 0.0,
    val ty: Double = 0.0,
) {
    init {
        if (!sx.isFinite() || !sy.isFinite() || !tx.isFinite() || !ty.isFinite()) {
            throw OpticsValidationException("Affine transform parameters must be finite")
        }
    }
    fun map(x: Double, y: Double): Vec2 = Vec2(sx * x + tx, sy * y + ty)
}

/** 2D point or vector in metres or dimensionless tangent space. */
data class Vec2(val x: Double, val y: Double) {
    init {
        if (!x.isFinite() || !y.isFinite()) {
            throw OpticsValidationException("Vec2 coordinates must be finite (got x=$x, y=$y)")
        }
    }
}

/** Physical illuminated panel and GL surface geometry (§2.1). */
data class DisplayGeometry(
    val panelWidthM: Double,
    val panelHeightM: Double,
    val surfaceWidthPx: Int,
    val surfaceHeightPx: Int,
    val surfaceToPanel: Affine2D = Affine2D(),
    val usableInsets: PixelInsets = PixelInsets(),
    val measurementSource: String = "estimated",
    val measurementRevision: Int = 1,
) {
    init {
        if (!panelWidthM.isFinite() || panelWidthM !in 0.08..0.20) {
            throw OpticsValidationException("Panel width $panelWidthM m outside validated bounds [0.08, 0.20]")
        }
        if (!panelHeightM.isFinite() || panelHeightM !in 0.03..0.12) {
            throw OpticsValidationException("Panel height $panelHeightM m outside validated bounds [0.03, 0.12]")
        }
        if (surfaceWidthPx <= 0 || surfaceHeightPx <= 0) {
            throw OpticsValidationException("Surface dimensions must be positive (got ${surfaceWidthPx}x${surfaceHeightPx})")
        }
    }
}

/** Maximum half-angle field-of-view caps in degrees (strictly in (0, 89)). */
data class MaxFov(
    val outer: Double,
    val inner: Double,
    val up: Double,
    val down: Double,
) {
    init {
        listOf("outer" to outer, "inner" to inner, "up" to up, "down" to down).forEach { (name, deg) ->
            if (!deg.isFinite() || deg <= 0.0 || deg >= 89.0) {
                throw OpticsValidationException("FOV cap $name ($deg°) must be strictly within (0, 89) degrees")
            }
        }
    }
}

/** Passive viewer physical optical specifications (§2.1, §4). */
data class ViewerOptics(
    val profileId: String,
    val profileRevision: Int = 1,
    val lensSeparationM: Double,
    val horizontalOffsetM: Double = 0.0,
    val verticalAlignment: VerticalAlignment = VerticalAlignment.CENTER,
    val verticalOffsetM: Double = 0.0,
    val trayToLensHeightM: Double? = null,
    val trayToActiveBottomM: Double? = null,
    val screenToLensM: Double,
    val coefficients: RadialCoefficients,
    val convention: RadialConvention = RadialConvention.SCREEN_TANGENT_TO_RAY_TANGENT_V1,
    val maxFov: MaxFov,
    val confidence: ParameterConfidence = ParameterConfidence.PROVISIONAL,
    val dividerPx: Int = 8,
) {
    init {
        if (!lensSeparationM.isFinite() || lensSeparationM <= 0.0) {
            throw OpticsValidationException("Lens separation $lensSeparationM m must be positive and finite")
        }
        if (!screenToLensM.isFinite() || screenToLensM !in 0.030..0.060) {
            throw OpticsValidationException("Screen-to-lens distance $screenToLensM m outside validated bounds [0.030, 0.060]")
        }
        if (dividerPx !in 0..40) {
            throw OpticsValidationException("Divider width $dividerPx px outside validated bounds [0, 40]")
        }
        if (verticalAlignment == VerticalAlignment.BOTTOM && trayToActiveBottomM == null) {
            throw OpticsValidationException("Bottom vertical alignment requires measured trayToActiveBottomM")
        }
    }
}

/** Observer IPD (§4). Independent of fixed viewer lens separation. */
data class ObserverGeometry(val ipdM: Double = 0.064) {
    init {
        if (!ipdM.isFinite() || ipdM !in 0.052..0.074) {
            throw OpticsValidationException("Observer IPD $ipdM m outside supported bounds [0.052, 0.074]")
        }
    }
}

/** Optical geometry resolved for one eye for rendering and warp mesh (§2.2, §2.3). */
data class EyeOptics(
    val eye: Eye,
    val viewport: Viewport,
    val lensCenterPanelM: Vec2,
    val screenToLensM: Double,
    val coefficients: RadialCoefficients,
    val sourceBounds: TangentBounds,
    val panelBottomLeftM: Vec2,
    val panelTopRightM: Vec2,
)

/** Complete stereo optical pair and typed cache key (§3). */
data class StereoOptics(
    val left: EyeOptics,
    val right: EyeOptics,
    val geometryKey: Any,
)

/** Immutable render configuration snapshot dispatched to the GL thread (§3). */
data class RenderConfiguration(
    val display: DisplayGeometry,
    val viewer: ViewerOptics,
    val observer: ObserverGeometry,
    val dividerPx: Int = 8,
    val distortionEnabled: Boolean = true,
    val neckModel: FloatArray? = null,
    val revision: Int = 1,
)
