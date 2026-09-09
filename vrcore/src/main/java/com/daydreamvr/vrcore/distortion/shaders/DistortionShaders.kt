package com.daydreamvr.vrcore.distortion.shaders

/**
 * GLSL ES 3.00 sources for the distortion resolve pass (ARCHITECTURE.md §6.6).
 *
 * The mesh vertex carries the clip-space position plus three sets of texture
 * coordinates ([com.daydreamvr.vrcore.distortion.DistortionMesh]). The fragment
 * shader samples the resolved eye colour texture once per channel when
 * `uChromatic` is set (chromatic aberration correction), else once; samples that
 * fall outside `[0, 1]` read as black so the unused corners of the eye texture do
 * not smear.
 */
object DistortionShaders {

    const val VERTEX = """#version 300 es
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aUvR;
layout(location = 2) in vec2 aUvG;
layout(location = 3) in vec2 aUvB;

out vec2 vUvR;
out vec2 vUvG;
out vec2 vUvB;

void main() {
    vUvR = aUvR;
    vUvG = aUvG;
    vUvB = aUvB;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    const val FRAGMENT = """#version 300 es
precision mediump float;

uniform sampler2D uTexture;
uniform bool uChromatic;

in vec2 vUvR;
in vec2 vUvG;
in vec2 vUvB;

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
