package com.daydreamvr.player.render

import com.daydreamvr.playback.PlaybackSnapshot
import com.daydreamvr.player.state.AppState
import com.daydreamvr.player.state.VrScreen
import com.daydreamvr.vrcore.math.Quat
import com.daydreamvr.vrcore.render.StereoLayout
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sin

class SceneRecenterYawTest {

    private fun identityMatrix(): FloatArray = FloatArray(16).apply {
        this[0] = 1f; this[5] = 1f; this[10] = 1f; this[15] = 1f
    }

    private fun yawMatrix(yawRad: Float): FloatArray {
        val q = FloatArray(4)
        // In Quaternion.kt / vrcore, rotation about +Y by angle theta
        Quat.fromAxisAngle(0f, 1f, 0f, -yawRad, q)
        val m = FloatArray(16)
        Quat.toMatrix(q, m)
        return m
    }

    @Test
    fun recenterArmsSettleDurationAndPinsScreenAnchorAtZero() {
        var currentState = AppState(screen = VrScreen.BROWSE)
        val scene = AppScene(
            stateProvider = { currentState },
            snapshotProvider = { PlaybackSnapshot.EMPTY },
            thumbnailCacheProvider = { throw UnsupportedOperationException() },
        )

        // Simulate head turned 45° to the right
        val headTurned = yawMatrix((45.0 * PI / 180.0).toFloat())
        val headYaw = atan2(-headTurned[8], headTurned[10])
        assertThat(headYaw).isWithin(1e-4f).of((45.0 * PI / 180.0).toFloat())

        // Initial update with no recenter
        scene.update(0.016f, headTurned)
        assertThat(scene.recenterRemainingSeconds).isEqualTo(0f)

        // Request recenter
        scene.recenter()
        assertThat(scene.recenterRemainingSeconds).isEqualTo(AppScene.RECENTER_SETTLE_SECONDS)

        // Next frame: settle window active
        scene.update(0.016f, headTurned)
        assertThat(scene.screenAnchorYawForTest).isEqualTo(0f)
        assertThat(scene.recenterRemainingSeconds).isGreaterThan(0f)

        // After settle duration elapses
        scene.update(AppScene.RECENTER_SETTLE_SECONDS + 0.05f, headTurned)
        assertThat(scene.recenterRemainingSeconds).isEqualTo(0f)
        assertThat(scene.screenAnchorYawForTest).isEqualTo(0f)
    }

    @Test
    fun recenterApproachDirectionIsFromLeftWhenLookingRight() {
        val distanceM = 2.5f

        // Head looking 30° to the right
        val initialAngleRad = (30.0 * PI / 180.0).toFloat()

        // Slew steps from 30° down to 0°
        val angles = listOf(30.0, 20.0, 10.0, 5.0, 0.0).map { (it * PI / 180.0).toFloat() }

        // A panel centered at anchor yaw = 0f has world position (0, 0, -distanceM)
        val panelWorldPos = floatArrayOf(0f, 0f, -distanceM, 1f)
        val viewM = FloatArray(16)

        val eyeXPositions = mutableListOf<Float>()

        for (angle in angles) {
            val pose = yawMatrix(angle)
            StereoLayout.viewMatrix(pose, eyeOffsetX = 0f, neckModel = null, out = viewM)

            // Transform panel world position into eye coordinates: P_eye = viewM * P_world
            val eyeX = viewM[0] * panelWorldPos[0] + viewM[4] * panelWorldPos[1] +
                viewM[8] * panelWorldPos[2] + viewM[12] * panelWorldPos[3]
            eyeXPositions += eyeX
        }

        // Initially when looking right (angle=30°), the panel centered at 0 is on the LEFT of the eye (x_eye < 0)
        assertThat(eyeXPositions.first()).isLessThan(-0.5f)

        // As recenter progresses, eyeX approaches 0 monotonically from the left
        for (i in 0 until eyeXPositions.size - 1) {
            assertThat(eyeXPositions[i + 1]).isGreaterThan(eyeXPositions[i])
        }

        // Finally settles dead ahead (eyeX ≈ 0)
        assertThat(eyeXPositions.last()).isWithin(1e-4f).of(0f)
    }

    @Test
    fun recenterApproachDirectionIsFromRightWhenLookingLeft() {
        val distanceM = 2.5f

        // Head looking 30° to the left
        val angles = listOf(-30.0, -20.0, -10.0, -5.0, 0.0).map { (it * PI / 180.0).toFloat() }

        val panelWorldPos = floatArrayOf(0f, 0f, -distanceM, 1f)
        val viewM = FloatArray(16)

        val eyeXPositions = mutableListOf<Float>()

        for (angle in angles) {
            val pose = yawMatrix(angle)
            StereoLayout.viewMatrix(pose, eyeOffsetX = 0f, neckModel = null, out = viewM)

            val eyeX = viewM[0] * panelWorldPos[0] + viewM[4] * panelWorldPos[1] +
                viewM[8] * panelWorldPos[2] + viewM[12] * panelWorldPos[3]
            eyeXPositions += eyeX
        }

        // Looking left (angle=-30°), panel at 0 is on the RIGHT of the eye (x_eye > 0)
        assertThat(eyeXPositions.first()).isGreaterThan(0.5f)

        // Monotonically approaches 0 from the right
        for (i in 0 until eyeXPositions.size - 1) {
            assertThat(eyeXPositions[i + 1]).isLessThan(eyeXPositions[i])
        }

        // Finally settles dead ahead (eyeX ≈ 0)
        assertThat(eyeXPositions.last()).isWithin(1e-4f).of(0f)
    }
}
