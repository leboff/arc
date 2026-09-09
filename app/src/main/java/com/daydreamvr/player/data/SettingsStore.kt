package com.daydreamvr.player.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.daydreamvr.player.state.Settings
import com.daydreamvr.playback.ResumeEntry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Persists the settings blob and the resume-positions table (ARCHITECTURE.md §13)
 * as `kotlinx.serialization` JSON in a `DataStore<Preferences>`.
 */
class SettingsStore(context: Context) {

    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("settings")
    private val store = context.applicationContext.dataStore
    private val settingsKey = stringPreferencesKey("settings.blob")
    private val legacyBackupKey = stringPreferencesKey("settings.blob.legacy.v1")
    private val resumeKey = stringPreferencesKey("resume.positions")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    data class SettingsBlob(
        val deviceProfileId: String = "daydream_view_2017",
        val predictionEnabled: Boolean = true,
        val neckModelEnabled: Boolean = true,
        val autoRecenterIdleSeconds: Int = 0,
        val ipdMm: Float = 64f,
        val screenDistanceM: Float = 4f,
        val screenWidthDegrees: Float = 60f,
        val screenToLensMm: Float = 40f,
        val lensK1: Float = 0.36f,
        val lensK2: Float = 0.42f,
        val dividerPx: Int = 8,
        val distortionCorrection: Boolean = true,
        val gamepadAbSwapped: Boolean = false,
    )

