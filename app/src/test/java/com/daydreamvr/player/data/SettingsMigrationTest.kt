package com.daydreamvr.player.data

import com.daydreamvr.vrcore.profile.DeviceProfiles
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test

class SettingsMigrationTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun absentBlobProducesFreshInstallV2() {
        val outcome = SettingsMigration.migrate(null)
        assertThat(outcome).isInstanceOf(MigrationOutcome.FreshInstall::class.java)
        assertThat(outcome.blob.schemaVersion).isEqualTo(2)
        assertThat(outcome.blob.opticsModelVersion).isEqualTo(1)
        assertThat(outcome.blob.observerIpdMm).isEqualTo(64f)
        assertThat(outcome.blob.deviceProfileId).isEqualTo("daydream_view_2017")
        assertThat(outcome.blob.viewerOverrides).isEmpty()
    }

    @Test
    fun supersamplingRoundTripsThroughMigration() {
        val raw = json.encodeToString(SettingsBlobV2(supersampling = true))

        val outcome = SettingsMigration.migrate(raw)

        assertThat(outcome.blob.supersampling).isTrue()
        assertThat(SettingsMigration.migrate(json.encodeToString(outcome.blob)).blob.supersampling).isTrue()
    }

    @Test
    fun legacyDefaultsAreMigratedWithArchivedTupleAndRecalibrationFlag() {
        // Exact legacy V1 JSON with default fields
        val legacyJson = """{
            "deviceProfileId": "daydream_view_2017",
            "predictionEnabled": true,
            "neckModelEnabled": true,
            "autoRecenterIdleSeconds": 0,
            "ipdMm": 64.0,
            "screenDistanceM": 4.0,
            "screenWidthDegrees": 60.0,
            "screenToLensMm": 40.0,
            "lensK1": 0.36,
            "lensK2": 0.42,
            "dividerPx": 8,
            "distortionCorrection": true,
            "gamepadAbSwapped": false
        }"""

        val outcome = SettingsMigration.migrate(legacyJson)
        assertThat(outcome).isInstanceOf(MigrationOutcome.MigratedFromV1::class.java)
        val migrated = outcome as MigrationOutcome.MigratedFromV1
        assertThat(migrated.legacyRawString).isEqualTo(legacyJson)

        val v2 = migrated.blob
        assertThat(v2.schemaVersion).isEqualTo(2)
        assertThat(v2.opticsModelVersion).isEqualTo(1)
        assertThat(v2.observerIpdMm).isEqualTo(64f)
        assertThat(v2.deviceProfileId).isEqualTo("daydream_view_2017")
        assertThat(v2.dividerPx).isEqualTo(8)
        assertThat(v2.distortionCorrection).isTrue()

        // §5.4: Old coefficients are NEVER loaded into physical overrides
        assertThat(v2.viewerOverrides).isEmpty()
        assertThat(v2.needsOpticsRecalibration).isTrue()
        assertThat(v2.migrationStatus).isEqualTo("migrated-v1")

        // Legacy tuple archived
        val legacy = v2.legacyCalibration
        assertThat(legacy).isNotNull()
        assertThat(legacy!!.convention).isEqualTo("LEGACY_CLIP_INVERSE")
        assertThat(legacy.lensK1).isWithin(1e-4f).of(0.36f)
        assertThat(legacy.lensK2).isWithin(1e-4f).of(0.42f)
        assertThat(legacy.screenToLensMm).isWithin(1e-4f).of(40.0f)
        assertThat(legacy.ipdMm).isWithin(1e-4f).of(64.0f)
    }

    @Test
    fun customizedLegacyTuplePreservesObserverIpdAndArchivesCustomOptics() {
        val legacyJson = """{
            "deviceProfileId": "cardboard_v2",
            "ipdMm": 68.5,
            "screenToLensMm": 41.5,
            "lensK1": 0.45,
            "lensK2": 0.55,
            "dividerPx": 14,
            "distortionCorrection": false
        }"""

        val outcome = SettingsMigration.migrate(legacyJson)
        assertThat(outcome).isInstanceOf(MigrationOutcome.MigratedFromV1::class.java)
        val v2 = outcome.blob
        assertThat(v2.observerIpdMm).isEqualTo(68.5f)
        assertThat(v2.deviceProfileId).isEqualTo("cardboard_v2")
        assertThat(v2.dividerPx).isEqualTo(14)
        assertThat(v2.distortionCorrection).isFalse()

        // Viewer overrides remain empty so new physical baseline takes effect
        assertThat(v2.viewerOverrides).isEmpty()
        assertThat(v2.needsOpticsRecalibration).isTrue()

        val leg = v2.legacyCalibration!!
        assertThat(leg.lensK1).isWithin(1e-4f).of(0.45f)
        assertThat(leg.ipdMm).isWithin(1e-4f).of(68.5f)
    }

    @Test
    fun invalidIpdInLegacyFallsBackTo64Mm() {
        val outOfBounds = """{"ipdMm": 120.0}"""
        val outcome = SettingsMigration.migrate(outOfBounds)
        assertThat(outcome.blob.observerIpdMm).isEqualTo(64f)
    }

    @Test
    fun unknownProfileFallsBackToDefaultAndArchivesOriginal() {
        val unknownProfile = """{"deviceProfileId": "non_existent_viewer_9000", "lensK1": 0.5}"""
        val outcome = SettingsMigration.migrate(unknownProfile)
        assertThat(outcome.blob.deviceProfileId).isEqualTo("daydream_view_2017")
        assertThat(outcome.blob.legacyCalibration?.profileId).isEqualTo("non_existent_viewer_9000")
    }

    @Test
    fun malformedJsonYieldsCorruptOutcomeWithRawBytesPreserved() {
        val corrupt = """{ this is not valid json: true """
        val outcome = SettingsMigration.migrate(corrupt)
        assertThat(outcome).isInstanceOf(MigrationOutcome.Corrupt::class.java)
        val c = outcome as MigrationOutcome.Corrupt
        assertThat(c.raw).isEqualTo(corrupt)
        assertThat(c.blob.needsOpticsRecalibration).isTrue()
    }

    @Test
    fun validV2WithMultipleViewerOverridesIsPreservedIdempotently() {
        val v2 = SettingsBlobV2(
            schemaVersion = 2,
            opticsModelVersion = 1,
            deviceProfileId = "daydream_view_2016",
            observerIpdMm = 65.5f,
            viewerOverrides = mapOf(
                "daydream_view_2016" to ViewerOverride(lensK1 = 0.35f, lensK2 = 0.50f, screenToLensMm = 39.5f),
                "cardboard_v2" to ViewerOverride(lensK1 = 0.32f, lensK2 = 0.48f, screenToLensMm = 38.0f),
            ),
        )
        val raw = json.encodeToString(v2)

        val outcome = SettingsMigration.migrate(raw)
        assertThat(outcome).isInstanceOf(MigrationOutcome.UpToDate::class.java)
        assertThat(outcome.blob.viewerOverrides).hasSize(2)
        assertThat(outcome.blob.viewerOverrides["cardboard_v2"]?.lensK1).isEqualTo(0.32f)

        // Idempotent migration
        val repeated = SettingsMigration.migrate(json.encodeToString(outcome.blob))
        assertThat(repeated.blob).isEqualTo(outcome.blob)
    }

    @Test
    fun futureSchemaVersionIsPreservedReadOnlyWithoutOverwriting() {
        val futureJson = """{ "schemaVersion": 3, "superCoolNewFeature": "unsupported" }"""
        val outcome = SettingsMigration.migrate(futureJson)
        assertThat(outcome).isInstanceOf(MigrationOutcome.FutureVersion::class.java)
        val f = outcome as MigrationOutcome.FutureVersion
        assertThat(f.schemaVersion).isEqualTo(3)
        assertThat(f.raw).isEqualTo(futureJson)
    }
}
