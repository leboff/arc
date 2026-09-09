package com.daydreamvr.vrcore.render

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import com.daydreamvr.vrcore.distortion.DistortionRenderer
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

    /** Supersample factor for the distortion FBO (ARCHITECTURE.md §6.6). */
    var renderScale: Float = 1.15f

    /**
     * When true the scene renders per eye into an offscreen [EyeFramebuffer] and
     * is resolved to the backbuffer through the lens-distortion warp mesh
     * (ARCHITECTURE.md §6.6). Off ⇒ straight-to-backbuffer, as in Phase 1–5.
     */
    var distortionEnabled: Boolean = false

    /** Per-channel radial correction in the distortion resolve pass. */
    var chromaticEnabled: Boolean = false

    /** MSAA sample count for the eye FBOs; 0 disables. Clamped to `GL_MAX_SAMPLES`. */
    var msaaSamples: Int = 0

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

    private val leftFbo = EyeFramebuffer()
    private val rightFbo = EyeFramebuffer()
    private val distortion = DistortionRenderer()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        lastFrameNs = 0L
        frameStats.reset()
        leftFbo.release()
        rightFbo.release()
        distortion.onGlCreate()
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
        scene.update(dt, pose)

        if (width == 0 || height == 0) return

        val (left, right) = StereoLayout.layout(
            width, height, displayWidthM, displayHeightM, profile, ipdM,
        )

        if (distortionEnabled) {
            drawFrameDistorted(left, right, pose, profile)
        } else {
            drawFrameDirect(left, right, pose, profile, width, height)
        }
    }

    private fun drawFrameDirect(
        left: EyeParams,
        right: EyeParams,
        pose: FloatArray,
        profile: DeviceProfile,
        width: Int,
        height: Int,
    ) {
        // Clear the whole surface black first so the divider gutter stays black
        // no matter what an eye clears to during development (ARCHITECTURE.md §6.2).
        GLES30.glScissor(0, 0, width, height)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        drawEyeDirect(left, pose, profile)
        drawEyeDirect(right, pose, profile)
    }

    private fun drawEyeDirect(eye: EyeParams, pose: FloatArray, profile: DeviceProfile) {
        val vp = eye.viewport
        GLES30.glViewport(vp.x, vp.y, vp.width, vp.height)
        GLES30.glScissor(vp.x, vp.y, vp.width, vp.height)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        drawSceneForEye(eye, pose, profile)
    }

    private fun drawFrameDistorted(
        left: EyeParams,
        right: EyeParams,
        pose: FloatArray,
        profile: DeviceProfile,
    ) {
        distortion.updateMeshes(left, right)

        renderEyeToTarget(leftFbo, left, pose, profile)
        renderEyeToTarget(rightFbo, right, pose, profile)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES30.glScissor(0, 0, surfaceWidth, surfaceHeight)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        distortion.render(left, leftFbo.resolve(), chromaticEnabled)
        distortion.render(right, rightFbo.resolve(), chromaticEnabled)
    }

    private fun renderEyeToTarget(
        fbo: EyeFramebuffer,
        eye: EyeParams,
        pose: FloatArray,
        profile: DeviceProfile,
    ) {
        fbo.renderScale = renderScale
        fbo.msaaSamples = msaaSamples
        fbo.ensure(eye.viewport.width, eye.viewport.height)
        fbo.bind()
        GLES30.glScissor(0, 0, fbo.width, fbo.height)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        drawSceneForEye(eye, pose, profile)
    }

    private fun drawSceneForEye(eye: EyeParams, pose: FloatArray, profile: DeviceProfile) {
        val optics = requireNotNull(eye.optics) { "renderer requires an optics snapshot" }
        StereoLayout.projectionMatrix(optics.sourceBounds, near, far, projM)
        StereoLayout.viewMatrix(pose, eye.eyeOffsetX, profile.neckModelM, viewM)
        scene.draw(eye, viewM, projM)
    }

    fun onGlDestroy() {
        scene.onGlDestroy()
        distortion.onGlDestroy()
        leftFbo.release()
        rightFbo.release()
    }
}
