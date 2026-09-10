package com.daydreamvr.player.data

import com.daydreamvr.player.state.Settings
import com.daydreamvr.vrcore.optics.MaxFov
import com.daydreamvr.vrcore.optics.ObserverGeometry
import com.daydreamvr.vrcore.optics.ParameterConfidence
import com.daydreamvr.vrcore.optics.RadialCoefficients
import com.daydreamvr.vrcore.optics.VerticalAlignment
import com.daydreamvr.vrcore.optics.ViewerOptics
import com.daydreamvr.vrcore.profile.DeviceProfile
import com.daydreamvr.vrcore.profile.DeviceProfiles

/**
 * Unified resolver from persisted settings to immutable optics models and profiles
 * (DISTORTION_REMEDIATION_PLAN §5, §6).
 *
 * Guarantees observer IPD and fixed viewer lens separation remain strictly decoupled,
 * and eliminates duplicate profile switching logic between SetupActivity, VrActivity,
 * and AppStateMachine.
 */
object OpticsSettingsResolver {

    fun resolveViewerOptics(
        blob: SettingsBlobV2,
        profileId: String = blob.deviceProfileId,
    ): ViewerOptics {
        val base = DeviceProfiles.byId(profileId) ?: DeviceProfiles.DEFAULT
        val override = blob.viewerOverrides[profileId]

        val k1 = (override?.lensK1 ?: base.distortionK[0]).toDouble()
        val k2 = (override?.lensK2 ?: base.distortionK[1]).toDouble()
        val screenToLensM = (override?.screenToLensMm ?: (base.screenToLensDistanceM * 1000f)).toDouble() / 1000.0
        val confidence = if (override != null) ParameterConfidence.USER_CALIBRATED else base.confidence

        return ViewerOptics(
            profileId = base.id,
            profileRevision = override?.profileRevision ?: 1,
            lensSeparationM = base.interLensDistanceM.toDouble(), // Fixed physical geometry!
            horizontalOffsetM = 0.0,
            verticalAlignment = VerticalAlignment.CENTER,
            verticalOffsetM = 0.0,
            screenToLensM = screenToLensM,
            coefficients = RadialCoefficients(k1, k2),
            convention = base.convention,
            maxFov = MaxFov(
                outer = base.maxFovDegrees.outer.toDouble(),
                inner = base.maxFovDegrees.inner.toDouble(),
                up = base.maxFovDegrees.up.toDouble(),
                down = base.maxFovDegrees.down.toDouble(),
            ),
            confidence = confidence,
            dividerPx = blob.dividerPx,
        )
    }

    fun resolveViewerOptics(settings: Settings): ViewerOptics {
        val base = DeviceProfiles.byId(settings.deviceProfileId) ?: DeviceProfiles.DEFAULT
        return ViewerOptics(
            profileId = base.id,
            lensSeparationM = base.interLensDistanceM.toDouble(),
            screenToLensM = settings.screenToLensMm.toDouble() / 1000.0,
            coefficients = RadialCoefficients(settings.lensK1.toDouble(), settings.lensK2.toDouble()),
            verticalAlignment = VerticalAlignment.CENTER,
            maxFov = MaxFov(
                outer = base.maxFovDegrees.outer.toDouble(),
                inner = base.maxFovDegrees.inner.toDouble(),
                up = base.maxFovDegrees.up.toDouble(),
                down = base.maxFovDegrees.down.toDouble(),
            ),
            confidence = ParameterConfidence.USER_CALIBRATED,
            dividerPx = settings.dividerPx,
        )
    }

    fun resolveObserverGeometry(settings: Settings): ObserverGeometry =
        ObserverGeometry(ipdM = (settings.ipdMm / 1000.0).coerceIn(0.052, 0.074))

    /**
     * Produces a compatibility [DeviceProfile] adapter for legacy callers.
     * Fixed viewer interLensDistanceM is strictly preserved from the profile baseline (§4).
     */
    fun resolveDeviceProfile(settings: Settings): DeviceProfile {
        val base = DeviceProfiles.byId(settings.deviceProfileId) ?: DeviceProfiles.DEFAULT
        return base.copy(
            interLensDistanceM = base.interLensDistanceM, // Fixed! Not coupled to settings.ipdMm
            screenToLensDistanceM = settings.screenToLensMm / 1000f,
            distortionK = floatArrayOf(settings.lensK1, settings.lensK2),
            dividerPx = settings.dividerPx,
            confidence = ParameterConfidence.USER_CALIBRATED,
        )
    }

    /**
     * Switches selected viewer profile while preserving global observer IPD (§4, §5).
     */
    fun switchProfile(
        current: Settings,
        targetProfileId: String,
        overrides: Map<String, ViewerOverride> = emptyMap(),
    ): Settings {
        val targetBase = DeviceProfiles.byId(targetProfileId) ?: DeviceProfiles.DEFAULT
        val override = overrides[targetBase.id]

        return current.copy(
            deviceProfileId = targetBase.id,
            // Observer IPD remains unchanged!
            ipdMm = current.ipdMm,
            screenToLensMm = override?.screenToLensMm ?: (targetBase.screenToLensDistanceM * 1000f),
            lensK1 = override?.lensK1 ?: targetBase.distortionK.getOrElse(0) { 0f },
            lensK2 = override?.lensK2 ?: targetBase.distortionK.getOrElse(1) { 0f },
            dividerPx = targetBase.dividerPx,
        )
    }

    fun cycleProfile(
        current: Settings,
        dir: Int,
        overrides: Map<String, ViewerOverride> = emptyMap(),
    ): Settings {
        val all = DeviceProfiles.ALL
        val cur = all.indexOfFirst { it.id == current.deviceProfileId }.coerceAtLeast(0)
        val step = if (dir >= 0) 1 else all.size - 1
        val next = all[(cur + step) % all.size]
        return switchProfile(current, next.id, overrides)
    }
}
