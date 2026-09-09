package com.daydreamvr.player.render

import android.view.Surface
import com.daydreamvr.player.screens.BrowseScreen
import com.daydreamvr.player.screens.CalibrationScreen
import com.daydreamvr.player.screens.GamepadCalibrationScreen
import com.daydreamvr.player.screens.OverlayRenderer
import com.daydreamvr.player.screens.PlayerHud
import com.daydreamvr.player.screens.ScreenPanel
import com.daydreamvr.player.screens.ServerListScreen
import com.daydreamvr.player.screens.SettingsScreen
import com.daydreamvr.player.state.AppState
import com.daydreamvr.player.state.GazeTarget
import com.daydreamvr.player.state.VrScreen
import com.daydreamvr.playback.PlaybackSnapshot
import com.daydreamvr.vrcore.gl.VideoTexture
import com.daydreamvr.vrcore.render.CylinderScreen
import com.daydreamvr.vrcore.render.EyeParams
import com.daydreamvr.vrcore.render.Scene
import com.daydreamvr.vrcore.render.SphereScreen
import com.daydreamvr.vrcore.ui.GazeRay
import com.daydreamvr.vrcore.ui.GazeStabilizer
import com.daydreamvr.vrcore.ui.GazeSurfaces
import com.daydreamvr.vrcore.ui.PanelRaycast
import com.daydreamvr.vrcore.ui.PanelSurface
import com.daydreamvr.vrcore.ui.Reticle
import com.daydreamvr.vrcore.ui.Theme
import kotlin.math.atan2

/**
 * The Phase 5/7 scene (ARCHITECTURE.md §6, §11; UI_GAZE_PLAN.md §3.1).
 *
 * Per frame on the GL thread: repaint any panel whose slice changed, advance the
 * lazy-follow anchors, run the gaze pipeline (ray → raycast → hit-test →
 * stabilize → edge-triggered dispatch), then draw the selected screen, the
 * overlay, and the reticle last in both eyes from the same cached hit.
 */
