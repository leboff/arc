package com.daydreamvr.vrcore.render

/** Which eye a viewport / frustum belongs to. */
enum class Eye { LEFT, RIGHT }

/** A pixel rectangle in the GL surface, origin bottom-left (GL convention). */
data class Viewport(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * Half-angles of an off-centre frustum, in **degrees**, all positive.
 *
 * [outer] is the temporal side (toward the edge of the display), [inner] the
 * nasal side (toward the centre divider). The two eyes carry identical
 * [FovAngles]; the mirroring happens when a projection matrix is built
 * (see [StereoLayout.projectionMatrix]).
 */
data class FovAngles(val outer: Float, val inner: Float, val up: Float, val down: Float)

/**
 * Everything the renderer needs to draw one eye for one frame.
 *
 * @param eyeOffsetX signed metres along head-space X (+ for the right eye).
 */
data class EyeParams(
    val eye: Eye,
    val viewport: Viewport,
    val fov: FovAngles,
    val eyeOffsetX: Float,
)
