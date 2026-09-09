package com.daydreamvr.player.screens

import android.graphics.Canvas
import android.opengl.Matrix
import com.daydreamvr.vrcore.render.EyeParams
import com.daydreamvr.vrcore.ui.PanelAnchor
import com.daydreamvr.vrcore.ui.PanelMetrics
import com.daydreamvr.vrcore.ui.PanelQuad
import com.daydreamvr.vrcore.ui.PanelSurface
import com.daydreamvr.vrcore.ui.Theme

/**
 * Shared plumbing for the in-headset screens: a [PanelSurface] drawn on a curved
 * [PanelQuad], world-locked with lazy-follow via [anchor], and redrawn only when
 * the view-model slice changed (ARCHITECTURE.md R4). [redrawCount] is surfaced in
 * the debug overlay to prove the panel is not repainted per frame.
 */
abstract class ScreenPanel(
    protected val panel: PanelSurface,
    protected val theme: Theme,
    panelWidthM: Float = 2.2f,
    panelHeightM: Float = 1.35f,
) {
    val anchor = PanelAnchor()

    protected val panelWidthM: Float = panelWidthM
    protected val panelHeightM: Float = panelHeightM

    /**
     * This panel's honest angular scale (UI_GAZE_PLAN.md §2.2). The arc is
     * `panelWidthM / anchor.distanceM`, not a shared constant, so text requested
     * in degrees is the size intended on *this* panel.
     */
    val metrics: PanelMetrics =
        PanelMetrics.curved(panel.widthPx, panel.heightPx, panelWidthM, anchor.distanceM)

    /** Metres to shift the panel vertically in head space (HUD sits below eye line). */
    protected open val verticalOffsetM: Float = 0f
    private val quad = PanelQuad(widthM = panelWidthM, heightM = panelHeightM, curved = true, curveRadiusM = anchor.distanceM)
    private val model = FloatArray(16)
    private var lastKey: Any? = Unit

    init {
        // The closed-form gaze raycast (UI_GAZE_PLAN.md §1.4) assumes this. F7.
        require(quad.curveRadiusM == anchor.distanceM) {
            "panel curve radius ${quad.curveRadiusM} must equal anchor distance ${anchor.distanceM}"
        }
    }

    /**
     * Angle for the panel's model rotation about +Y, radians. Negated because
     * `Matrix.rotateM` about +Y turns the opposite way from the `atan2(x, −z)`
     * azimuth convention the tracker and raycast use (UI_GAZE_PLAN.md §1.6, F6).
     */
    val modelYawRad: Float get() = -anchor.yawRad

    var redrawCount: Int = 0
        private set

    open fun onGlCreate() {
        panel.createOnGlThread()
        quad.onGlCreate()
    }

    open fun onGlDestroy() {
        quad.onGlDestroy()
        panel.release()
    }

    fun updateTexture(): Boolean = panel.updateIfDirty()

    /** Repaints the panel Canvas only when [key] differs from the last render. */
    protected fun renderIfChanged(key: Any?, block: (Canvas) -> Unit) {
        if (key == lastKey) return
        lastKey = key
        redrawCount++
        panel.draw(block)
    }

    /** Forces a repaint on the next [renderIfChanged] (e.g. after GL recreate). */
    fun invalidate() {
        lastKey = INVALID
    }

    open fun drawGl(eye: EyeParams, viewM: FloatArray, projM: FloatArray, alpha: Float = 1f) {
        Matrix.setIdentityM(model, 0)
        Matrix.rotateM(model, 0, Math.toDegrees(modelYawRad.toDouble()).toFloat(), 0f, 1f, 0f)
        Matrix.translateM(model, 0, 0f, verticalOffsetM, -anchor.distanceM)
        quad.draw(eye, viewM, projM, model, panel, alpha)
    }

    private companion object {
        val INVALID = Any()
    }
}

/** Fills the panel with the themed background + rounded frame. */
fun Canvas.panelBackground(theme: Theme, widthPx: Int, heightPx: Int) {
    drawColor(theme.panelColor)
    drawRoundRect(
        4f, 4f, widthPx - 4f, heightPx - 4f,
        theme.cornerRadiusPx, theme.cornerRadiusPx,
        theme.strokePaint(theme.panelStrokeColor, 2f),
    )
}
