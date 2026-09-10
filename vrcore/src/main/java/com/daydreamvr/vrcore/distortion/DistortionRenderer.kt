package com.daydreamvr.vrcore.distortion

import android.opengl.GLES30
import com.daydreamvr.vrcore.distortion.shaders.DistortionShaders
import com.daydreamvr.vrcore.gl.GlUtils
import com.daydreamvr.vrcore.gl.Mesh
import com.daydreamvr.vrcore.gl.Shader
import com.daydreamvr.vrcore.render.Eye
import com.daydreamvr.vrcore.render.EyeParams

/**
 * Resolves an [com.daydreamvr.vrcore.render.EyeFramebuffer] colour texture through
 * the [DistortionMesh] onto the bound framebuffer (the display backbuffer).
 *
 * One coherent warp mesh pair, rebuilt only when the optical snapshot changes.
 * GL thread only.
 */
class DistortionRenderer(private val gridSize: Int = 40) {

    private var shader: Shader? = null
    private var leftMesh: Mesh? = null
    private var rightMesh: Mesh? = null
    private var meshKey: Any? = null

    fun onGlCreate() {
        shader = Shader(DistortionShaders.VERTEX, DistortionShaders.FRAGMENT)
        leftMesh?.release()
        rightMesh?.release()
        leftMesh = null
        rightMesh = null
        meshKey = null
    }

    /**
     * Rebuilds both warp meshes if the optical geometry differs from the last build.
     * Builds both replacements before releasing the active coherent pair (§3).
     * Safe to call every frame.
     */
    fun updateMeshes(left: EyeParams, right: EyeParams) {
        val key = listOf(left.optics, right.optics, gridSize)
        if (key == meshKey) return

        val newLeft = DistortionMesh.build(left, gridSize)
        val newRight = try {
            DistortionMesh.build(right, gridSize)
        } catch (t: Throwable) {
            newLeft.release()
            throw t
        }

        leftMesh?.release()
        rightMesh?.release()
        leftMesh = newLeft
        rightMesh = newRight
        meshKey = key
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
        GLES30.glScissor(vp.x, vp.y, vp.width, vp.height)
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
