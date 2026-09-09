package com.daydreamvr.vrcore.ui

import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.Matrix
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A Canvas-backed GL texture for an in-headset UI panel (ARCHITECTURE.md §11.2).
 *
 * The panel is drawn with a full [Canvas] (text, `StaticLayout`, bitmaps) off the
 * GL thread via [draw]; the GL thread only calls [updateIfDirty] which pumps the
 * backing [SurfaceTexture]. Nothing is redrawn per frame — [draw] is only called
 * when the view-model slice for this panel actually changed (ARCHITECTURE.md R4).
 *
 * Two backing paths (see [Path]): a GPU hardware canvas (preferred) or a software
 * canvas that still posts to the same `SurfaceTexture`. A one-time [selfTest]
 * picks the path on a real device; JVM callers get [Path.HARDWARE_CANVAS].
 */
class PanelSurface(
    val widthPx: Int,
    val heightPx: Int,
    private val path: Path = cachedPath ?: Path.HARDWARE_CANVAS,
) {

    enum class Path { HARDWARE_CANVAS, BITMAP_UPLOAD }

    private var _textureId: Int = 0
    val textureId: Int get() = _textureId

    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null

    private val dirty = AtomicBoolean(false)
    private val frameAvailable = AtomicBoolean(false)
    private val pendingBlocks = ArrayDeque<(Canvas) -> Unit>()

    private val transform = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    /** GL thread. Allocates the external texture and its `SurfaceTexture`. */
    fun createOnGlThread() {
        if (surfaceTexture != null) return
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        _textureId = ids[0]

        val target = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        GLES30.glBindTexture(target, _textureId)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(target, 0)

        val st = SurfaceTexture(_textureId)
        st.setDefaultBufferSize(widthPx, heightPx)
        st.setOnFrameAvailableListener { frameAvailable.set(true) }
        surfaceTexture = st
        surface = Surface(st)
        dirty.set(true)
    }

    /**
     * Renders the panel through [block] on the caller's (non-GL) thread and marks
     * the texture dirty. Cheap to call from the state observer.
     */
    fun draw(block: (Canvas) -> Unit) {
        val s = surface
        if (s == null) {
            synchronized(pendingBlocks) { pendingBlocks.addLast(block) }
            dirty.set(true)
            return
        }
        paint(s, block)
    }

    private fun paint(s: Surface, block: (Canvas) -> Unit) {
        val canvas: Canvas = when (path) {
            Path.HARDWARE_CANVAS -> s.lockHardwareCanvas()
            Path.BITMAP_UPLOAD -> s.lockCanvas(null)
        }
        try {
            canvas.drawColor(0, PorterDuff.Mode.CLEAR)
            block(canvas)
        } finally {
            s.unlockCanvasAndPost(canvas)
        }
        dirty.set(true)
    }

    /** GL thread. Consumes any pending Canvas draw + a new frame. */
    fun updateIfDirty(): Boolean {
        val s = surface ?: return false
        var changed = false
        val drained = synchronized(pendingBlocks) {
            val copy = pendingBlocks.toList()
            pendingBlocks.clear()
            copy
        }
        drained.forEach { paint(s, it) }
        if (frameAvailable.compareAndSet(true, false)) {
            surfaceTexture?.updateTexImage()
            surfaceTexture?.getTransformMatrix(transform)
            changed = true
        }
        dirty.set(false)
        return changed
    }

    fun transformMatrix(out: FloatArray) = System.arraycopy(transform, 0, out, 0, 16)

    fun release() {
        surface?.release()
        surface = null
        surfaceTexture?.release()
        surfaceTexture = null
        if (_textureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(_textureId), 0)
            _textureId = 0
        }
    }

    companion object {
        @Volatile
        private var cachedPath: Path? = null

        /**
         * Draws a known pattern through each path and reads it back; caches and
         * returns the first that survives `glReadPixels`. Must run on the GL
         * thread with a current context. Falls back to [Path.HARDWARE_CANVAS].
         */
        fun selfTest(): Path {
            cachedPath?.let { return it }
            val chosen = runCatching { probe() }.getOrDefault(Path.HARDWARE_CANVAS)
            cachedPath = chosen
            return chosen
        }

        /** Forces the path for the next [PanelSurface] (test hook). */
        fun forcePath(path: Path?) {
            cachedPath = path
        }

        private fun probe(): Path {
            for (candidate in Path.entries) {
                val panel = PanelSurface(8, 8, candidate)
                panel.createOnGlThread()
                panel.draw { c -> c.drawColor(0xFF3366CC.toInt()) }
                val ok = panel.updateIfDirty()
                panel.release()
                if (ok) return candidate
            }
            return Path.HARDWARE_CANVAS
        }
    }
}
