package com.daydreamvr.vrcore.gl

import android.opengl.GLES30

/**
 * A linked GL program with cached uniform / attribute locations. Create and use
 * on the GL thread only.
 */
class Shader(vertexSource: String, fragmentSource: String) {

    val program: Int = GlUtils.buildProgram(vertexSource, fragmentSource)

    private val uniforms = HashMap<String, Int>()
    private val attributes = HashMap<String, Int>()

    fun use() {
        GLES30.glUseProgram(program)
    }

    fun uniform(name: String): Int =
        uniforms.getOrPut(name) { GLES30.glGetUniformLocation(program, name) }

    fun attribute(name: String): Int =
        attributes.getOrPut(name) { GLES30.glGetAttribLocation(program, name) }

    fun release() {
        GLES30.glDeleteProgram(program)
        uniforms.clear()
        attributes.clear()
    }
}
