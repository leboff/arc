package com.daydreamvr.vrcore.distortion

import android.opengl.GLES30
import com.daydreamvr.vrcore.distortion.shaders.DistortionShaders
import com.daydreamvr.vrcore.gl.GlUtils
import com.daydreamvr.vrcore.gl.Mesh
import com.daydreamvr.vrcore.gl.Shader
import com.daydreamvr.vrcore.profile.DeviceProfile
import com.daydreamvr.vrcore.render.Eye
import com.daydreamvr.vrcore.render.EyeParams

/**
 * Resolves an [com.daydreamvr.vrcore.render.EyeFramebuffer] colour texture through
 * the [DistortionMesh] onto whatever framebuffer is currently bound — the
 * backbuffer, in the frame loop (ARCHITECTURE.md §6.1 step 7).
 *
 * One static warp mesh per eye, rebuilt only when the profile or the surface size
 * changes. GL thread only.
 */
class DistortionRenderer(private val gridSize: Int = 40) {

    private var shader: Shader? = null
    private var leftMesh: Mesh? = null
    private var rightMesh: Mesh? = null
    private var meshKey: Any? = null

    fun onGlCreate() {
        shader = Shader(DistortionShaders.VERTEX, DistortionShaders.FRAGMENT)
        meshKey = null
    }

    /**
     * Rebuilds both warp meshes if [left]/[right]/[profile] differ from the last
     * build. Safe to call every frame.
     */
    fun updateMeshes(left: EyeParams, right: EyeParams, profile: DeviceProfile) {
        val key = listOf(
            profile, gridSize,
            left.viewport, right.viewport,
        )
        if (key == meshKey) return
        meshKey = key
        leftMesh?.release()
        rightMesh?.release()
        leftMesh = DistortionMesh.build(left, profile, gridSize)
        rightMesh = DistortionMesh.build(right, profile, gridSize)
    }

    /**
     * Draws the warp for [eye] into `eye.viewport` of the bound framebuffer,
     * sampling [colorTextureId] (the resolved eye texture).
     */
    fun render(eye: EyeParams, colorTextureId: Int, chromatic: Boolean) {
        val program = shader ?: return
        val mesh = (if (eye.eye == Eye.LEFT) leftMesh else rightMesh) ?: return
        val vp = eye.viewport

        GLES30.glViewport(vp.x, vp.y, vp.width, vp.height)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)

        program.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, colorTextureId)
        GLES30.glUniform1i(program.uniform("uTexture"), 0)
        GLES30.glUniform1i(program.uniform("uChromatic"), if (chromatic) 1 else 0)

        mesh.bind()
        val stride = DistortionMesh.FLOATS_PER_VERTEX * Float.SIZE_BYTES
        enable(program.attribute("aPos"), 0, stride)
        enable(program.attribute("aUvR"), 2, stride)
        enable(program.attribute("aUvG"), 4, stride)
        enable(program.attribute("aUvB"), 6, stride)

        mesh.draw()

        disable(program.attribute("aPos"))
        disable(program.attribute("aUvR"))
        disable(program.attribute("aUvG"))
        disable(program.attribute("aUvB"))
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GlUtils.checkGlError("DistortionRenderer.render")
    }

    fun onGlDestroy() {
        leftMesh?.release()
        rightMesh?.release()
        shader?.release()
        leftMesh = null
        rightMesh = null
        shader = null
        meshKey = null
    }

    private fun enable(loc: Int, floatOffset: Int, stride: Int) {
        if (loc < 0) return
        GLES30.glEnableVertexAttribArray(loc)
        GLES30.glVertexAttribPointer(
            loc, 2, GLES30.GL_FLOAT, false, stride, floatOffset * Float.SIZE_BYTES,
        )
    }

    private fun disable(loc: Int) {
        if (loc >= 0) GLES30.glDisableVertexAttribArray(loc)
    }
}
