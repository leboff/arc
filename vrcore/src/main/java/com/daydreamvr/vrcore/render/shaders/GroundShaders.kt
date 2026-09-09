package com.daydreamvr.vrcore.render.shaders

/**
 * The procedural ground-grid shader (UI_REDESIGN_REVIEWED_PLAN.md §12.2, R16).
 *
 * `GL_LINES` at world scale gives a line whose screen width collapses with
 * distance and then aliases violently once it drops below a pixel — and the
 * barrel-distortion resample amplifies exactly that. This grid is analytic: its
 * line width is defined in **pixels** via `fwidth()`, so it stays ~1 px at any
 * distance. Output is PREMULTIPLIED to match `glBlendFunc(ONE, ONE_MINUS_SRC_ALPHA)`.
 */
object GroundShaders {

    val VERTEX = """
        #version 300 es
        uniform mat4 uMvp;
        in vec3 aPos;
        out vec3 vWorld;
        void main() {
            vWorld = aPos;
            gl_Position = uMvp * vec4(aPos, 1.0);
        }
    """.trimIndent()

    val FRAGMENT = """
        #version 300 es
        precision highp float;
        in vec3 vWorld;
        uniform float uCell;
        uniform float uNear;
        uniform float uFadeStart;
        uniform float uFadeEnd;
        uniform vec3  uLine;
        uniform vec3  uGlow;
        uniform float uIntensity;
        uniform float uGlowEnabled;
        out vec4 fragColor;

        float gridLine(vec2 p, float cell) {
            vec2 d = abs(fract(p / cell - 0.5) - 0.5) * cell;
            vec2 w = max(fwidth(p), vec2(1e-6));
            return 1.0 - min(min(d.x / w.x, d.y / w.y), 1.0);
        }

        void main() {
            vec2  p = vWorld.xz;
            float r = length(p);

            float line  = gridLine(p, uCell);
            float minor = gridLine(p, uCell * 4.0) * 0.6;

            float hole  = smoothstep(uNear, uNear + 0.8, r);
            float fade  = 1.0 - smoothstep(uFadeStart, uFadeEnd, r);
            float a     = max(line, minor) * hole * fade;

            float band  = exp(-pow((r - uFadeEnd * 0.85) / 1.6, 2.0)) * uGlowEnabled;
            vec3  color = uLine * a + uGlow * band * 0.35;
            float alpha = (a + band * 0.35) * uIntensity;

            fragColor = vec4(color * uIntensity, alpha);
        }
    """.trimIndent()
}
