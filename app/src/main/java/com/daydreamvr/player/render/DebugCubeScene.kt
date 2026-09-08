package com.daydreamvr.player.render

import android.opengl.GLES30
import android.opengl.Matrix
import com.daydreamvr.vrcore.gl.Mesh
import com.daydreamvr.vrcore.gl.Shader
import com.daydreamvr.vrcore.render.EyeParams
import com.daydreamvr.vrcore.render.Scene

/**
 * Phase 1 placeholder scene: a slowly spinning cube at a fixed world position,
 * two metres in front of the recentre direction. Its only job is to prove the
 * two eye viewports render distinctly with correct asymmetric frustums
 * (Phase 1 acceptance criterion 3). The real environment lands in Phase 2.
 */
class DebugCubeScene : Scene {

    private var shader: Shader? = null
    private var mesh: Mesh? = null

    private val model = FloatArray(16)
    private val viewProj = FloatArray(16)
    private val mvp = FloatArray(16)
    private var spinDegrees = 0f

    override fun onGlCreate() {
        shader = Shader(VERTEX_SRC, FRAGMENT_SRC)
        val vertices = buildCube()
        mesh = Mesh(vertices, vertexCount = 36, strideBytes = STRIDE_BYTES)
    }

    override fun onGlResize(width: Int, height: Int) = Unit

    override fun update(dtSeconds: Float) {
        spinDegrees = (spinDegrees + dtSeconds * 28f) % 360f
    }

    override fun draw(eye: EyeParams, viewM: FloatArray, projM: FloatArray) {
        val program = shader ?: return
        val cube = mesh ?: return

        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, 0f, 0f, -2f)
        Matrix.rotateM(model, 0, spinDegrees, 0.35f, 1f, 0f)

        Matrix.multiplyMM(viewProj, 0, projM, 0, viewM, 0)
        Matrix.multiplyMM(mvp, 0, viewProj, 0, model, 0)

        program.use()
        GLES30.glUniformMatrix4fv(program.uniform("uMvp"), 1, false, mvp, 0)

        cube.bind()
        val posLoc = program.attribute("aPos")
        val colLoc = program.attribute("aColor")
        GLES30.glEnableVertexAttribArray(posLoc)
        GLES30.glVertexAttribPointer(posLoc, 3, GLES30.GL_FLOAT, false, STRIDE_BYTES, 0)
        GLES30.glEnableVertexAttribArray(colLoc)
        GLES30.glVertexAttribPointer(colLoc, 3, GLES30.GL_FLOAT, false, STRIDE_BYTES, 3 * Float.SIZE_BYTES)

        cube.draw()

        GLES30.glDisableVertexAttribArray(posLoc)
        GLES30.glDisableVertexAttribArray(colLoc)
    }

    override fun onGlDestroy() {
        mesh?.release()
        shader?.release()
        mesh = null
        shader = null
    }

    private fun buildCube(): FloatArray {
        val c = arrayOf(
            floatArrayOf(-0.5f, -0.5f, -0.5f),
            floatArrayOf(0.5f, -0.5f, -0.5f),
            floatArrayOf(0.5f, 0.5f, -0.5f),
            floatArrayOf(-0.5f, 0.5f, -0.5f),
            floatArrayOf(-0.5f, -0.5f, 0.5f),
            floatArrayOf(0.5f, -0.5f, 0.5f),
            floatArrayOf(0.5f, 0.5f, 0.5f),
            floatArrayOf(-0.5f, 0.5f, 0.5f),
        )
        val faces = listOf(
            intArrayOf(4, 5, 6, 7) to floatArrayOf(0.95f, 0.30f, 0.30f), // +Z
            intArrayOf(1, 0, 3, 2) to floatArrayOf(0.30f, 0.85f, 0.40f), // -Z
            intArrayOf(0, 4, 7, 3) to floatArrayOf(0.35f, 0.45f, 0.95f), // -X
            intArrayOf(5, 1, 2, 6) to floatArrayOf(0.95f, 0.85f, 0.30f), // +X
            intArrayOf(7, 6, 2, 3) to floatArrayOf(0.90f, 0.40f, 0.90f), // +Y
            intArrayOf(0, 1, 5, 4) to floatArrayOf(0.35f, 0.85f, 0.90f), // -Y
        )
        val out = ArrayList<Float>(36 * 6)
        for ((quad, color) in faces) {
            for (index in intArrayOf(quad[0], quad[1], quad[2], quad[0], quad[2], quad[3])) {
                out += c[index][0]; out += c[index][1]; out += c[index][2]
                out += color[0]; out += color[1]; out += color[2]
            }
        }
        return out.toFloatArray()
    }

    private companion object {
        const val STRIDE_BYTES = 6 * Float.SIZE_BYTES

        val VERTEX_SRC = """
            #version 300 es
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec3 aColor;
            uniform mat4 uMvp;
            out vec3 vColor;
            void main() {
                vColor = aColor;
                gl_Position = uMvp * vec4(aPos, 1.0);
            }
        """.trimIndent()

        val FRAGMENT_SRC = """
            #version 300 es
            precision mediump float;
            in vec3 vColor;
            out vec4 fragColor;
            void main() {
                fragColor = vec4(vColor, 1.0);
            }
        """.trimIndent()
    }
}
