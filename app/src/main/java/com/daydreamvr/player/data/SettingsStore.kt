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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Persists the settings blob and the resume-positions table (ARCHITECTURE.md §13)
 * as `kotlinx.serialization` JSON in a `DataStore<Preferences>`.
 *
 * Implements schema V2 migration, atomic legacy backup, and per-profile override preservation
 * (DISTORTION_REMEDIATION_PLAN §5).
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
        val supersampling: Boolean = false,
        val gamepadAbSwapped: Boolean = false,
        val subnetPrefix: String? = null,
    )

    @Serializable
    data class ResumeBlob(
        val itemKey: String,
        val positionMs: Long,
        val durationMs: Long,
        val finishedAtMs: Long?,
    )

    /** Emits after reading and executing any pending migration atomically. */
    val settings = flow { emit(readAndMigrate()) }

    suspend fun current(): Settings = readAndMigrate()

    suspend fun currentV2(): SettingsBlobV2 {
        val raw = store.data.first()[settingsKey]
        val outcome = SettingsMigration.migrate(raw)
        return outcome.blob
    }

    suspend fun save(settings: Settings) {
        store.edit { prefs ->
            val raw = prefs[settingsKey]
            val root = raw?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            val version = root?.get("schemaVersion")?.jsonPrimitive?.intOrNull
            // Never overwrite a future schema version
            if (version != null && version > 2) return@edit

            val outcome = SettingsMigration.migrate(raw)
            val existingV2 = outcome.blob

            val updatedOverrides = existingV2.viewerOverrides + (settings.deviceProfileId to ViewerOverride(
                profileRevision = 1,
                convention = "SCREEN_TANGENT_TO_RAY_TANGENT_V1",
                screenToLensMm = settings.screenToLensMm,
                lensK1 = settings.lensK1,
                lensK2 = settings.lensK2,
                confidence = "USER_CALIBRATED",
            ))

            val v2 = existingV2.copy(
                deviceProfileId = settings.deviceProfileId,
                predictionEnabled = settings.predictionEnabled,
                neckModelEnabled = settings.neckModelEnabled,
                autoRecenterIdleSeconds = settings.autoRecenterIdleSeconds,
                observerIpdMm = settings.ipdMm,
                screenDistanceM = settings.screenDistanceM,
                screenWidthDegrees = settings.screenWidthDegrees,
                dividerPx = settings.dividerPx,
                distortionCorrection = settings.distortionCorrection,
                supersampling = settings.supersampling,
                gamepadAbSwapped = settings.gamepadAbSwapped,
                subnetPrefix = settings.subnetPrefix,
                viewerOverrides = updatedOverrides,
            )

            prefs[settingsKey] = json.encodeToString(v2)
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
        val raw = store.data.first()[settingsKey]
        val outcome = SettingsMigration.migrate(raw)

        if (outcome is MigrationOutcome.MigratedFromV1) {
            store.edit { prefs ->
                if (prefs[legacyBackupKey] == null) {
                    prefs[legacyBackupKey] = outcome.legacyRawString
                }
                prefs[settingsKey] = json.encodeToString(outcome.blob)
            }
        }

        return outcome.blob.toSettings()
    }

    private fun SettingsBlobV2.toSettings(): Settings {
        val override = viewerOverrides[deviceProfileId]
        val base = com.daydreamvr.vrcore.profile.DeviceProfiles.byId(deviceProfileId)
            ?: com.daydreamvr.vrcore.profile.DeviceProfiles.DEFAULT

        return Settings(
            deviceProfileId = deviceProfileId,
            predictionEnabled = predictionEnabled,
            neckModelEnabled = neckModelEnabled,
            autoRecenterIdleSeconds = autoRecenterIdleSeconds,
            ipdMm = observerIpdMm,
            screenDistanceM = screenDistanceM,
            screenWidthDegrees = screenWidthDegrees,
            screenToLensMm = override?.screenToLensMm ?: (base.screenToLensDistanceM * 1000f),
            lensK1 = override?.lensK1 ?: base.distortionK.getOrElse(0) { 0f },
            lensK2 = override?.lensK2 ?: base.distortionK.getOrElse(1) { 0f },
            dividerPx = dividerPx,
            distortionCorrection = distortionCorrection,
            supersampling = supersampling,
            gamepadAbSwapped = gamepadAbSwapped,
            subnetPrefix = subnetPrefix,
        )
    }
}
