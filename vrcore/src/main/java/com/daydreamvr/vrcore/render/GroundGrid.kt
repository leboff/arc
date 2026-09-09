package com.daydreamvr.vrcore.render

import android.opengl.GLES30
import android.opengl.Matrix
import com.daydreamvr.vrcore.gl.GlUtils
import com.daydreamvr.vrcore.gl.Mesh
import com.daydreamvr.vrcore.gl.Shader
import com.daydreamvr.vrcore.render.shaders.GroundShaders

/**
 * A subliminal wireframe floor at eye-height-below for a seated user, to anchor
 * vestibular balance in the OLED void (UI_REDESIGN_REVIEWED_PLAN.md §12).
 *
 * One quad, no tessellation — the grid is procedural in the fragment shader
 * ([GroundShaders]), so two triangles with perspective-correct world XZ varyings
 * are enough. Tracking is 3DOF: correct under rotation, wrong under translation,
 * which the near hole and the intensity ceiling keep imperceptible.
 *
 * [buildVertices] and [environmentEnvelope] are pure and JVM-testable.
 */
class GroundGrid {

    /** Interleaved `[x, y, z]` for two triangles on the `y = GROUND_Y` plane, ±[HALF_M] in XZ. */
    fun buildVertices(): FloatArray {
        val h = HALF_M
        val y = GROUND_Y
        return floatArrayOf(
            -h, y, -h,
            h, y, -h,
            h, y, h,
            -h, y, -h,
            h, y, h,
            -h, y, h,
        )
    }

    // ---- GL draw path (not exercised by JVM tests) --------------------------

    private var shader: Shader? = null
    private var mesh: Mesh? = null
    private val mvp = FloatArray(16)
    private val model = FloatArray(16)
    private val viewProj = FloatArray(16)

    /** Grid yaw, slaved to the recentre anchor so it turns with the world. */
    var yawRad: Float = 0f

    fun onGlCreate() {
        shader = Shader(GroundShaders.VERTEX, GroundShaders.FRAGMENT)
        val v = buildVertices()
        mesh = Mesh(v, v.size / 3, 3 * Float.SIZE_BYTES)
    }

    /**
     * @param intensity 0..1 from the thermal governor (0 = grid off).
     * @param glow whether to add the horizon band (skipped at MODERATE+).
     */
    fun draw(
        viewM: FloatArray,
        projM: FloatArray,
        lineColor: FloatArray,
        glowColor: FloatArray,
        intensity: Float,
        glow: Boolean,
    ) {
        if (intensity <= 0f) return
        val program = shader ?: return
        val geo = mesh ?: return

        Matrix.setIdentityM(model, 0)
        Matrix.rotateM(model, 0, Math.toDegrees(-yawRad.toDouble()).toFloat(), 0f, 1f, 0f)
        Matrix.multiplyMM(viewProj, 0, projM, 0, viewM, 0)
        Matrix.multiplyMM(mvp, 0, viewProj, 0, model, 0)

        program.use()
        GLES30.glUniformMatrix4fv(program.uniform("uMvp"), 1, false, mvp, 0)
        GLES30.glUniform1f(program.uniform("uCell"), CELL_M)
        GLES30.glUniform1f(program.uniform("uNear"), NEAR_M)
        GLES30.glUniform1f(program.uniform("uFadeStart"), FADE_START_M)
        GLES30.glUniform1f(program.uniform("uFadeEnd"), FADE_END_M)
        GLES30.glUniform3fv(program.uniform("uLine"), 1, lineColor, 0)
        GLES30.glUniform3fv(program.uniform("uGlow"), 1, glowColor, 0)
        GLES30.glUniform1f(program.uniform("uIntensity"), intensity.coerceIn(0f, 1f))
        GLES30.glUniform1f(program.uniform("uGlowEnabled"), if (glow) 1f else 0f)

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)

        geo.bind()
        val posLoc = program.attribute("aPos")
        GLES30.glEnableVertexAttribArray(posLoc)
        GLES30.glVertexAttribPointer(posLoc, 3, GLES30.GL_FLOAT, false, 3 * Float.SIZE_BYTES, 0)
        geo.draw()
        GLES30.glDisableVertexAttribArray(posLoc)

        GLES30.glDepthMask(true)
        GlUtils.checkGlError("GroundGrid.draw")
    }

    fun onGlDestroy() {
        mesh?.release()
        shader?.release()
        mesh = null
        shader = null
    }

    companion object {
        const val GROUND_Y = -1.2f
        const val HALF_M = 14f
        const val CELL_M = 0.5f
        const val NEAR_M = 1.2f
        const val FADE_START_M = 3.0f
        const val FADE_END_M = 12.0f

        /** `smoothstep`, matching GLSL. */
        fun smoothstep(e0: Float, e1: Float, x: Float): Float {
            if (e0 == e1) return if (x < e0) 0f else 1f
            val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        /**
         * The distance envelope the shader multiplies the line pattern by:
         * `hole(r) · fade(r)`. 0 at the near hole, rises to ~1 past the hole,
         * falls back to 0 by [FADE_END_M]. Pure mirror for tests (§12.2).
         */
        fun environmentEnvelope(r: Float): Float {
            val hole = smoothstep(NEAR_M, NEAR_M + 0.8f, r)
            val fade = 1f - smoothstep(FADE_START_M, FADE_END_M, r)
            return hole * fade
        }

        /** Where the ground plane is, per gaze pitch below horizontal (§12.3). */
        fun groundDistanceAtPitch(pitchDegBelowHorizon: Float): Float =
            kotlin.math.abs(GROUND_Y) / kotlin.math.sin(Math.toRadians(pitchDegBelowHorizon.toDouble())).toFloat()
    }
}
