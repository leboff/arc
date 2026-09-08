package com.daydreamvr.vrcore.gl

import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Small GL ES 3.0 helpers. All calls must be made on the GL thread. */
object GlUtils {

    private const val TAG = "GlUtils"

    fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            error("Shader compile failed: $log")
        }
        return shader
    }

    fun buildProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertex)
        GLES30.glAttachShader(program, fragment)
        GLES30.glLinkProgram(program)
        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        // The shader objects are no longer needed once linked (or failed).
        GLES30.glDeleteShader(vertex)
        GLES30.glDeleteShader(fragment)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            error("Program link failed: $log")
        }
        return program
    }

    fun floatBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(data)
                position(0)
            }

    /** Logs (does not throw) any pending GL errors. Debug aid only. */
    fun checkGlError(where: String) {
        var error = GLES30.glGetError()
        while (error != GLES30.GL_NO_ERROR) {
            Log.e(TAG, "$where: glError 0x${error.toString(16)}")
            error = GLES30.glGetError()
        }
    }
}
