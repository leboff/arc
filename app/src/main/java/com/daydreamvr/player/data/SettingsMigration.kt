package com.daydreamvr.player.data

import com.daydreamvr.vrcore.profile.DeviceProfiles
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class SettingsBlobV2(
    val schemaVersion: Int = 2,
    val opticsModelVersion: Int = 1,
    val deviceProfileId: String = "daydream_view_2017",
    val predictionEnabled: Boolean = true,
    val neckModelEnabled: Boolean = true,
    val autoRecenterIdleSeconds: Int = 0,
    val observerIpdMm: Float = 64f,
    val screenDistanceM: Float = 4f,
    val screenWidthDegrees: Float = 60f,
    val dividerPx: Int = 8,
    val distortionCorrection: Boolean = true,
    val supersampling: Boolean = false,
    val gamepadAbSwapped: Boolean = false,
    val subnetPrefix: String? = null,
    val viewerOverrides: Map<String, ViewerOverride> = emptyMap(),
    val displayCalibrations: Map<String, DisplayCalibration> = emptyMap(),
    val legacyCalibration: LegacyCalibration? = null,
    val needsOpticsRecalibration: Boolean = false,
    val migrationStatus: String = "v2",
)

@Serializable
data class ViewerOverride(
    val profileRevision: Int = 1,
    val convention: String = "SCREEN_TANGENT_TO_RAY_TANGENT_V1",
    val screenToLensMm: Float? = null,
    val lensK1: Float? = null,
    val lensK2: Float? = null,
    val confidence: String = "USER_CALIBRATED",
)

@Serializable
data class DisplayCalibration(
    val panelWidthM: Float,
    val panelHeightM: Float,
    val revision: Int = 1,
)

@Serializable
data class LegacyCalibration(
    val profileId: String?,
    val lensK1: Float?,
    val lensK2: Float?,
    val screenToLensMm: Float?,
    val ipdMm: Float?,
    val convention: String = "LEGACY_CLIP_INVERSE",
)

sealed interface MigrationOutcome {
    val blob: SettingsBlobV2

    data class FreshInstall(override val blob: SettingsBlobV2) : MigrationOutcome
    data class UpToDate(override val blob: SettingsBlobV2) : MigrationOutcome
    data class MigratedFromV1(override val blob: SettingsBlobV2, val legacyRawString: String) : MigrationOutcome
    data class FutureVersion(val raw: String, override val blob: SettingsBlobV2, val schemaVersion: Int) : MigrationOutcome
    data class Corrupt(val raw: String, override val blob: SettingsBlobV2, val reason: String) : MigrationOutcome
}

/**
 * Pure JSON parser, validator and migrator for settings persistence
 * (DISTORTION_REMEDIATION_PLAN §5, §8.2).
 */
object SettingsMigration {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun migrate(rawJson: String?): MigrationOutcome {
        if (rawJson.isNullOrBlank()) {
            return MigrationOutcome.FreshInstall(SettingsBlobV2())
        }

        val rootElement = runCatching { json.parseToJsonElement(rawJson) }.getOrElse { err ->
            return MigrationOutcome.Corrupt(
                raw = rawJson,
                blob = SettingsBlobV2(needsOpticsRecalibration = true, migrationStatus = "corrupt_recovered"),
                reason = "JSON parse error: ${err.message}",
            )
        }

        val root = rootElement as? JsonObject ?: return MigrationOutcome.Corrupt(
            raw = rawJson,
            blob = SettingsBlobV2(needsOpticsRecalibration = true, migrationStatus = "corrupt_not_object"),
            reason = "Root is not a JSON object",
        )

        val schemaVersion = root["schemaVersion"]?.jsonPrimitive?.intOrNull

        return when {
            schemaVersion == null -> migrateV1(rawJson, root)
            schemaVersion == 2 -> validateV2(rawJson, root)
            schemaVersion > 2 -> MigrationOutcome.FutureVersion(
                raw = rawJson,
                blob = SettingsBlobV2(migrationStatus = "read_only_future_schema_$schemaVersion"),
                schemaVersion = schemaVersion,
            )
            else -> MigrationOutcome.Corrupt(
                raw = rawJson,
                blob = SettingsBlobV2(needsOpticsRecalibration = true, migrationStatus = "corrupt_schema_version_$schemaVersion"),
                reason = "Invalid schemaVersion: $schemaVersion",
            )
        }
    }

