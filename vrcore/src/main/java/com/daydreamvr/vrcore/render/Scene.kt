package com.daydreamvr.vrcore.render

/**
 * The renderable world. [VrRenderer] owns the frame loop and the per-eye
 * viewport/matrix setup; a `Scene` only knows how to build its GL resources and
 * draw itself once per eye.
 *
 * Lifecycle: [onGlCreate] and [onGlDestroy] bracket a GL context (treated as
 * always-lost across pause/resume, ARCHITECTURE.md §15); [onGlResize] follows
 * every surface size change; [update] runs once per frame before both eyes are
 * drawn; [draw] runs once per eye.
 */
interface Scene {
    fun onGlCreate()

    fun onGlResize(width: Int, height: Int)

    fun update(dtSeconds: Float)

    fun draw(eye: EyeParams, viewM: FloatArray, projM: FloatArray)

    fun onGlDestroy()
}
