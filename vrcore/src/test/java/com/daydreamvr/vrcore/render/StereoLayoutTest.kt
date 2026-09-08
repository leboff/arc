package com.daydreamvr.vrcore.render

import com.google.common.truth.Truth.assertThat
import com.daydreamvr.vrcore.profile.DeviceProfiles
import org.junit.Test
import kotlin.math.tan

class StereoLayoutTest {

    private val cardboardV2 = DeviceProfiles.CARDBOARD_V2

    @Test
    fun layout_splitsSurfaceIntoTwoEqualViewports_withDivider() {
        val (left, right) = StereoLayout.layout(
            surfaceWidthPx = 2400,
            surfaceHeightPx = 1080,
            displayWidthM = 0.1406f,
            displayHeightM = 0.0633f,
            profile = cardboardV2, // dividerPx = 8
            ipdM = 0.063f,
        )

        assertThat(left.viewport).isEqualTo(Viewport(0, 0, 1196, 1080))
        assertThat(right.viewport).isEqualTo(Viewport(1204, 0, 1196, 1080))
    }

    @Test
    fun layout_producesAsymmetricFov_withOuterGreaterThanInner() {
        val (left, _) = StereoLayout.layout(
            surfaceWidthPx = 2400,
            surfaceHeightPx = 1080,
            displayWidthM = 0.1406f,
            displayHeightM = 0.0633f,
            profile = cardboardV2,
            ipdM = 0.063f,
        )

        assertThat(left.fov.outer).isWithin(0.2f).of(44.5f)
        assertThat(left.fov.inner).isWithin(0.2f).of(39.4f)
        assertThat(left.fov.outer).isGreaterThan(left.fov.inner)
    }

    @Test
    fun layout_mirrorsFovBetweenEyes() {
        val (left, right) = StereoLayout.layout(
            2400, 1080, 0.1406f, 0.0633f, cardboardV2, 0.063f,
        )
        assertThat(left.fov).isEqualTo(right.fov)
        assertThat(left.eyeOffsetX).isWithin(1e-6f).of(-right.eyeOffsetX)
        assertThat(right.eyeOffsetX).isWithin(1e-6f).of(0.063f / 2f)
    }

    @Test
    fun layout_shrinksBothViewportsSymmetrically_whenCutoutInsetGiven() {
        val plain = StereoLayout.layout(2400, 1080, 0.14f, 0.063f, cardboardV2, 0.063f)
        val inset = StereoLayout.layout(2400, 1080, 0.14f, 0.063f, cardboardV2, 0.063f, cutoutInsetPx = 40)

        assertThat(inset.first.viewport.width).isEqualTo(plain.first.viewport.width - 40)
        assertThat(inset.second.viewport.width).isEqualTo(plain.second.viewport.width - 40)
        assertThat(inset.first.viewport.width).isEqualTo(inset.second.viewport.width)
        assertThat(inset.first.viewport.x).isEqualTo(40)
        assertThat(inset.second.viewport.x).isEqualTo(plain.second.viewport.x)
    }

    @Test
    fun projectionMatrix_matchesReferenceOffCentreFrustum_symmetricCase() {
        val out = FloatArray(16)
        StereoLayout.projectionMatrix(FovAngles(45f, 45f, 45f, 45f), near = 1f, far = 3f, out = out)

        // Hand-computed glFrustum for l=-1, r=1, b=-1, t=1, n=1, f=3.
        val expected = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, -2f, -1f,
            0f, 0f, -3f, 0f,
        )
        assertMatrix(expected, out, 1e-5f)
    }

    @Test
    fun projectionMatrix_matchesReferenceOffCentreFrustum_asymmetricCase() {
        val fov = FovAngles(outer = 50f, inner = 35f, up = 40f, down = 30f)
        val near = 0.1f
        val far = 100f
        val out = FloatArray(16)
        StereoLayout.projectionMatrix(fov, near, far, out)

        val l = -tan(Math.toRadians(50.0)).toFloat() * near
        val r = tan(Math.toRadians(35.0)).toFloat() * near
        val b = -tan(Math.toRadians(30.0)).toFloat() * near
        val t = tan(Math.toRadians(40.0)).toFloat() * near

        // Textbook off-centre perspective frustum, column-major.
        val expected = FloatArray(16)
        expected[0] = 2f * near / (r - l)
        expected[5] = 2f * near / (t - b)
        expected[8] = (r + l) / (r - l)
        expected[9] = (t + b) / (t - b)
        expected[10] = -(far + near) / (far - near)
        expected[11] = -1f
        expected[14] = -2f * far * near / (far - near)

        assertMatrix(expected, out, 1e-4f)
    }

    @Test
    fun viewMatrix_appliesEyeOffsetInHeadSpace() {
        val identity = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )
        val ipd = 0.063f
        val out = FloatArray(16)
        StereoLayout.viewMatrix(identity, eyeOffsetX = ipd / 2f, neckModel = null, out = out)

        // Right eye: the world is translated by -ipd/2 along X.
        assertThat(out[12]).isWithin(1e-6f).of(-ipd / 2f)
        assertThat(out[13]).isWithin(1e-6f).of(0f)
        assertThat(out[14]).isWithin(1e-6f).of(0f)
        // Rotation block stays identity.
        assertThat(out[0]).isWithin(1e-6f).of(1f)
        assertThat(out[5]).isWithin(1e-6f).of(1f)
        assertThat(out[10]).isWithin(1e-6f).of(1f)
    }

    private fun assertMatrix(expected: FloatArray, actual: FloatArray, tol: Float) {
        for (i in 0 until 16) {
            assertThat(actual[i]).isWithin(tol).of(expected[i])
        }
    }
}
