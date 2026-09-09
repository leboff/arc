package com.daydreamvr.player.render

import android.view.Surface
import com.daydreamvr.player.screens.BrowseScreen
import com.daydreamvr.player.screens.CalibrationScreen
import com.daydreamvr.player.screens.GamepadCalibrationScreen
import com.daydreamvr.player.screens.OverlayRenderer
import com.daydreamvr.player.screens.PlayerHud
import com.daydreamvr.player.screens.ServerListScreen
import com.daydreamvr.player.screens.SettingsScreen
import com.daydreamvr.player.state.AppState
import com.daydreamvr.player.state.VrScreen
import com.daydreamvr.playback.PlaybackSnapshot
import com.daydreamvr.vrcore.gl.VideoTexture
import com.daydreamvr.vrcore.render.CylinderScreen
import com.daydreamvr.vrcore.render.EyeParams
import com.daydreamvr.vrcore.render.Scene
import com.daydreamvr.vrcore.render.SphereScreen
import com.daydreamvr.vrcore.ui.PanelSurface
import com.daydreamvr.vrcore.ui.Theme

/**
 * The Phase 5 scene (ARCHITECTURE.md §6, §11). Replaces `DebugCubeScene`:
 *
 *  - while browsing it draws whichever in-headset UI panel the [AppState] selects
 *    (server list / browser / settings), world-locked with lazy-follow;
 *  - while playing it draws the [CylinderScreen] (or [SphereScreen] for
 *    equirectangular content) fed by the shared [VideoTexture], plus the
 *    [PlayerHud] when it is visible;
 *  - the [OverlayRenderer] panel rides on top for toasts / dialogs / keyboard.
 *
 * All panel repaints go through `renderIfChanged`, so nothing is redrawn per
 * frame. Runs entirely on the GL thread except [recenter], which just latches a
 * request consumed in [update].
 */
