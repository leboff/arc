package com.daydreamvr.vrcore.gl

import android.opengl.GLES30

/**
 * An interleaved vertex buffer plus enough metadata to bind and draw it.
 * [strideBytes] is the byte stride of one vertex; attribute layout is the
 * caller's business (via [GLES30.glVertexAttribPointer]).
 */
class Mesh(
    vertices: FloatArray,
    val vertexCount: Int,
    val strideBytes: Int,
    private val drawMode: Int = GLES30.GL_TRIANGLES,
) {
    private val vbo = IntArray(1)

    init {
        GLES30.glGenBuffers(1, vbo, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            vertices.size * Float.SIZE_BYTES,
            GlUtils.floatBuffer(vertices),
            GLES30.GL_STATIC_DRAW,
        )
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    fun bind() {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
    }

    fun draw() {
        GLES30.glDrawArrays(drawMode, 0, vertexCount)
    }

    fun release() {
        GLES30.glDeleteBuffers(1, vbo, 0)
    }
}
