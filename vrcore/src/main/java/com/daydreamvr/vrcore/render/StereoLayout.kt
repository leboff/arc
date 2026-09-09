package com.daydreamvr.vrcore.render

import com.daydreamvr.vrcore.profile.DeviceProfile
import kotlin.math.tan
import com.daydreamvr.vrcore.optics.DisplayGeometry
import com.daydreamvr.vrcore.optics.MaxFov
import com.daydreamvr.vrcore.optics.ObserverGeometry
import com.daydreamvr.vrcore.optics.OpticsGeometry
import com.daydreamvr.vrcore.optics.RadialCoefficients
import com.daydreamvr.vrcore.optics.ViewerOptics
import com.daydreamvr.vrcore.optics.VerticalAlignment
import com.daydreamvr.vrcore.optics.TangentBounds

/**
 * Pure, JVM-testable stereo geometry — no GL, no allocation on the callers'
 * behalf beyond the returned pair. See ARCHITECTURE.md §6.2–§6.4.
 *
 * Matrix conventions: column-major `FloatArray(16)`, right-handed, `-Z` forward
 * (ARCHITECTURE.md §5). The math here deliberately does **not** call
 * `android.opengl.Matrix` so it runs off-device.
 */
object StereoLayout {

    /**
     * Split [surfaceWidthPx] × [surfaceHeightPx] into two equal eye viewports
     * separated by `profile.dividerPx` of untouched black gutter, and compute the
     * asymmetric per-eye FOV from the physical display size and the viewer optics.
     *
     * [cutoutInsetPx] shrinks **both** viewports by the same number of columns on
     * their outer edges, so a display cutout can be letterboxed around without
     * disturbing the optical centres (ARCHITECTURE.md §18).
     */
    fun layout(
        surfaceWidthPx: Int,
        surfaceHeightPx: Int,
        displayWidthM: Float,
        displayHeightM: Float,
        profile: DeviceProfile,
        ipdM: Float,
        cutoutInsetPx: Int = 0,
    ): Pair<EyeParams, EyeParams> {
        val optics = OpticsGeometry.compute(
            DisplayGeometry(displayWidthM.toDouble(), displayHeightM.toDouble(), surfaceWidthPx, surfaceHeightPx,
                usableInsets = com.daydreamvr.vrcore.optics.PixelInsets(left = cutoutInsetPx, right = cutoutInsetPx)),
            ViewerOptics(profile.id, lensSeparationM = profile.interLensDistanceM.toDouble(),
                screenToLensM = profile.screenToLensDistanceM.toDouble(), coefficients = RadialCoefficients(profile.distortionK[0].toDouble(), profile.distortionK[1].toDouble()),
                verticalAlignment = VerticalAlignment.CENTER, maxFov = MaxFov(profile.maxFovDegrees.outer.toDouble(), profile.maxFovDegrees.inner.toDouble(), profile.maxFovDegrees.up.toDouble(), profile.maxFovDegrees.down.toDouble()), dividerPx = profile.dividerPx),
            ObserverGeometry(ipdM.toDouble()),
        )
        fun fov(bounds: TangentBounds) = FovAngles(
            Math.toDegrees(kotlin.math.atan(-bounds.left)).toFloat(), Math.toDegrees(kotlin.math.atan(bounds.right)).toFloat(),
            Math.toDegrees(kotlin.math.atan(bounds.top)).toFloat(), Math.toDegrees(kotlin.math.atan(-bounds.bottom)).toFloat())
        return EyeParams(Eye.LEFT, optics.left.viewport, fov(optics.left.sourceBounds), -ipdM / 2f, optics.left) to
            EyeParams(Eye.RIGHT, optics.right.viewport, fov(optics.right.sourceBounds), ipdM / 2f, optics.right)
    }

    fun projectionMatrix(bounds: TangentBounds, near: Float, far: Float, out: FloatArray) {
        val left = (near * bounds.left).toFloat(); val right = (near * bounds.right).toFloat()
        val bottom = (near * bounds.bottom).toFloat(); val top = (near * bounds.top).toFloat()
        out.fill(0f)
        out[0] = 2f * near / (right - left); out[5] = 2f * near / (top - bottom)
        out[8] = (right + left) / (right - left); out[9] = (top + bottom) / (top - bottom)
        out[10] = -(far + near) / (far - near); out[11] = -1f; out[14] = -2f * far * near / (far - near)
    }

    /**
     * Off-centre perspective frustum for a [FovAngles]. [fov.outer]/[fov.inner]
     * are treated as the left/right half-angles respectively — pass a
     * [FovAngles] with `outer`/`inner` swapped for the right eye.
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
     *
     * With an identity [headRotation] and `neckModel == null` the result is a
     * pure translation of `(-eyeOffsetX, 0, 0)`.
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
