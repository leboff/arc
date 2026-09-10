package com.daydreamvr.vrcore.profile

import com.daydreamvr.vrcore.optics.ParameterConfidence
import com.daydreamvr.vrcore.optics.RadialConvention
import com.daydreamvr.vrcore.render.FovAngles

/**
 * Passive-viewer optics (ARCHITECTURE.md §1.3 / DISTORTION_REMEDIATION_PLAN §3, §4).
 *
 * All distances are metres.
 *
 * `equals`/`hashCode` are written by hand rather than generated because the class
 * carries [FloatArray] fields (structural array comparison, no lint suppression).
 */
class DeviceProfile(
    val id: String,
    val displayName: String,
    val interLensDistanceM: Float,
    val screenToLensDistanceM: Float,
    val trayToLensHeightM: Float,
    val maxFovDegrees: FovAngles,
    val distortionK: FloatArray,
    val chromaticScale: FloatArray?,
    val dividerPx: Int = 8,
    val neckModelM: FloatArray = floatArrayOf(0f, -0.075f, 0.080f),
    val confidence: ParameterConfidence = ParameterConfidence.PROVISIONAL,
    val convention: RadialConvention = RadialConvention.SCREEN_TANGENT_TO_RAY_TANGENT_V1,
) {
    fun copy(
        id: String = this.id,
        displayName: String = this.displayName,
        interLensDistanceM: Float = this.interLensDistanceM,
        screenToLensDistanceM: Float = this.screenToLensDistanceM,
        trayToLensHeightM: Float = this.trayToLensHeightM,
        maxFovDegrees: FovAngles = this.maxFovDegrees,
        distortionK: FloatArray = this.distortionK,
        chromaticScale: FloatArray? = this.chromaticScale,
        dividerPx: Int = this.dividerPx,
        neckModelM: FloatArray = this.neckModelM,
        confidence: ParameterConfidence = this.confidence,
        convention: RadialConvention = this.convention,
    ): DeviceProfile = DeviceProfile(
        id, displayName, interLensDistanceM, screenToLensDistanceM, trayToLensHeightM,
        maxFovDegrees, distortionK, chromaticScale, dividerPx, neckModelM, confidence, convention,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DeviceProfile) return false

        val chromaticEqual = when {
            chromaticScale == null && other.chromaticScale == null -> true
            chromaticScale != null && other.chromaticScale != null ->
                chromaticScale.contentEquals(other.chromaticScale)
            else -> false
        }

        return id == other.id &&
            displayName == other.displayName &&
            interLensDistanceM == other.interLensDistanceM &&
            screenToLensDistanceM == other.screenToLensDistanceM &&
            trayToLensHeightM == other.trayToLensHeightM &&
            maxFovDegrees == other.maxFovDegrees &&
            distortionK.contentEquals(other.distortionK) &&
            chromaticEqual &&
            dividerPx == other.dividerPx &&
            neckModelM.contentEquals(other.neckModelM) &&
            confidence == other.confidence &&
            convention == other.convention
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + interLensDistanceM.hashCode()
        result = 31 * result + screenToLensDistanceM.hashCode()
        result = 31 * result + trayToLensHeightM.hashCode()
        result = 31 * result + maxFovDegrees.hashCode()
        result = 31 * result + distortionK.contentHashCode()
        result = 31 * result + (chromaticScale?.contentHashCode() ?: 0)
        result = 31 * result + dividerPx
        result = 31 * result + neckModelM.contentHashCode()
        result = 31 * result + confidence.hashCode()
        result = 31 * result + convention.hashCode()
        return result
    }

    override fun toString(): String = "DeviceProfile($id, confidence=$confidence)"
}
