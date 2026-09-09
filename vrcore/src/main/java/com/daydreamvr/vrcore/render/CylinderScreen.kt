package com.daydreamvr.vrcore.render

import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.Matrix
import com.daydreamvr.vrcore.gl.GlUtils
import com.daydreamvr.vrcore.gl.Mesh
import com.daydreamvr.vrcore.gl.Shader
import com.daydreamvr.vrcore.gl.VideoTexture
import com.daydreamvr.vrcore.render.shaders.VideoShaders
import kotlin.math.cos
import kotlin.math.sin

/**
 * The virtual cinema screen: a cylindrical section, concave toward the viewer
 * (ARCHITECTURE.md §6.5). World-locked in yaw, gravity-locked in pitch/roll —
 * the screen never follows the head.
 *
 * The geometry generator ([buildVertices]) is pure and JVM-testable; [buildMesh]
 * wraps it in a GL [Mesh] and [draw] needs a GL context.
 */
class CylinderScreen(
    var radiusM: Float = 4f,
    var widthDegrees: Float = 60f,
) {

    /** Video display aspect (width / height), after pixel-aspect correction. */
    var aspect: Float = 16f / 9f
        private set

    /** Vertical extent of the screen in metres, derived from [aspect]. */
    val heightM: Float
        get() = arcWidthM / aspect

    private val arcWidthM: Float
        get() = radiusM * Math.toRadians(widthDegrees.toDouble()).toFloat()

    /** Rebuilds the vertical extent from the media aspect, keeping [widthDegrees]. */
    fun setAspect(videoAspect: Float) {
        if (videoAspect > 0f) aspect = videoAspect
    }

    /**
     * Interleaved `[x, y, z, u, v]` triangle soup for the current radius / width /
     * aspect. `hSegments × vSegments` quads, two triangles each, wound
     * counter-clockwise as seen from the eye at the origin (front face inward).
     */
    fun buildVertices(hSegments: Int = 48, vSegments: Int = 24): FloatArray {
        require(hSegments >= 1 && vSegments >= 1)
        val widthRad = Math.toRadians(widthDegrees.toDouble()).toFloat()
        val halfHeight = heightM / 2f

        val out = FloatArray(hSegments * vSegments * 6 * FLOATS_PER_VERTEX)
        var w = 0

        fun put(i: Int, j: Int, hs: Int, vs: Int) {
            val fu = i.toFloat() / hs
            val fv = j.toFloat() / vs
            val theta = (fu - 0.5f) * widthRad
            out[w++] = radiusM * sin(theta)
            out[w++] = (fv - 0.5f) * 2f * halfHeight
            out[w++] = -radiusM * cos(theta)
            out[w++] = fu
            out[w++] = 1f - fv
        }

        for (i in 0 until hSegments) {
            for (j in 0 until vSegments) {
                put(i, j, hSegments, vSegments)
                put(i + 1, j, hSegments, vSegments)
                put(i + 1, j + 1, hSegments, vSegments)
                put(i, j, hSegments, vSegments)
                put(i + 1, j + 1, hSegments, vSegments)
                put(i, j + 1, hSegments, vSegments)
            }
        }
        return out
    }

    fun buildMesh(hSegments: Int = 48, vSegments: Int = 24): Mesh {
        val verts = buildVertices(hSegments, vSegments)
        return Mesh(verts, verts.size / FLOATS_PER_VERTEX, FLOATS_PER_VERTEX * Float.SIZE_BYTES)
    }

    // ---- GL draw path (not exercised by JVM tests) --------------------------

    private var shader: Shader? = null
    private var mesh: Mesh? = null
    private val mvp = FloatArray(16)
    private val viewProj = FloatArray(16)
    private val model = FloatArray(16)
    private val stMatrix = FloatArray(16)

    /** Screen yaw offset in radians (set from the recentre anchor). */
    var yawRad: Float = 0f

    fun onGlCreate() {
        shader = Shader(VideoShaders.VERTEX, VideoShaders.FRAGMENT)
        rebuildMesh()
    }

    fun rebuildMesh() {
        mesh?.release()
        mesh = buildMesh()
    }

    fun draw(
        eye: EyeParams,
        viewM: FloatArray,
        projM: FloatArray,
        videoTexture: VideoTexture,
        projection: ProjectionMode,
    ) {
        val program = shader ?: return
        val geo = mesh ?: return

        Matrix.setIdentityM(model, 0)
        Matrix.rotateM(model, 0, Math.toDegrees(yawRad.toDouble()).toFloat(), 0f, 1f, 0f)
        Matrix.multiplyMM(viewProj, 0, projM, 0, viewM, 0)
        Matrix.multiplyMM(mvp, 0, viewProj, 0, model, 0)

        videoTexture.transformMatrix(stMatrix)
        val uvRect = ProjectionMode.uvRectFor(projection, eye.eye)

        program.use()
        GLES30.glUniformMatrix4fv(program.uniform("uMvp"), 1, false, mvp, 0)
        GLES30.glUniformMatrix4fv(program.uniform("uStMatrix"), 1, false, stMatrix, 0)
        GLES30.glUniform4fv(program.uniform("uUvRect"), 1, uvRect, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTexture.textureId)
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
        GlUtils.checkGlError("CylinderScreen.draw")
    }

    fun onGlDestroy() {
        mesh?.release()
        shader?.release()
        mesh = null
        shader = null
    }

    companion object {
        const val FLOATS_PER_VERTEX = 5
    }
}
