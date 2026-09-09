package com.daydreamvr.vrcore.render

/**
 * The renderable world. [VrRenderer] owns the frame loop and the per-eye
 * viewport/matrix setup; a `Scene` only knows how to build its GL resources and
 * draw itself once per eye.
 *
 * Lifecycle: [onGlCreate] and [onGlDestroy] bracket a GL context (treated as
 * always-lost across pause/resume, ARCHITECTURE.md §15); [onGlResize] follows
 * every surface size change; [update] runs once per frame before both eyes are
 * drawn, receiving the head [pose] already fetched for this frame; [draw] runs
 * once per eye.
 */
interface Scene {
    fun onGlCreate()

    fun onGlResize(width: Int, height: Int)

    /** @param pose column-major `R_W_H` for this frame (UI_GAZE_PLAN.md §3.1). */
    fun update(dtSeconds: Float, pose: FloatArray)

    fun draw(eye: EyeParams, viewM: FloatArray, projM: FloatArray)

    fun onGlDestroy()
}
