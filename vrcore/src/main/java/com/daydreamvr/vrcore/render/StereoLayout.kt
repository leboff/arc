package com.daydreamvr.vrcore.render

import com.daydreamvr.vrcore.optics.DisplayGeometry
import com.daydreamvr.vrcore.optics.MaxFov
import com.daydreamvr.vrcore.optics.ObserverGeometry
import com.daydreamvr.vrcore.optics.OpticsGeometry
import com.daydreamvr.vrcore.optics.PixelInsets
import com.daydreamvr.vrcore.optics.RadialCoefficients
import com.daydreamvr.vrcore.optics.TangentBounds
import com.daydreamvr.vrcore.optics.VerticalAlignment
import com.daydreamvr.vrcore.optics.ViewerOptics
import com.daydreamvr.vrcore.profile.DeviceProfile
import kotlin.math.atan
import kotlin.math.tan

/**
 * Pure, JVM-testable stereo geometry (ARCHITECTURE.md §6.2–§6.4 / DISTORTION_REMEDIATION_PLAN §2).
 *
 * Matrix conventions: column-major `FloatArray(16)`, right-handed, `-Z` forward.
 */
object StereoLayout {

    /**
     * Splits [surfaceWidthPx] × [surfaceHeightPx] into two eye viewports separated by
     * `profile.dividerPx` and evaluates exact physical tangent bounds and lens centers.
     *
     * When [distortionEnabled] is false, evaluates identity radial coefficients `(0, 0)`
     * so direct rendering and direct projection match unwarped physical bounds (§2.3).
     */
    fun layout(
        surfaceWidthPx: Int,
        surfaceHeightPx: Int,
        displayWidthM: Float,
        displayHeightM: Float,
        profile: DeviceProfile,
        ipdM: Float,
        cutoutInsetPx: Int = 0,
        distortionEnabled: Boolean = true,
    ): Pair<EyeParams, EyeParams> {
        val coeffs = if (distortionEnabled) {
            RadialCoefficients(profile.distortionK[0].toDouble(), profile.distortionK[1].toDouble())
        } else {
            RadialCoefficients(0.0, 0.0)
        }

        val display = DisplayGeometry(
            panelWidthM = displayWidthM.toDouble(),
            panelHeightM = displayHeightM.toDouble(),
            surfaceWidthPx = surfaceWidthPx,
            surfaceHeightPx = surfaceHeightPx,
            usableInsets = PixelInsets(left = cutoutInsetPx, right = cutoutInsetPx),
        )

        val viewer = ViewerOptics(
            profileId = profile.id,
            lensSeparationM = profile.interLensDistanceM.toDouble(),
            screenToLensM = profile.screenToLensDistanceM.toDouble(),
            coefficients = coeffs,
            verticalAlignment = VerticalAlignment.CENTER,
            maxFov = MaxFov(
                outer = profile.maxFovDegrees.outer.toDouble(),
                inner = profile.maxFovDegrees.inner.toDouble(),
                up = profile.maxFovDegrees.up.toDouble(),
                down = profile.maxFovDegrees.down.toDouble(),
            ),
            dividerPx = profile.dividerPx,
        )

        val observer = ObserverGeometry(ipdM.toDouble())
        val optics = OpticsGeometry.compute(display, viewer, observer)

        fun fov(bounds: TangentBounds) = FovAngles(
            outer = Math.toDegrees(atan(-bounds.left)).toFloat(),
            inner = Math.toDegrees(atan(bounds.right)).toFloat(),
            up = Math.toDegrees(atan(bounds.top)).toFloat(),
            down = Math.toDegrees(atan(-bounds.bottom)).toFloat(),
        )

        return EyeParams(Eye.LEFT, optics.left.viewport, fov(optics.left.sourceBounds), -ipdM / 2f, optics.left) to
            EyeParams(Eye.RIGHT, optics.right.viewport, fov(optics.right.sourceBounds), ipdM / 2f, optics.right)
    }

    /**
     * Column-major, right-handed off-centre perspective projection matrix from [TangentBounds] (§2.3).
     * `left < 0 < right`, `bottom < 0 < top`.
     */
    fun projectionMatrix(bounds: TangentBounds, near: Float, far: Float, out: FloatArray) {
        val left = (near * bounds.left).toFloat()
        val right = (near * bounds.right).toFloat()
        val bottom = (near * bounds.bottom).toFloat()
        val top = (near * bounds.top).toFloat()

        out.fill(0f)
        out[0] = 2f * near / (right - left)
        out[5] = 2f * near / (top - bottom)
        out[8] = (right + left) / (right - left)
        out[9] = (top + bottom) / (top - bottom)
        out[10] = -(far + near) / (far - near)
        out[11] = -1f
        out[14] = -2f * far * near / (far - near)
    }

    /**
     * Off-centre perspective frustum for a [FovAngles]. Legacy helper.
     */
    fun projectionMatrix(fov: FovAngles, near: Float, far: Float, out: FloatArray) {
        val left = -tan(Math.toRadians(fov.outer.toDouble())).toFloat() * near
        val right = tan(Math.toRadians(fov.inner.toDouble())).toFloat() * near
        val bottom = -tan(Math.toRadians(fov.down.toDouble())).toFloat() * near
        val top = tan(Math.toRadians(fov.up.toDouble())).toFloat() * near

        out.fill(0f)
        out[0] = 2f * near / (right - left)
        out[5] = 2f * near / (top - bottom)
        out[8] = (right + left) / (right - left)
        out[9] = (top + bottom) / (top - bottom)
        out[10] = -(far + near) / (far - near)
        out[11] = -1f
        out[14] = -2f * far * near / (far - near)
    }

    /**
     * View matrix for one eye: the rotation-only inverse of the head rotation
     * (a transpose), post-translated by the optional neck-model offset and then
     * by `-eyeOffsetX` in head space (ARCHITECTURE.md §6.3).
     */
    fun viewMatrix(
        headRotation: FloatArray,
        eyeOffsetX: Float,
        neckModel: FloatArray?,
        out: FloatArray,
    ) {
        transpose4(headRotation, out)
        if (neckModel != null) {
            postTranslate(out, -neckModel[0], -neckModel[1], -neckModel[2])
        }
        postTranslate(out, -eyeOffsetX, 0f, 0f)
    }

    private fun transpose4(m: FloatArray, out: FloatArray) {
        for (col in 0..3) {
            for (row in 0..3) {
                out[col * 4 + row] = m[row * 4 + col]
            }
        }
    }

    /** Post-multiply the column-major [m] in place by `translate(tx, ty, tz)`. */
    private fun postTranslate(m: FloatArray, tx: Float, ty: Float, tz: Float) {
        m[12] += m[0] * tx + m[4] * ty + m[8] * tz
        m[13] += m[1] * tx + m[5] * ty + m[9] * tz
        m[14] += m[2] * tx + m[6] * ty + m[10] * tz
        m[15] += m[3] * tx + m[7] * ty + m[11] * tz
    }
}
