package com.daydreamvr.vrcore.render

import android.opengl.GLES30
import com.daydreamvr.vrcore.gl.GlUtils

/**
 * An offscreen render target for one eye (ARCHITECTURE.md §6.6).
 *
 * The scene renders into this FBO at `renderScale × viewportSize` (supersample),
 * optionally multisampled; [resolve] blits it down to a plain colour texture the
 * distortion pass samples. When [msaaSamples] is 0 the render FBO *is* the texture
 * FBO and [resolve] is a no-op that just returns the texture id.
 *
 * All methods run on the GL thread.
 */
class EyeFramebuffer(
    var renderScale: Float = 1.15f,
    var msaaSamples: Int = 0,
) {

    private var texWidth = 0
    private var texHeight = 0
    private var builtScale = 0f
    private var builtSamples = -1

    private var colorTex = 0
    private var resolveFbo = 0
    private var renderFbo = 0
    private var msaaColorRb = 0
    private var depthRb = 0

    /** Scaled render size, valid after [ensure]. */
    var width = 0
        private set
    var height = 0
        private set

    /**
     * (Re)allocates the target for a base eye viewport of [baseWidth] × [baseHeight].
     * Cheap and idempotent when nothing relevant changed.
     */
    fun ensure(baseWidth: Int, baseHeight: Int) {
        val w = (baseWidth * renderScale).toInt().coerceAtLeast(1)
        val h = (baseHeight * renderScale).toInt().coerceAtLeast(1)
        val samples = effectiveSamples()
        if (w == texWidth && h == texHeight && renderScale == builtScale && samples == builtSamples) return

        release()
        texWidth = w
        texHeight = h
        width = w
        height = h
        builtScale = renderScale
        builtSamples = samples

        val ids = IntArray(1)

        GLES30.glGenTextures(1, ids, 0)
        colorTex = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, colorTex)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)

        GLES30.glGenFramebuffers(1, ids, 0)
        resolveFbo = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, resolveFbo)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, colorTex, 0,
        )

        if (samples > 0) {
            GLES30.glGenRenderbuffers(1, ids, 0)
            msaaColorRb = ids[0]
            GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, msaaColorRb)
            GLES30.glRenderbufferStorageMultisample(
                GLES30.GL_RENDERBUFFER, samples, GLES30.GL_RGBA8, w, h,
            )

            GLES30.glGenRenderbuffers(1, ids, 0)
            depthRb = ids[0]
            GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, depthRb)
            GLES30.glRenderbufferStorageMultisample(
                GLES30.GL_RENDERBUFFER, samples, GLES30.GL_DEPTH_COMPONENT24, w, h,
            )

            GLES30.glGenFramebuffers(1, ids, 0)
            renderFbo = ids[0]
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, renderFbo)
            GLES30.glFramebufferRenderbuffer(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_RENDERBUFFER, msaaColorRb,
            )
            GLES30.glFramebufferRenderbuffer(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_RENDERBUFFER, depthRb,
            )
        } else {
            GLES30.glGenRenderbuffers(1, ids, 0)
            depthRb = ids[0]
            GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, depthRb)
            GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT24, w, h)
            GLES30.glFramebufferRenderbuffer(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_RENDERBUFFER, depthRb,
            )
            renderFbo = resolveFbo
        }

        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GlUtils.checkGlError("EyeFramebuffer.ensure")
    }

    /** Binds the render FBO and sets the viewport to the scaled size. */
    fun bind() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, renderFbo)
        GLES30.glViewport(0, 0, width, height)
    }

    /** Resolves MSAA (if any) and returns the plain colour texture id. */
    fun resolve(): Int {
        if (renderFbo != resolveFbo) {
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, renderFbo)
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, resolveFbo)
            GLES30.glBlitFramebuffer(
                0, 0, width, height, 0, 0, width, height,
                GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST,
            )
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        }
        return colorTex
    }

    fun release() {
        val ids = IntArray(1)
        if (renderFbo != 0 && renderFbo != resolveFbo) {
            ids[0] = renderFbo; GLES30.glDeleteFramebuffers(1, ids, 0)
        }
        if (resolveFbo != 0) { ids[0] = resolveFbo; GLES30.glDeleteFramebuffers(1, ids, 0) }
        if (msaaColorRb != 0) { ids[0] = msaaColorRb; GLES30.glDeleteRenderbuffers(1, ids, 0) }
        if (depthRb != 0) { ids[0] = depthRb; GLES30.glDeleteRenderbuffers(1, ids, 0) }
        if (colorTex != 0) { ids[0] = colorTex; GLES30.glDeleteTextures(1, ids, 0) }
        renderFbo = 0
        resolveFbo = 0
        msaaColorRb = 0
        depthRb = 0
        colorTex = 0
        texWidth = 0
        texHeight = 0
        builtSamples = -1
    }

    private fun effectiveSamples(): Int {
        if (msaaSamples <= 1) return 0
        val max = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_SAMPLES, max, 0)
        return msaaSamples.coerceAtMost(max[0].coerceAtLeast(0))
    }
}
