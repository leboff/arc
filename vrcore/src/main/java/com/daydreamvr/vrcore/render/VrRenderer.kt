package com.daydreamvr.vrcore.render

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import com.daydreamvr.vrcore.profile.DeviceProfile
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * The frame loop (ARCHITECTURE.md §6.1). Runs on the GL thread only. It reads the
 * device profile and head pose through the injected providers — in Phase 1 the
 * pose provider always returns identity (no sensors yet).
 *
 * The GL thread never blocks and never allocates on the hot path beyond the
 * two [EyeParams] returned by [StereoLayout.layout]; zero-allocation is enforced
 * in Phase 6.
 */
class VrRenderer(
    private val scene: Scene,
    private val profileProvider: () -> DeviceProfile,
    private val poseProvider: () -> FloatArray,
) : GLSurfaceView.Renderer {

    /** Supersample factor for the (Phase 6) distortion FBO. Unused until then. */
    var renderScale: Float = 1.15f

    val frameStats: FrameStats = FrameStats()

    /** Physical display size in metres — set by the host from `DisplayMetrics`. */
    var displayWidthM: Float = 0.140f
    var displayHeightM: Float = 0.065f
    var ipdM: Float = 0.063f
    var near: Float = 0.1f
    var far: Float = 100f

    @Volatile
    var surfaceWidth: Int = 0
        private set

    @Volatile
    var surfaceHeight: Int = 0
        private set

    private var lastFrameNs = 0L
    private val projM = FloatArray(16)
    private val viewM = FloatArray(16)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        lastFrameNs = 0L
        frameStats.reset()
        scene.onGlCreate()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        GLES30.glViewport(0, 0, width, height)
        scene.onGlResize(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = if (lastFrameNs == 0L) 0f else ((now - lastFrameNs) / 1_000_000_000.0).toFloat()
        lastFrameNs = now
        frameStats.record(now)

        val width = surfaceWidth
        val height = surfaceHeight

        val profile = profileProvider()
        val pose = poseProvider()
        scene.update(dt)

        if (width == 0 || height == 0) return

        val (left, right) = StereoLayout.layout(
            width, height, displayWidthM, displayHeightM, profile, ipdM,
        )

        // Clear the whole surface black first so the divider gutter stays black
        // no matter what an eye clears to during development (ARCHITECTURE.md §6.2).
        GLES30.glScissor(0, 0, width, height)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        drawEye(left, pose, profile)
        drawEye(right, pose, profile)
    }

    private fun drawEye(eye: EyeParams, pose: FloatArray, profile: DeviceProfile) {
        val vp = eye.viewport
        GLES30.glViewport(vp.x, vp.y, vp.width, vp.height)
        GLES30.glScissor(vp.x, vp.y, vp.width, vp.height)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        StereoLayout.projectionMatrix(projectionFov(eye), near, far, projM)
        StereoLayout.viewMatrix(pose, eye.eyeOffsetX, profile.neckModelM, viewM)
        scene.draw(eye, viewM, projM)
    }

    /** Right eye mirrors the frustum: temporal side is now +X. */
    private fun projectionFov(eye: EyeParams): FovAngles =
        if (eye.eye == Eye.LEFT) {
            eye.fov
        } else {
            FovAngles(eye.fov.inner, eye.fov.outer, eye.fov.up, eye.fov.down)
        }

    fun onGlDestroy() {
        scene.onGlDestroy()
    }
}
