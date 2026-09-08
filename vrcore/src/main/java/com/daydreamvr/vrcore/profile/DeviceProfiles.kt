package com.daydreamvr.vrcore.profile

import com.daydreamvr.vrcore.render.FovAngles

/**
 * Built-in viewer profiles. Reference values from ARCHITECTURE.md §20.1 — these
 * are starting points; the in-VR calibration grid (Phase 6) is the real
 * measuring instrument.
 */
object DeviceProfiles {

    val CARDBOARD_V1: DeviceProfile = DeviceProfile(
        id = "cardboard_v1",
        displayName = "Cardboard v1 (2014)",
        interLensDistanceM = 0.060f,
        screenToLensDistanceM = 0.042f,
        trayToLensHeightM = 0.035f,
        maxFovDegrees = FovAngles(40f, 40f, 40f, 40f),
        distortionK = floatArrayOf(0.441f, 0.156f),
        chromaticScale = null,
    )

    val CARDBOARD_V2: DeviceProfile = DeviceProfile(
        id = "cardboard_v2",
        displayName = "Cardboard v2 (2015)",
        interLensDistanceM = 0.064f,
        screenToLensDistanceM = 0.039f,
        trayToLensHeightM = 0.035f,
        maxFovDegrees = FovAngles(50f, 50f, 50f, 50f),
        distortionK = floatArrayOf(0.34f, 0.55f),
        chromaticScale = null,
    )

    val DAYDREAM_VIEW_2016: DeviceProfile = DeviceProfile(
        id = "daydream_view_2016",
        displayName = "Daydream View (2016)",
        interLensDistanceM = 0.064f,
        screenToLensDistanceM = 0.039f,
        trayToLensHeightM = 0.035f,
        maxFovDegrees = FovAngles(50f, 50f, 50f, 50f),
        distortionK = floatArrayOf(0.34f, 0.55f),
        chromaticScale = null,
    )

    val DAYDREAM_VIEW_2017: DeviceProfile = DeviceProfile(
        id = "daydream_view_2017",
        displayName = "Daydream View (2017)",
        interLensDistanceM = 0.064f,
        screenToLensDistanceM = 0.040f,
        trayToLensHeightM = 0.035f,
        maxFovDegrees = FovAngles(55f, 55f, 50f, 50f),
        distortionK = floatArrayOf(0.36f, 0.42f),
        chromaticScale = null,
    )

    val GENERIC_100: DeviceProfile = DeviceProfile(
        id = "generic_100",
        displayName = "Generic 100° shell",
        interLensDistanceM = 0.063f,
        screenToLensDistanceM = 0.045f,
        trayToLensHeightM = 0.035f,
        maxFovDegrees = FovAngles(55f, 55f, 55f, 55f),
        distortionK = floatArrayOf(0.30f, 0.30f),
        chromaticScale = null,
    )

    val ALL: List<DeviceProfile> = listOf(
        CARDBOARD_V1,
        CARDBOARD_V2,
        DAYDREAM_VIEW_2016,
        DAYDREAM_VIEW_2017,
        GENERIC_100,
    )

    val DEFAULT: DeviceProfile = CARDBOARD_V2

    fun byId(id: String): DeviceProfile? = ALL.firstOrNull { it.id == id }
}
