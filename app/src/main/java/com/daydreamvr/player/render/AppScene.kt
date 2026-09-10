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
import com.daydreamvr.player.screens.SystemDockScreen
import com.daydreamvr.player.screens.BrowseLayout
import com.daydreamvr.player.screens.DockLayout
import com.daydreamvr.player.media.thumb.ThumbnailCache
import com.daydreamvr.player.state.AppState
import com.daydreamvr.player.state.GazeTarget
import com.daydreamvr.player.state.VrScreen
import com.daydreamvr.playback.PlaybackSnapshot
import com.daydreamvr.vrcore.gl.VideoTexture
import com.daydreamvr.vrcore.render.CylinderScreen
import com.daydreamvr.vrcore.render.EyeParams
import com.daydreamvr.vrcore.render.GroundGrid
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
    /** `PowerManager.THERMAL_STATUS_*`; drives the ground-grid intensity (§12.4). */
    private val thermalStatusProvider: () -> Int = { 0 },
    /** The shared thumbnail cache the browse grid peeks each repaint (§9). */
    private val thumbnailCacheProvider: () -> ThumbnailCache,
    private val onVideoSurfaceReady: (Surface) -> Unit,
) : Scene {

    private var serverList: ServerListScreen? = null
    private var browse: BrowseScreen? = null

    /**
     * The detached system dock — a second interactive surface on `BROWSE` at a
     * smaller radius than [browse]. Its anchor is *slaved* to the browse anchor
     * each frame so the two move as one rigid assembly (§3.1).
     */
    private var dock: SystemDockScreen? = null
    private var settings: SettingsScreen? = null
    private var calibration: CalibrationScreen? = null
    private var gamepadCal: GamepadCalibrationScreen? = null
    private var hud: PlayerHud? = null
    private var overlay: OverlayRenderer? = null

    private val cylinder = CylinderScreen()
    private val sphere = SphereScreen()
    private val video = VideoTexture()
    private val reticle = Reticle()
    private val groundGrid = GroundGrid()

    private val groundLine = rgb(theme.accent)
    private val groundGlow = rgb(theme.accent)

    private val stabilizer = GazeStabilizer<GazeTarget>()
    private var lastDispatched: GazeTarget? = null
    private var listWindowReported = false

    private var created = false

    private val worldYaw = com.daydreamvr.vrcore.render.WorldYaw()
    private var screenAnchorYaw = 0f
    @Volatile private var yawRate = 0f

    fun setYawRate(rate: Float) { yawRate = rate }

    @Volatile private var recenterSettleSeconds = 0f
    internal val recenterRemainingSeconds: Float get() = recenterSettleSeconds
    internal var screenAnchorYawForTest: Float
        get() = screenAnchorYaw
        set(value) { screenAnchorYaw = value }

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
            dock?.let { put("dock", it.redrawCount) }
            settings?.let { put("settings", it.redrawCount) }
            calibration?.let { put("calibration", it.redrawCount) }
            gamepadCal?.let { put("gamepadCal", it.redrawCount) }
            hud?.let { put("hud", it.redrawCount) }
            overlay?.let { put("overlay", it.redrawCount) }
        }

    override fun onGlCreate() {
        if (created) onGlDestroy()

        serverList = ServerListScreen(PanelSurface(1024, 676), theme).also { it.onGlCreate() }
        browse = BrowseScreen(PanelSurface(BrowseLayout.WIDTH_PX, BrowseLayout.HEIGHT_PX), theme).also { it.onGlCreate() }
        dock = SystemDockScreen(PanelSurface(DockLayout.WIDTH_PX, DockLayout.HEIGHT_PX), theme).also { it.onGlCreate() }
        settings = SettingsScreen(PanelSurface(1024, 700), theme).also { it.onGlCreate() }
        calibration = CalibrationScreen(PanelSurface(1536, 1024), theme).also { it.onGlCreate() }
        gamepadCal = GamepadCalibrationScreen(PanelSurface(1024, 512), theme).also { it.onGlCreate() }
        hud = PlayerHud(PanelSurface(1280, 332), theme).also { it.onGlCreate() }
        overlay = OverlayRenderer(PanelSurface(1024, 668), theme).also { it.onGlCreate() }

        cylinder.onGlCreate()
        sphere.onGlCreate()
        video.createOnGlThread()
        reticle.onGlCreate()
        groundGrid.onGlCreate()
        onVideoSurfaceReady(video.surface)

        stabilizer.reset()
        lastDispatched = null
        listWindowReported = false
        created = true
    }

    override fun onGlResize(width: Int, height: Int) = Unit

    fun recenter() {
        recenterSettleSeconds = RECENTER_SETTLE_SECONDS
    }

    override fun update(dtSeconds: Float, pose: FloatArray) {
        val state = stateProvider()
        val snap = snapshotProvider()
        val headYaw = atan2(-pose[8], pose[10])

        serverList?.render(state)
        browse?.render(state, thumbnailCacheProvider())
        dock?.render(state)
        settings?.render(state)
        calibration?.render(state)
        gamepadCal?.render(state)
        hud?.render(state)
        overlay?.render(state)

        reportListWindowOnce()

        if (recenterSettleSeconds > 0f) {
            recenterSettleSeconds = (recenterSettleSeconds - dtSeconds).coerceAtLeast(0f)
            serverList?.anchor?.snapTo(0f)
            browse?.anchor?.snapTo(0f)
            dock?.anchor?.snapTo(0f)
            settings?.anchor?.snapTo(0f)
            hud?.anchor?.snapTo(0f)
            overlay?.anchor?.snapTo(0f)
            calibration?.anchor?.snapTo(0f)
            gamepadCal?.anchor?.snapTo(0f)
            screenAnchorYaw = 0f
        } else {
            activePanelAnchor(state.screen)?.update(headYaw, dtSeconds)
            overlay?.anchor?.update(headYaw, dtSeconds)
            calibration?.anchor?.update(headYaw, dtSeconds)
            gamepadCal?.anchor?.update(headYaw, dtSeconds)
        }

        if (state.screen != VrScreen.PLAYER) yawRate = 0f
        worldYaw.update(yawRate, dtSeconds)
        cylinder.yawRad = screenAnchorYaw + worldYaw.offsetRad
        sphere.yawRad = cylinder.yawRad

        // Slave the dock to the browse panel AFTER the browse anchor has moved, so
        // the two never shear apart during a head turn (§3.1).
        browse?.let { dock?.anchor?.snapTo(it.anchor.yawRad) }
        groundGrid.yawRad = activePanelAnchor(state.screen)?.yawRad ?: cylinder.yawRad

        runGazePipeline(state, pose, dtSeconds)

        if (state.screen == VrScreen.PLAYER) {
            if (snap.videoWidth > 0 && snap.videoHeight > 0) {
                video.setBufferSize(snap.videoWidth, snap.videoHeight)
                val aspect = snap.dimensions.displayAspect
                if (aspect > 0f) cylinder.setAspect(aspect)
            }
            runCatching {
                video.updateIfDirty()
            }.onFailure { t ->
                android.util.Log.e("AppScene", "Error updating video texture frame", t)
            }
        }

        serverList?.updateTexture()
        browse?.updateTexture()
        dock?.updateTexture()
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
                browse = b.visibleSidebarRows(),
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
            surfaces.map { s -> GazeSurfaces.Surface(s.gazeGeometry()) { x, y -> s.hitTest(x, y) } },
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

        // OLED void → ground grid → panels → overlay → reticle (§3.4).
        if (GroundGridPolicy.visibleOn(state.screen)) {
            val thermal = thermalStatusProvider()
            groundGrid.draw(
                viewM, projM, groundLine, groundGlow,
                GroundGridPolicy.intensityFor(thermal),
                glow = GroundGridPolicy.glowFor(thermal),
            )
        }

        when (state.screen) {
            VrScreen.SERVER_LIST -> serverList?.drawGl(eye, viewM, projM)
            VrScreen.BROWSE -> {
                browse?.drawGl(eye, viewM, projM)
                dock?.drawGl(eye, viewM, projM)
            }
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
                    sphere.domeFovDegrees = mode.domeFov?.degrees ?: com.daydreamvr.vrcore.render.DomeFov.DEG_180.degrees
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
        listOfNotNull(serverList, browse, dock, settings, calibration, gamepadCal, hud, overlay).forEach { it.onGlDestroy() }
        serverList = null
        browse = null
        dock = null
        settings = null
        calibration = null
        gamepadCal = null
        hud = null
        overlay = null
        cylinder.onGlDestroy()
        sphere.onGlDestroy()
        video.release()
        reticle.onGlDestroy()
        groundGrid.onGlDestroy()
        created = false
    }

    private fun activePanelAnchor(screen: VrScreen) = when (screen) {
        VrScreen.SERVER_LIST -> serverList?.anchor
        VrScreen.BROWSE -> browse?.anchor
        VrScreen.SETTINGS -> settings?.anchor
        VrScreen.PLAYER -> hud?.anchor
    }

    /**
     * The ground grid is a browsing-comfort cue, not scenery. It is suppressed in
     * [VrScreen.PLAYER] unconditionally — inside a `SphereScreen` the plane would
     * paint a glowing grid across the lower third of a 360 video (§12.4, R17).
     */
    private fun groundVisible(screen: VrScreen): Boolean = when (screen) {
        VrScreen.SERVER_LIST, VrScreen.BROWSE, VrScreen.SETTINGS -> true
        VrScreen.PLAYER -> false
    }

    companion object {
        /** Duration to hold anchors at 0 while RecenterController slews the head pose to 0 (tau=0.06s). */
        const val RECENTER_SETTLE_SECONDS = 0.20f

        fun rgb(argb: Int): FloatArray = floatArrayOf(
            ((argb shr 16) and 0xFF) / 255f,
            ((argb shr 8) and 0xFF) / 255f,
            (argb and 0xFF) / 255f,
        )
    }
}
