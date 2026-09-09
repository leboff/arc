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
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persists the settings blob and the resume-positions table (ARCHITECTURE.md §13)
 * as `kotlinx.serialization` JSON in a `DataStore<Preferences>`.
 */
class SettingsStore(context: Context) {

    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("settings")
    private val store = context.applicationContext.dataStore
    private val settingsKey = stringPreferencesKey("settings.blob")
    private val resumeKey = stringPreferencesKey("resume.positions")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    data class SettingsBlob(
        val deviceProfileId: String = "cardboard_v2",
        val predictionEnabled: Boolean = true,
        val neckModelEnabled: Boolean = true,
        val autoRecenterIdleSeconds: Int = 0,
        val ipdMm: Float = 63f,
        val screenDistanceM: Float = 4f,
        val screenWidthDegrees: Float = 60f,
        val screenToLensMm: Float = 39f,
        val lensK1: Float = 0.34f,
        val lensK2: Float = 0.55f,
        val dividerPx: Int = 8,
        val distortionCorrection: Boolean = true,
        val gamepadAbSwapped: Boolean = false,
    )

    @Serializable
    data class ResumeBlob(
        val itemKey: String,
        val positionMs: Long,
        val durationMs: Long,
        val finishedAtMs: Long?,
    )

    val settings = store.data.map { prefs ->
        val blob = prefs[settingsKey]
            ?.let { runCatching { json.decodeFromString<SettingsBlob>(it) }.getOrNull() }
            ?: SettingsBlob()
        blob.toSettings()
    }

    suspend fun current(): Settings = settings.first()

    suspend fun save(settings: Settings) {
        store.edit { it[settingsKey] = json.encodeToString(settings.toBlob()) }
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

    private fun Settings.toBlob() = SettingsBlob(
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
}