    /** V2 separates storage/equation revisions from the legacy active tuple. */
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
        val gamepadAbSwapped: Boolean = false,
        val viewerOverrides: Map<String, ViewerOverride> = emptyMap(),
        val displayCalibrations: Map<String, DisplayCalibration> = emptyMap(),
        val legacyCalibration: LegacyCalibration? = null,
        val needsOpticsRecalibration: Boolean = false,
        val migrationStatus: String = "v2",
    )

    @Serializable data class ViewerOverride(
        val profileRevision: Int = 1, val convention: String = "SCREEN_TANGENT_TO_RAY_TANGENT_V1",
        val screenToLensMm: Float? = null, val lensK1: Float? = null, val lensK2: Float? = null,
        val confidence: String = "USER_CALIBRATED",
    )
    @Serializable data class DisplayCalibration(val panelWidthM: Float, val panelHeightM: Float, val revision: Int = 1)
    @Serializable data class LegacyCalibration(
        val profileId: String?, val lensK1: Float?, val lensK2: Float?, val screenToLensMm: Float?, val ipdMm: Float?,
        val convention: String = "LEGACY_CLIP_INVERSE",
    )

    @Serializable
    data class ResumeBlob(
        val itemKey: String,
        val positionMs: Long,
        val durationMs: Long,
        val finishedAtMs: Long?,
    )

    /** Migration occurs before publication, so defaults cannot overwrite legacy optics. */
    val settings = flow { emit(readAndMigrate()) }

    suspend fun current(): Settings = readAndMigrate()

    suspend fun save(settings: Settings) {
        store.edit { prefs ->
            val raw = prefs[settingsKey]
            val root = raw?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            // A newer writer owns its blob; ordinary saves must not downgrade it.
            if (root?.get("schemaVersion")?.jsonPrimitive?.intOrNull?.let { it > 2 } == true) return@edit
            val existing = raw?.let { runCatching { json.decodeFromString<SettingsBlobV2>(it) }.getOrNull() }
            prefs[settingsKey] = json.encodeToString(settings.toV2(existing))
        }
    }

    suspend fun loadResume(): List<ResumeEntry> =
        (store.data.first()[resumeKey]
            ?.let { runCatching { json.decodeFromString<List<ResumeBlob>>(it) }.getOrNull() }
            ?: emptyList())
            .map { ResumeEntry(it.itemKey, it.positionMs, it.durationMs, it.finishedAtMs) }

    suspend fun saveResume(entries: List<ResumeEntry>) {
        val blob = entries.map { ResumeBlob(it.itemKey, it.positionMs, it.durationMs, it.finishedAtMs) }
        store.edit { it[resumeKey] = json.encodeToString(blob) }
    }

    private suspend fun readAndMigrate(): Settings {
        val raw = store.data.first()[settingsKey] ?: return Settings()
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return Settings()
        val version = root["schemaVersion"]?.jsonPrimitive?.intOrNull
        if (version != null && version > 2) return Settings() // recovery/read-only: never rewrite newer data
        if (version == 2) return runCatching { json.decodeFromString<SettingsBlobV2>(raw).toSettings() }.getOrDefault(Settings())
        val legacy = runCatching { json.decodeFromString<SettingsBlob>(raw) }.getOrNull() ?: return Settings()
        val v2 = legacy.toV2(root)
        // Backup and replacement are one Preferences transaction; resume.positions is untouched.
        store.edit { prefs ->
            if (prefs[legacyBackupKey] == null) prefs[legacyBackupKey] = raw
            prefs[settingsKey] = json.encodeToString(v2)
        }
        return v2.toSettings()
    }

    private fun SettingsBlob.toSettings() = Settings(
        deviceProfileId = deviceProfileId,
        predictionEnabled = predictionEnabled,
        neckModelEnabled = neckModelEnabled,
        autoRecenterIdleSeconds = autoRecenterIdleSeconds,
        ipdMm = ipdMm,
        screenDistanceM = screenDistanceM,
        screenWidthDegrees = screenWidthDegrees,
        screenToLensMm = screenToLensMm,
        lensK1 = lensK1,
        lensK2 = lensK2,
        dividerPx = dividerPx,
        distortionCorrection = distortionCorrection,
        gamepadAbSwapped = gamepadAbSwapped,
    )

    private fun SettingsBlobV2.toSettings() = Settings(
        deviceProfileId = deviceProfileId,
        predictionEnabled = predictionEnabled, neckModelEnabled = neckModelEnabled,
        autoRecenterIdleSeconds = autoRecenterIdleSeconds, ipdMm = observerIpdMm,
        screenDistanceM = screenDistanceM, screenWidthDegrees = screenWidthDegrees,
        // Legacy scalar fields are UI compatibility only; resolve from V2's per-viewer override/baseline.
        screenToLensMm = viewerOverrides[deviceProfileId]?.screenToLensMm ?: baseline().screenToLensDistanceM * 1000f,
        lensK1 = viewerOverrides[deviceProfileId]?.lensK1 ?: baseline().distortionK[0],
        lensK2 = viewerOverrides[deviceProfileId]?.lensK2 ?: baseline().distortionK[1],
        dividerPx = dividerPx, distortionCorrection = distortionCorrection, gamepadAbSwapped = gamepadAbSwapped,
    )

    private fun SettingsBlob.toV2(original: JsonObject? = null): SettingsBlobV2 {
        val validIpd = ipdMm.takeIf { it.isFinite() && it in 52f..74f } ?: 64f
        val profile = deviceProfileId.takeIf { com.daydreamvr.vrcore.profile.DeviceProfiles.byId(it) != null } ?: "daydream_view_2017"
        return SettingsBlobV2(
            deviceProfileId = profile, predictionEnabled = predictionEnabled, neckModelEnabled = neckModelEnabled,
            autoRecenterIdleSeconds = autoRecenterIdleSeconds, observerIpdMm = validIpd,
            screenDistanceM = screenDistanceM, screenWidthDegrees = screenWidthDegrees,
            dividerPx = dividerPx.coerceIn(0, 40), distortionCorrection = distortionCorrection, gamepadAbSwapped = gamepadAbSwapped,
            legacyCalibration = LegacyCalibration(original?.get("deviceProfileId")?.jsonPrimitive?.contentOrNull, lensK1, lensK2, screenToLensMm, ipdMm),
            needsOpticsRecalibration = true, migrationStatus = "migrated-v1",
        )
    }

    private fun SettingsBlobV2.baseline() = com.daydreamvr.vrcore.profile.DeviceProfiles.byId(deviceProfileId)
        ?: com.daydreamvr.vrcore.profile.DeviceProfiles.DEFAULT

    private fun Settings.toV2(existing: SettingsBlobV2? = null): SettingsBlobV2 = SettingsBlobV2(
        deviceProfileId = deviceProfileId,
        predictionEnabled = predictionEnabled,
        neckModelEnabled = neckModelEnabled,
        autoRecenterIdleSeconds = autoRecenterIdleSeconds,
        observerIpdMm = ipdMm,
        screenDistanceM = screenDistanceM,
        screenWidthDegrees = screenWidthDegrees,
        dividerPx = dividerPx,
        distortionCorrection = distortionCorrection,
        gamepadAbSwapped = gamepadAbSwapped,
        viewerOverrides = (existing?.viewerOverrides ?: emptyMap()) + (deviceProfileId to ViewerOverride(
            screenToLensMm = screenToLensMm, lensK1 = lensK1, lensK2 = lensK2,
        )),
        displayCalibrations = existing?.displayCalibrations ?: emptyMap(),
        legacyCalibration = existing?.legacyCalibration,
        needsOpticsRecalibration = existing?.needsOpticsRecalibration ?: false,
    )
}
