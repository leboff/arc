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
import kotlin.math.PI

/**
 * Inside-out UV sphere for equirectangular 180 / 360 content (ARCHITECTURE.md
 * §10.3). The eye sits at the centre; only yaw (the recentre anchor) moves the
 * mapping. Screen size / distance controls are disabled for this geometry.
 *
 * As with [CylinderScreen], [buildVertices] is a pure geometry generator.
 */
class SphereScreen(
    var radiusM: Float = 50f,
    domeFovDegrees: Int = DomeFov.DEG_180.degrees,
) {

    var domeFovDegrees: Int = domeFovDegrees
        set(value) {
            require(DomeFov.entries.any { it.degrees == value })
            field = value
        }

    init { require(DomeFov.entries.any { it.degrees == domeFovDegrees }) }

    /**
     * Interleaved `[x, y, z, u, v]` triangle soup covering the longitude span of
     * [mode] (π for 180, 2π for 360) and the full latitude range. Triangles are
     * wound so the textured face points inward (toward the origin).
     */
    fun buildVertices(
        mode: ProjectionMode,
        hSegments: Int = 64,
        vSegments: Int = 32,
    ): FloatArray {
        require(hSegments >= 1 && vSegments >= 1)
        if (mode.mapping == ProjectionMapping.EQUIDISTANT_FISHEYE) {
            return buildFisheyeVertices(mode.fisheyeFovDegrees!!, hSegments, hSegments * 3)
        }
        val lonSpan = if (mode == ProjectionMode.EQUIRECT_360) (2f * PI.toFloat())
            else domeFovDegrees * PI.toFloat() / 180f

        val out = FloatArray(hSegments * vSegments * 6 * FLOATS_PER_VERTEX)
        var w = 0

        fun put(i: Int, j: Int) {
            val fu = i.toFloat() / hSegments
            val fv = j.toFloat() / vSegments
            // Longitude 0 straight ahead (-Z), centred on the span.
            val lon = (fu - 0.5f) * lonSpan
            val lat = (fv - 0.5f) * PI.toFloat()
            val cosLat = cos(lat)
            out[w++] = radiusM * cosLat * sin(lon)
            out[w++] = radiusM * sin(lat)
            out[w++] = -radiusM * cosLat * cos(lon)
            out[w++] = fu
            out[w++] = fv
        }

        for (i in 0 until hSegments) {
            for (j in 0 until vSegments) {
                put(i, j)
                put(i + 1, j)
                put(i + 1, j + 1)
                put(i, j)
                put(i + 1, j + 1)
                put(i, j + 1)
            }
        }
        return out
    }

    /** Polar cap mesh for an equidistant fisheye lens. The center is a fan,
     * avoiding the degenerate origin ring used by latitude/longitude meshes. */
    fun buildFisheyeVertices(fovDegrees: Int, radialSegments: Int = 64, azimuthSegments: Int = 192): FloatArray {
        require(fovDegrees in 1..359 && radialSegments >= 1 && azimuthSegments >= 3)
        val max = Math.toRadians(fovDegrees / 2.0)
        val out = ArrayList<Float>()
        fun put(theta: Double, phi: Double) {
            out += (radiusM * sin(theta) * cos(phi)).toFloat()
            out += (radiusM * sin(theta) * sin(phi)).toFloat()
            out += (-radiusM * cos(theta)).toFloat()
            out += (0.5 + 0.5 * (theta / max) * cos(phi)).toFloat()
            out += (0.5 + 0.5 * (theta / max) * sin(phi)).toFloat()
        }
        for (r in 0 until radialSegments) {
            val t0 = r * max / radialSegments
            val t1 = (r + 1) * max / radialSegments
            for (a in 0 until azimuthSegments) {
                val p0 = 2.0 * PI * a / azimuthSegments
                val p1 = 2.0 * PI * (a + 1) / azimuthSegments
                if (r == 0) {
                    put(0.0, 0.0); put(t1, p1); put(t1, p0)
                } else {
                    put(t0, p0); put(t1, p1); put(t1, p0)
                    put(t0, p0); put(t0, p1); put(t1, p1)
                }
            }
        }
        return out.toFloatArray()
    }

    fun buildMesh(mode: ProjectionMode, hSegments: Int = 64, vSegments: Int = 32): Mesh {
        val verts = buildVertices(mode, hSegments, vSegments)
        return Mesh(verts, verts.size / FLOATS_PER_VERTEX, FLOATS_PER_VERTEX * Float.SIZE_BYTES)
    }

    // ---- GL draw path (not exercised by JVM tests) --------------------------

    private var shader: Shader? = null
    private var fisheyeShader: Shader? = null
    private var mesh: Mesh? = null
    private var meshFovDegrees: Int? = null
    private var meshMode: ProjectionMode? = null
    private val mvp = FloatArray(16)
    private val viewProj = FloatArray(16)
    private val model = FloatArray(16)
    private val stMatrix = FloatArray(16)

    var yawRad: Float = 0f

    fun onGlCreate() {
        shader = Shader(VideoShaders.VERTEX, VideoShaders.FRAGMENT)
        fisheyeShader = Shader(VideoShaders.FISHEYE_VERTEX, VideoShaders.FISHEYE_FRAGMENT)
    }

    fun draw(
        eye: EyeParams,
        viewM: FloatArray,
        projM: FloatArray,
        videoTexture: VideoTexture,
        projection: ProjectionMode,
    ) {
        val program = if (projection.mapping == ProjectionMapping.EQUIDISTANT_FISHEYE) fisheyeShader else shader
        program ?: return
        if (mesh == null || meshMode != projection || meshFovDegrees != domeFovDegrees) {
            mesh?.release()
            mesh = buildMesh(projection)
            meshMode = projection
            meshFovDegrees = domeFovDegrees
        }
        val geo = mesh ?: return

        Matrix.setIdentityM(model, 0)
        // Negated to match the atan2(x,−z) azimuth convention (UI_GAZE_PLAN.md §1.6, F6).
        Matrix.rotateM(model, 0, Math.toDegrees(-yawRad.toDouble()).toFloat(), 0f, 1f, 0f)
        Matrix.multiplyMM(viewProj, 0, projM, 0, viewM, 0)
        Matrix.multiplyMM(mvp, 0, viewProj, 0, model, 0)

        videoTexture.transformMatrix(stMatrix)
        val uvRect = ProjectionMode.uvRectFor(projection, eye.eye)

        program.use()
        GLES30.glUniformMatrix4fv(program.uniform("uMvp"), 1, false, mvp, 0)
        GLES30.glUniformMatrix4fv(program.uniform("uStMatrix"), 1, false, stMatrix, 0)
        GLES30.glUniform4fv(program.uniform("uUvRect"), 1, uvRect, 0)
        if (projection.mapping == ProjectionMapping.EQUIDISTANT_FISHEYE) {
            GLES30.glUniform1f(program.uniform("uThetaMax"), Math.toRadians(projection.fisheyeFovDegrees!! / 2.0).toFloat())
        }

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
        GlUtils.checkGlError("SphereScreen.draw")
    }

    fun onGlDestroy() {
        mesh?.release()
        shader?.release()
        fisheyeShader?.release()
        mesh = null
        meshMode = null
        shader = null
        fisheyeShader = null
    }

    companion object {
        const val FLOATS_PER_VERTEX = 5
    }
}
