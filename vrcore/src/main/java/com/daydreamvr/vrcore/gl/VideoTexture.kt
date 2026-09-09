package com.daydreamvr.vrcore.gl

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.Matrix
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The decoder-to-GL bridge (ARCHITECTURE.md §10.2).
 *
 * A single `GL_TEXTURE_EXTERNAL_OES` texture fed by a [SurfaceTexture]; the
 * ExoPlayer video renderer draws into [surface]. [createOnGlThread], every
 * [updateIfDirty] and [release] must run on the GL thread; the frame-available
 * signal arrives on an arbitrary thread and is picked up on the next
 * [updateIfDirty].
 */
class VideoTexture {

    var textureId: Int = 0
        private set

    private var _surfaceTexture: SurfaceTexture? = null
    private var _surface: Surface? = null

    val surfaceTexture: SurfaceTexture
        get() = checkNotNull(_surfaceTexture) { "VideoTexture.createOnGlThread() not called" }

    val surface: Surface
        get() = checkNotNull(_surface) { "VideoTexture.createOnGlThread() not called" }

    private val frameAvailable = AtomicBoolean(false)
    private val transform = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    fun createOnGlThread() {
        if (_surfaceTexture != null) return

        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        textureId = ids[0]

        val target = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        GLES30.glBindTexture(target, textureId)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(target, 0)

        val st = SurfaceTexture(textureId)
        st.setOnFrameAvailableListener { frameAvailable.set(true) }
        _surfaceTexture = st
        _surface = Surface(st)
    }

    /** Mirrors `Player.Listener.onVideoSizeChanged` so the buffer is sized to the media. */
    fun setBufferSize(width: Int, height: Int) {
        if (width > 0 && height > 0) _surfaceTexture?.setDefaultBufferSize(width, height)
    }

    /**
     * Consumes the newest decoded frame if one is pending. Returns true when the
     * texture (and [transformMatrix]) changed this call.
     */
    fun updateIfDirty(): Boolean {
        if (!frameAvailable.compareAndSet(true, false)) return false
        val st = _surfaceTexture ?: return false
        st.updateTexImage()
        st.getTransformMatrix(transform)
        return true
    }

    fun transformMatrix(out: FloatArray) {
        System.arraycopy(transform, 0, out, 0, 16)
    }

    fun release() {
        _surface?.release()
        _surface = null
        _surfaceTexture?.release()
        _surfaceTexture = null
        if (textureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
    }
}
