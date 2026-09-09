package com.daydreamvr.vrcore.render.shaders

/**
 * GLSL ES 3.00 sources for sampling the decoder's `GL_TEXTURE_EXTERNAL_OES`
 * output onto the cinema geometry (ARCHITECTURE.md §10.2).
 *
 * The vertex shader folds three transforms into the sampled coordinate:
 *  1. `uUvRect` — the per-eye sub-rect for SBS / over-under content
 *     ([com.daydreamvr.vrcore.render.ProjectionMode.uvRectFor]).
 *  2. `uStMatrix` — the `SurfaceTexture` transform, which encodes the
 *     per-device / per-codec crop and Y-flip. Applying it is mandatory.
 */
object VideoShaders {

    const val VERTEX = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aUv;

uniform mat4 uMvp;
uniform mat4 uStMatrix;
uniform vec4 uUvRect;      // uOff, vOff, uScale, vScale (image space, v=0 at top)

out vec2 vUv;

void main() {
    vec2 packed = aUv * uUvRect.zw + uUvRect.xy;
    vUv = (uStMatrix * vec4(packed, 0.0, 1.0)).xy;
    gl_Position = uMvp * vec4(aPos, 1.0);
}
"""

    const val FRAGMENT = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;

uniform samplerExternalOES uTexture;

in vec2 vUv;
out vec4 fragColor;

void main() {
    fragColor = texture(uTexture, vUv);
}
"""
}