    private fun migrateV1(rawJson: String, root: JsonObject): MigrationOutcome {
        val legacy = runCatching { json.decodeFromString<SettingsStore.SettingsBlob>(rawJson) }.getOrNull()

        val rawIpd = legacy?.ipdMm ?: root["ipdMm"]?.jsonPrimitive?.floatOrNull ?: 64f
        val validIpd = if (rawIpd.isFinite() && rawIpd in 52f..74f) rawIpd else 64f

        val rawProfileId = root["deviceProfileId"]?.jsonPrimitive?.contentOrNull ?: legacy?.deviceProfileId ?: "daydream_view_2017"
        val profileId = if (DeviceProfiles.byId(rawProfileId) != null) rawProfileId else DeviceProfiles.DEFAULT.id

        val rawDivider = root["dividerPx"]?.jsonPrimitive?.intOrNull ?: legacy?.dividerPx ?: 8
        val dividerPx = rawDivider.coerceIn(0, 40)

        val distortionCorrection = root["distortionCorrection"]?.jsonPrimitive?.booleanOrNull
            ?: legacy?.distortionCorrection ?: true
        val supersampling = root["supersampling"]?.jsonPrimitive?.booleanOrNull
            ?: legacy?.supersampling ?: false

        val prediction = root["predictionEnabled"]?.jsonPrimitive?.booleanOrNull ?: legacy?.predictionEnabled ?: true
        val neckModel = root["neckModelEnabled"]?.jsonPrimitive?.booleanOrNull ?: legacy?.neckModelEnabled ?: true
        val autoRecenter = root["autoRecenterIdleSeconds"]?.jsonPrimitive?.intOrNull ?: legacy?.autoRecenterIdleSeconds ?: 0
        val screenDist = root["screenDistanceM"]?.jsonPrimitive?.floatOrNull?.takeIf { it.isFinite() && it in 1.5f..12f }
            ?: legacy?.screenDistanceM ?: 4f
        val screenWidthDeg = root["screenWidthDegrees"]?.jsonPrimitive?.floatOrNull?.takeIf { it.isFinite() && it in 30f..110f }
            ?: legacy?.screenWidthDegrees ?: 60f
        val gamepadAb = root["gamepadAbSwapped"]?.jsonPrimitive?.booleanOrNull ?: legacy?.gamepadAbSwapped ?: false

        val legacyCal = LegacyCalibration(
            profileId = root["deviceProfileId"]?.jsonPrimitive?.contentOrNull,
            lensK1 = root["lensK1"]?.jsonPrimitive?.floatOrNull ?: legacy?.lensK1,
            lensK2 = root["lensK2"]?.jsonPrimitive?.floatOrNull ?: legacy?.lensK2,
            screenToLensMm = root["screenToLensMm"]?.jsonPrimitive?.floatOrNull ?: legacy?.screenToLensMm,
            ipdMm = root["ipdMm"]?.jsonPrimitive?.floatOrNull ?: legacy?.ipdMm,
            convention = "LEGACY_CLIP_INVERSE",
        )

        val v2 = SettingsBlobV2(
            schemaVersion = 2,
            opticsModelVersion = 1,
            deviceProfileId = profileId,
            predictionEnabled = prediction,
            neckModelEnabled = neckModel,
            autoRecenterIdleSeconds = autoRecenter,
            observerIpdMm = validIpd,
            screenDistanceM = screenDist,
            screenWidthDegrees = screenWidthDeg,
            dividerPx = dividerPx,
            distortionCorrection = distortionCorrection,
            supersampling = supersampling,
            gamepadAbSwapped = gamepadAb,
            viewerOverrides = emptyMap(), // Legacy clip coefficients are NEVER loaded into physical overrides (§5.4)
            displayCalibrations = emptyMap(),
            legacyCalibration = legacyCal,
            needsOpticsRecalibration = true,
            migrationStatus = "migrated-v1",
        )

        return MigrationOutcome.MigratedFromV1(v2, rawJson)
    }

    private fun validateV2(rawJson: String, root: JsonObject): MigrationOutcome {
        val parsed = runCatching { json.decodeFromString<SettingsBlobV2>(rawJson) }.getOrElse { err ->
            return MigrationOutcome.Corrupt(
                raw = rawJson,
                blob = SettingsBlobV2(needsOpticsRecalibration = true, migrationStatus = "v2_decode_error"),
                reason = "Failed to decode V2 JSON: ${err.message}",
            )
        }

        // Validate individual override fields (§5.7)
        val validatedOverrides = parsed.viewerOverrides.mapValues { (profId, override) ->
            val k1 = override.lensK1?.takeIf { it.isFinite() && it in 0f..1f }
            val k2 = override.lensK2?.takeIf { it.isFinite() && it in 0f..1f }
            val d = override.screenToLensMm?.takeIf { it.isFinite() && it in 30f..60f }
            override.copy(lensK1 = k1, lensK2 = k2, screenToLensMm = d)
        }

        val validatedIpd = parsed.observerIpdMm.takeIf { it.isFinite() && it in 52f..74f } ?: 64f
        val validatedDivider = parsed.dividerPx.coerceIn(0, 40)
        val validatedProfile = if (DeviceProfiles.byId(parsed.deviceProfileId) != null) parsed.deviceProfileId else DeviceProfiles.DEFAULT.id

        val validated = parsed.copy(
            observerIpdMm = validatedIpd,
            dividerPx = validatedDivider,
            deviceProfileId = validatedProfile,
            viewerOverrides = validatedOverrides,
        )

        return MigrationOutcome.UpToDate(validated)
    }
}
