package com.daydreamvr.vrcore.ui

import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.Matrix
import com.daydreamvr.vrcore.gl.GlUtils
import com.daydreamvr.vrcore.gl.Mesh
import com.daydreamvr.vrcore.gl.Shader
import com.daydreamvr.vrcore.render.EyeParams
import kotlin.math.cos
import kotlin.math.sin

/**
 * The geometry a [PanelSurface] is drawn on: a flat quad or a shallow cylindrical
 * section, concave toward the viewer, so a wide panel does not read as
 * corner-stretched through Cardboard optics (ARCHITECTURE.md §6.5, §11.3).
 *
 * [buildVertices] is a pure `[x, y, z, u, v]` generator; the GL draw path needs a
 * context. The panel is positioned by the caller (via [modelMatrix]); this class
 * only owns the mesh and the textured draw.
 */
class PanelQuad(
    var widthM: Float = 1.6f,
    var heightM: Float = 1.0f,
    var curved: Boolean = true,
    var curveRadiusM: Float = 2.5f,
) {

    fun buildVertices(hSegments: Int = if (curved) 20 else 1, vSegments: Int = 1): FloatArray {
        require(hSegments >= 1 && vSegments >= 1)
        val out = FloatArray(hSegments * vSegments * 6 * FLOATS_PER_VERTEX)
        var w = 0
        val halfH = heightM / 2f

        fun put(i: Int, j: Int) {
            val fu = i.toFloat() / hSegments
            val fv = j.toFloat() / vSegments
            val x: Float
            val z: Float
            if (curved) {
                val arc = widthM / curveRadiusM
                val theta = (fu - 0.5f) * arc
                x = curveRadiusM * sin(theta)
                z = -curveRadiusM * cos(theta) + curveRadiusM
            } else {
                x = (fu - 0.5f) * widthM
                z = 0f
            }
            out[w++] = x
            out[w++] = (fv - 0.5f) * 2f * halfH
            out[w++] = z
            out[w++] = fu
            out[w++] = 1f - fv
        }

        for (i in 0 until hSegments) {
            for (j in 0 until vSegments) {
                put(i, j); put(i + 1, j); put(i + 1, j + 1)
                put(i, j); put(i + 1, j + 1); put(i, j + 1)
            }
        }
        return out
    }

    fun buildMesh(hSegments: Int = if (curved) 20 else 1, vSegments: Int = 1): Mesh {
        val v = buildVertices(hSegments, vSegments)
        return Mesh(v, v.size / FLOATS_PER_VERTEX, FLOATS_PER_VERTEX * Float.SIZE_BYTES)
    }

    // ---- GL draw path -------------------------------------------------------

    private var shader: Shader? = null
    private var mesh: Mesh? = null
    private val mvp = FloatArray(16)
    private val vp = FloatArray(16)
    private val st = FloatArray(16)

    fun onGlCreate() {
        shader = Shader(VERTEX, FRAGMENT)
        rebuildMesh()
    }

    fun rebuildMesh() {
        mesh?.release()
        mesh = buildMesh()
    }

    /**
     * Draws the panel. [modelMatrix] places it in the world (yaw + translate to
     * [PanelAnchor.distanceM]); alpha < 1 fades it.
     */
    fun draw(
        eye: EyeParams,
        viewM: FloatArray,
        projM: FloatArray,
        modelMatrix: FloatArray,
        panel: PanelSurface,
        alpha: Float = 1f,
    ) {
        val program = shader ?: return
        val geo = mesh ?: return

        Matrix.multiplyMM(vp, 0, projM, 0, viewM, 0)
        Matrix.multiplyMM(mvp, 0, vp, 0, modelMatrix, 0)
        panel.transformMatrix(st)

        program.use()
        GLES30.glUniformMatrix4fv(program.uniform("uMvp"), 1, false, mvp, 0)
        GLES30.glUniformMatrix4fv(program.uniform("uStMatrix"), 1, false, st, 0)
        GLES30.glUniform1f(program.uniform("uAlpha"), alpha.coerceIn(0f, 1f))

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, panel.textureId)
        GLES30.glUniform1i(program.uniform("uTexture"), 0)

        geo.bind()
        val posLoc = program.attribute("aPos")
        val uvLoc = program.attribute("aUv")
        GLES30.glEnableVertexAttribArray(posLoc)
        GLES30.glVertexAttribPointer(posLoc, 3, GLES30.GL_FLOAT, false, FLOATS_PER_VERTEX * Float.SIZE_BYTES, 0)
        GLES30.glEnableVertexAttribArray(uvLoc)
        GLES30.glVertexAttribPointer(
            uvLoc, 2, GLES30.GL_FLOAT, false, FLOATS_PER_VERTEX * Float.SIZE_BYTES, 3 * Float.SIZE_BYTES,
        )
        geo.draw()
        GLES30.glDisableVertexAttribArray(posLoc)
        GLES30.glDisableVertexAttribArray(uvLoc)

        GLES30.glDepthMask(true)
        GlUtils.checkGlError("PanelQuad.draw")
    }

    fun onGlDestroy() {
        mesh?.release()
        shader?.release()
        mesh = null
        shader = null
    }

    companion object {
        const val FLOATS_PER_VERTEX = 5

        private const val VERTEX = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aUv;
uniform mat4 uMvp;
uniform mat4 uStMatrix;
out vec2 vUv;
void main() {
    vUv = (uStMatrix * vec4(aUv, 0.0, 1.0)).xy;
    gl_Position = uMvp * vec4(aPos, 1.0);
}
"""

        private const val FRAGMENT = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
uniform samplerExternalOES uTexture;
uniform float uAlpha;
in vec2 vUv;
out vec4 fragColor;
void main() {
    vec4 c = texture(uTexture, vUv);
    fragColor = c * uAlpha;
}
"""
    }
}
