package com.daydreamvr.player.screens

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.opengl.Matrix
import com.daydreamvr.player.state.GazeTarget
import com.daydreamvr.vrcore.render.EyeParams
import com.daydreamvr.vrcore.ui.HitMap
import com.daydreamvr.vrcore.ui.PanelAnchor
import com.daydreamvr.vrcore.ui.PanelGeometry
import com.daydreamvr.vrcore.ui.PanelMetrics
import com.daydreamvr.vrcore.ui.PanelQuad
import com.daydreamvr.vrcore.ui.PanelSurface
import com.daydreamvr.vrcore.ui.Radius
import com.daydreamvr.vrcore.ui.Space
import com.daydreamvr.vrcore.ui.Surfaces
import com.daydreamvr.vrcore.ui.Theme
import com.daydreamvr.vrcore.ui.Type

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
    /**
     * Viewing distance, metres. The panel's curve radius is slaved to this so the
     * closed-form gaze raycast stays a quadratic (F7). The detached dock sits at a
     * smaller distance than the browse panel (UI_REDESIGN_REVIEWED_PLAN.md §3.2, R4).
     */
    distanceM: Float = 2.5f,
) {
    val anchor = PanelAnchor(distanceM = distanceM)

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

    /**
     * Pixel → [GazeTarget] map for this panel, rebuilt on every repaint and read
     * by the gaze pass on the GL thread (UI_GAZE_PLAN.md §2.4). `@Volatile` is
     * insurance against panel painting ever moving to its own thread; today both
     * happen on the GL thread and it only changes inside `renderIfChanged`.
     */
    @Volatile
    var hitMap: HitMap<GazeTarget> = HitMap.empty()
        protected set

    /** The world placement the gaze raycast tests against. */
    open fun gazeGeometry(): PanelGeometry = PanelGeometry(
        modelYawRad = modelYawRad,
        distanceM = anchor.distanceM,
        widthM = panelWidthM,
        heightM = panelHeightM,
        verticalOffsetM = verticalOffsetM,
        widthPx = panel.widthPx,
        heightPx = panel.heightPx,
        curved = true,
    )

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

    /** A right-aligned status pill shown in the header. */
    data class Chip(val label: String, val accented: Boolean = false)

    /**
     * Shared header: title top-left, optional status chip top-right, a divider
     * underneath (UI_GAZE_PLAN.md §5.1). Returns the y at which content starts so
     * headers stop drifting between screens.
     */
    protected fun drawHeader(canvas: Canvas, title: String, chip: Chip? = null): Float {
        val padL = metrics.px(Space.XL)
        val padT = metrics.px(Space.L)
        val titleDeg = Type.screenTitle.degrees
        val titlePx = metrics.px(titleDeg)
        val baseline = padT + titlePx * 0.82f
        canvas.drawText(title, padL, baseline, theme.text(Type.screenTitle, metrics))

        chip?.let {
            val cp = theme.text(Type.chip, metrics, if (it.accented) theme.textOnAccent else theme.accentText)
            cp.textAlign = Paint.Align.RIGHT
            val tw = cp.measureText(it.label)
            val padChip = metrics.px(Space.S)
            val rect = RectF(
                metrics.widthPx - padL - tw - padChip * 2,
                padT,
                metrics.widthPx - padL,
                padT + titlePx * 1.05f,
            )
            Surfaces.chip(canvas, rect, metrics, theme, accented = it.accented)
            canvas.drawText(it.label, rect.right - padChip, baseline, cp)
        }

        val contentTop = padT + titlePx * 1.1f + metrics.px(Space.M)
        Surfaces.divider(canvas, padL, metrics.widthPx - padL, contentTop - metrics.px(Space.S), theme)
        return contentTop
    }

    protected fun contentLeft(): Float = metrics.px(Space.XL)

    protected fun bodyBottom(): Float = metrics.heightPx - metrics.px(Space.L)

    private companion object {
        val INVALID = Any()
    }
}

/** Fills the panel with the glassmorphic background (gradient + sheen + hairline). */
fun Canvas.panelBackground(theme: Theme, metrics: PanelMetrics) {
    Surfaces.panel(this, metrics, theme)
}
