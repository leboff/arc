package com.daydreamvr.vrcore.ui

import android.opengl.GLES30
import android.opengl.Matrix
import com.daydreamvr.vrcore.gl.GlUtils
import com.daydreamvr.vrcore.gl.Mesh
import com.daydreamvr.vrcore.gl.Shader
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The gaze pointer: a world-space billboard drawn with the per-eye view/proj so
 * its stereo disparity matches the panel it sits on (UI_GAZE_PLAN.md §2.6). A
 * screen-space dot at fixed disparity would fight vergence and double-image.
 *
 * The ring is entirely procedural in the fragment shader — no texture, no mip
 * chain — and is the only per-frame animation in the UI.
 */
class Reticle {

    /** Angular radius of the ring; 1.1° total is unobtrusive but visible over video. */
    var angularRadiusDeg: Float = 0.55f

    private var shader: Shader? = null
    private var mesh: Mesh? = null
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private val vp = FloatArray(16)

    /** Smoothed hover state, advanced on the GL thread (90 ms time constant). */
    private var hoverT = 0f

    fun onGlCreate() {
        shader = Shader(VERTEX, FRAGMENT)
        // Unit quad in the XY plane, [-1,1]², two triangles, interleaved [x,y].
        val v = floatArrayOf(
            -1f, -1f, 1f, -1f, 1f, 1f,
            -1f, -1f, 1f, 1f, -1f, 1f,
        )
        mesh = Mesh(v, 6, 2 * Float.SIZE_BYTES)
    }

    fun onGlDestroy() {
        mesh?.release()
        shader?.release()
        mesh = null
        shader = null
        hoverT = 0f
    }

    /** Advances the hover-glow envelope toward [target] (0 idle, 1 over a target). */
    fun advance(target: Float, dtSeconds: Float) {
        val tau = 0.09f
        val a = if (dtSeconds > 0f) (1f - exp(-dtSeconds / tau)).coerceIn(0f, 1f) else 0f
        hoverT += (target.coerceIn(0f, 1f) - hoverT) * a
    }

    fun draw(
        viewM: FloatArray,
        projM: FloatArray,
        px: Float,
        py: Float,
        pz: Float,
        camX: Float,
        camY: Float,
        camZ: Float,
        alpha: Float,
    ) {
        val program = shader ?: return
        val geo = mesh ?: return
        val radius = radiusMForDistance(distance(px, py, pz, camX, camY, camZ), angularRadiusDeg) * (1f + 0.18f * hoverT)
        billboardModel(px, py, pz, camX, camY, camZ, radius, model)

        Matrix.multiplyMM(vp, 0, projM, 0, viewM, 0)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)

        program.use()
        GLES30.glUniformMatrix4fv(program.uniform("uMvp"), 1, false, mvp, 0)
        GLES30.glUniform1f(program.uniform("uHover"), hoverT)
        GLES30.glUniform1f(program.uniform("uAlpha"), alpha.coerceIn(0f, 1f))

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)

        geo.bind()
        val pos = program.attribute("aPos")
        GLES30.glEnableVertexAttribArray(pos)
        GLES30.glVertexAttribPointer(pos, 2, GLES30.GL_FLOAT, false, 2 * Float.SIZE_BYTES, 0)
        geo.draw()
        GLES30.glDisableVertexAttribArray(pos)

        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(true)
        GlUtils.checkGlError("Reticle.draw")
    }

    companion object {

        private fun distance(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float): Float {
            val dx = ax - bx
            val dy = ay - by
            val dz = az - bz
            return sqrt(dx * dx + dy * dy + dz * dz)
        }

        /** Constant apparent size across depth: `radius = distance · tan(angularRadius)`. */
        fun radiusMForDistance(distanceM: Float, angularRadiusDeg: Float): Float =
            distanceM * tan(Math.toRadians(angularRadiusDeg.toDouble())).toFloat()

        /**
         * Unit quad → billboard at `(px,py,pz)` facing the camera. Column-major.
         * Columns: `[ r·radius | u·radius | f | p ]` with `f` the normal toward
         * the camera and `r`,`u` an orthonormal in-plane basis.
         */
        fun billboardModel(
            px: Float,
            py: Float,
            pz: Float,
            camX: Float,
            camY: Float,
            camZ: Float,
            radiusM: Float,
            out: FloatArray,
        ) {
            var fx = camX - px
            var fy = camY - py
            var fz = camZ - pz
            val fl = sqrt(fx * fx + fy * fy + fz * fz).takeIf { it > 1e-6f } ?: 1f
            fx /= fl; fy /= fl; fz /= fl

            // r = normalize(worldUp × f); fall back to worldRight when looking straight up/down.
            var rx = 1f * fz - 0f * fy // (0,1,0) × f
            var ry = 0f * fx - 0f * fz
            var rz = 0f * fy - 1f * fx
            var rl = sqrt(rx * rx + ry * ry + rz * rz)
            if (rl < 1e-4f) {
                rx = 1f; ry = 0f; rz = 0f; rl = 1f
            }
            rx /= rl; ry /= rl; rz /= rl

            // u = f × r
            val ux = fy * rz - fz * ry
            val uy = fz * rx - fx * rz
            val uz = fx * ry - fy * rx

            out[0] = rx * radiusM; out[1] = ry * radiusM; out[2] = rz * radiusM; out[3] = 0f
            out[4] = ux * radiusM; out[5] = uy * radiusM; out[6] = uz * radiusM; out[7] = 0f
            out[8] = fx; out[9] = fy; out[10] = fz; out[11] = 0f
            out[12] = px; out[13] = py; out[14] = pz; out[15] = 1f
        }

        // Kept for reference / potential dwell-progress use.
        @Suppress("unused")
        fun glowFalloff(r: Float): Float = exp(-18f * (r - 0.82f) * (r - 0.82f))

        private const val VERTEX = """#version 300 es
layout(location = 0) in vec2 aPos;
uniform mat4 uMvp;
out vec2 vPos;
void main() {
    vPos = aPos;
    gl_Position = uMvp * vec4(aPos, 0.0, 1.0);
}
"""

        private const val FRAGMENT = """#version 300 es
precision mediump float;
uniform float uHover;
uniform float uAlpha;
in vec2 vPos;
out vec4 fragColor;

vec4 premul(vec3 c, float a) { return vec4(c * a, a); }

void main() {
    float r = length(vPos);
    vec3 idle = vec3(0.662, 0.905, 0.960);
    vec3 hot  = vec3(0.435, 0.847, 0.925);
    vec3 core = mix(idle, hot, uHover);

    float a = 0.0;
    // dark halo for readability over bright video
    if (r > 0.58 && r < 0.86) a = max(a, 0.70);
    vec3 col = vec3(0.0);
    // bright ring
    if (r > 0.62 && r < 0.82) { a = 0.95; col = core; }
    // centre dot
    if (r < 0.16) { a = 0.90; col = core; }
    // hover glow
    float glow = exp(-18.0 * (r - 0.82) * (r - 0.82)) * 0.35 * uHover;
    a = max(a, glow);
    col = mix(col, hot, glow);

    if (r > 1.0) discard;
    fragColor = premul(col, a * uAlpha);
}
"""
    }
}