class AppScene(
    private val theme: Theme = Theme(),
    private val stateProvider: () -> AppState,
    private val snapshotProvider: () -> PlaybackSnapshot,
    private val headYawProvider: () -> Float,
    private val onVideoSurfaceReady: (Surface) -> Unit,
) : Scene {

    private var serverList: ServerListScreen? = null
    private var browse: BrowseScreen? = null
    private var settings: SettingsScreen? = null
    private var calibration: CalibrationScreen? = null
    private var gamepadCal: GamepadCalibrationScreen? = null
    private var hud: PlayerHud? = null
    private var overlay: OverlayRenderer? = null

    private val cylinder = CylinderScreen()
    private val sphere = SphereScreen()
    private val video = VideoTexture()

    private var created = false
    private var lastFrameNs = 0L

    @Volatile private var recenterRequested = false

    /** Redraw counters for every panel — surfaced in the debug overlay (§5.4). */
    val redrawCounts: Map<String, Int>
        get() = buildMap {
            serverList?.let { put("servers", it.redrawCount) }
            browse?.let { put("browse", it.redrawCount) }
            settings?.let { put("settings", it.redrawCount) }
            calibration?.let { put("calibration", it.redrawCount) }
            gamepadCal?.let { put("gamepadCal", it.redrawCount) }
            hud?.let { put("hud", it.redrawCount) }
            overlay?.let { put("overlay", it.redrawCount) }
        }

    override fun onGlCreate() {
        if (created) onGlDestroy()

        // Texture sizes chosen so widthPx/widthM ≈ heightPx/heightM (UI_GAZE_PLAN.md §2.2, F8).
        serverList = ServerListScreen(PanelSurface(1024, 676), theme).also { it.onGlCreate() }
        browse = BrowseScreen(PanelSurface(1280, 800), theme).also { it.onGlCreate() }
        settings = SettingsScreen(PanelSurface(1024, 700), theme).also { it.onGlCreate() }
        calibration = CalibrationScreen(PanelSurface(1536, 1024), theme).also { it.onGlCreate() }
        gamepadCal = GamepadCalibrationScreen(PanelSurface(1024, 512), theme).also { it.onGlCreate() }
        hud = PlayerHud(PanelSurface(1280, 332), theme).also { it.onGlCreate() }
        overlay = OverlayRenderer(PanelSurface(1024, 668), theme).also { it.onGlCreate() }

        cylinder.onGlCreate()
        sphere.onGlCreate()
        video.createOnGlThread()
        onVideoSurfaceReady(video.surface)

        lastFrameNs = 0L
        created = true
    }

    override fun onGlResize(width: Int, height: Int) = Unit

    /** Latches a recentre; the snap happens on the GL thread in [update]. */
    fun recenter() {
        recenterRequested = true
    }

    override fun update(dtSeconds: Float) {
        val state = stateProvider()
        val snap = snapshotProvider()
        val headYaw = headYawProvider()

        serverList?.render(state)
        browse?.render(state)
        settings?.render(state)
        calibration?.render(state)
        gamepadCal?.render(state)
        hud?.render(state)
        overlay?.render(state)

        if (recenterRequested) {
            recenterRequested = false
            activePanelAnchor(state.screen)?.snapTo(headYaw)
            overlay?.anchor?.snapTo(headYaw)
            calibration?.anchor?.snapTo(headYaw)
            gamepadCal?.anchor?.snapTo(headYaw)
            cylinder.yawRad = headYaw
            sphere.yawRad = headYaw
        } else {
            activePanelAnchor(state.screen)?.update(headYaw, dtSeconds)
            overlay?.anchor?.update(headYaw, dtSeconds)
            calibration?.anchor?.update(headYaw, dtSeconds)
            gamepadCal?.anchor?.update(headYaw, dtSeconds)
        }

        if (state.screen == VrScreen.PLAYER) {
            if (snap.videoWidth > 0 && snap.videoHeight > 0) {
                video.setBufferSize(snap.videoWidth, snap.videoHeight)
                val aspect = snap.dimensions.displayAspect
                if (aspect > 0f) cylinder.setAspect(aspect)
            }
            video.updateIfDirty()
        }

        serverList?.updateTexture()
        browse?.updateTexture()
        settings?.updateTexture()
        calibration?.updateTexture()
        gamepadCal?.updateTexture()
        hud?.updateTexture()
        overlay?.updateTexture()
    }

    override fun draw(eye: EyeParams, viewM: FloatArray, projM: FloatArray) {
        val state = stateProvider()
        when (state.screen) {
            VrScreen.SERVER_LIST -> serverList?.drawGl(eye, viewM, projM)
            VrScreen.BROWSE -> browse?.drawGl(eye, viewM, projM)
            VrScreen.SETTINGS -> {
                val row = com.daydreamvr.player.state.Settings.ROWS.getOrNull(state.hud.focusIndex)
                if (row in com.daydreamvr.player.state.Settings.CALIBRATION_ROWS) {
                    calibration?.drawGl(eye, viewM, projM)
                }
                if (row == "Gamepad buttons") gamepadCal?.drawGl(eye, viewM, projM)
                settings?.drawGl(eye, viewM, projM)
            }
            VrScreen.PLAYER -> {
                val mode = state.playback.projection
                if (mode.isSpherical) {
                    sphere.draw(eye, viewM, projM, video, mode)
                } else {
                    cylinder.draw(eye, viewM, projM, video, mode)
                }
                if (state.hud.visible) hud?.drawGl(eye, viewM, projM)
            }
        }
        if (overlay?.visible == true) overlay?.drawGl(eye, viewM, projM)
    }

    override fun onGlDestroy() {
        listOfNotNull(serverList, browse, settings, calibration, gamepadCal, hud, overlay).forEach { it.onGlDestroy() }
        serverList = null
        browse = null
        settings = null
        calibration = null
        gamepadCal = null
        hud = null
        overlay = null
        cylinder.onGlDestroy()
        sphere.onGlDestroy()
        video.release()
        created = false
    }

    private fun activePanelAnchor(screen: VrScreen) = when (screen) {
        VrScreen.SERVER_LIST -> serverList?.anchor
        VrScreen.BROWSE -> browse?.anchor
        VrScreen.SETTINGS -> settings?.anchor
        VrScreen.PLAYER -> hud?.anchor
    }
}
