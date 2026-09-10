package com.daydreamvr.vrcore.distortion.shaders

/**
 * GLSL ES 3.00 sources for the distortion resolve pass (ARCHITECTURE.md §6.6 / DISTORTION_REMEDIATION_PLAN §2.4).
 *
 * Uses highp precision for UV varyings and fragment arithmetic.
 * Masking explicitly evaluates against unclamped UVs so samples outside [0, 1]² resolve to black.
 */
object DistortionShaders {

    const val VERTEX = """#version 300 es
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aUvR;
layout(location = 2) in vec2 aUvG;
layout(location = 3) in vec2 aUvB;

out highp vec2 vUvR;
out highp vec2 vUvG;
out highp vec2 vUvB;

void main() {
    vUvR = aUvR;
    vUvG = aUvG;
    vUvB = aUvB;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    const val FRAGMENT = """#version 300 es
precision highp float;

uniform sampler2D uTexture;
uniform bool uChromatic;

in highp vec2 vUvR;
in highp vec2 vUvG;
in highp vec2 vUvB;

out vec4 fragColor;

float mask(vec2 uv) {
    vec2 inside = step(vec2(0.0), uv) * step(uv, vec2(1.0));
    return inside.x * inside.y;
}

void main() {
    if (uChromatic) {
        float r = texture(uTexture, vUvR).r * mask(vUvR);
        float g = texture(uTexture, vUvG).g * mask(vUvG);
        float b = texture(uTexture, vUvB).b * mask(vUvB);
        fragColor = vec4(r, g, b, 1.0);
    } else {
        fragColor = vec4(texture(uTexture, vUvG).rgb * mask(vUvG), 1.0);
    }
}
"""
}