class AppScene(
    private val theme: Theme = Theme(),
    private val stateProvider: () -> AppState,
    private val snapshotProvider: () -> PlaybackSnapshot,
    private val neckOffsetProvider: () -> FloatArray? = { null },
    private val trackerCalibratedProvider: () -> Boolean = { true },
    private val onGazeTarget: (GazeTarget?) -> Unit = {},
    private val onVideoSurfaceReady: (Surface) -> Unit,
) : Scene {

    private var serverList: ServerListScreen? = null
    private var browse: BrowseScreen? = null

    /**
     * The detached system dock — a second interactive surface on `BROWSE` at a
     * smaller radius than [browse]. Its anchor is *slaved* to the browse anchor
     * each frame so the two move as one rigid assembly (§3.1). Wired in M8.
     */
    private var dock: ScreenPanel? = null
    private var settings: SettingsScreen? = null
    private var calibration: CalibrationScreen? = null
    private var gamepadCal: GamepadCalibrationScreen? = null
    private var hud: PlayerHud? = null
    private var overlay: OverlayRenderer? = null

    private val cylinder = CylinderScreen()
    private val sphere = SphereScreen()
    private val video = VideoTexture()
    private val reticle = Reticle()

    private val stabilizer = GazeStabilizer<GazeTarget>()
    private var lastDispatched: GazeTarget? = null
    private var listWindowReported = false

    private var created = false

    @Volatile private var recenterRequested = false

    // Reticle placement, written in update, read in draw.
    private var reticleVisible = false
    private var reticleAlpha = 1f
    private var rHitX = 0f
    private var rHitY = 0f
    private var rHitZ = -2.5f
    private var rCamX = 0f
    private var rCamY = 0f
    private var rCamZ = 0f

    /** For the debug overlay / measurement (UI_GAZE_PLAN.md §3.3). */
    var onListWindowMeasured: (com.daydreamvr.player.state.ListWindow) -> Unit = {}

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
        reticle.onGlCreate()
        onVideoSurfaceReady(video.surface)

        stabilizer.reset()
        lastDispatched = null
        listWindowReported = false
        created = true
    }

    override fun onGlResize(width: Int, height: Int) = Unit

    fun recenter() {
        recenterRequested = true
    }

    override fun update(dtSeconds: Float, pose: FloatArray) {
        val state = stateProvider()
        val snap = snapshotProvider()
        val headYaw = atan2(-pose[8], pose[10])

        serverList?.render(state)
        browse?.render(state)
        settings?.render(state)
        calibration?.render(state)
        gamepadCal?.render(state)
        hud?.render(state)
        overlay?.render(state)

        reportListWindowOnce()

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

        // Slave the dock to the browse panel AFTER the browse anchor has moved, so
        // the two never shear apart during a head turn (§3.1).
        browse?.let { dock?.anchor?.snapTo(it.anchor.yawRad) }

        runGazePipeline(state, pose, dtSeconds)

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

    private fun reportListWindowOnce() {
        if (listWindowReported) return
        val s = settings ?: return
        val b = browse ?: return
        listWindowReported = true
        onListWindowMeasured(
            com.daydreamvr.player.state.ListWindow(
                browse = b.visibleRows(),
                settings = s.visibleRows(),
                servers = com.daydreamvr.player.state.ListWindow().servers,
            ),
        )
    }

    private fun runGazePipeline(state: AppState, pose: FloatArray, dt: Float) {
        val surfaces = activeGazeSurfaces(state)
        val calibrated = trackerCalibratedProvider()
        if (surfaces.isEmpty() || !calibrated) {
            stabilizer.update(null, dt)
            if (lastDispatched != null) {
                lastDispatched = null
                onGazeTarget(null)
            }
            reticle.advance(0f, dt)
            reticleVisible = false
            return
        }

        val ray = GazeRay.fromPose(pose, neckOffsetProvider())

        // Nearest hit wins. A ray that pierces two bounded quads reports the
        // smaller distanceM; a hit on the panel but off every HitRegion yields a
        // null target that must NOT fall through to a farther surface (§3.3).
        val resolved = GazeSurfaces.resolve(
            ray,
            surfaces.map { s -> GazeSurfaces.Surface(s.gazeGeometry()) { x, y -> s.hitMap.hitTest(x, y) } },
        )
        val best = resolved.hit
        val bestTarget = resolved.target

        val stable = stabilizer.update(bestTarget, dt)
        if (stable != lastDispatched) {
            lastDispatched = stable
            onGazeTarget(stable)
        }

        // Reticle: the winning hit, else an unbounded solve against the primary surface.
        val place = best ?: PanelRaycast.intersectUnbounded(ray, surfaces.first().gazeGeometry())
        reticle.advance(if (bestTarget != null) 1f else 0f, dt)
        if (place != null) {
            reticleVisible = true
            reticleAlpha = if (best != null) 1f else 0.45f
            rHitX = place.wx; rHitY = place.wy; rHitZ = place.wz
            rCamX = ray.ox; rCamY = ray.oy; rCamZ = ray.oz
        } else {
            reticleVisible = false
        }
    }

    /**
     * Interactive surfaces for this state, in paint order (nearest-hit breaks
     * ties by list order). Overlay, when up, suppresses all others.
     */
    private fun activeGazeSurfaces(state: AppState): List<ScreenPanel> {
        if (overlay?.visible == true) return listOfNotNull(overlay)
        return when (state.screen) {
            VrScreen.SERVER_LIST -> listOfNotNull(serverList)
            VrScreen.BROWSE -> listOfNotNull(browse, dock)
            VrScreen.SETTINGS -> listOfNotNull(settings)
            VrScreen.PLAYER -> if (state.hud.visible) listOfNotNull(hud) else emptyList()
        }
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

        if (reticleVisible) {
            reticle.draw(viewM, projM, rHitX, rHitY, rHitZ, rCamX, rCamY, rCamZ, reticleAlpha)
        }
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
        reticle.onGlDestroy()
        created = false
    }

    private fun activePanelAnchor(screen: VrScreen) = when (screen) {
        VrScreen.SERVER_LIST -> serverList?.anchor
        VrScreen.BROWSE -> browse?.anchor
        VrScreen.SETTINGS -> settings?.anchor
        VrScreen.PLAYER -> hud?.anchor
    }
}
